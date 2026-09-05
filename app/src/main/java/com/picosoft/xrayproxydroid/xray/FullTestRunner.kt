package com.picosoft.xrayproxydroid.xray

import android.content.Context
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.monitor.ServerLabels
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

/**
 * Полный адаптивный тест (перенос Termux run_full_test_with_early_connect):
 *   Этап 1: ping всех (real-ping, пул 8) → отсев мёртвых (pingMs>=0), сорт по возр. пинга.
 *   Этап 2: speed ПОСЛЕДОВАТЕЛЬНО по кандидатам. Первый живой (≥MIN_USABLE) → СРАЗУ подключиться
 *           (early-connect). Дальше ПРОГРЕССИВНЫЙ апгрейд ПО ХОДУ: >10% ([marginRatio]) сверх текущего.
 *
 * РУЧНОЙ ЗАПУСК = мерим ВСЕХ живых по очереди (Промпт 82: пользователь ждёт полной картины), но не дольше
 * общего БЮДЖЕТА времени [fullTestBudgetSec] (защита от зависания на медленных). Ступенчатый top-N по
 * скорости — НЕ здесь: это забота монитора «держать самый быстрый» (NetworkMonitor).
 *
 * РЕЖИМ ЭКОНОМИИ ([trafficSaveMode], Пр.125) — совсем ДРУГОЙ путь ([startEconomyPhase]): пинг живых остаётся,
 * но фазу скорости НЕ гоняем по всем. Кандидаты упорядочены по СОХРАНЁННОЙ скорости [speedMbps]; подключаемся
 * к первому и мерим ТОЛЬКО активного (Пр.125.D — скорость показываем всегда); ниже порога — следующий; стоп на
 * первом годном. Нет прошлых замеров — честный текст + замер первых [trafficSaveBlindProbe] по пингу.
 *
 * proxy-check НЕ нужен — real-ping уже проходит весь протокол до бэкенда и тянет URL через туннель.
 */
object FullTestRunner {

    // Промпт 103. ПРЕДОХРАНИТЕЛЬ «Этап 2» не должен виснуть.
    //  STALL — фаза не двигается (нет ни одного шага прогресса) дольше этого → честно завершаем.
    //  PRIORITY_CAP — приоритетный замер активного туннеля (прямой+↓+↑ с фолбэками) может тянуться ~2 мин
    //  на провалах отдачи; он идёт ДО перебора и НЕ покрыт кооперативным бюджетом (тот проверяется лишь на
    //  верху цикла). Ограничиваем его вклад в видимый «застой», не давая застопорить бар на 0/N.
    private const val STALL_LIMIT_MS = 120_000L
    private const val PRIORITY_MEASURE_CAP_MS = 60_000L
    // Пр.151: сколько ГОДНЫХ набрать на быстром Этапе 2 (после подключения) прежде чем перейти к точному Этапу 3.
    // Хватает пула для точного замера топ-K; не мерим все десятки серверов подряд.
    private const val FAST_SCAN_ENOUGH = 10

    data class Result(
        val connected: ServerProfile?,   // к чему подключены в итоге
        val fastest: ServerProfile?,     // самый быстрый по замеру
        val fastestMbps: Double,
        val aliveCount: Int,
        val cancelled: Boolean,
    )

    interface Handle {
        fun cancel()
    }

    fun run(
        context: Context,
        allServers: List<ServerProfile>,
        marginRatio: Double = SettingsStore.current().marginRatio,   // живой порог из настроек
        onPhase: (String) -> Unit,
        onPingResult: (ServerProfile, Int) -> Unit = { _, _ -> },
        onSpeedResult: (ServerProfile, Double) -> Unit = { _, _ -> },
        emitProgress: (done: Int, total: Int) -> Unit = { _, _ -> },  // числовой прогресс наружу (для бара)
        connect: (ServerProfile) -> Unit,
        onDone: (Result) -> Unit,
    ): Handle {
        val appCtx = context.applicationContext
        ServerSpeedTester.resetMeasureAbort()   // Промпт 123.D: снять флаг отмены прошлого теста — иначе замеры «немы»
        val cancelled = AtomicBoolean(false)
        // Промпт 103: терминальное состояние прогресса — РОВНО ОДИН раз. Сторож фазы и штатное завершение
        // могут прийти оба; второй вызов onDone игнорируем, чтобы не дёргать флаги/персист/UI дважды.
        val doneFired = AtomicBoolean(false)
        fun finishOnce(r: Result) { if (doneFired.compareAndSet(false, true)) onDone(r) }
        var pingHandle: ServerTester.TestHandle? = null

        val pingByKey = ConcurrentHashMap<String, Int>()

        // Заблокированных НЕ мерим (в отличие от отключённых по протоколу): блокировка — «не нужен вовсе».
        val blocklist = BlocklistStore.current()
        val testable = allServers.filter { !ServerFilter.isBlocked(it, blocklist) }

        fun key(p: ServerProfile) = SubscriptionManager.serverKey(p)
        fun label(p: ServerProfile) = p.remarks.ifBlank { p.address }
        fun fmt(v: Double) = "${(v * 10).roundToInt() / 10.0}"

        // --- Этап 2 в РЕЖИМЕ ЭКОНОМИИ (Пр.125): опираемся на СОХРАНЁННЫЕ замеры, мерим ТОЛЬКО выбранного ---
        // Режим НЕ отсеивает серверы — меняет СПОСОБ ВЫБОРА. Пинг живых уже сделан (дёшев). Здесь:
        //  • есть прошлые замеры → упорядочить кандидатов по ИЗВЕСТНОЙ скорости (быстрые первыми), подключаться
        //    по одному и мерить ТОЛЬКО активного; первый ≥ порога — стоп. Массового замера НЕТ.
        //  • прошлых замеров нет (первый запуск / список обновлён) → честно сказать и померить первых
        //    [trafficSaveBlindProbe] по пингу temp-инстансом, выбрать лучшего.
        // Скорость активного ВСЕГДА измеряется и показывается (Пр.125.D). Сторож завершения — как в обычном режиме.
        fun startEconomyPhase(aliveByPing: List<ServerProfile>) {
            val s = SettingsStore.current()
            val threshold = s.monitorTunnelThreshold
            val budgetMs = s.fullTestBudgetSec.coerceAtLeast(30) * 1000L
            val known = aliveByPing.filter { (it.speedMbps ?: 0.0) > 0.0 }
                .sortedByDescending { it.speedMbps ?: 0.0 }
            val blind = known.isEmpty()
            // Порядок: известные по скорости (быстрые первыми) + остальные живые по пингу хвостом (запас, если
            // все известные не пройдут). Вслепую — первые N по пингу (из чего выбрать).
            val order: List<ServerProfile> =
                if (!blind) known + aliveByPing.filter { (it.speedMbps ?: 0.0) <= 0.0 }
                else aliveByPing.take(s.trafficSaveBlindProbe.coerceAtLeast(1))

            onPhase(
                if (blind) "Экономия: нет данных о скорости — выбор вслепую, мерю первых ${order.size} по пингу"
                else "Экономия: выбор по прошлым замерам (${known.size}) — мерю только выбранного"
            )
            emitProgress(0, order.size)

            val hbConnected = AtomicReference<ServerProfile?>(null)
            val hbBest = AtomicReference<ServerProfile?>(null)
            val lastMoveMs = AtomicLong(System.currentTimeMillis())
            val phaseStartWall = System.currentTimeMillis()
            fun beat() { lastMoveMs.set(System.currentTimeMillis()) }

            Thread {
                while (!doneFired.get()) {
                    try { Thread.sleep(3_000) } catch (e: InterruptedException) { break }
                    if (doneFired.get()) break
                    val nowMs = System.currentTimeMillis()
                    val overBudget = nowMs - phaseStartWall > budgetMs + 60_000
                    val stalled = nowMs - lastMoveMs.get() > STALL_LIMIT_MS
                    if (overBudget || stalled) {
                        cancelled.set(true)
                        runCatching {
                            onPhase(
                                if (overBudget) "Бюджет теста (${s.fullTestBudgetSec / 60} мин) исчерпан — завершаю"
                                else "Замер завис (${STALL_LIMIT_MS / 1000}с без движения) — завершаю"
                            )
                        }
                        finishOnce(Result(hbConnected.get() ?: hbBest.get(), hbBest.get(), 0.0, order.size, false))
                        break
                    }
                }
            }.apply { isDaemon = true }.start()

            Thread {
                var connected: ServerProfile? = null
                var connectedSpeed = 0.0
                var best: ServerProfile? = null
                var bestSpeed = 0.0
                var measured = 0
                // Подключиться к серверу, дождаться что активен ИМЕННО он, затем измерить ЖИВОЙ туннель (Пр.125.D).
                fun connectAndMeasureActive(p: ServerProfile): Double {
                    connect(p)
                    val k = key(p)
                    var waited = 0
                    while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != k)) {
                        if (cancelled.get()) return -1.0
                        try { Thread.sleep(500) } catch (e: InterruptedException) {}
                        waited += 500
                    }
                    try { Thread.sleep(1000) } catch (e: InterruptedException) {}   // дать туннелю осесть
                    if (cancelled.get() || ProxyState.state.value.serverKey != k) return -1.0
                    return ServerSpeedTester.measureActiveDownloadMbps(appCtx)
                }
                try {
                    if (blind) {
                        // Пр.125.C: замерить первых N temp-инстансом (сопоставимо), выбрать лучшего, подключиться.
                        for ((i, p) in order.withIndex()) {
                            if (cancelled.get()) break
                            if ((System.currentTimeMillis() - phaseStartWall) > budgetMs) break
                            onPhase("Экономия (вслепую): замер ${i + 1}/${order.size} · ${label(p)}")
                            val mbps = ServerSpeedTester.measureSpeed(appCtx, p)
                            if (cancelled.get()) break
                            measured++
                            onSpeedResult(p, mbps)
                            if (mbps > bestSpeed) { bestSpeed = mbps; best = p; hbBest.set(p) }
                            emitProgress(measured, order.size); beat()
                        }
                        val pick = best
                        if (pick != null && !cancelled.get()) {
                            onPhase("Экономия: подключаю лучшего из проб — ${label(pick)} (${fmt(bestSpeed)} Мбит/с)")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(pick), "экономия: лучший из проб вслепую", "${fmt(bestSpeed)} Мбит/с")
                            val act = connectAndMeasureActive(pick)
                            connected = pick; connectedSpeed = if (act > 0) act else bestSpeed; hbConnected.set(pick)
                            if (act > 0) onSpeedResult(pick, act)
                        }
                    } else {
                        // Пр.125.B: по одному, от быстрых по ПРОШЛЫМ замерам; первый ≥ порога — стоп.
                        for ((i, p) in order.withIndex()) {
                            if (cancelled.get()) break
                            if ((System.currentTimeMillis() - phaseStartWall) > budgetMs) {
                                onPhase("Бюджет теста исчерпан на $i/${order.size} — стоп"); break
                            }
                            val known0 = p.speedMbps ?: 0.0
                            onPhase("Экономия: пробую ${i + 1}/${order.size} · ${label(p)} (было ${fmt(known0)} Мбит/с)")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "экономия: кандидат по прошлым замерам", "было ${fmt(known0)} Мбит/с")
                            val mbps = connectAndMeasureActive(p)
                            if (cancelled.get()) break
                            measured++
                            onSpeedResult(p, mbps)
                            connected = p; connectedSpeed = mbps; hbConnected.set(p)
                            if (mbps > bestSpeed) { bestSpeed = mbps; best = p; hbBest.set(p) }
                            emitProgress(measured, order.size); beat()
                            if (mbps >= threshold) { onPhase("Годный: ${label(p)} ↓${fmt(mbps)} ≥ ${fmt(threshold)} Мбит/с — стоп"); break }
                            onPhase("${label(p)} ↓${fmt(mbps)} < порога ${fmt(threshold)} — следующий по списку")
                        }
                        // Никто не прошёл порог → остаться на ЛУЧШЕМ измеренном (связь любой ценой, Пр.95),
                        // если подключены сейчас не к нему.
                        val pick = best
                        if (!cancelled.get() && pick != null && connected != null && key(pick) != key(connected!!) && bestSpeed > connectedSpeed) {
                            onPhase("Порог никто не прошёл — держу лучший: ${label(pick)} (${fmt(bestSpeed)} Мбит/с)")
                            connectAndMeasureActive(pick)
                            connected = pick; connectedSpeed = bestSpeed; hbConnected.set(pick)
                        }
                    }
                } catch (e: Throwable) {
                    runCatching { onPhase("Ошибка теста: ${e.javaClass.simpleName}: ${e.message}") }
                }
                finishOnce(Result(connected ?: best, best, bestSpeed, order.size, cancelled.get()))
            }.start()
        }

        // --- Этап 2: speed + early-connect, ПОСЛЕДОВАТЕЛЬНО (обычный режим — мерим всех живых) ---
        fun startSpeedPhase(aliveByPing: List<ServerProfile>) {
            if (cancelled.get()) { finishOnce(Result(null, null, 0.0, aliveByPing.size, true)); return }
            if (aliveByPing.isEmpty()) {
                onPhase("Нет живых серверов — проверь интернет / все мёртвые")
                finishOnce(Result(null, null, 0.0, 0, cancelled.get()))
                return
            }
            val s = SettingsStore.current()
            // Пр.125: РЕЖИМ ЭКОНОМИИ — отдельный путь (выбор по прошлым замерам, мерим только выбранного).
            if (s.trafficSaveMode) { startEconomyPhase(aliveByPing); return }
            // Ручной тест (Промпт 82): мерим ВСЕХ живых по очереди (без ранней остановки), но с общим бюджетом времени.
            val candidates = aliveByPing
            val batch = Int.MAX_VALUE          // без батч-остановки (у экономии теперь свой путь, Пр.125)
            val minAlive = 0
            val modeStr = "все живые (${aliveByPing.size}), бюджет ${s.fullTestBudgetSec / 60} мин"
            val budgetMs = s.fullTestBudgetSec.coerceAtLeast(30) * 1000L
            onPhase("Этап 2: скорость — $modeStr…")
            emitProgress(0, candidates.size)

            // Промпт 103. ПРЕДОХРАНИТЕЛЬ ЗАВЕРШЕНИЯ. Наблюдение из поля: «Этап 2» долго стоял, плашка зеленела
            // (монитор), а бар не двигался. Причина: приоритетный замер активного туннеля (ниже) идёт ДО перебора
            // и НЕ покрыт кооперативным бюджетом (тот проверяется лишь на верху цикла и не прервёт заблокированный
            // замер). Ниже — независимый сторож: если фаза не двигается дольше STALL_LIMIT_MS ИЛИ вышла за бюджет,
            // прогресс ЧЕСТНО завершается (finishOnce ⇒ бар гаснет, монитор снова активен). Держим «пульс» фазы
            // (beat) и лучшее-на-сейчас (hb*), чтобы сторож выдал осмысленный итог, а не «завис».
            val hbConnected = AtomicReference<ServerProfile?>(null)
            val hbBest = AtomicReference<ServerProfile?>(null)
            val lastMoveMs = AtomicLong(System.currentTimeMillis())
            val phaseStartWall = System.currentTimeMillis()
            fun beat() { lastMoveMs.set(System.currentTimeMillis()) }

            Thread {
                while (!doneFired.get()) {
                    try { Thread.sleep(3_000) } catch (e: InterruptedException) { break }
                    if (doneFired.get()) break
                    val nowMs = System.currentTimeMillis()
                    val overBudget = nowMs - phaseStartWall > budgetMs + 60_000
                    val stalled = nowMs - lastMoveMs.get() > STALL_LIMIT_MS
                    if (overBudget || stalled) {
                        cancelled.set(true)   // попросить рабочий поток выйти на ближайшей проверке
                        runCatching {
                            onPhase(
                                if (overBudget) "Бюджет теста (${s.fullTestBudgetSec / 60} мин) исчерпан — завершаю"
                                else "Замер завис (${STALL_LIMIT_MS / 1000}с без движения) — завершаю"
                            )
                        }
                        finishOnce(Result(hbConnected.get() ?: hbBest.get(), hbBest.get(), 0.0, candidates.size, false))
                        break
                    }
                }
            }.apply { isDaemon = true }.start()

            Thread {
                val phaseStart = System.nanoTime()
                var connected: ServerProfile? = null
                var connectedSpeed = 0.0
                var keepActive = false   // Промпт 108: инкумбент = активный сервер «в норме» → консервативно (Пр.90 «держать лучший ×N»)
                var best: ServerProfile? = null
                var bestSpeed = 0.0
                var selectable = 0
                var measured = 0
                val goodOnes = ArrayList<ServerProfile>()   // Пр.151: годные (Этап 2) → полный замер топа на Этапе 3
                // Промпт 93.C: фон не должен ронять процесс — любое исключение фазы завершает тест штатно (finishOnce).
                try {
                // ПРИОРИТЕТ АКТИВНОГО ТУННЕЛЯ (обратная связь): если есть активное соединение — мерим ЕГО ПЕРВЫМ,
                // ДО общего перебора. Плашка сразу показывает реальную ↓/↑ активного сервера. Промпт 103: замер идёт
                // на ОТДЕЛЬНОМ потоке с потолком времени — он может длиться ~2 мин на провалах отдачи, и раньше это
                // «подвешивало» бар на 0/N без обратной связи. Теперь: явный текст фазы + join не дольше потолка
                // (замер, если долгий, дойдёт сам в фоне — плашка обновится позже, перебор не ждёт его вечно).
                val activeServerKey = ProxyState.state.value.serverKey
                if (ProxyState.state.value.running && activeServerKey != null) {
                    onPhase("Этап 2: замер активного туннеля…")
                    beat()
                    // Промпт 104.B: отдача (↑) — display-only и сток → не делаем. Промпт 108: замер на отдельном
                    // потоке с потолком. Промпт 109: ЭТОТ живой замер — ТОЛЬКО для «живой» ↓/↑ на плашке (что
                    // пользователь реально видит); в РЕШЕНИЕ его число НЕ берём (см. ниже — оно систематически занижено).
                    val probe = Thread { runCatching { NetworkMonitor.probeActiveSpeedNow(appCtx, includeUpload = false) } }.apply { isDaemon = true }
                    probe.start()
                    probe.join(PRIORITY_MEASURE_CAP_MS)
                    if (probe.isAlive) {
                        // Промпт 104.A: потолок обязан ПРЕРЫВАТЬ работу, а не просто перестать её ждать — иначе
                        // фоновый замер продолжит качать через активный SOCKS ПАРАЛЛЕЛЬНО с перебором временных
                        // инстансов и испортит их числа (то самое наложение, ради устранения которого делался Пр.101).
                        runCatching { ServerSpeedTester.abortCurrentMeasure() }
                        probe.join(10_000)                              // дождаться РЕАЛЬНОГО завершения после прерывания
                        runCatching { ServerSpeedTester.resetMeasureAbort() }   // снять флаг — иначе монитор/следующие замеры «немы»
                    }
                    beat()
                    // Промпт 109.D: КРИТЕРИЙ СОПОСТАВИМОСТИ. Замер через ЖИВОЙ SOCKS систематически ЗАНИЖАЕТ (один и
                    // тот же сервер: живой 2.67 vs temp-инстанс 68 — перегружен/нестабилен сам путь замера, не пробник
                    // и не окно). Поэтому для РЕШЕНИЯ активный сервер меряем ТЕМ ЖЕ способом, что кандидата — свежим
                    // temp-инстансом (measureSpeed) → числа сравнимы. Иначе кандидат всегда «кратно быстрее» и
                    // переключение случайно. Активный «в норме» (≥ порога) → ИНКУМБЕНТ (Пр.90 «держать лучший ×N»);
                    // иначе — честный текст + прежний ранний коннект.
                    val activeProfile = allServers.firstOrNull { key(it) == activeServerKey }
                    val activeMbps = if (activeProfile != null && !cancelled.get())
                        ServerSpeedTester.measureSpeed(appCtx, activeProfile) else -1.0
                    if (activeProfile != null && activeMbps > 0.0) onSpeedResult(activeProfile, activeMbps)   // сопоставимое число — в «Живые»
                    beat()
                    if (activeProfile != null && activeMbps >= s.monitorTunnelThreshold) {
                        connected = activeProfile; connectedSpeed = activeMbps; hbConnected.set(activeProfile); keepActive = true
                        onPhase("Активный ${label(activeProfile)}: ↓${fmt(activeMbps)} Мбит/с (в норме) — переключусь только если кандидат ×${fmt(s.keepBestMultiplier)} быстрее")
                    } else {
                        onPhase(
                            when {
                                activeMbps <= 0.0 -> "Активный туннель не измерился — ищу рабочий сервер"
                                else -> "Активный ↓${fmt(activeMbps)} < порога ${fmt(s.monitorTunnelThreshold)} Мбит/с — ищу быстрее"
                            }
                        )
                    }
                }
                for (p in candidates) {
                    if (cancelled.get()) break
                    // Общий бюджет времени (Промпт 82): не мерим ВСЕХ бесконечно — стоп по лимиту.
                    if ((System.nanoTime() - phaseStart) / 1_000_000 > budgetMs) {
                        onPhase("Бюджет теста (${s.fullTestBudgetSec / 60} мин) исчерпан на $measured/${candidates.size} — стоп")
                        break
                    }
                    // Пр.151 Этап 2 — БЫСТРЫЙ замер (measureSufficiency, стоп на пороге достаточности): быстро находим
                    // ГОДНОГО и подключаемся. Точную скорость (кто самый быстрый) добьём на Этапе 3 полным замером топа.
                    val mbps = ServerSpeedTester.measureSufficiency(appCtx, p, s.sufficientMbps.coerceAtLeast(0.1))
                    if (cancelled.get()) break   // Промпт 103: сторож/отмена сработали во время замера — не действуем по нему
                    measured++
                    onSpeedResult(p, mbps)   // результат сохраняем всегда
                    if (ServerFilter.isSelectable(p, mbps, s, blocklist)) {
                        selectable++
                        goodOnes.add(p)   // Пр.151: годный — в кандидаты на точный замер (Этап 3)
                        if (mbps > bestSpeed) { bestSpeed = mbps; best = p; hbBest.set(p) }
                        val curConn = connected
                        if (curConn == null) {
                            // Нет инкумбента (активный не измерен / ниже порога / не запущен) → ранний коннект к первому годному.
                            connected = p; connectedSpeed = mbps; hbConnected.set(p)
                            onPhase("Подключён ${label(p)} ($mbps Мбит/с), продолжаю…")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "полный тест: первый рабочий", "${fmt(mbps)} Мбит/с")
                            connect(p)
                        } else if (key(p) != key(curConn)) {
                            // Промпт 108.C: инкумбент есть — сравниваем. Активный «в норме» → порог КРАТНЫЙ
                            // (keepBestMultiplier, Пр.90 «держать лучший»); иначе обычный апгрейд по марже (>10%).
                            // Так первый годный кандидат уже НЕ подключается безусловно, если активный не хуже.
                            val need = if (keepActive) connectedSpeed * s.keepBestMultiplier else connectedSpeed * (1 + marginRatio)
                            if (mbps > need) {
                                val prev = connectedSpeed
                                onPhase("Быстрее: ${label(p)} (${fmt(mbps)} > ${fmt(prev)}) — переключаюсь")
                                val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                                val cause = if (keepActive) "полный тест: кандидат кратно быстрее активного" else "полный тест: апгрейд"
                                MonitorLog.switch(appCtx, from, ServerLabels.display(p), cause, "было ${fmt(prev)} → стало ${fmt(mbps)} Мбит/с")
                                connected = p; connectedSpeed = mbps; hbConnected.set(p); keepActive = false
                                connect(p)
                            }
                        }
                    }
                    onPhase("Этап 2: скорость $measured / ${candidates.size} · подключён: ${connected?.let(::label) ?: "—"}")
                    emitProgress(measured, candidates.size)
                    beat()   // Промпт 103: шаг прогресса — «пульс» для сторожа
                    // Батч-остановка: набрали минимум живых на границе батча — дальше не мерим (экономия трафика).
                    if (minAlive > 0 && selectable >= minAlive && measured % batch == 0) break
                    // Пр.151: Этап 2 БЫСТРЫЙ — как только ПОДКЛЮЧИЛИСЬ и набрали достаточно годных для точного топа,
                    // дальше НЕ мерим все подряд (иначе «Самый быстрый» тянется минутами на десятках серверов).
                    if (connected != null && goodOnes.size >= FAST_SCAN_ENOUGH) {
                        onPhase("Годных достаточно (${goodOnes.size}) — перехожу к точному замеру топа")
                        break
                    }
                }
                // ── Пр.151 Этап 3: ТОЧНЫЙ ТОП. Этап 2 был быстрым (все годные ≈ порог достаточности, не различить
                // самого быстрого). Полным замером мерим ТОП-K годных → переключаемся на реально самый быстрый, если
                // заметно быстрее подключённого. Так «Самый быстрый» = быстро связь + затем действительно быстрейший.
                if (!cancelled.get() && goodOnes.size > 1) {
                    val topK = goodOnes.distinctBy { key(it) }
                        .sortedByDescending { it.speedMbps ?: 0.0 }   // по быстрому числу Этапа 2 (+ пинг вторично)
                        .take(SettingsStore.current().activeTopBatch.coerceAtLeast(3))
                    onPhase("Этап 3: точный замер топа (${topK.size})…")
                    for (p in topK) {
                        if (cancelled.get()) break
                        if ((System.nanoTime() - phaseStart) / 1_000_000 > budgetMs) break
                        val full = ServerSpeedTester.measureSpeed(appCtx, p)   // полный замер (точная скорость)
                        if (cancelled.get()) break
                        onSpeedResult(p, full)
                        if (full > bestSpeed) { bestSpeed = full; best = p; hbBest.set(p) }
                        beat()
                    }
                    val pick = best
                    val conn = connected
                    if (!cancelled.get() && pick != null && conn != null && key(pick) != key(conn) && bestSpeed > connectedSpeed * (1 + marginRatio)) {
                        onPhase("Точный топ: ${label(pick)} ${fmt(bestSpeed)} > ${fmt(connectedSpeed)} — переключаюсь")
                        val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                        MonitorLog.switch(appCtx, from, ServerLabels.display(pick), "полный тест: точный топ", "было ${fmt(connectedSpeed)} → стало ${fmt(bestSpeed)} Мбит/с")
                        connected = pick; connectedSpeed = bestSpeed; hbConnected.set(pick)
                        connect(pick)
                    }
                }
                } catch (e: Throwable) {
                    runCatching { onPhase("Ошибка теста: ${e.javaClass.simpleName}: ${e.message}") }
                }
                finishOnce(Result(connected ?: best, best, bestSpeed, candidates.size, cancelled.get()))
            }.start()
        }

        // --- Этап 1: ping всех (кроме заблокированных) — нужен для ранжирования, дёшев ---
        onPhase("Этап 1: пинг ${testable.size}…")
        emitProgress(0, testable.size)
        pingHandle = ServerTester.testAll(
            context = appCtx,
            servers = testable,
            concurrency = 8,
            onResult = { p, ms ->
                val v = ms.toInt()
                pingByKey[key(p)] = v
                onPingResult(p, v)
            },
            onProgress = { done, total ->
                onPhase("Этап 1: пинг $done / $total")
                emitProgress(done, total)
            },
            onFinish = {
                if (cancelled.get()) {
                    finishOnce(Result(null, null, 0.0, 0, true))
                } else {
                    // Промпт 108: defensive-дедуп по serverKey. По логам один и тот же сервер попадал в перебор
                    // 6–15 раз (реестр отдаёт дубли) → тест мерил его многократно и тянулся минутами. Один serverKey
                    // за прогон меряем ОДИН раз (корневую дупликацию реестра разобрать отдельно).
                    val alive = testable
                        .filter { (pingByKey[key(it)] ?: it.pingMs ?: -1) >= 0 }
                        .distinctBy { key(it) }
                        .sortedBy { pingByKey[key(it)] ?: it.pingMs ?: Int.MAX_VALUE }
                    startSpeedPhase(alive)
                }
            },
        )

        return object : Handle {
            override fun cancel() {
                cancelled.set(true)
                pingHandle?.cancel()
                // Промпт 123.D: флаг cancelled проверяется лишь МЕЖДУ замерами — идущий замер скорости
                // (measureSpeed активного/кандидата, приоритетный замер) висел бы до своего таймаута.
                // Рвём ИДУЩЕЕ соединение замера немедленно → «Прервать» действует за секунды, а не за минуты.
                runCatching { ServerSpeedTester.abortCurrentMeasure() }
            }
        }
    }
}
