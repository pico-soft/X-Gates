package com.picosoft.xrayproxydroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayController
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Тонкий foreground-сервис (как v2rayNG CoreProxyOnlyService, но без VPN):
 * держит xray-core живым в фоне. Config приходит через Intent extra; START_NOT_STICKY
 * (без авто-рестарта — персистенцию выбранного сервера отложили). Один процесс с Activity,
 * состояние отдаём через [ProxyState].
 */
class XrayProxyService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(intent)
            ACTION_STOP -> handleStop()
            else -> stopSelfAndForeground()
        }
        return START_NOT_STICKY
    }

    private fun handleStart(intent: Intent) {
        val config = intent.getStringExtra(EXTRA_CONFIG)
        val label = intent.getStringExtra(EXTRA_LABEL)
        if (config.isNullOrEmpty()) {
            stopSelfAndForeground()
            return
        }

        // В foreground нужно уйти в течение ~5с после старта — иначе ANR.
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this, label))
        ProxyState.update(running = false, label = label, message = "запуск…")

        Thread {
            if (XrayController.isRunning) XrayController.stop()   // авто-переключение сервера
            val res = runCatching { XrayController.start(applicationContext, config) }
            res.fold(
                onSuccess = { ok ->
                    if (ok) {
                        val socks = probe(XrayConfig.SOCKS_PORT)
                        val http = probe(XrayConfig.HTTP_PORT)
                        ProxyState.update(
                            running = true, label = label,
                            message = "socks ${XrayConfig.SOCKS_PORT}: ${mark(socks)}  ·  http ${XrayConfig.HTTP_PORT}: ${mark(http)}"  // порты — токены
                        )
                    } else {
                        ProxyState.update(running = false, label = label, message = "ядро не запустилось")
                        stopSelfAndForeground()
                    }
                },
                onFailure = { e ->
                    ProxyState.update(running = false, label = label, message = "ОШИБКА: ${e.message}")
                    stopSelfAndForeground()
                }
            )
        }.start()
    }

    private fun handleStop() {
        Thread {
            XrayController.stop()
            ProxyState.update(running = false, label = null, message = "остановлено")
            stopSelfAndForeground()
        }.start()
    }

    private fun stopSelfAndForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Страховка: если сервис уничтожают — гасим ядро.
        if (XrayController.isRunning) XrayController.stop()
        if (ProxyState.state.value.running) {
            ProxyState.update(running = false, label = null, message = "остановлено")
        }
    }

    private fun probe(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(XrayConfig.LISTEN, port), 500); true }
    } catch (e: Exception) {
        false
    }

    private fun mark(open: Boolean) = if (open) "✓" else "✗"

    companion object {
        const val ACTION_START = "com.picosoft.xrayproxydroid.action.START"
        const val ACTION_STOP = "com.picosoft.xrayproxydroid.action.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_LABEL = "label"

        /** Запуск сервиса с готовым конфигом (из видимой Activity — startForegroundService разрешён). */
        fun start(context: Context, config: String, label: String) {
            val intent = Intent(context, XrayProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_LABEL, label)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, XrayProxyService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
