package com.picosoft.xrayproxydroid.crash

import android.content.Context
import android.os.Build
import android.util.Log
import com.picosoft.xrayproxydroid.BuildConfig
import com.picosoft.xrayproxydroid.monitor.MonitorCoordinator
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.settings.SettingsStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Промпт 93.I — сбор отчётов о падениях ИЗ САМОГО приложения (тестировщики удалённые, adb нет).
 *
 * Ставит глобальный обработчик необработанных исключений (весь процесс: main + фоновые потоки + сервис).
 * При падении: пишет отчёт в приватный каталог (трассировка, время, версия, устройство, что происходило,
 * последние строки журнала), вычищает чувствительное (адреса подписок, UUID, пароли), хранит несколько
 * последних, старые удаляет — и ДАЁТ процессу упасть штатно (цепляем прежний обработчик).
 */
object CrashReporter {
    private const val TAG = "CrashReporter"
    private const val DIR = "crashes"
    private const val KEEP = 6                      // сколько последних отчётов хранить
    private const val PREF = "crash_prefs"
    private const val KEY_SEEN = "last_seen"        // имя последнего ПОКАЗАННОГО отчёта (чтобы не нудеть)

    /**
     * Промпт 97: адрес получателя отчётов о сбоях — ОДНО место (константа). Отправка ТОЛЬКО по явному
     * действию человека (открыть письмо и нажать «отправить»); автоматической отправки НЕТ и быть не должно
     * (приложение для обхода блокировок не может тихо слать данные разработчику).
     */
    const val DEVELOPER_EMAIL = "pico.soft.github@gmail.com"
    private const val APP_NAME = "XrayProxyDroid"

    /** Тема письма: название, версия, versionCode, модель устройства, версия Android (Промпт 97.D). */
    fun emailSubject(): String =
        "$APP_NAME ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) — " +
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} — отчёт о сбое"

    /**
     * Тело письма (Промпт 97.D/E): необязательное описание пользователя «что делал перед сбоем» отдельным
     * блоком СВЕРХУ, затем очищенный отчёт как есть. Описание тоже прогоняем через [Sanitizer] — человек мог
     * вписать адрес/учётку, а письмо уходит постороннему.
     */
    fun emailBody(description: String, report: String): String {
        val sb = StringBuilder()
        val desc = description.trim()
        if (desc.isNotEmpty()) {
            sb.appendLine("--- Что делал пользователь перед сбоем ---")
            sb.appendLine(Sanitizer.clean(desc))
            sb.appendLine()
        }
        sb.append(report)
        return sb.toString()
    }

    @Volatile private var appContext: Context? = null
    private var previous: Thread.UncaughtExceptionHandler? = null

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app
        previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try { writeReport(thread, throwable) } catch (t: Throwable) {
                try { Log.e(TAG, "не удалось записать отчёт", t) } catch (_: Throwable) {}
            }
            // ДАТЬ упасть штатно — не продолжаем после необработанного исключения.
            val prev = previous
            if (prev != null) prev.uncaughtException(thread, throwable)
            else { android.os.Process.killProcess(android.os.Process.myPid()); exitProcess(10) }
        }
    }

    private fun dir(): File = File(appContext!!.filesDir, DIR).apply { if (!exists()) mkdirs() }

    /** Отчёты, свежие первыми (имя содержит сортируемую метку времени). */
    fun reports(): List<File> =
        runCatching { dir().listFiles { f -> f.isFile && f.name.startsWith("crash-") }?.sortedByDescending { it.name }?.toList() }
            .getOrNull() ?: emptyList()

    fun read(f: File): String = runCatching { f.readText() }.getOrDefault("(отчёт недоступен)")

    fun clearAll() {
        runCatching { reports().forEach { it.delete() } }
        markLatestSeen()
    }

    fun delete(f: File) { runCatching { f.delete() }; markLatestSeen() }

    /** Есть НЕпоказанный отчёт (для баннера на главной/в настройках). */
    fun hasUnseen(): Boolean {
        val latest = reports().firstOrNull()?.name ?: return false
        val seen = prefs()?.getString(KEY_SEEN, null)
        return latest != seen
    }

    /** Пометить самый свежий отчёт «показанным» — баннер снимается (для следующего падения покажется снова). */
    fun markLatestSeen() {
        val latest = reports().firstOrNull()?.name
        prefs()?.edit()?.putString(KEY_SEEN, latest)?.apply()
    }

    private fun prefs() = appContext?.getSharedPreferences(PREF, Context.MODE_PRIVATE)

    private fun writeReport(thread: Thread, t: Throwable) {
        val ctx = appContext ?: return
        val sb = StringBuilder()
        sb.appendLine("=== XrayProxyDroid — отчёт о сбое ===")
        runCatching { sb.appendLine("Время: ${stamp("yyyy-MM-dd HH:mm:ss")}") }
        sb.appendLine("Версия: ${BuildConfig.VERSION_NAME} (versionCode ${BuildConfig.VERSION_CODE})")
        sb.appendLine("Устройство: ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("Поток: ${thread.name}")
        // Что происходило в момент падения (best-effort, каждое поле защищено).
        runCatching { sb.appendLine("Состояние: ${CrashContext.breadcrumb}") }
        runCatching { sb.appendLine("Полный тест идёт: ${MonitorCoordinator.fullTestRunning}") }
        runCatching { sb.appendLine("Прокси активен: ${ProxyState.state.value.running}") }
        runCatching { sb.appendLine("Монитор включён: ${SettingsStore.current().monitorEnabled}") }
        runCatching { sb.appendLine("Серверов в реестре: ${CrashContext.registrySize}") }
        runCatching {
            val rt = Runtime.getRuntime()
            val usedMb = (rt.totalMemory() - rt.freeMemory()) / 1_048_576
            val maxMb = rt.maxMemory() / 1_048_576
            sb.appendLine("Память JVM: ${usedMb} / ${maxMb} МБ (использовано/лимит)")
        }
        sb.appendLine()
        sb.appendLine("--- Трассировка ---")
        sb.appendLine(runCatching { Log.getStackTraceString(t) }.getOrDefault(t.toString()))
        sb.appendLine("--- Журнал (последние события) ---")
        runCatching {
            MonitorLog.state.value.takeLast(15).forEach { sb.appendLine("• ${it.text}${if (it.detail.isNotBlank()) " — ${it.detail}" else ""}") }
        }
        val report = Sanitizer.clean(sb.toString())
        val f = File(dir(), "crash-${stamp("yyyyMMdd-HHmmss")}.txt")
        f.writeText(report)
        prune()
        Log.e(TAG, "отчёт о сбое записан: ${f.name}")
    }

    private fun prune() {
        val all = reports()
        if (all.size > KEEP) all.drop(KEEP).forEach { runCatching { it.delete() } }
    }

    private fun stamp(fmt: String) = SimpleDateFormat(fmt, Locale.US).format(Date())
}

/**
 * «Хлебные крошки» для отчёта: что приложение делает прямо сейчас. Ключевые операции обновляют [breadcrumb]
 * (полный тест/фаза, импорт источника, обновление, монитор), а reload обновляет [registrySize]. Volatile —
 * читается из потока-обработчика падений без блокировок.
 */
object CrashContext {
    @Volatile var breadcrumb: String = "старт"
    @Volatile var registrySize: Int = -1
    fun set(b: String) { breadcrumb = b }
}

/**
 * Вычистка чувствительного из отчёта (Промпт 93.B): адреса подписок, учётные данные серверов, UUID, пароли,
 * IP — человек пересылает отчёт в мессенджере. Лучше перемаскировать, чем утечь.
 */
object Sanitizer {
    private val url = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>]+""")        // http(s)/vless/vmess/trojan/ss://…
    private val uuid = Regex("""[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}""")
    private val creds = Regex("""[\w.\-]+:[^@\s/]+@""")                          // user:pass@host
    private val ip = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val token = Regex("""[A-Za-z0-9+/=_\-]{24,}""")                      // длинные base64/hex-токены (ключи/uuid-подобное)

    fun clean(s: String): String = s
        .replace(url, "<адрес>")
        .replace(creds, "<логин>@")
        .replace(uuid, "<uuid>")
        .replace(ip, "<ip>")
        .replace(token, "<токен>")
}
