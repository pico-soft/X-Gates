package com.picosoft.xrayproxydroid.xray

import android.content.Context
import android.util.Log
import go.Seq
import libv2ray.CoreCallbackHandler
import libv2ray.CoreController
import libv2ray.Libv2ray

/**
 * Тонкая обёртка над libv2ray (AndroidLibXrayLite).
 *
 * Задача этапа 1 — доказать, что xray-core стартует из APK и держит порты.
 * Модель локального прокси: startLoop(config, tunFd = 0) — БЕЗ VpnService.
 */
object XrayController {

    private const val TAG = "XrayController"

    /** tunFd = 0 → xray не создаёт TUN, работает как обычный локальный прокси. */
    private const val NO_TUN_FD = 0

    private var controller: CoreController? = null
    private var envInitialized = false

    /** Пробрасываем статус из нативных колбэков наружу (для UI-лога). */
    val isRunning: Boolean
        get() = controller?.isRunning == true

    /**
     * Инициализация окружения libv2ray. Идемпотентна, потокобезопасна.
     * Нужна и для start(), и для measureOutboundDelay() (замер требует env даже без запущенного прокси).
     */
    @Synchronized
    fun ensureEnv(context: Context) {
        if (envInitialized) return
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        envInitialized = true
        Log.i(TAG, "env initialized, ${Libv2ray.checkVersionX()}")
    }

    /**
     * Запуск ядра с переданным конфигом (freedom или vless — решает вызывающий).
     * Возвращает true, если после старта isRunning == true. Бросает исключение
     * при ошибке конфига/старта — вызывающий код должен показать его в UI.
     */
    @Synchronized
    fun start(context: Context, configJson: String): Boolean {
        if (isRunning) return true
        ensureEnv(context)
        val core = Libv2ray.newCoreController(CoreCallback())
        controller = core
        core.startLoop(configJson, NO_TUN_FD)
        Log.i(TAG, "startLoop done, isRunning=${core.isRunning}")
        return core.isRunning
    }

    /** Остановка ядра. Идемпотентна. */
    @Synchronized
    fun stop() {
        val core = controller ?: return
        try {
            core.stopLoop()
        } catch (e: Exception) {
            Log.w(TAG, "stopLoop error", e)
        } finally {
            controller = null
        }
    }

    /**
     * Дельта трафика активного туннеля через core queryStats по тегу outbound "proxy".
     * queryStats (AndroidLibXrayLite) читает счётчик С СБРОСОМ → возвращает байты с ПРОШЛОГО опроса.
     * @return (принято downlink, отдано uplink) или null, если ядро не запущено/ошибка.
     */
    @Synchronized
    fun queryTunnelDelta(): Pair<Long, Long>? {
        val core = controller ?: return null
        return try {
            val up = core.queryStats("proxy", "uplink")
            val down = core.queryStats("proxy", "downlink")
            down to up
        } catch (e: Exception) {
            Log.d(TAG, "queryStats failed: ${e.message}")
            null
        }
    }

    /**
     * Real ping: задержка через outbound переданного конфига БЕЗ запуска loop.
     * `Libv2ray.measureOutboundDelay` обнуляет inbounds и поднимает временный инстанс —
     * не трогает активный прокси и не занимает порты, потому безопасно и параллельно.
     *
     * НЕ @Synchronized — чтобы батч из нескольких замеров шёл параллельно; env-init внутри
     * ([ensureEnv]) потокобезопасен сам по себе.
     *
     * @return задержку в мс (≥0) или -1 при недоступности/ошибке (мёртвый сервер).
     */
    fun measureOutboundDelay(context: Context, configJson: String, url: String): Long {
        ensureEnv(context)
        return try {
            Libv2ray.measureOutboundDelay(configJson, url)
        } catch (e: Exception) {
            Log.d(TAG, "measureOutboundDelay failed: ${e.message}")
            -1L
        }
    }

    /**
     * Колбэк ядра. На этапе локального прокси без VPN все три метода тривиальны
     * (0 = OK). protect()/setup() в текущем API отсутствуют.
     */
    private class CoreCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long {
            Log.i(TAG, "onEmitStatus: $l $s")
            return 0
        }
    }
}
