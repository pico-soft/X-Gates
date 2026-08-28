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
 * скорости — НЕ здесь: это забота монитора «держать самый быстрый» (NetworkMonitor). Единственное сокращение
 * ручного теста — РЕЖИМ ЭКОНОМИИ ([trafficSaveMode], явный опт-ин): кандидаты по ПИНГУ, батчами по
 * [trafficSaveBatch], стоп по [trafficSaveMinAlive] живых.
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

        // --- Этап 2: speed + early-connect, ПОСЛЕДОВАТЕЛЬНО с батч-остановкой ---
        fun startSpeedPhase(aliveByPing: List<ServerProfile>) {
            if (cancelled.get()) { finishOnce(Result(null, null, 0.0, aliveByPing.size, true)); return }
            if (aliveByPing.isEmpty()) {
                onPhase("Нет живых серверов — проверь интернет / все мёртвые")
                finishOnce(Result(null, null, 0.0, 0, cancelled.get()))
                return
            }
            val s = SettingsStore.current()
            // План кандидатов. Ручной тест (Промпт 82): мерим ВСЕХ живых (без ранней остановки), но с общим
            // бюджетом времени. Экономия — единственное сокращение (явный опт-ин): батчами, стоп по живым.
            val candidates: List<ServerProfile>
            val batch: Int
            val minAlive: Int
            val modeStr: String
            val budgetMs = s.fullTestBudgetSec.coerceAtLeast(30) * 1000L
            if (s.trafficSaveMode) {
                candidates = aliveByPing                                   // лучшие по пингу
                batch = s.trafficSaveBatch.coerceAtLeast(1)
                minAlive = s.trafficSaveMinAlive.coerceAtLeast(1)
                modeStr = "экономия ${batch}×, до ${minAlive} живых"
            } else {
                candidates = aliveByPing                                   // ВСЕ живые
                batch = Int.MAX_VALUE; minAlive = 0                        // без ранней остановки
                modeStr = "все живые (${aliveByPing.size}), бюджет ${s.fullTestBudgetSec / 60} мин"
            }
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
                var best: ServerProfile? = null
                var bestSpeed = 0.0
                var selectable = 0
                var measured = 0
                // Промпт 93.C: фон не должен ронять процесс — любое исключение фазы завершает тест штатно (finishOnce).
                try {
                // ПРИОРИТЕТ АКТИВНОГО ТУННЕЛЯ (обратная связь): если есть активное соединение — мерим ЕГО ПЕРВЫМ,
                // ДО общего перебора. Плашка сразу показывает реальную ↓/↑ активного сервера. Промпт 103: замер идёт
                // на ОТДЕЛЬНОМ потоке с потолком времени — он может длиться ~2 мин на провалах отдачи, и раньше это
                // «подвешивало» бар на 0/N без обратной связи. Теперь: явный текст фазы + join не дольше потолка
                // (замер, если долгий, дойдёт сам в фоне — плашка обновится позже, перебор не ждёт его вечно).
                if (ProxyState.state.value.running && ProxyState.state.value.serverKey != null) {
                    onPhase("Этап 2: замер активного туннеля…")
                    beat()
                    val probe = Thread { runCatching { NetworkMonitor.probeActiveSpeedNow(appCtx) } }.apply { isDaemon = true }
                    probe.start()
                    probe.join(PRIORITY_MEASURE_CAP_MS)
                    beat()
                }
                for (p in candidates) {
                    if (cancelled.get()) break
                    // Общий бюджет времени (Промпт 82): не мерим ВСЕХ бесконечно — стоп по лимиту.
                    if ((System.nanoTime() - phaseStart) / 1_000_000 > budgetMs) {
                        onPhase("Бюджет теста (${s.fullTestBudgetSec / 60} мин) исчерпан на $measured/${candidates.size} — стоп")
                        break
                    }
                    val mbps = ServerSpeedTester.measureSpeed(appCtx, p)
                    if (cancelled.get()) break   // Промпт 103: сторож/отмена сработали во время замера — не действуем по нему
                    measured++
                    onSpeedResult(p, mbps)   // результат сохраняем всегда
                    if (ServerFilter.isSelectable(p, mbps, s, blocklist)) {
                        selectable++
                        if (mbps > bestSpeed) { bestSpeed = mbps; best = p; hbBest.set(p) }
                        if (connected == null) {
                            connected = p; connectedSpeed = mbps; hbConnected.set(p)
                            onPhase("Подключён ${label(p)} ($mbps Мбит/с), продолжаю…")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "полный тест: первый рабочий", "${fmt(mbps)} Мбит/с")
                            connect(p)
                        } else if (p !== connected && mbps > connectedSpeed * (1 + marginRatio)) {
                            val prev = connectedSpeed
                            onPhase("Быстрее на >10%: ${label(p)} ($mbps > $connectedSpeed) — переключаюсь")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "полный тест: апгрейд", "было ${fmt(prev)} → стало ${fmt(mbps)} Мбит/с")
                            connected = p; connectedSpeed = mbps; hbConnected.set(p)
                            connect(p)
                        }
                    }
                    onPhase("Этап 2: скорость $measured / ${candidates.size} · подключён: ${connected?.let(::label) ?: "—"}")
                    emitProgress(measured, candidates.size)
                    beat()   // Промпт 103: шаг прогресса — «пульс» для сторожа
                    // Батч-остановка: набрали минимум живых на границе батча — дальше не мерим (экономия трафика).
                    if (minAlive > 0 && selectable >= minAlive && measured % batch == 0) break
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
                    val alive = testable
                        .filter { (pingByKey[key(it)] ?: it.pingMs ?: -1) >= 0 }
                        .sortedBy { pingByKey[key(it)] ?: it.pingMs ?: Int.MAX_VALUE }
                    startSpeedPhase(alive)
                }
            },
        )

        return object : Handle {
            override fun cancel() {
                cancelled.set(true)
                pingHandle?.cancel()
            }
        }
    }
}
