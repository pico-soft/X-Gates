package com.picosoft.xrayproxydroid.xray

import android.content.Context
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.monitor.ServerLabels
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/**
 * Полный адаптивный тест (перенос Termux run_full_test_with_early_connect):
 *   Этап 1: ping всех (real-ping, пул 8) → отсев мёртвых (pingMs>=0), сорт по возр. пинга.
 *   Этап 2: speed ПОСЛЕДОВАТЕЛЬНО по кандидатам. Первый живой (≥MIN_USABLE) → СРАЗУ подключиться
 *           (early-connect). Дальше ПРОГРЕССИВНЫЙ апгрейд ПО ХОДУ: >10% ([marginRatio]) сверх текущего.
 *
 * СТУПЕНЧАТЫЙ ЗАМЕР (Промпт 77) — экономия трафика (замер = скачивание, до 13 МБ на сервер):
 *   • РЕЖИМ ЭКОНОМИИ ([trafficSaveMode]): кандидаты = лучшие по ПИНГУ; меряем батчами по
 *     [trafficSaveBatch]; как только набрано [trafficSaveMinAlive] живых — СТОП (следующие батчи не трогаем).
 *   • ОБЫЧНЫЙ, но ПОСЛЕ первого полного топа ([fullTopBuilt]): меряем только top-[normalTopBatch] по
 *     известной скорости, с той же ранней остановкой.
 *   • ОБЫЧНЫЙ первый прогон: меряем ВСЕХ живых (строим топ), без ранней остановки.
 *
 * proxy-check НЕ нужен — real-ping уже проходит весь протокол до бэкенда и тянет URL через туннель.
 */
object FullTestRunner {

    /** Построен ли уже полный топ по скорости (за процесс). Первый тест меряет всех; далее — top-N. */
    @Volatile var fullTopBuilt = false

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
            if (cancelled.get()) { onDone(Result(null, null, 0.0, aliveByPing.size, true)); return }
            if (aliveByPing.isEmpty()) {
                onPhase("Нет живых серверов — проверь интернет / все мёртвые")
                onDone(Result(null, null, 0.0, 0, cancelled.get()))
                return
            }
            val s = SettingsStore.current()
            // План: какие кандидаты, батч, минимум-живых-для-стопа, помечать ли «топ построен».
            val candidates: List<ServerProfile>
            val batch: Int
            val minAlive: Int
            val markBuilt: Boolean
            val modeStr: String
            when {
                s.trafficSaveMode -> {
                    candidates = aliveByPing                                   // лучшие по пингу
                    batch = s.trafficSaveBatch.coerceAtLeast(1)
                    minAlive = s.trafficSaveMinAlive.coerceAtLeast(1)
                    markBuilt = false; modeStr = "экономия ${batch}×, до ${minAlive} живых"
                }
                fullTopBuilt && s.normalTopBatch > 0 -> {
                    candidates = aliveByPing.sortedByDescending { it.speedMbps ?: 0.0 }.take(s.normalTopBatch)
                    batch = s.trafficSaveBatch.coerceAtLeast(1)
                    minAlive = s.trafficSaveMinAlive.coerceAtLeast(1)
                    markBuilt = false; modeStr = "top-${s.normalTopBatch} по скорости"
                }
                else -> {
                    candidates = aliveByPing                                   // ПЕРВЫЙ топ — меряем всех
                    batch = Int.MAX_VALUE; minAlive = 0                        // без ранней остановки
                    markBuilt = true; modeStr = "полный (${aliveByPing.size})"
                }
            }
            onPhase("Этап 2: скорость — $modeStr…")
            emitProgress(0, candidates.size)

            Thread {
                var connected: ServerProfile? = null
                var connectedSpeed = 0.0
                var best: ServerProfile? = null
                var bestSpeed = 0.0
                var selectable = 0
                var measured = 0
                for (p in candidates) {
                    if (cancelled.get()) break
                    val mbps = ServerSpeedTester.measureSpeed(appCtx, p)
                    measured++
                    onSpeedResult(p, mbps)   // результат сохраняем всегда
                    if (ServerFilter.isSelectable(p, mbps, s, blocklist)) {
                        selectable++
                        if (mbps > bestSpeed) { bestSpeed = mbps; best = p }
                        if (connected == null) {
                            connected = p; connectedSpeed = mbps
                            onPhase("Подключён ${label(p)} ($mbps Мбит/с), продолжаю…")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "полный тест: первый рабочий", "${fmt(mbps)} Мбит/с")
                            connect(p)
                        } else if (p !== connected && mbps > connectedSpeed * (1 + marginRatio)) {
                            val prev = connectedSpeed
                            onPhase("Быстрее на >10%: ${label(p)} ($mbps > $connectedSpeed) — переключаюсь")
                            val from = ServerLabels.displayForKey(appCtx, ProxyState.state.value.serverKey)
                            MonitorLog.switch(appCtx, from, ServerLabels.display(p), "полный тест: апгрейд", "было ${fmt(prev)} → стало ${fmt(mbps)} Мбит/с")
                            connected = p; connectedSpeed = mbps
                            connect(p)
                        }
                    }
                    onPhase("Этап 2: скорость $measured / ${candidates.size} · подключён: ${connected?.let(::label) ?: "—"}")
                    emitProgress(measured, candidates.size)
                    // Батч-остановка: набрали минимум живых на границе батча — дальше не мерим (экономия трафика).
                    if (minAlive > 0 && selectable >= minAlive && measured % batch == 0) break
                }
                if (markBuilt && !cancelled.get()) fullTopBuilt = true
                onDone(Result(connected ?: best, best, bestSpeed, candidates.size, cancelled.get()))
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
                    onDone(Result(null, null, 0.0, 0, true))
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
