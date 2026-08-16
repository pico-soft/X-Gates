package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.service.NotificationHelper
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.XrayController
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlin.math.roundToInt

/**
 * Цикл АВТОМОНИТОРИНГА. Корутина в foreground-сервисе; запускается ТОЛЬКО когда монитор включён И прокси
 * активен (иначе не работает вообще — сервис не создаёт эту корутину, ради батареи). Включён — следит И
 * ПЕРЕКЛЮЧАЕТ (режима «только наблюдать» больше нет).
 *
 * Порядок цикла:
 *   0. Сигнал A (нет интернета) — В САМОМ НАЧАЛЕ, ДО перебора. Нет сети → пауза с удвоением (10м→4ч),
 *      сброс по событию ConnectivityManager / действию пользователя ([MonitorCoordinator.awaitWake]).
 *   1. Сигнал B (внешний IP через активный SOCKS) — туннель жив? да → рутина, ничего не пишем.
 *   2. Тяжёлый замер (прямой русскими CDN + туннель зарубежным) только при провале B.
 *   3. Падение (N неудач подряд) → перебор кандидатов и переключение.
 *
 * Перебор: ВЕСЬ список сверху вниз (от быстрых к медленным по известной скорости); дешёвая проверка
 * (temp-инстанс, реальный запрос через ServerTester.ping) → подключаемся к ПЕРВОМУ живому (связь важнее
 * точного числа), скорость меряем ПОСЛЕ подключения. Не подошёл никто → обновить подписки → ещё проход.
 * Опять никто → предложить включить выключенные источники (не включаем сами) → пауза с удвоением.
 */
object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    private val directDnsProbes = listOf("77.88.8.8" to 53, "8.8.8.8" to 53, "1.1.1.1" to 53)
    private val directSpeedProbes = listOf("https://ya.ru/", "https://mc.yandex.ru/", "https://vk.ru/")
    private const val TUNNEL_SPEED_PROBE = "https://speed.cloudflare.com/__down?bytes=2000000"

    private const val SWITCH_THROTTLE_MS = 60_000L        // в фоне переключаться не чаще раза в 60с
    private const val BACKOFF_START_MS = 10 * 60_000L     // первая пауза при отсутствии сети — 10 минут
    // Верхний предел паузы — 4 часа: дальше удваивать бессмысленно (это уже «редкая фоновая проверка»),
    // а ConnectivityManager всё равно разбудит мгновенно при появлении сети; экономим батарею при долгом офлайне.
    private const val BACKOFF_MAX_MS = 4 * 3600_000L

    private enum class SwitchResult { SWITCHED, ABORTED, NO_CANDIDATES }

    suspend fun loop(context: Context) {
        val app = context.applicationContext
        var failures = 0
        var phase = "ok"            // ok | nonet | problem
        var problemSince = 0L
        var verdictActed = false
        var lastSwitchMs = 0L
        var backoffMs = 0L          // текущая пауза «нет интернета/нет замены» (0 = нет)
        var cycles = 0
        Log.i(TAG, "monitor loop started")

        while (true) {
            val s = SettingsStore.current()
            if (!s.monitorEnabled) return                 // выключили — корутина завершается (сервис пересоздаст при включении)
            if (!XrayController.isRunning || !ProxyState.state.value.running) return

            // Обычный интервал (прерываемый — чтобы wake() поднял досрочно).
            MonitorCoordinator.drainWakeups()
            MonitorCoordinator.awaitWake(s.monitorIntervalSec.coerceAtLeast(60) * 1000L)

            val cur = SettingsStore.current()
            if (!cur.monitorEnabled) return
            if (!XrayController.isRunning || !ProxyState.state.value.running) return
            if (MonitorCoordinator.fullTestRunning) continue   // ручной тест сам переключает — молчим

            cycles++

            // ── Сигнал A: нет интернета → пауза с удвоением, ДО любого перебора ──
            if (!directAlive()) {
                backoffMs = if (backoffMs == 0L) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
                if (phase != "nonet") {
                    MonitorLog.event(app, "net", "Пропал интернет", "пауза ${humanDur(backoffMs)}")
                    phase = "nonet"; failures = 0; verdictActed = false
                } else {
                    MonitorLog.event(app, "net", "Интернета всё нет — пауза увеличена", humanDur(backoffMs))
                }
                MonitorStatus.update(true, "нет интернета, пауза ${humanDur(backoffMs)}", now(), cycles)
                MonitorCoordinator.drainWakeups()
                val woke = MonitorCoordinator.awaitWake(backoffMs)
                if (woke) MonitorLog.event(app, "net", "Пробуждение (сеть/действие) — проверяю", "пауза длилась < ${humanDur(backoffMs)}")
                continue
            }
            if (phase == "nonet") {
                MonitorLog.event(app, "net", "Интернет появился", if (backoffMs > 0) "пауза сброшена" else "")
                phase = "ok"; backoffMs = 0
            }

            // ── Сигнал B: туннель отвечает? (лёгкая проверка) ──
            if (ExternalIpChecker.fetch() != null) {
                onHealthy(app, phase, problemSince, "OK")
                phase = "ok"; failures = 0; verdictActed = false; backoffMs = 0
                MonitorStatus.update(true, "ок", now(), cycles)
                continue
            }

            // ── Тяжёлый замер (лёгкая указала на проблему) ──
            val directMbps = measureDirectMbps(app)
            if (directMbps == null) {   // канал не тянет — считаем как нет интернета
                backoffMs = if (backoffMs == 0L) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
                if (phase != "nonet") { MonitorLog.event(app, "net", "Пропал интернет", "канал не тянет, пауза ${humanDur(backoffMs)}"); phase = "nonet" }
                failures = 0; verdictActed = false
                MonitorStatus.update(true, "нет интернета, пауза ${humanDur(backoffMs)}", now(), cycles)
                MonitorCoordinator.drainWakeups(); MonitorCoordinator.awaitWake(backoffMs)
                continue
            }
            val tunnelMbps = measureTunnelMbps()
            val weak = directMbps < cur.monitorDirectThreshold
            val effThr = if (weak) maxOf(directMbps / 2, 0.05) else cur.monitorTunnelThreshold
            if (tunnelMbps >= effThr) {
                onHealthy(app, phase, problemSince, fmt(tunnelMbps))
                phase = "ok"; failures = 0; verdictActed = false; backoffMs = 0
                MonitorStatus.update(true, "ок", now(), cycles)
                continue
            }

            // ── ПАДЕНИЕ ──
            failures++
            if (phase != "problem") { problemSince = now(); phase = "problem" }
            val reason = "туннель ${fmt(tunnelMbps)} < порог ${fmt(effThr)}" +
                if (weak) " (слабый интернет, 50% от ${fmt(directMbps)})" else " (прямой ${fmt(directMbps)})"
            MonitorStatus.update(true, "падение ($failures)", now(), cycles)
            if (failures == 1) MonitorLog.event(app, "monitor", "Туннель: первая неудача", reason)

            if (failures >= cur.monitorFailuresToVerdict && !verdictActed) {
                verdictActed = true
                if (now() - lastSwitchMs < SWITCH_THROTTLE_MS) {
                    MonitorLog.event(app, "monitor", "Падение — переключение отложено", "$reason · троттлинг 60с")
                } else {
                    MonitorLog.event(app, "monitor", "Падение туннеля — ищу замену", reason)
                    when (runSwitchSearch(app, cur)) {
                        SwitchResult.SWITCHED -> {
                            lastSwitchMs = now(); phase = "ok"; failures = 0; verdictActed = false; backoffMs = 0
                            MonitorPrompt.resetDeclined()
                        }
                        SwitchResult.ABORTED -> { /* пользователь вмешался — оставляем как есть до следующего цикла */ }
                        SwitchResult.NO_CANDIDATES -> {
                            backoffMs = if (backoffMs == 0L) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
                            MonitorLog.event(app, "monitor", "Замену не нашёл — пауза ${humanDur(backoffMs)}", "")
                            MonitorStatus.update(true, "нет замены, пауза ${humanDur(backoffMs)}", now(), cycles)
                            MonitorCoordinator.drainWakeups(); MonitorCoordinator.awaitWake(backoffMs)
                        }
                    }
                }
            }
        }
    }

    /** Переход в здоровое состояние: восстановление (со временем простоя) — событие; рутину не пишем. */
    private fun onHealthy(context: Context, prevPhase: String, problemSince: Long, tunnelStr: String) {
        if (prevPhase == "problem") {
            MonitorLog.event(context, "monitor", "Восстановление туннеля", "скорость $tunnelStr, длилось ${humanDur(now() - problemSince)}")
        }
        // Здоровы → снимаем предложение включить источники (если висело) и разрешаем спрашивать снова.
        if (MonitorPrompt.pending) { MonitorPrompt.clear(); NotificationHelper.cancelEnableSources(context) }
        MonitorPrompt.resetDeclined()
    }

    // ---- Перебор кандидатов / переключение ----

    private suspend fun runSwitchSearch(app: Context, s: AppSettings): SwitchResult {
        MonitorCoordinator.monitorSearchRunning = true
        try {
            return runSwitchSearchInner(app, s)
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
        }
    }

    private suspend fun runSwitchSearchInner(app: Context, s: AppSettings): SwitchResult {
        val startKey = ProxyState.state.value.serverKey   // если сменится не нами — значит вмешался пользователь

        val r1 = probeAndConnect(app, s, startKey, "1")
        if (r1 != SwitchResult.NO_CANDIDATES) return r1

        // Никто не подошёл → обновить ВСЕ включённые подписки → пройти список заново.
        if (aborted(startKey)) return SwitchResult.ABORTED
        MonitorLog.event(app, "monitor", "Никто не подошёл — обновляю подписки", "")
        MonitorStatus.update(true, "обновляю подписки", now(), 0)
        runCatching { SubscriptionManager.refreshAllEnabled(app, cancelled = { aborted(startKey) }, onEach = { _, _ -> }) }
            .onFailure { MonitorLog.event(app, "error", "Ошибка обновления подписок", it.message ?: "") }
        if (aborted(startKey)) return SwitchResult.ABORTED

        val r2 = probeAndConnect(app, s, startKey, "2")
        if (r2 != SwitchResult.NO_CANDIDATES) return r2

        handleNoAlive(app)   // пункт E: предложить включить выключенные источники (или записать «все включены»)
        return SwitchResult.NO_CANDIDATES
    }

    /** true, если перебор надо прервать: идёт ручной тест ИЛИ активный сервер сменил кто-то другой (пользователь). */
    private fun aborted(startKey: String?): Boolean =
        MonitorCoordinator.fullTestRunning || ProxyState.state.value.serverKey != startKey

    /**
     * Один проход по ВСЕМУ списку (от быстрых к медленным). Кандидаты — единый предикат (протокол +
     * стоп-лист), МИНУЯ пинг-фильтр (мёртвый пинг ≠ мёртвый сервер, v2rayN). Дешёвая проверка на temp-
     * инстансе; подключаемся к ПЕРВОМУ живому; скорость меряем ПОСЛЕ.
     */
    private suspend fun probeAndConnect(app: Context, s: AppSettings, startKey: String?, round: String): SwitchResult {
        val bl = BlocklistStore.current()
        val candidates = SubscriptionManager.allServers(app)
            .filter { SubscriptionManager.serverKey(it) != startKey }
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) }
            .sortedByDescending { it.speedMbps ?: 0.0 }
        val total = candidates.size

        for ((i, c) in candidates.withIndex()) {
            if (aborted(startKey)) return SwitchResult.ABORTED
            MonitorStatus.update(true, "перебор $round: ${i + 1}/$total · ${ServerLabels.display(c)}", now(), 0)
            // Дешёвая проверка (Промпт 52): temp-инстанс + РЕАЛЬНАЯ передача нескольких КБ (байты пришли),
            // НЕ пинг/задержка — иначе «не пингуется, но работает» серверы отбрасывались бы молча. НЕ полный замер.
            if (!ServerSpeedTester.probeAlive(app, c)) continue
            val cfg = runCatching { XrayConfigBuilder.build(c) }.getOrNull()
            if (cfg == null) { MonitorLog.event(app, "error", "Кандидат ${ServerLabels.display(c)}: ошибка конфига", ""); continue }
            val from = ServerLabels.displayForKey(app, startKey)
            XrayProxyService.start(app, cfg, ServerLabels.full(c), SubscriptionManager.serverKey(c))
            MonitorLog.switch(app, from, ServerLabels.display(c), "монитор", "первый живой (проход $round)")
            measureAfterConnect(app, c)
            return SwitchResult.SWITCHED
        }
        return SwitchResult.NO_CANDIDATES
    }

    /** Скорость измеряем ПОСЛЕ подключения (пользователю нужна связь, не число) и записываем серверу. */
    private suspend fun measureAfterConnect(app: Context, c: com.picosoft.xrayproxydroid.xray.link.ServerProfile) {
        val key = SubscriptionManager.serverKey(c)
        var waited = 0
        while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != key)) {
            delay(500); waited += 500
        }
        delay(1000)   // дать туннелю осесть
        val mbps = measureTunnelMbps()
        if (mbps > 0) {
            SubscriptionManager.applySpeedResults(app, mapOf(key to mbps))
            MonitorLog.event(app, "monitor", "Скорость нового сервера", "${fmt(mbps)}")
        }
    }

    /** Пункт E: живых нет после двух проходов. Есть выключенные источники → предложить (не включать сами). */
    private fun handleNoAlive(app: Context) {
        val disabled = SubscriptionManager.sources(app).filter { !it.enabled }
        if (disabled.isNotEmpty() && !MonitorPrompt.declined) {
            val srv = SubscriptionManager.serversFromDisabled(app)
            MonitorPrompt.request(disabled.size, srv)
            NotificationHelper.notifyEnableSources(app, disabled.size, srv)
            MonitorLog.event(app, "monitor", "Нет живых серверов", "есть ${disabled.size} выключенных источников (~$srv серв.) — предложил включить")
        } else {
            MonitorLog.event(app, "monitor", "Нет живых серверов",
                if (disabled.isEmpty()) "все источники включены" else "пользователь отказался включать")
        }
    }

    // ---- Замеры/утилиты ----

    private fun now(): Long = System.currentTimeMillis()

    private fun fmt(mbps: Double) = "${(mbps * 10).roundToInt() / 10.0} Мбит/с"

    private fun humanDur(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}с"
            sec < 3600 -> "${sec / 60}м"
            else -> "${sec / 3600}ч ${(sec % 3600) / 60}м"
        }
    }

    private fun directAlive(): Boolean {
        for ((host, port) in directDnsProbes) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 3000); return true }
            } catch (e: Exception) { /* следующий */ }
        }
        return false
    }

    private fun measureDirectMbps(context: Context): Double? {
        for (url in directSpeedProbes) {
            try {
                val start = System.nanoTime()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000; readTimeout = 3000
                    setRequestProperty("User-Agent", "curl/8.0")
                }
                if (conn.responseCode !in 200..399) { conn.disconnect(); continue }
                var totalBytes = 0L
                conn.inputStream.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        totalBytes += n
                        if (totalBytes >= 1_000_000L || (System.nanoTime() - start) / 1e9 > 3.0) break
                    }
                }
                conn.disconnect()
                val secs = (System.nanoTime() - start) / 1e9
                if (totalBytes > 0 && secs > 0) {
                    TrafficTracker.addTest(totalBytes)
                    return totalBytes * 8 / 1e6 / secs
                }
            } catch (e: Exception) { /* следующий */ }
        }
        return null
    }

    private fun measureTunnelMbps(): Double {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT))
            val start = System.nanoTime()
            val conn = (URL(TUNNEL_SPEED_PROBE).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 6000
                setRequestProperty("User-Agent", "curl/8.0")
            }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return 0.0 }
            var totalBytes = 0L
            conn.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    totalBytes += n
                    if (totalBytes >= 2_000_000L || (System.nanoTime() - start) / 1e9 > 6.0) break
                }
            }
            conn.disconnect()
            val secs = (System.nanoTime() - start) / 1e9
            if (totalBytes > 0 && secs > 0) totalBytes * 8 / 1e6 / secs else 0.0
        } catch (e: Exception) { 0.0 }
    }
}
