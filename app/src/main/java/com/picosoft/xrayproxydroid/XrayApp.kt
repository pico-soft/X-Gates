package com.picosoft.xrayproxydroid

import android.app.Application
import com.picosoft.xrayproxydroid.crash.CrashReporter
import com.picosoft.xrayproxydroid.update.UpdateInstaller

/**
 * Application: ставит обработчик падений МАКСИМАЛЬНО РАНО (до Activity и сервиса), чтобы ловить необработанные
 * исключения во ВСЁМ процессе, включая фоновые потоки (Промпт 93.I). Инициализация сторов остаётся в
 * MainActivity.onCreate / сервисе — здесь только перехват падений.
 */
class XrayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        // Уборка старых скачанных APK авто-обновления: после успешной установки процесс перезапускается — на старте
        // удаляем APK, чей versionCode ≤ уже установленного (своё отработал), оставляя лишь более новый «последний
        // скачанный». Плюс чистим НАКОПЛЕННЫЕ экспорты в «Загрузках» (за 50+ обновлений могло скопиться), оставляя
        // только актуальную версию. Фоновый поток — не блокируем запуск (парсинг манифеста APK, MediaStore, удаление).
        Thread {
            runCatching { UpdateInstaller.pruneObsoleteApks(this) }
            runCatching { UpdateInstaller.pruneExportedDownloads(this) }
        }.start()
    }
}
