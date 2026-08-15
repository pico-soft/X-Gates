package com.picosoft.xrayproxydroid.xray

import android.content.Context
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Полный адаптивный тест (перенос Termux run_full_test_with_early_connect):
 *   Этап 1: ping всех (real-ping, пул 8) → отсев мёртвых (pingMs>=0), сорт по возр. пинга.
 *   Этап 2: speed ПОСЛЕДОВАТЕЛЬНО по ВСЕМ живым (в порядке пинга). Первый с speed>0 →
 *           СРАЗУ подключиться (early-connect, через переданный [connect]). Перебор НЕ прерывается.
 *   Этап 3: апгрейд — переключиться на самый быстрый, ТОЛЬКО если он заметно быстрее
 *           подключённого (порог запаса [marginRatio], дефолт 10%).
 *
 * proxy-check НЕ нужен — real-ping уже проходит весь протокол до бэкенда и тянет URL через туннель.
 * Порог скорости здесь НЕ гейт (это в мониторе) — коннект к первому speed>0.
 * Монитора здесь НЕТ (следующий этап).
 *
 * Оркестратор: переиспользует [ServerTester] (ping) + [ServerSpeedTester] (speed) + [connect].
 * Колбэки — на фоновых потоках, UI-маршалинг на вызывающем.
 */
object FullTestRunner {

    /** Порог «заметно быстрее» для апгрейда: best.speed >= connected.speed × (1 + 0.10). */
    const val DEFAULT_MARGIN_RATIO = 0.10

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

        // --- Этап 3: апгрейд ---
        fun finishWithUpgrade(
            alive: List<ServerProfile>,
            connected: ServerProfile?,
            connectedSpeed: Double,
            best: ServerProfile?,
            bestSpeed: Double,
        ) {
            if (best != null && best !== connected &&
                bestSpeed > connectedSpeed * (1 + marginRatio)
            ) {
                onPhase("Переключение на быстрейший: ${label(best)} $bestSpeed Мбит/с (был $connectedSpeed)")
                connect(best)
                onDone(Result(best, best, bestSpeed, alive.size, false))
            } else {
                onDone(Result(connected ?: best, best, bestSpeed, alive.size, cancelled.get()))
            }
        }

        // --- Этап 2: speed + early-connect ---
        fun startSpeedPhase(alive: List<ServerProfile>) {
            if (cancelled.get()) { onDone(Result(null, null, 0.0, alive.size, true)); return }
            if (alive.isEmpty()) {
                onPhase("Нет живых серверов — проверь интернет / все мёртвые")
                onDone(Result(null, null, 0.0, 0, cancelled.get()))
                return
            }
            onPhase("Этап 2: скорость по ${alive.size} живым…")

            var connected: ServerProfile? = null
            var connectedSpeed = 0.0
            var best: ServerProfile? = null
            var bestSpeed = 0.0

            speedHandle = ServerSpeedTester.testAll(
                context = appCtx,
                servers = alive,
                onResult = { p, mbps ->
                    onSpeedResult(p, mbps)
                    if (mbps > bestSpeed) { bestSpeed = mbps; best = p }
                    if (mbps > 0 && connected == null) {           // АВТО-ПОДКЛЮЧЕНИЕ: первый рабочий
                        connected = p; connectedSpeed = mbps
                        onPhase("Подключён ${label(p)} ($mbps Мбит/с), продолжаю…")
                        connect(p)
                    }
                },
                onProgress = { done, total -> onPhase("Этап 2: скорость $done / $total · подключён: ${connected?.let(::label) ?: "—"}") },
                onFinish = { finishWithUpgrade(alive, connected, connectedSpeed, best, bestSpeed) },
            )
        }

        // --- Этап 1: ping всех ---
        onPhase("Этап 1: пинг ${allServers.size}…")
        pingHandle = ServerTester.testAll(
            context = appCtx,
            servers = allServers,
            concurrency = 8,
            onResult = { p, ms ->
                val v = ms.toInt()
                pingByKey[key(p)] = v
                onPingResult(p, v)
            },
            onProgress = { done, total -> onPhase("Этап 1: пинг $done / $total") },
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
