package com.picosoft.xrayproxydroid.xray

import android.content.Context
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Полный адаптивный тест (перенос Termux run_full_test_with_early_connect):
 *   Этап 1: ping всех (real-ping, пул 8) → отсев мёртвых (pingMs>=0), сорт по возр. пинга.
 *   Этап 2: speed ПОСЛЕДОВАТЕЛЬНО по ВСЕМ живым (в порядке пинга). Первый живой (≥MIN_USABLE) →
 *           СРАЗУ подключиться (early-connect). Дальше ПРОГРЕССИВНЫЙ апгрейд ПО ХОДУ: как только
 *           очередной замер даёт >10% ([marginRatio]) сверх текущего подключённого — переключиться.
 *
 * proxy-check НЕ нужен — real-ping уже проходит весь протокол до бэкенда и тянет URL через туннель.
 * Монитора здесь НЕТ (следующий этап).
 *
 * Оркестратор: переиспользует [ServerTester] (ping) + [ServerSpeedTester] (speed) + [connect].
 * Колбэки — на фоновых потоках, UI-маршалинг на вызывающем.
 */
object FullTestRunner {

    /** Порог «заметно быстрее» для апгрейда: best.speed >= connected.speed × (1 + 0.10). */
    const val DEFAULT_MARGIN_RATIO = 0.10

    /**
     * Минимальная ПОЛЕЗНАЯ скорость (Мбит/с) — «живой» сервер. Ниже — «0.0»: скрыт в списке
     * (MainActivity) и не годится для авто-подключения. 0.05 = граница округления до «0.0».
     */
    const val MIN_USABLE_MBPS = 0.05

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
        marginRatio: Double = DEFAULT_MARGIN_RATIO,
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
        var speedHandle: ServerSpeedTester.Handle? = null

        val pingByKey = ConcurrentHashMap<String, Int>()

        fun key(p: ServerProfile) = SubscriptionManager.serverKey(p)
        fun label(p: ServerProfile) = p.remarks.ifBlank { p.address }

        // --- Этап 2: speed + early-connect ---
        fun startSpeedPhase(alive: List<ServerProfile>) {
            if (cancelled.get()) { onDone(Result(null, null, 0.0, alive.size, true)); return }
            if (alive.isEmpty()) {
                onPhase("Нет живых серверов — проверь интернет / все мёртвые")
                onDone(Result(null, null, 0.0, 0, cancelled.get()))
                return
            }
            onPhase("Этап 2: скорость по ${alive.size} живым…")
            emitProgress(0, alive.size)   // новая шкала фазы скорости — сброс в 0

            var connected: ServerProfile? = null
            var connectedSpeed = 0.0
            var best: ServerProfile? = null
            var bestSpeed = 0.0

            speedHandle = ServerSpeedTester.testAll(
                context = appCtx,
                servers = alive,
                onResult = { p, mbps ->
                    onSpeedResult(p, mbps)
                    if (mbps >= MIN_USABLE_MBPS) {
                        if (mbps > bestSpeed) { bestSpeed = mbps; best = p }
                        if (connected == null) {
                            // Сначала — к ПЕРВОМУ живому: связь сразу, любая полезная скорость.
                            connected = p; connectedSpeed = mbps
                            onPhase("Подключён ${label(p)} ($mbps Мбит/с), продолжаю…")
                            connect(p)
                        } else if (p !== connected && mbps > connectedSpeed * (1 + marginRatio)) {
                            // Прогрессивный апгрейд ПО ХОДУ: переключаемся на заметно (>10%) более быстрый.
                            onPhase("Быстрее на >10%: ${label(p)} ($mbps > $connectedSpeed) — переключаюсь")
                            connected = p; connectedSpeed = mbps
                            connect(p)
                        }
                    }
                },
                onProgress = { done, total ->
                    onPhase("Этап 2: скорость $done / $total · подключён: ${connected?.let(::label) ?: "—"}")
                    emitProgress(done, total)
                },
                onFinish = { onDone(Result(connected ?: best, best, bestSpeed, alive.size, cancelled.get())) },
            )
        }

        // --- Этап 1: ping всех ---
        onPhase("Этап 1: пинг ${allServers.size}…")
        emitProgress(0, allServers.size)   // шкала фазы пинга
        pingHandle = ServerTester.testAll(
            context = appCtx,
            servers = allServers,
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
                    val alive = allServers
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
                speedHandle?.cancel()
            }
        }
    }
}
