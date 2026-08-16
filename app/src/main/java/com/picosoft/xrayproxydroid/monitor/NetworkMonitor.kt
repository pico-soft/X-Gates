package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayController
import kotlinx.coroutines.delay
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL
import kotlin.math.roundToInt

/**
 * Цикл АВТОМОНИТОРИНГА, этап 1: только НАБЛЮДЕНИЕ и запись журнала — НИКАКИХ переключений.
 * Живёт корутиной внутри foreground-сервиса (Doze его не усыпляет), работает только пока прокси активен.
 *
 * Порядок каждого активного цикла (перенос эталонного _monitor_loop, облегчённый под батарею):
 *   0. Гейты: монитор включён? прокси жив? не идёт ручной тест? (иначе молчим)
 *   1. ЭКОНОМИЯ: с прошлого цикла через туннель не прошло ни байта → простой → проверку пропускаем.
 *   2. Сигнал A (прямой канал МИМО туннеля, дешёвый TCP к DNS) — ГЛАВНЫЙ ГЕЙТ: мёртв → «нет интернета»,
 *      туннель НЕ винить, падением НЕ считать.
 *   3. Сигнал B (туннель): лёгкая достижимость внешнего мира через активный SOCKS (внешний IP).
 *      Отвечает → «всё в порядке» (пишем разрежённо). Не отвечает → это лёгкая проверка УЖЕ указала
 *      на проблему →
 *   4. Только тогда HEAVY-замер: прямой канал (Мбит/с, русские CDN) + туннель (зарубежный пробник).
 *      Решение по эталону (адаптивный порог 50% при слабом интернете, иначе tunnel_threshold).
 *      Падение копится; после N подряд формируем ГИПОТЕТИЧЕСКОЕ действие «переключился бы на X…».
 *
 * Общие ресурсы: счётчики туннеля берём из [TrafficTracker] (накопленные), НЕ через queryTunnelDelta
 * (её потребляет поллер трафика сервиса — был бы конфликт). Активный сервер/SOCKS при ручном тесте
 * не трогаем (см. [MonitorCoordinator]).
 */
object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    // Сигнал A (лёгкий): «есть ли интернет вообще» — TCP-connect к DNS мимо туннеля (полезной нагрузки ~0).
    private val directDnsProbes = listOf("77.88.8.8" to 53, "8.8.8.8" to 53, "1.1.1.1" to 53)
    // Heavy-замер прямого канала (Мбит/с) — русские CDN (эталон сигнала A), НЕ Cloudflare (блок в РФ напрямую).
    private val directSpeedProbes = listOf("https://ya.ru/", "https://mc.yandex.ru/", "https://vk.ru/")
    // Heavy-замер туннеля — зарубежный пробник ЧЕРЕЗ активный SOCKS (смысл туннеля = доступ к миру).
    private const val TUNNEL_SPEED_PROBE = "https://speed.cloudflare.com/__down?bytes=2000000"

    private const val IDLE_WAIT_MS = 30_000L
    private const val OK_HEARTBEAT_MS = 30 * 60 * 1000L   // «всё в порядке» — не чаще раза в 30 мин

    suspend fun loop(context: Context) {
        val app = context.applicationContext
        var baseline = tunnelBytes()
        var failures = 0
        var lastTag = ""
        var lastOkMs = 0L
        Log.i(TAG, "monitor loop started")

        while (true) {
            val s = SettingsStore.current()
            if (!s.monitorEnabled) { delay(IDLE_WAIT_MS); continue }
            if (!XrayController.isRunning || !ProxyState.state.value.running) { delay(IDLE_WAIT_MS); continue }

            delay(s.monitorIntervalSec.coerceAtLeast(60) * 1000L)

            // Пере-проверка после сна.
            val cur = SettingsStore.current()
            if (!cur.monitorEnabled) continue
            if (!XrayController.isRunning || !ProxyState.state.value.running) continue

            // Взаимное исключение с ручным полным тестом — он сам переключает сервер, монитор молчит.
            if (MonitorCoordinator.fullTestRunning) {
                if (lastTag != "manual") { record(app, "—", "—", "пропуск: идёт ручной тест"); lastTag = "manual" }
                baseline = tunnelBytes()
                continue
            }

            // Простой = нет байт через туннель с прошлого цикла. ВАЖНО (Промпт 43): сам по себе он НЕ
            // повод пропускать проверки — мёртвый туннель тоже не даёт байт, именно потому что мёртв, и
            // пропуск цикла по нулю байт маскировал бы поломку. Лёгкие сигналы A/B идут ВСЕГДА (дёшевы);
            // простой отменяет ТОЛЬКО тяжёлый замер (шаг ниже).
            val idle = tunnelBytes() - baseline <= 0

            // --- Сигнал A (гейт «есть интернет») — ВСЕГДА ---
            if (!directAlive()) {
                failures = 0
                if (lastTag != "nonet") { record(app, "нет", "—", "нет интернета — туннель не виню"); lastTag = "nonet" }
                baseline = tunnelBytes()
                continue
            }

            // --- Сигнал B (лёгкая достижимость через туннель) — ВСЕГДА ---
            if (ExternalIpChecker.fetch() != null) {
                failures = 0
                if (idle) {
                    // Простой И проверки в порядке → тяжёлый замер не гоним (экономия трафика/батареи).
                    if (lastTag != "idle") { record(app, "жив", "OK", "простой — трафика нет, проверки в порядке"); lastTag = "idle" }
                } else {
                    val nowMs = System.currentTimeMillis()
                    if (lastTag != "ok" || nowMs - lastOkMs > OK_HEARTBEAT_MS) {
                        record(app, "жив", "OK", "всё в порядке"); lastTag = "ok"; lastOkMs = nowMs
                    }
                }
                baseline = tunnelBytes()
                continue
            }

            // --- Лёгкая B НЕ прошла → это ПОЛОМКА (даже при нуле байт), не простой → HEAVY-замер ---
            val directMbps = measureDirectMbps(app)
            if (directMbps == null) {
                failures = 0
                record(app, "нет", "—", "нет интернета (канал не тянет) — туннель не виню"); lastTag = "nonet"
                baseline = tunnelBytes()
                continue
            }
            val tunnelMbps = measureTunnelMbps()
            val weak = directMbps < cur.monitorDirectThreshold
            val effThr = if (weak) maxOf(directMbps / 2, 0.05) else cur.monitorTunnelThreshold

            if (tunnelMbps >= effThr) {
                // Внешний IP не получен, но по скорости туннель тянет — проба IP дала ложную тревогу.
                failures = 0
                record(app, fmt(directMbps), fmt(tunnelMbps),
                    "внешний IP не получен, но туннель тянет — не считаю падением")
                lastTag = "ok"; lastOkMs = System.currentTimeMillis()
                baseline = tunnelBytes()
                continue
            }

            // Падение — копим подряд; вывод (гипотетическое действие) только после N. Ноль байт при
            // неотвечающем туннеле — это ПОЛОМКА, отличаем в вердикте от «простой, проверки в порядке».
            failures++
            val verdict = if (idle) "нет трафика, туннель не отвечает" else "падение туннеля"
            val reason = "туннель ${fmt(tunnelMbps)} < порог ${fmt(effThr)}" +
                if (weak) " (слабый интернет, 50% от ${fmt(directMbps)})" else " (прямой ${fmt(directMbps)})"
            val wouldDo = if (failures >= cur.monitorFailuresToVerdict) {
                hypotheticalSwitch(app, reason, failures)
            } else {
                "наблюдаю ($failures/${cur.monitorFailuresToVerdict}) — пока не вмешался бы"
            }
            record(app, fmt(directMbps), fmt(tunnelMbps), verdict, wouldDo)
            lastTag = "fall"
            baseline = tunnelBytes()
        }
    }

    // Накопленные байты туннеля из TrafficTracker (НЕ queryTunnelDelta — её читает поллер сервиса).
    private fun tunnelBytes(): Long {
        val s = TrafficTracker.state.value
        return s.sessionRx + s.sessionTx
    }

    private fun record(context: Context, direct: String, tunnel: String, verdict: String, wouldDo: String = "") {
        MonitorLog.add(context, MonitorEvent(System.currentTimeMillis(), direct, tunnel, verdict, wouldDo))
    }

    private fun fmt(mbps: Double) = "${(mbps * 10).roundToInt() / 10.0} Мбит/с"

    /** Сигнал A: жив ли прямой канал (TCP к DNS мимо туннеля). Полезной нагрузки нет — учитывать нечего. */
    private fun directAlive(): Boolean {
        for ((host, port) in directDnsProbes) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 3000); return true }
            } catch (e: Exception) { /* следующий */ }
        }
        return false
    }

    /** Heavy-замер прямого канала (МИМО туннеля). Трафик проверки → поток «Тест». null — канал не тянет. */
    private fun measureDirectMbps(context: Context): Double? {
        for (url in directSpeedProbes) {
            try {
                val start = System.nanoTime()
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 3000; readTimeout = 3000
                    setRequestProperty("User-Agent", "curl/8.0")
                }
                if (conn.responseCode !in 200..399) { conn.disconnect(); continue }
                var total = 0L
                conn.inputStream.use { ins ->
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val n = ins.read(buf); if (n < 0) break
                        total += n
                        if (total >= 1_000_000L || (System.nanoTime() - start) / 1e9 > 3.0) break
                    }
                }
                conn.disconnect()
                val secs = (System.nanoTime() - start) / 1e9
                if (total > 0 && secs > 0) {
                    TrafficTracker.addTest(total)   // прямой канал мимо туннеля иначе нигде не учтётся
                    return total * 8 / 1e6 / secs
                }
            } catch (e: Exception) { /* следующий */ }
        }
        return null
    }

    /** Heavy-замер туннеля через активный SOCKS. Это реальный трафик туннеля (учтёт поллер сервиса). */
    private fun measureTunnelMbps(): Double {
        return try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT))
            val start = System.nanoTime()
            val conn = (URL(TUNNEL_SPEED_PROBE).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = 5000; readTimeout = 6000
                setRequestProperty("User-Agent", "curl/8.0")
            }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return 0.0 }
            var total = 0L
            conn.inputStream.use { ins ->
                val buf = ByteArray(32 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    if (total >= 2_000_000L || (System.nanoTime() - start) / 1e9 > 6.0) break
                }
            }
            conn.disconnect()
            val secs = (System.nanoTime() - start) / 1e9
            if (total > 0 && secs > 0) total * 8 / 1e6 / secs else 0.0
        } catch (e: Exception) { 0.0 }
    }

    /** Что монитор СДЕЛАЛ БЫ (переключений сейчас нет): лучший ИНОЙ кандидат по известной скорости. */
    private fun hypotheticalSwitch(context: Context, reason: String, failures: Int): String {
        val settings = SettingsStore.current()
        val bl = BlocklistStore.current()
        val curKey = ProxyState.state.value.serverKey
        val best = SubscriptionManager.allServers(context)
            .filter { SubscriptionManager.serverKey(it) != curKey }
            .filter { ServerFilter.isSelectable(it, it.speedMbps, settings, bl) }
            .maxByOrNull { it.speedMbps ?: 0.0 }
            ?: return "переключаться некуда — нет живых кандидатов (нужен полный тест). Причина: $reason"
        val name = best.remarks.ifBlank { best.address }
        val sp = best.speedMbps?.let { fmt(it) } ?: "скорость неизв."
        return "переключился бы на «$name» ($sp), потому что $reason ($failures циклов подряд)"
    }
}
