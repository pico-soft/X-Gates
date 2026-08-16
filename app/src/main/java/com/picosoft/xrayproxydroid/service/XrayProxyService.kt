package com.picosoft.xrayproxydroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
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

    @Volatile private var polling = false

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
        val serverKey = intent.getStringExtra(EXTRA_SERVERKEY)
        if (config.isNullOrEmpty()) {
            stopSelfAndForeground()
            return
        }

        // В foreground нужно уйти в течение ~5с после старта — иначе ANR.
        startForeground(NotificationHelper.NOTIFICATION_ID, NotificationHelper.buildNotification(this, label))
        ProxyState.update(running = false, label = label, serverKey = serverKey, message = "запуск…")

        Thread {
            polling = false   // остановить опрос прежнего туннеля при переключении сервера
            if (XrayController.isRunning) {
                XrayController.queryTunnelDelta()?.let { TrafficTracker.addTunnel(it.first, it.second) }  // финал старого туннеля
                XrayController.stop()   // авто-переключение сервера
            }
            val res = runCatching { XrayController.start(applicationContext, config) }
            res.fold(
                onSuccess = { ok ->
                    if (ok) {
                        val socks = probe(XrayConfig.SOCKS_PORT)
                        val http = probe(XrayConfig.HTTP_PORT)
                        ProxyState.update(
                            running = true, label = label, serverKey = serverKey,
                            message = "", socksOk = socks, httpOk = http,   // порты — отдельными флагами (одна строка в UI)
                        )
                        startTrafficPolling()
                    } else {
                        ProxyState.update(running = false, label = label, serverKey = serverKey, message = "ядро не запустилось")
                        stopSelfAndForeground()
                    }
                },
                onFailure = { e ->
                    ProxyState.update(running = false, label = label, serverKey = serverKey, message = "ОШИБКА: ${e.message}")
                    stopSelfAndForeground()
                }
            )
        }.start()
    }

    private fun handleStop() {
        Thread {
            polling = false
            XrayController.queryTunnelDelta()?.let { TrafficTracker.addTunnel(it.first, it.second) }  // финальный замер туннеля
            XrayController.stop()
            TrafficTracker.onServiceStop()
            ProxyState.update(running = false, label = null, serverKey = null, message = "остановлено")
            stopSelfAndForeground()
        }.start()
    }

    /** Опрос трафика туннеля раз в ~2.5с, только пока сервис активен (батарея важнее секундной точности). */
    private fun startTrafficPolling() {
        TrafficTracker.attachContext(applicationContext)
        TrafficTracker.onServiceStart()
        polling = true
        Thread {
            while (polling && XrayController.isRunning) {
                try { Thread.sleep(POLL_MS) } catch (e: InterruptedException) { break }
                if (!polling) break
                XrayController.queryTunnelDelta()?.let { (rx, tx) ->
                    // ДИАГНОСТИКА reset-семантики queryStats: два опроса подряд без трафика между ними
                    // должны дать нулевую вторую дельту. Если повторяет первую — счётчик НЕ сбрасывается.
                    if (SettingsStore.current().verboseLogs) Log.i("TrafficPoll", "delta ↓$rx ↑$tx (байт с прошлого опроса)")
                    TrafficTracker.addTunnel(rx, tx)
                }
            }
        }.start()
    }

    private fun stopSelfAndForeground() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        polling = false
        // Страховка: если сервис уничтожают — гасим ядро.
        if (XrayController.isRunning) {
            XrayController.queryTunnelDelta()?.let { TrafficTracker.addTunnel(it.first, it.second) }
            XrayController.stop()
            TrafficTracker.onServiceStop()
        }
        if (ProxyState.state.value.running) {
            ProxyState.update(running = false, label = null, serverKey = null, message = "остановлено")
        }
    }

    private fun probe(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(XrayConfig.LISTEN, port), 500); true }
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val POLL_MS = 2_500L
        const val ACTION_START = "com.picosoft.xrayproxydroid.action.START"
        const val ACTION_STOP = "com.picosoft.xrayproxydroid.action.STOP"
        const val EXTRA_CONFIG = "config"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SERVERKEY = "serverKey"

        /** Запуск сервиса с готовым конфигом (из видимой Activity — startForegroundService разрешён). */
        fun start(context: Context, config: String, label: String, serverKey: String) {
            val intent = Intent(context, XrayProxyService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONFIG, config)
                .putExtra(EXTRA_LABEL, label)
                .putExtra(EXTRA_SERVERKEY, serverKey)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, XrayProxyService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
