package com.picosoft.xrayproxydroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.picosoft.xrayproxydroid.monitor.MonitorCoordinator
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.net.NetworkUtils
import com.picosoft.xrayproxydroid.net.VpnRelation
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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

    // Корутина автомониторинга. Живёт ТОЛЬКО когда монитор включён И туннель активен (реактивно).
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var monitorJob: Job? = null

    // Подписка на системную сеть: появление сети мгновенно будит монитор из паузы «нет интернета».
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // Обход ЧУЖОГО системного VPN (Промпт 57/60). coreActive — ядро поднято (привязать исходящие ДО
    // первого дозвона). lastRelation — для логирования СМЕНЫ СОСТОЯНИЯ. bypassRetryAfterMs — троттлинг
    // повторов обхода после отката по lockdown.
    @Volatile private var coreActive = false
    @Volatile private var lastRelation = VpnRelation.NONE
    @Volatile private var bypassRetryAfterMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Реактивно поднимаем/гасим монитор: работает только при monitorEnabled И активном прокси
        // (выключенный монитор не выполняет НИЧЕГО — ради батареи).
        monitorScope.launch {
            combine(SettingsStore.state, ProxyState.state) { s, p -> s.monitorEnabled && p.running }
                .distinctUntilChanged()
                .collect { active -> if (active) startMonitor() else stopMonitor() }
        }
        // Переключение «Обходить системный VPN» применяем сразу (не ждём сетевого события).
        monitorScope.launch {
            SettingsStore.state.map { it.bypassSystemVpn }.distinctUntilChanged().collect { applyVpnBypass() }
        }
        registerNetworkCallback()
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { MonitorCoordinator.wake(); scheduleVpnBypass() }
            override fun onLost(network: Network) { scheduleVpnBypass() }
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) { scheduleVpnBypass() }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    /** applyVpnBypass делает БЛОКИРУЮЩУЮ пробу связности — вызывать с фона (не из callback'а/main). */
    private fun scheduleVpnBypass() { monitorScope.launch { applyVpnBypass() } }

    /**
     * Обход ЧУЖОГО системного VPN (Промпт 60), 3 состояния. В libv2ray НЕТ API привязать исходящие ядра к
     * сети → ядро в НАШЕМ процессе уводим `bindProcessToNetwork(физ.сеть)` МИМО VPN. Loopback (локальный
     * SOCKS + клиенты вроде Телеграма) привязка не трогает.
     *  - Состояние определяем ФАКТОМ (getActiveNetwork per-UID), СНЯВ нашу привязку.
     *  - Привязку ставим ТОЛЬКО в INSIDE (мы внутри VPN) при вкл. настройке; в EXCLUDED/NONE — не ставим.
     *  - После привязки — лёгкая проверка связности: не прошла мимо, а через VPN проходит → LOCKDOWN,
     *    авто-откат (идём через VPN), троттлинг повторов. Настройку при этом НЕ трогаем.
     * @Synchronized — привязку/состояние трогают несколько источников (callback/collector/старт).
     */
    @Synchronized
    private fun applyVpnBypass() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.bindProcessToNetwork(null) }   // снять нашу привязку, чтобы увидеть ИСТИННЫЙ маршрут
        val relation = NetworkUtils.vpnRelation(cm)

        if (relation != lastRelation) {
            MonitorLog.event(applicationContext, "net", "Системный VPN: ${relationText(relation)}", "")
            bypassRetryAfterMs = 0L    // смена состояния сети → снова можно пробовать обход
            lastRelation = relation
        }

        var bypassed = false
        var bypassFailed = false
        if (relation == VpnRelation.INSIDE && coreActive && SettingsStore.current().bypassSystemVpn) {
            if (System.currentTimeMillis() < bypassRetryAfterMs) {
                bypassFailed = true    // недавно откатились по lockdown — идём через VPN, не долбим
            } else {
                val phys = NetworkUtils.physicalNetwork(cm)
                if (phys != null && runCatching { cm.bindProcessToNetwork(phys) }.getOrDefault(false)) {
                    if (NetworkUtils.probeConnectivity(2_500)) {
                        bypassed = true    // мимо VPN есть связь
                    } else {
                        runCatching { cm.bindProcessToNetwork(null) }
                        if (NetworkUtils.probeConnectivity(2_500)) {
                            // мимо не проходит, а через VPN — да → LOCKDOWN. Авто-откат.
                            bypassFailed = true
                            bypassRetryAfterMs = System.currentTimeMillis() + LOCKDOWN_RETRY_MS
                            MonitorLog.event(applicationContext, "net", "Обход VPN не удался (lockdown)",
                                "иду через системный VPN, замер = его канал")
                        }
                        // иначе связи нет вовсе — не lockdown, просто остаёмся без привязки
                    }
                }
            }
        }
        if (!bypassed) runCatching { cm.bindProcessToNetwork(null) }   // гарантированно без битой привязки
        SystemVpnState.update(relation = relation, bypassed = bypassed, bypassFailed = bypassFailed)
    }

    private fun relationText(r: VpnRelation): String = when (r) {
        VpnRelation.NONE -> "нет"
        VpnRelation.INSIDE -> "мы внутри него"
        VpnRelation.EXCLUDED -> "есть, но нас не касается"
    }

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

        coreActive = true

        Thread {
            // Привязку МИМО чужого VPN ставим ДО старта ядра (его первый дозвон уже мимо VPN). На фоне,
            // т.к. applyVpnBypass делает блокирующую пробу связности (нельзя на main).
            applyVpnBypass()
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
                        LastServerStore.save(applicationContext, serverKey)   // запомнить для автозапуска
                        startTrafficPolling()
                        // Монитор поднимается РЕАКТИВНО (combine settings+ProxyState в onCreate) — здесь не зовём.
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

    /** Запустить цикл монитора (идемпотентно). Зовётся реактивным коллектором в onCreate. */
    private fun startMonitor() {
        if (monitorJob?.isActive == true) return
        monitorJob = monitorScope.launch { NetworkMonitor.loop(applicationContext) }
    }

    private fun stopMonitor() {
        monitorJob?.cancel()
        monitorJob = null
    }

    private fun handleStop() {
        stopMonitor()
        coreActive = false
        // Снять привязку сразу и без блокирующей пробы (main-поток) — полное applyVpnBypass не зовём.
        runCatching { getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
        SystemVpnState.reset()
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
        coreActive = false
        // ОБЯЗАТЕЛЬНО снять привязку процесса к сети — иначе приложение осталось бы прибито к сети и после стопа.
        runCatching { getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
        SystemVpnState.reset()
        monitorScope.cancel()   // погасить цикл монитора + реактивный коллектор вместе с сервисом
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
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
        private const val LOCKDOWN_RETRY_MS = 5 * 60_000L   // после отката по lockdown — не пробуем обход чаще
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
