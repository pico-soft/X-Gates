package com.picosoft.xrayproxydroid

import android.app.Application
import com.picosoft.xrayproxydroid.crash.CrashReporter

/**
 * Application: ставит обработчик падений МАКСИМАЛЬНО РАНО (до Activity и сервиса), чтобы ловить необработанные
 * исключения во ВСЁМ процессе, включая фоновые потоки (Промпт 93.I). Инициализация сторов остаётся в
 * MainActivity.onCreate / сервисе — здесь только перехват падений.
 */
class XrayApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
