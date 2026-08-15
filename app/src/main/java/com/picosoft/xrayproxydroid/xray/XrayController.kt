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
     * Инициализация окружения libv2ray. Вызывается один раз, идемпотентна.
     * assetPath указываем на filesDir — для freedom-конфига geo-файлы не нужны,
     * но initCoreEnv ожидает валидный путь.
     */
    @Synchronized
    private fun initEnv(context: Context) {
        if (envInitialized) return
        Seq.setContext(context.applicationContext)
        Libv2ray.initCoreEnv(context.filesDir.absolutePath, "")
        envInitialized = true
        Log.i(TAG, "env initialized, ${Libv2ray.checkVersionX()}")
    }

    /**
     * Запуск ядра с тестовым конфигом. Возвращает true, если после старта
     * isRunning == true. Бросает исключение при ошибке конфига/старта —
     * вызывающий код должен показать его в UI.
     */
    @Synchronized
    fun start(context: Context): Boolean {
        if (isRunning) return true
        initEnv(context)
        val core = Libv2ray.newCoreController(CoreCallback())
        controller = core
        core.startLoop(XrayConfig.testConfigJson(), NO_TUN_FD)
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
