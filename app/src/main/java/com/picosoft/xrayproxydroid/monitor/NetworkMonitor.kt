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

    // Промпт 98.C: неподтверждённый провал перепроверяем БЫСТРО (не ждём полный интервал 60с — это и давало «минуту»).
    private const val RECHECK_MS = 4_000L
    // Промпт 98.B: событие (передний план) вскоре после подтверждённого ОК — не гоняем внешний запрос заново.
    private const val RECENT_OK_SKIP_MS = 45_000L
    private const val SWITCH_THROTTLE_MS = 60_000L        // в фоне переключаться не чаще раза в 60с
    private const val BACKOFF_START_MS = 10 * 60_000L     // первая пауза при отсутствии сети — 10 минут
    // Верхний предел паузы — 4 часа: дальше удваивать бессмысленно (это уже «редкая фоновая проверка»),
    // а ConnectivityManager всё равно разбудит мгновенно при появлении сети; экономим батарею при долгом офлайне.
    private const val BACKOFF_MAX_MS = 4 * 3600_000L

    private enum class SwitchResult { SWITCHED, ABORTED, NO_CANDIDATES }

    /**
     * Промпт 95 — ФАКТ-ПЕРВЫЙ цикл непрерывности связи. Работает ВСЕГДА, пока прокси активен (НЕ гейтится
     * monitorEnabled — это ОСНОВНОЙ принцип, а не опция; monitorEnabled гейтит лишь вторичную оптимизацию
     * «держать лучший»). ЖИВОСТЬ = ФАКТ прохождения запроса (внешний IP через SOCKS). Провал = мёртвый
     * туннель → немедленная лестница восстановления. Проверка идёт и по расписанию, и по событиям (wake()).
     */
    suspend fun loop(context: Context) {
        val app = context.applicationContext
        var failures = 0
        var backoffMs = 0L
        var cycles = 0
        var lastSwitchMs = 0L
        var lastOptimizeMs = now()
        Log.i(TAG, "monitor loop started")

        while (true) {
            if (!XrayController.isRunning || !ProxyState.state.value.running) { TunnelHealth.reset(); return }

            // Интервал: неподтверждённый провал → перепроверяем БЫСТРО (Промпт 98.C); восстановление/нет-серверов →
            // чаще обычного; в норме — обычный. Прерываемый wake() — событийные триггеры поднимают ДОСРОЧНО.
            val s = SettingsStore.current()
            val ph = TunnelHealth.snapshot().phase
            val recovering = ph == TunnelHealth.Phase.RECOVERING || ph == TunnelHealth.Phase.NO_SERVERS
            val confirming = failures in 1 until s.monitorFailuresToVerdict
            val waitMs = when {
                confirming -> RECHECK_MS
                recovering -> s.monitorProblemIntervalSec.coerceAtLeast(15) * 1000L
                else -> s.connectionCheckIntervalSec.coerceAtLeast(15) * 1000L
            }
            MonitorCoordinator.drainWakeups()
            val wokenEarly = MonitorCoordinator.awaitWake(waitMs)
            val forced = MonitorCoordinator.consumeForceRecheck()

            val cur = SettingsStore.current()
            if (!XrayController.isRunning || !ProxyState.state.value.running) { TunnelHealth.reset(); return }
            if (MonitorCoordinator.fullTestRunning) {
                // Тест сам управляет переключением — лестницу НЕ запускаем. Но статус держим ЧЕСТНЫМ:
                // нет интернета → сказать; иначе не трогаем (факт подтвердит UI-проверка/следующий цикл).
                if (!directAlive()) TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
                continue
            }

            // ── Промпт 98.B: возврат на передний план (частое переключение приложений) ВСКОРЕ после подтверждённого
            // ОК — НЕ повторяем внешний запрос. Достаточно, что прямой интернет на месте. Сеть/экран (forced) и
            // истёкший интервал (не wokenEarly) — полная проверка. Реальную смерть в окне пропуска ловит след. цикл.
            val snap = TunnelHealth.snapshot()
            if (wokenEarly && !forced && failures == 0 && snap.phase == TunnelHealth.Phase.OK &&
                now() - snap.lastOkMs < RECENT_OK_SKIP_MS) {
                if (!directAlive()) {
                    TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
                    MonitorStatus.update(true, "нет интернета", now(), cycles)
                }
                continue
            }
            cycles++

            // ── СИГНАЛ A: есть ли интернет ВООБЩЕ (прямой канал) — отличаем «нет интернета» от «нет серверов» ──
            if (!directAlive()) {
                TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
                NotificationHelper.cancelNoServers(app)   // это НЕ «нет серверов» — туннель не виноват
                backoffMs = if (backoffMs == 0L) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
                MonitorStatus.update(true, "нет интернета, пауза ${humanDur(backoffMs)}", now(), cycles)
                MonitorCoordinator.drainWakeups(); MonitorCoordinator.awaitWake(backoffMs)
                continue
            }

            // ── СИГНАЛ B (ФАКТ): реальный запрос через SOCKS вернул внешний IP = связь РАБОТАЕТ ──
            // Промпт 98.A: при первом провале (failures==0) — ПОВТОР с прогревом (холодный сокет после простоя),
            // чтобы не объявлять живой туннель мёртвым. На перепроверках повтор не нужен.
            val ip = probeExternalIp(app, warmRetry = (failures == 0))
            if (ip != null) {
                onRecovered(app)
                TunnelHealth.ok(ip, now())
                failures = 0; backoffMs = 0
                MonitorStatus.update(true, "ок", now(), cycles)
                // Вторичное: «держать лучший» — ТОЛЬКО если monitorEnabled, в норме, раз в monitorOptimizeSec, без живого трафика.
                if (cur.monitorEnabled && cur.monitorOptimizeSec > 0 && now() - lastOptimizeMs >= cur.monitorOptimizeSec * 1000L && !userTrafficActive()) {
                    lastOptimizeMs = now()
                    runCatching { optimizeToFastest(app, cur) }.onFailure { MonitorLog.event(app, "error", "Ошибка оптимизации", it.message ?: "") }
                }
                continue
            }

            // Живой трафик пользователя — тоже ФАКТ работы туннеля (сигнал B мог мигнуть).
            if (userTrafficActive()) {
                onRecovered(app)
                TunnelHealth.ok(TunnelHealth.snapshot().ip, now())
                failures = 0; backoffMs = 0
                MonitorStatus.update(true, "ок (активный трафик)", now(), cycles)
                continue
            }

            // ── ЗАПРОС НЕ ПРОШЁЛ (даже с повтором) → ПОДТВЕРЖДЕНИЕ, потом восстановление ──
            failures++
            if (failures < cur.monitorFailuresToVerdict) {
                // Промпт 98.D: провал НЕ подтверждён (одиночная миганка / холодный сокет) — НЕ пугаем «туннель упал».
                // Статус «проверяю связь…» (не восстановление), быстрая перепроверка (RECHECK_MS), не минута.
                TunnelHealth.setPhase(TunnelHealth.Phase.VERIFYING, now(), "проверяю связь…")
                MonitorStatus.update(true, "проверяю ($failures)", now(), cycles)
                MonitorLog.event(app, "monitor", "Связь не подтвердилась — перепроверяю", "попытка $failures из ${cur.monitorFailuresToVerdict}")
                continue
            }
            // Отказ ПОДТВЕРЖДЁН (несколько неудач подряд) → лестница восстановления (сама, без пользователя).
            TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "восстановление…")
            MonitorStatus.update(true, "восстановление", now(), cycles)
            MonitorLog.event(app, "monitor", "Связь не подтверждена — восстанавливаю", "внешний IP не получен ($failures×)")

            when (runRecoveryLadder(app, cur)) {
                SwitchResult.SWITCHED -> {
                    lastSwitchMs = now(); failures = 0; backoffMs = 0
                    val ip2 = ExternalIpChecker.fetch()   // подтвердить ФАКТОМ сразу
                    if (ip2 != null) { TunnelHealth.ok(ip2, now()); onRecovered(app) }
                    else TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "проверяю после переключения…")
                }
                SwitchResult.ABORTED -> { /* пользователь вмешался — оставляем как есть */ }
                SwitchResult.NO_CANDIDATES -> {
                    // РАБОЧИХ СЕРВЕРОВ НЕТ НИ ОДНИМ СПОСОБОМ — СКАЗАТЬ ПРЯМО (Промпт 95.E) + уведомление в шторке.
                    TunnelHealth.setPhase(TunnelHealth.Phase.NO_SERVERS, now(), "рабочих серверов нет")
                    NotificationHelper.notifyNoServers(app)
                    backoffMs = if (backoffMs == 0L) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
                    MonitorLog.event(app, "monitor", "Рабочих серверов нет — пауза ${humanDur(backoffMs)}", "выход по любому событию")
                    MonitorStatus.update(true, "нет рабочих серверов, пауза ${humanDur(backoffMs)}", now(), cycles)
                    MonitorCoordinator.drainWakeups(); MonitorCoordinator.awaitWake(backoffMs)
                }
            }
        }
    }

    /**
     * Промпт 98.A: живость туннеля фактом (внешний IP), с ПОВТОРОМ при первом провале. Холодный сокет после
     * простоя (возврат из фона/Doze) может не ответить на первый запрос — это ещё не смерть. Логирует
     * длительность и исход (для разбора «из чего минута» и ложных падений). warmRetry=false на перепроверках.
     */
    private suspend fun probeExternalIp(app: Context, warmRetry: Boolean): String? {
        val t0 = now()
        ExternalIpChecker.fetch()?.let { return it }
        if (!warmRetry) {
            Log.i(TAG, "внешний IP не получен за ${now() - t0} мс (без прогрева)")
            return null
        }
        delay(1500)   // дать холодному туннелю ожить, затем повторить
        val ip = ExternalIpChecker.fetch()
        if (ip != null) MonitorLog.event(app, "monitor", "Связь подтвердилась со 2-й попытки", "холодный туннель ожил за ${now() - t0} мс")
        else Log.i(TAG, "внешний IP не получен даже с повтором за ${now() - t0} мс")
        return ip
    }

    /** Связь подтверждена — снять предложения/уведомления «нет серверов»/«включить источники». */
    private fun onRecovered(context: Context) {
        if (MonitorPrompt.pending) { MonitorPrompt.clear(); NotificationHelper.cancelEnableSources(context) }
        MonitorPrompt.resetDeclined()
        NotificationHelper.cancelNoServers(context)
    }

    /**
     * ЛЕСТНИЦА ВОССТАНОВЛЕНИЯ (Промпт 95.C), сама, по порядку:
     *  1) ПЕРЕЗАПУСК ЯДРА на том же сервере (после смены сети соединения привязаны к ушедшей сети, сами не оживают);
     *  2–5) перебор живых сверху вниз → обновить подписки → перебор заново → предложить включить источники.
     * После каждой ступени — подтверждение ФАКТОМ (внешний IP). Отменяется, если вмешался пользователь.
     */
    private suspend fun runRecoveryLadder(app: Context, s: AppSettings): SwitchResult {
        MonitorCoordinator.monitorSearchRunning = true
        try {
            val startKey = ProxyState.state.value.serverKey
            // Ступень 1: перезапуск ядра на ТОМ ЖЕ сервере.
            if (startKey != null && !aborted(startKey)) {
                val curSrv = SubscriptionManager.allServers(app).firstOrNull { SubscriptionManager.serverKey(it) == startKey }
                val cfg = curSrv?.let { runCatching { XrayConfigBuilder.build(it) }.getOrNull() }
                if (curSrv != null && cfg != null) {
                    MonitorLog.event(app, "monitor", "Восстановление 1: перезапуск ядра", ServerLabels.display(curSrv))
                    TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "перезапуск ядра…")
                    XrayProxyService.start(app, cfg, ServerLabels.full(curSrv), startKey)
                    // Промпт 98.C: перезапуск на том же сервере должен занимать секунды. Ждём готовности ядра
                    // короче (до 5с), даём осесть 800мс, подтверждаем фактом с повтором (холодный сокет).
                    var waited = 0
                    while (waited < 5000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != startKey)) { delay(250); waited += 250 }
                    delay(800)
                    if (aborted(startKey)) return SwitchResult.ABORTED
                    if (probeExternalIp(app, warmRetry = true) != null) { MonitorLog.event(app, "monitor", "Восстановление: перезапуск ядра помог", ""); return SwitchResult.SWITCHED }
                }
            }
            // Ступени 2–5.
            return runSwitchSearchInner(app, s)
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
        }
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
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
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
        val mbps = ServerSpeedTester.measureActiveDownloadMbps(app)   // сопоставимо с кандидатами (Промпт 90.A)
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

    /**
     * «Держать самый быстрый» (Промпт 82): в здоровом состоянии перемерять top-[normalTopBatch] по известной
     * скорости и переключиться на быстрейший, если он БЫСТРЕЕ текущего на >margin. Текущий меряем реальным
     * туннелем (measureTunnelMbps), остальных — temp-инстансом (активный прокси не трогаем). Трафик заметный —
     * потому раз в час по умолчанию. Уступаем ручному тесту и ручной смене сервера.
     */
    private suspend fun optimizeToFastest(app: Context, s: AppSettings) {
        val curKey = ProxyState.state.value.serverKey ?: return
        val bl = BlocklistStore.current()
        val topN = SubscriptionManager.allServers(app)
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
            .sortedByDescending { it.speedMbps ?: 0.0 }
            .take(s.normalTopBatch.coerceAtLeast(1))
        if (topN.size <= 1) return
        MonitorLog.event(app, "monitor", "Оптимизация: перемер top-${topN.size} по скорости", "правило «держать лучший» ×${s.keepBestMultiplier}")
        MonitorStatus.update(true, "оптимизация: перемер top-${topN.size}", now(), 0)
        MonitorCoordinator.monitorSearchRunning = true
        try {
            val curMbps = ServerSpeedTester.measureActiveDownloadMbps(app)   // сопоставимо с кандидатами (90.A)
            val results = HashMap<String, Double>()
            if (curMbps > 0) results[curKey] = curMbps
            var bestKey: String? = null; var bestMbps = 0.0
            for (c in topN) {
                if (MonitorCoordinator.fullTestRunning) return               // ручной тест — уступаем
                if (ProxyState.state.value.serverKey != curKey) return       // пользователь сменил — уступаем
                val key = SubscriptionManager.serverKey(c)
                if (key == curKey) continue
                val mbps = ServerSpeedTester.measureSpeed(app, c)            // temp-инстанс
                results[key] = mbps
                if (mbps > bestMbps) { bestMbps = mbps; bestKey = key }
            }
            SubscriptionManager.applySpeedResults(app, results)
            // ПРАВИЛО «ДЕРЖАТЬ ЛУЧШИЙ» (Промпт 90.A): переключаемся, если лучший быстрее текущего КРАТНО
            // (в keepBestMultiplier раз), даже когда текущий выше порога. Кратно — чтобы шум не дёргал.
            if (bestKey != null && curMbps > 0 && bestMbps >= curMbps * s.keepBestMultiplier) {
                val target = topN.first { SubscriptionManager.serverKey(it) == bestKey }
                val cfg = runCatching { XrayConfigBuilder.build(target) }.getOrNull() ?: return
                val from = ServerLabels.displayForKey(app, curKey)
                XrayProxyService.start(app, cfg, ServerLabels.full(target), bestKey!!)
                MonitorLog.switch(app, from, ServerLabels.display(target), "монитор: держать лучший",
                    "было ${fmt(curMbps)} → стало ${fmt(bestMbps)} (×${s.keepBestMultiplier})")
            } else {
                MonitorLog.event(app, "monitor", "Оптимизация: смены нет",
                    "текущий ${fmt(curMbps)}, лучший из top ${fmt(bestMbps)} (нужно ×${s.keepBestMultiplier})")
            }
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
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

    // internal (не private): переиспользуется кнопкой «Проверить» на главной как интернет-гейт.
    internal fun directAlive(): Boolean {
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

    /**
     * Идёт ли ЖИВОЙ трафик пользователя через туннель (Промпт 90.D): дельта принятых+отданных байт за ~1.2с.
     * Порог 128 КБ отсекает служебные keepalive, но ловит реальную загрузку. Если идёт — тяжёлый замер
     * пропускаем (испортили бы и замер, и его загрузку). Байты — накопленные из TrafficTracker (поллер
     * сервиса их обновляет; queryTunnelDelta НЕ трогаем — её потребляет поллер).
     */
    internal suspend fun userTrafficActive(): Boolean {
        val before = TrafficTracker.state.value.let { it.sessionRx + it.sessionTx }
        delay(1200)
        val after = TrafficTracker.state.value.let { it.sessionRx + it.sessionTx }
        return after - before > 128 * 1024
    }
}
