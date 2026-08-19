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
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import com.picosoft.xrayproxydroid.update.UpdateChecker
import com.picosoft.xrayproxydroid.update.UpdateNotifier
import com.picosoft.xrayproxydroid.update.UpdateStore
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
    // Обработчик глушит НЕОБРАБОТАННЫЕ исключения фоновых корутин (монитор/обход/каскад) — фон не должен
    // ронять процесс (Промпт 66.B). SupervisorJob — падение одной не гасит остальные.
    private val bgErrors = CoroutineExceptionHandler { _, e -> Log.w("XrayProxyService", "фоновая корутина упала (проглочено): ${e.message}", e) }
    private val monitorScope = CoroutineScope(Dispatchers.IO + SupervisorJob() + bgErrors)
    private var monitorJob: Job? = null

    // Подписка на системную сеть: появление сети мгновенно будит монитор из паузы «нет интернета».
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    // Промпт 95.B: экран включён / устройство вышло из простоя → немедленная проверка связи (в Doze циклы
    // растягиваются и после пробуждения монитор может не успеть — событие компенсирует).
    private var screenReceiver: android.content.BroadcastReceiver? = null

    // Обход ЧУЖОГО системного VPN (Промпт 57/60). coreActive — ядро поднято (привязать исходящие ДО
    // первого дозвона). lastRelation — для логирования СМЕНЫ СОСТОЯНИЯ. bypassRetryAfterMs — троттлинг
    // повторов обхода после отката по lockdown.
    @Volatile private var coreActive = false
    @Volatile private var lastRelation = VpnRelation.NONE
    @Volatile private var bypassRetryAfterMs = 0L
    // Сеть, к которой МЫ привязали процесс (null = не привязывали). Нужно, чтобы определять состояние
    // БЕЗ глобального снятия привязки (иначе параллельные запросы уйдут не тем маршрутом, Промпт 62.A).
    @Volatile private var ourBinding: Network? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Промпт 95: цикл НЕПРЕРЫВНОСТИ СВЯЗИ работает ВСЕГДА, пока прокси активен (НЕ гейтится monitorEnabled —
        // это ОСНОВНОЙ принцип, а не опция; monitorEnabled внутри цикла гейтит лишь вторичную оптимизацию).
        monitorScope.launch {
            ProxyState.state.map { it.running }.distinctUntilChanged()
                .collect { active -> if (active) startMonitor() else stopMonitor() }
        }
        // Переключение «Обходить системный VPN» применяем сразу (не ждём сетевого события).
        monitorScope.launch {
            SettingsStore.state.map { it.bypassSystemVpn }.distinctUntilChanged().collect { applyVpnBypass() }
        }
        // Промпт 93.I: пока прокси активен — раз в сутки проверять новую версию ЧЕРЕЗ уже работающий туннель
        // (несколько КБ). НЕ будим телефон (обычный delay в живом сервисе), НЕ при загрузке, тихо при неудаче.
        monitorScope.launch {
            while (true) {
                delay(60 * 60 * 1000L)   // раз в час смотрим «пора ли»; dueForAutoCheck троттлит до суток
                if (!ProxyState.state.value.running) continue
                if (!UpdateStore.dueForAutoCheck(System.currentTimeMillis())) continue
                val r = runCatching { UpdateChecker.check(applicationContext) }.getOrNull() ?: continue
                UpdateStore.apply(applicationContext, r, System.currentTimeMillis())
                UpdateNotifier.maybeNotify(applicationContext)
            }
        }
        registerNetworkCallback()
        registerScreenReceiver()
    }

    private fun registerScreenReceiver() {
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) { MonitorCoordinator.wake() }   // немедленная проверка связи
        }
        val f = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        runCatching { registerReceiver(r, f); screenReceiver = r }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            // Любое сетевое событие СБРАСЫВАЕТ троттлинг отката (Промпт 62.C): выключил пользователь
            // lockdown — обход должен вернуться сразу, а не по 5-минутному таймеру.
            override fun onAvailable(network: Network) { bypassRetryAfterMs = 0L; MonitorCoordinator.wake(); scheduleVpnBypass() }
            override fun onLost(network: Network) { bypassRetryAfterMs = 0L; scheduleVpnBypass() }
            override fun onCapabilitiesChanged(network: Network, caps: android.net.NetworkCapabilities) { bypassRetryAfterMs = 0L; scheduleVpnBypass() }
        }
        runCatching { cm.registerDefaultNetworkCallback(cb); netCallback = cb }
    }

    /** applyVpnBypass делает БЛОКИРУЮЩУЮ пробу связности — вызывать с фона (не из callback'а/main). */
    private fun scheduleVpnBypass() { monitorScope.launch { applyVpnBypass() } }

    /**
     * Определить отношение к чужому VPN БЕЗ снятия глобальной привязки (Промпт 62.A). Если МЫ уже
     * привязали процесс (ourBinding != null) — значит вошли в INSIDE осознанно, состояние INSIDE. Иначе
     * процесс не связан нами → getActiveNetwork (per-UID) говорит правду. lockdown не путает: детекция по
     * маршруту нашего uid, а не по пробе (которую фаервол блокирует).
     */
    private fun detectRelation(cm: ConnectivityManager): VpnRelation {
        if (!NetworkUtils.vpnActive(cm)) return VpnRelation.NONE
        if (ourBinding != null) return VpnRelation.INSIDE
        return NetworkUtils.vpnRelation(cm)
    }

    /**
     * Обход ЧУЖОГО системного VPN (Промпт 60/62). В libv2ray НЕТ API привязать исходящие ядра к сети →
     * ядро в НАШЕМ процессе уводим `bindProcessToNetwork(физ.сеть)` МИМО VPN. Loopback (локальный SOCKS +
     * клиенты вроде Телеграма) не трогается.
     *  - EXCLUDED (боевой режим Elyor) и NONE — НЕ привязываем, НЕ пробуем, ничего не тратим.
     *  - INSIDE + настройка вкл — привязка к физ.сети + лёгкая проба: мимо есть → bypassed; мимо нет, через
     *    VPN есть → lockdown-откат (идём через VPN); мимо нет и через VPN нет → naружу никто (noExit, E3).
     * @Synchronized — привязку/состояние трогают callback/collector/старт.
     */
    @Synchronized
    private fun applyVpnBypass() {
        try {
            applyVpnBypassUnsafe()
        } catch (e: Exception) {
            // Обход — удобство, а не условие работы: любая ошибка → отказ от обхода, снять привязку, жить.
            Log.w("XrayProxyService", "обход VPN недоступен (проглочено): ${e.message}", e)
            runCatching { getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
            ourBinding = null
            runCatching { SystemVpnState.update(VpnRelation.NONE, bypassed = false, bypassFailed = false) }
        }
    }

    @Synchronized
    private fun applyVpnBypassUnsafe() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val relation = detectRelation(cm)   // БЕЗ глобального снятия привязки

        if (relation != lastRelation) {
            MonitorLog.event(applicationContext, "net", "Системный VPN: ${relationText(relation)}", "")
            bypassRetryAfterMs = 0L    // смена состояния → снова можно пробовать обход
            lastRelation = relation
        }

        var bypassed = false
        var bypassFailed = false
        var noExit = false
        if (relation == VpnRelation.INSIDE && coreActive && SettingsStore.current().bypassSystemVpn) {
            if (System.currentTimeMillis() < bypassRetryAfterMs) {
                bypassFailed = true    // недавно откатились по lockdown — идём через VPN, не долбим
            } else {
                val phys = NetworkUtils.physicalNetwork(cm)
                if (phys != null && runCatching { cm.bindProcessToNetwork(phys) }.getOrDefault(false)) {
                    ourBinding = phys
                    if (NetworkUtils.probeConnectivity(2_500)) {
                        bypassed = true    // мимо VPN есть связь
                    } else {
                        runCatching { cm.bindProcessToNetwork(null) }; ourBinding = null
                        if (NetworkUtils.probeConnectivity(2_500)) {
                            // мимо не проходит, а через VPN — да → LOCKDOWN. Авто-откат (идём через VPN).
                            bypassFailed = true
                            bypassRetryAfterMs = System.currentTimeMillis() + LOCKDOWN_RETRY_MS
                            MonitorLog.event(applicationContext, "net", "Обход VPN не удался (lockdown)",
                                "иду через системный VPN, замер = его канал")
                        } else {
                            // ни мимо, ни через VPN → наружу не выходит НИКТО (VPN не пропускает + lockdown). E3.
                            noExit = true
                            bypassRetryAfterMs = System.currentTimeMillis() + LOCKDOWN_RETRY_MS
                            MonitorLog.event(applicationContext, "net", "Наружу не выходит никто",
                                "системный VPN не пропускает трафик, а обход запрещён его настройками (lockdown)")
                        }
                    }
                }
            }
        }
        if (!bypassed && ourBinding != null) { runCatching { cm.bindProcessToNetwork(null) }; ourBinding = null }
        SystemVpnState.update(relation = relation, bypassed = bypassed, bypassFailed = bypassFailed, noExit = noExit)
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
            ACTION_RETRY_BYPASS -> { bypassRetryAfterMs = 0L; scheduleVpnBypass() }   // «Повторить» из статус-бокса
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
        bypassRetryAfterMs = 0L   // ручной (пере)запуск = действие пользователя → снять троттлинг отката

        Thread {
          // Весь поток старта под перехватом: любая ошибка (в т.ч. сетевого API) → сообщение + стоп, НЕ падение.
          try {
            // Привязку МИМО чужого VPN ставим ДО старта ядра (его первый дозвон уже мимо VPN). На фоне,
            // т.к. applyVpnBypass делает блокирующую пробу связности (нельзя на main). В INSIDE это ~2.5с —
            // объясняем состоянием (в EXCLUDED/NONE пробы нет, задержки нет).
            if (getSystemService(ConnectivityManager::class.java)?.let { NetworkUtils.vpnActive(it) } == true) {
                ProxyState.update(running = false, label = label, serverKey = serverKey, message = "проверка маршрута (системный VPN)…")
            }
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
          } catch (e: Exception) {
            Log.w("XrayProxyService", "старт упал (проглочено): ${e.message}", e)
            runCatching { ProxyState.update(running = false, label = label, serverKey = serverKey, message = "ОШИБКА старта: ${e.message}") }
            runCatching { stopSelfAndForeground() }
          }
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
        com.picosoft.xrayproxydroid.monitor.TunnelHealth.reset()   // Промпт 95: прокси остановлен — статус не зелёный
        NotificationHelper.cancelNoServers(applicationContext)
        coreActive = false
        // Снять привязку сразу и без блокирующей пробы (main-поток) — полное applyVpnBypass не зовём.
        runCatching { getSystemService(ConnectivityManager::class.java)?.bindProcessToNetwork(null) }
        ourBinding = null
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
                // Ошибка одного опроса не должна ронять поток/процесс — пропускаем тик.
                try {
                    XrayController.queryTunnelDelta()?.let { (rx, tx) ->
                        if (SettingsStore.current().verboseLogs) Log.i("TrafficPoll", "delta ↓$rx ↑$tx (байт с прошлого опроса)")
                        TrafficTracker.addTunnel(rx, tx)
                    }
                } catch (e: Exception) {
                    Log.w("TrafficPoll", "опрос трафика упал (проглочено): ${e.message}")
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
        ourBinding = null
        SystemVpnState.reset()
        monitorScope.cancel()   // погасить цикл монитора + реактивный коллектор вместе с сервисом
        netCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) } }
        netCallback = null
        screenReceiver?.let { r -> runCatching { unregisterReceiver(r) } }
        screenReceiver = null
        com.picosoft.xrayproxydroid.monitor.TunnelHealth.reset()
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
        const val ACTION_RETRY_BYPASS = "com.picosoft.xrayproxydroid.action.RETRY_BYPASS"
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

        /** «Повторить обход» из статус-бокса (Промпт 62.C): сбросить троттлинг и пере-попробовать сразу. */
        fun retryVpnBypass(context: Context) {
            val intent = Intent(context, XrayProxyService::class.java).setAction(ACTION_RETRY_BYPASS)
            runCatching { context.startService(intent) }
        }
    }
}
