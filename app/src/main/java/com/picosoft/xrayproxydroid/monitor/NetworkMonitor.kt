package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
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
import java.net.InetSocketAddress
import java.net.Socket
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

    private const val SWITCH_THROTTLE_MS = 60_000L        // в фоне переключаться не чаще раза в 60с
    // Пауза при «НЕТ РАБОЧИХ СЕРВЕРОВ» (интернет есть, но ни один сервер не поднялся): удвоение 10 мин → 4 ч.
    private const val BACKOFF_START_MS = 10 * 60_000L     // первая пауза «нет серверов» — 10 минут
    // Верхний предел паузы — 4 часа: дальше удваивать бессмысленно (это уже «редкая фоновая проверка»),
    // а ConnectivityManager всё равно разбудит мгновенно при появлении сети; экономим батарею при долгом офлайне.
    private const val BACKOFF_MAX_MS = 4 * 3600_000L

    // НЕТ ИНТЕРНЕТА (прямой канал мёртв): туннель чинить бессмысленно — НЕ трогаем ядро. Честно «нет связи» и
    // ПЕРЕСПРАШИВАЕМ прямой канал по нарастающей: 1→2→4→8→16→…→30 мин, пока интернет не появится. Событие сети
    // (ConnectivityManager) поднимает мгновенно; экспонента — лишь верхняя граница проактивной проверки.
    private const val NO_NET_BACKOFF_START_MS = 60_000L        // первый повтор — через 1 мин
    private const val NO_NET_BACKOFF_MAX_MS = 30 * 60_000L     // потолок повтора — 30 мин

    // Живая скорость плашки: не чаще раза в ~50с (совпадает с циклом при connectionCheckIntervalSec=60);
    // окно пассивного замера — 6с (счётчик трафика тикает раз в POLL_MS=2.5с → окно должно перекрыть ≥2 тика,
    // иначе дельта случайно 0 при живом трафике); порог «трафик идёт» — 32 КБ за окно (keepalive < этого).
    private const val SPEED_SAMPLE_MIN_GAP_MS = 50_000L
    private const val SPEED_PASSIVE_WINDOW_MS = 6_000L
    private const val SPEED_PASSIVE_MIN_BYTES = 32L * 1024L

    private enum class SwitchResult { SWITCHED, ABORTED, NO_CANDIDATES }

    // Промпт 102 (ГЛАВНЫЙ ФИКС): ключ сервера, ядро которого УЖЕ перезапускали в текущем эпизоде обрыва.
    // ПОЧЕМУ: перезапуск ядра (шаг 1 лестницы) через XrayProxyService.start ставит running=false → сервис
    // пересоздаёт корутину монитора и УБИВАЕТ идущую лестницу ещё ДО перебора серверов; новый монитор снова
    // делает шаг 1 → ВЕЧНЫЙ ЦИКЛ на мёртвом сервере (подтверждено логами P102). Поле object'а переживает рестарт
    // монитора: один и тот же сервер повторно НЕ перезапускаем — сразу к перебору живых. Сбрасывается при OK.
    @Volatile private var restartedKeySinceOk: String? = null

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
        var noNetBackoffMs = 0L         // экспонента повтора прямого канала при «нет интернета» (1→2→4→…→30 мин)
        var cycles = 0
        var lastSwitchMs = 0L
        var lastOptimizeMs = now()
        var lastSpeedSampleMs = 0L      // живая скорость плашки: когда последний раз сэмплировали
        var lastActiveProbeMs = 0L      // когда последний раз делали АКТИВНУЮ пробу (в простое)
        Log.i(TAG, "monitor loop started")

        while (true) {
            if (!XrayController.isRunning || !ProxyState.state.value.running) { TunnelHealth.reset(); TunnelSpeed.clear(); return }

            // Интервал: при восстановлении/нет-серверов — чаще (быстрее вернуть связь); в норме — обычный.
            // Прерываемый wake() — событийные триггеры (сеть/экран/передний план) поднимают ДОСРОЧНО.
            val s = SettingsStore.current()
            val ph = TunnelHealth.snapshot().phase
            val waitMs = when (ph) {
                // Нет интернета — переспрашиваем прямой канал по экспоненте (1→2→…→30 мин), не чаще.
                TunnelHealth.Phase.NO_INTERNET -> noNetBackoffMs.coerceAtLeast(NO_NET_BACKOFF_START_MS)
                // Восстановление туннеля / нет серверов — чаще (быстрее вернуть связь).
                TunnelHealth.Phase.RECOVERING, TunnelHealth.Phase.NO_SERVERS -> s.monitorProblemIntervalSec.coerceAtLeast(15) * 1000L
                else -> s.connectionCheckIntervalSec.coerceAtLeast(15) * 1000L
            }
            MonitorCoordinator.drainWakeups()
            MonitorCoordinator.awaitWake(waitMs)

            val cur = SettingsStore.current()
            if (!XrayController.isRunning || !ProxyState.state.value.running) { TunnelHealth.reset(); TunnelSpeed.clear(); return }
            if (MonitorCoordinator.fullTestRunning) {
                // Тест сам управляет переключением — лестницу НЕ запускаем. Но статус держим ЧЕСТНЫМ:
                // нет интернета → сказать; иначе не трогаем (факт подтвердит UI-проверка/следующий цикл).
                if (!directAlive()) TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
                continue
            }
            cycles++

            // ── СИГНАЛ A: есть ли интернет ВООБЩЕ (прямой канал) — отличаем «нет интернета» от «нет серверов» ──
            // Нет интернета → туннель чинить БЕССМЫСЛЕННО (ядро НЕ трогаем): честно «нет связи» + переспрос
            // прямого канала по экспоненте (пауза берётся вверху по фазе NO_INTERNET). Интернет вернётся → ниже
            // проверим туннель и активируем его по нашим правилам (лестница).
            if (!directAlive()) {
                failures = 0
                noNetBackoffMs = enterNoInternet(app, noNetBackoffMs, cycles)
                continue
            }
            noNetBackoffMs = 0L   // интернет есть — сбросить экспоненту повтора

            // ── СИГНАЛ B (ФАКТ): реальный запрос через SOCKS вернул внешний IP = связь РАБОТАЕТ ──
            val ip = ExternalIpChecker.fetch()
            if (ip != null) {
                onRecovered(app)
                TunnelHealth.ok(ip, now())
                failures = 0; backoffMs = 0; restartedKeySinceOk = null   // Промпт 102: связь ок — сбросить «уже перезапускали»
                MonitorStatus.update(true, "ок", now(), cycles)
                // ЖИВАЯ скорость плашки (гибрид): раз в цикл сэмплируем — реальный трафик даёт скорость
                // бесплатно, иначе активная проба в простое. Держим ↓/↑ и время замера свежими.
                if (cur.liveSpeedEnabled && now() - lastSpeedSampleMs >= SPEED_SAMPLE_MIN_GAP_MS) {
                    lastSpeedSampleMs = now()
                    lastActiveProbeMs = runCatching { sampleTunnelSpeed(app, cur, lastActiveProbeMs) }
                        .onFailure { MonitorLog.event(app, "error", "Ошибка замера скорости", it.message ?: "") }
                        .getOrDefault(lastActiveProbeMs)
                }
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
                failures = 0; backoffMs = 0; restartedKeySinceOk = null   // Промпт 102
                MonitorStatus.update(true, "ок (активный трафик)", now(), cycles)
                continue
            }

            // Туннель не подтвердился. ПЕРЕПРОВЕРИТЬ прямой канал: интернет мог пропасть между сигналами A и B
            // (или мигал). Нет интернета → это НЕ поломка туннеля — ядро НЕ перезапускаем, честно «нет связи».
            if (!directAlive()) {
                failures = 0
                noNetBackoffMs = enterNoInternet(app, noNetBackoffMs, cycles)
                continue
            }

            // ── ИНТЕРНЕТ ЕСТЬ, но туннель не пропускает → ЛЕСТНИЦА ВОССТАНОВЛЕНИЯ (активируем туннель по правилам) ──
            failures++
            TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "восстановление…")
            MonitorStatus.update(true, "восстановление ($failures)", now(), cycles)
            if (failures == 1) MonitorLog.event(app, "monitor", "Связь не подтверждена — восстанавливаю", "внешний IP не получен")
            if (failures < cur.monitorFailuresToVerdict) continue   // короткий анти-дребезг (одиночная миганка)

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
     * НЕТ ИНТЕРНЕТА (прямой канал мёртв): честно сказать «нет связи», ядро НЕ трогаем. Возвращает СЛЕДУЮЩУЮ паузу
     * экспоненты (1→2→4→…→30 мин) — само ожидание делает верх цикла по фазе NO_INTERNET (прерывается событием сети).
     */
    private fun enterNoInternet(app: Context, prevBackoffMs: Long, cycles: Int): Long {
        TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
        NotificationHelper.cancelNoServers(app)   // это НЕ «нет серверов» — туннель не виноват
        val b = if (prevBackoffMs == 0L) NO_NET_BACKOFF_START_MS else minOf(prevBackoffMs * 2, NO_NET_BACKOFF_MAX_MS)
        MonitorStatus.update(true, "нет интернета · повтор через ${humanDur(b)}", now(), cycles)
        return b
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
            // Ступень 1: перезапуск ядра на ТОМ ЖЕ сервере — ТОЛЬКО ОДИН РАЗ за эпизод обрыва (Промпт 102).
            // Если этот сервер уже перезапускали и связь не вернулась — перезапуск бесполезен (сервер мёртв) И
            // вдобавок убивает лестницу (см. restartedKeySinceOk). Пропускаем шаг 1 → сразу перебор живых серверов.
            val alreadyRestarted = startKey != null && startKey == restartedKeySinceOk
            if (startKey != null && !aborted(startKey) && !alreadyRestarted) {
                val curSrv = SubscriptionManager.allServers(app).firstOrNull { SubscriptionManager.serverKey(it) == startKey }
                val cfg = curSrv?.let { runCatching { XrayConfigBuilder.build(it) }.getOrNull() }
                if (curSrv != null && cfg != null) {
                    // Отметить ДО start: XrayProxyService.start ставит running=false → монитор (и эта корутина)
                    // пересоздаётся, код НИЖЕ может не выполниться; флаг object'а переживёт рестарт.
                    restartedKeySinceOk = startKey
                    MonitorLog.event(app, "monitor", "Восстановление 1: перезапуск ядра", ServerLabels.display(curSrv))
                    TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "перезапуск ядра…")
                    XrayProxyService.start(app, cfg, ServerLabels.full(curSrv), startKey)
                    var waited = 0
                    while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != startKey)) { delay(500); waited += 500 }
                    delay(1500)
                    if (aborted(startKey)) return SwitchResult.ABORTED
                    if (ExternalIpChecker.fetch() != null) { MonitorLog.event(app, "monitor", "Восстановление: перезапуск ядра помог", ""); return SwitchResult.SWITCHED }
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
            TunnelSpeed.setProbe(null, mbps, null, now())   // начальная туннельная ↓ (прямую/↑ доберёт проба в простое)
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

    /**
     * ЖИВАЯ скорость активного туннеля для плашки (гибрид). Возвращает обновлённое время последней АКТИВНОЙ пробы.
     *  1) ПАССИВНО (бесплатно): дельта туннельных байт (TrafficTracker) за окно ~2с. Идёт реальный трафик →
     *     это и есть актуальная скорость ↓/↑ → source=LIVE. Ничего лишнего не качаем.
     *  2) ПРОСТОЙ (трафика нет): раз в [liveSpeedActiveProbeSec] и ТОЛЬКО при включённом экране — активная проба
     *     download+upload через туннель → source=PROBE. Экономия: не тратим трафик на замер для невидимой плашки.
     * Download активной пробы попутно пишем серверу (обновляет список «Живые» — решение «то, что монитор меряет»).
     */
    private suspend fun sampleTunnelSpeed(app: Context, cur: AppSettings, lastActiveProbeMs: Long): Long {
        val curKey = ProxyState.state.value.serverKey ?: return lastActiveProbeMs

        // (1) Пассивно: реальный трафик пользователя даёт скорость бесплатно.
        val t0 = SystemClock.elapsedRealtime()
        val rx0 = TrafficTracker.state.value.sessionRx; val tx0 = TrafficTracker.state.value.sessionTx
        delay(SPEED_PASSIVE_WINDOW_MS)
        val dtSec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val dRx = (TrafficTracker.state.value.sessionRx - rx0).coerceAtLeast(0)
        val dTx = (TrafficTracker.state.value.sessionTx - tx0).coerceAtLeast(0)
        if (dtSec > 0 && dRx + dTx > SPEED_PASSIVE_MIN_BYTES) {
            val down = dRx * 8.0 / dtSec / 1_000_000.0
            val up = dTx * 8.0 / dtSec / 1_000_000.0
            TunnelSpeed.setLive(down, up, now())   // прямую скорость сохраняем от прошлой пробы
            return lastActiveProbeMs
        }

        // (2) Простой: активная проба — только если пришло время И экран включён (иначе не тратим трафик).
        if (now() - lastActiveProbeMs < cur.liveSpeedActiveProbeSec * 1000L) return lastActiveProbeMs
        if (!screenInteractive(app)) return lastActiveProbeMs
        if (MonitorCoordinator.fullTestRunning || ProxyState.state.value.serverKey != curKey) return lastActiveProbeMs
        probeActiveSpeedNow(app)
        return now()
    }

    /**
     * ПРИОРИТЕТНЫЙ активный замер СЕЙЧАС: прямой канал + туннель ↓/↑ активного сервера → в [TunnelSpeed] (плашка).
     * Блокирующий (зовётся из потока полного теста сразу после подключения И из монитора). Обратная связь после
     * 0.23: чтобы плашка показывала РЕАЛЬНЫЕ числа активного сервера ASAP (а не «замеряю…» до цикла монитора),
     * и чтобы они были ЕДИНЫМ источником правды по скорости (без расхождения с «Готово: …»).
     */
    // Промпт 104.B: [includeUpload]=false — пропустить замер ОТДАЧИ (он display-only и гарантированный сток
    // времени, см. ServerSpeedTester.uploadMbps). Полный тест зовёт с false (важна скорость перебора), монитор —
    // с true (для ↑ на плашке; там замер в фоне и с жёстким потолком времени).
    // Промпт 108: ВОЗВРАЩАЕТ измеренную скорость активного туннеля ↓ (Мбит/с, -1 если не запущен/провал/сервер
    // сменился) — чтобы полный тест мог СРАВНИТЬ активный сервер с кандидатами, а не подключаться к первому годному
    // безусловно. Прочие вызыватели (монитор/onDone) возврат игнорируют.
    fun probeActiveSpeedNow(app: Context, includeUpload: Boolean = true): Double {
        val curKey = ProxyState.state.value.serverKey ?: return -1.0
        val direct = ServerSpeedTester.measureDirectDownloadMbps(app).takeIf { it >= 0.0 }   // НАПРЯМУЮ ↓ (без туннеля)
        val down = ServerSpeedTester.measureActiveDownloadMbps(app)                          // В ТУННЕЛЕ ↓
        if (ProxyState.state.value.serverKey != curKey) return -1.0   // сервер сменили во время замера — не приписываем чужое
        val up = if (includeUpload) ServerSpeedTester.measureActiveUploadMbps(app) else -1.0 // В ТУННЕЛЕ ↑
        TunnelSpeed.setProbe(direct, down.takeIf { it >= 0.0 }, up.takeIf { it >= 0.0 }, now())
        if (down > 0) SubscriptionManager.applySpeedResults(app, mapOf(curKey to down))      // обновить «Живые»
        return down
    }

    /** Экран включён (пользователь потенциально смотрит на плашку)? Гейт для платной активной пробы. */
    private fun screenInteractive(app: Context): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

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
