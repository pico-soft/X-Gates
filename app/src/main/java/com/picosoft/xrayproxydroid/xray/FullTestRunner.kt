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
 * Полный адаптивный тест (новый ЕДИНЫЙ порядок, ТЗ Elyor 2026-10-14):
 *   Этап 0: ФЕТЧ подписок из сети (свежий список) — делает вызывающий (onFullTest) ДО run.
 *   Этап 1: ping ВСЕХ → отсев мёртвых (pingMs>=0), сорт по возр. пинга.
 *   Этап 2: МИНИМАЛЬНЫЙ замер (~100 КБ) ВСЕХ живых. У кого throughput ≥ minGate (0.5 Мбит/с) — кандидат на точный
 *           замер; к ПЕРВОМУ живому сразу подключаемся (непрерывность, Пр.95). В «Живых» эти числа — «≈» (быстрые).
 *   Этап 3: ПОЛНЫЙ точный замер ТОПа прошедших minGate — лимит fullCap: на МОБИЛЬНОЙ 10 (обычный)/3 (экономия),
 *           на ШНУРЕ+Wi-Fi — БЕЗ лимита (мерим всех). Переключаемся на реально быстрейший (инкумбент — кратный порог).
 *
 * Общий БЮДЖЕТ времени [fullTestBudgetSec] + сторож застоя защищают от зависания. Тот же принцип (пинг→минимальный
 * всех→полный топ) использует фоновый монитор (NetworkMonitor). proxy-check НЕ нужен — real-ping проходит протокол.
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
        onSpeedResult: (ServerProfile, Double, Boolean) -> Unit = { _, _, _ -> },   // full=true полный замер, false минимальный («≈»)
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

        // --- Этап 2: speed + early-connect, ПОСЛЕДОВАТЕЛЬНО (обычный режим — мерим всех живых) ---
        fun startSpeedPhase(aliveByPing: List<ServerProfile>) {
            if (cancelled.get()) { finishOnce(Result(null, null, 0.0, aliveByPing.size, true)); return }
            if (aliveByPing.isEmpty()) {
                onPhase("Нет живых серверов — проверь интернет / все мёртвые")
                finishOnce(Result(null, null, 0.0, 0, cancelled.get()))
                return
            }
            val s = SettingsStore.current()
            // ТЗ Elyor (новый ЕДИНЫЙ порядок, обычный и экономия): МИНИМАЛЬНЫЙ замер (~100 КБ) ВСЕХ живых → у кого
            // throughput ≥ minGate (0.5 Мбит/с) — те в ПОЛНЫЙ точный замер, но не больше fullCap (10 обычный / 3
            // экономия). minGate НИЖЕ финального «держим» (1 Мбит/с): минимальный замер по короткому окну занижает,
            // 0.5 отсекает мёртвых/призраков, но не рубит жизнеспособных. Экономия теперь = только меньший fullCap.
            val candidates = aliveByPing
            val minGate = 0.5
            // Лимит полных замеров: на МОБИЛЬНОЙ — 10 (обычный) / 3 (экономия). На ШНУРЕ+Wi-Fi (батарея/трафик не
            // жмут) — БЕЗ лимита: полностью мерим всех прошедших minGate (ТЗ Elyor).
            val fullCap = if (NetworkMonitor.chargingAndWifi(appCtx)) Int.MAX_VALUE else if (s.trafficSaveMode) 3 else 10
            val budgetMs = s.fullTestBudgetSec.coerceAtLeast(30) * 1000L
            onPhase("Этап 2: быстрый замер всех живых (${candidates.size})…")
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
                var measured = 0
                val goodOnes = ArrayList<ServerProfile>()        // прошедшие minGate на минимальном → точный замер (топ fullCap)
                val minimalByKey = HashMap<String, Double>()     // скорость МИНИМАЛЬНОГО замера этого прогона (для сорта топа)
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
                    if (activeProfile != null && activeMbps > 0.0) onSpeedResult(activeProfile, activeMbps, true)   // measureSpeed — полный; в «Живые»
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
                    // Активный уже измерен ПОЛНЫМ выше — минимально не перемеряем (число не затирается на «≈»).
                    if (activeServerKey != null && key(p) == activeServerKey) {
                        measured++; emitProgress(measured, candidates.size); beat(); continue
                    }
                    // МИНИМАЛЬНЫЙ замер (~100 КБ) — дёшево узнать «есть ли throughput». ВСЕ живые (без стоп-на-N).
                    val mbps = ServerSpeedTester.measureSpeedMinimal(appCtx, p)
                    minimalByKey[key(p)] = mbps
                    if (cancelled.get()) break   // Промпт 103: сторож/отмена сработали во время замера — не действуем по нему
                    measured++
                    onSpeedResult(p, mbps, false)   // минимальный → «≈» (быстрый/приблизительный)
                    // Порог minGate: есть throughput → кандидат на ТОЧНЫЙ замер (Этап 3) + ранний коннект к ПЕРВОМУ
                    // живому для НЕПРЕРЫВНОСТИ (Пр.95). Реальное переключение на быстрейший — на Этапе 3 (по полному числу).
                    if (mbps >= minGate && !ServerFilter.isPaused(p, blocklist) && ServerFilter.protocolAllowed(p, s)) {
                        goodOnes.add(p)
                        if (mbps > bestSpeed) { bestSpeed = mbps; best = p; hbBest.set(p) }
                        if (connected == null) {
                            connected = p; connectedSpeed = mbps; hbConnected.set(p)
                            onPhase("Подключён ${label(p)} (${fmt(mbps)} Мбит/с), продолжаю замер…")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "полный тест: первый рабочий", "${fmt(mbps)} Мбит/с")
                            connect(p)
                        }
                    }
                    onPhase("Этап 2: быстрый замер $measured / ${candidates.size} · подключён: ${connected?.let(::label) ?: "—"}")
                    emitProgress(measured, candidates.size)
                    beat()   // Промпт 103: шаг прогресса — «пульс» для сторожа
                }
                // ── Этап 3: ТОЧНЫЙ замер топа. Минимальный замер не различает самого быстрого (все ≈). Полным замером
                // мерим ТОП fullCap прошедших minGate (по минимальному числу ЭТОГО прогона; на шнуре+Wi-Fi — всех) →
                // переключаемся на реально быстрейший, если заметно быстрее подключённого (инкумбент — кратный порог).
                var bestFull = 0.0; var bestFullP: ServerProfile? = null
                if (!cancelled.get() && goodOnes.isNotEmpty()) {
                    val topK = goodOnes.distinctBy { key(it) }
                        .sortedByDescending { minimalByKey[key(it)] ?: 0.0 }   // по МИНИМАЛЬНОМУ замеру этого прогона
                        .take(fullCap)
                    onPhase("Этап 3: точный замер топа (${topK.size})…")
                    for (p in topK) {
                        if (cancelled.get()) break
                        if ((System.nanoTime() - phaseStart) / 1_000_000 > budgetMs) break
                        val full = ServerSpeedTester.measureSpeed(appCtx, p)   // ПОЛНЫЙ/точный замер
                        if (cancelled.get()) break
                        onSpeedResult(p, full, true)
                        if (full > bestFull) { bestFull = full; bestFullP = p }
                        if (full > bestSpeed) { bestSpeed = full; best = p; hbBest.set(p) }
                        beat()
                    }
                    val pick = bestFullP
                    val conn = connected
                    // Инкумбент «в норме» → порог КРАТНЫЙ (keepBestMultiplier, Пр.90); иначе апгрейд по марже (>10%).
                    val need = if (keepActive) connectedSpeed * s.keepBestMultiplier else connectedSpeed * (1 + marginRatio)
                    if (!cancelled.get() && pick != null && (conn == null || (key(pick) != key(conn) && bestFull > need))) {
                        onPhase("Точный топ: ${label(pick)} ${fmt(bestFull)} Мбит/с — переключаюсь")
                        val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                        MonitorLog.switch(appCtx, from, ServerLabels.display(pick), "полный тест: точный топ", "было ${fmt(connectedSpeed)} → стало ${fmt(bestFull)} Мбит/с")
                        connected = pick; connectedSpeed = bestFull; hbConnected.set(pick)
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
            concurrency = 16,   // в (ТЗ Elyor): «пинг всех» быстрее — больше параллелизма (пинг дёшев; jniPool cap 24)
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
