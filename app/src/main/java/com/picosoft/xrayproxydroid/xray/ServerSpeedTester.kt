package com.picosoft.xrayproxydroid.xray

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/**
 * Throughput-тест (реальная скорость сервера, Mbps).
 *
 * measureOutboundDelay даёт только задержку → поднимаем ЛОКАЛЬНЫЙ временный CoreController
 * с socks-inbound на ЭФЕМЕРНОМ порту (не 10815/10816, не трогая активный прокси),
 * качаем пробник через этот socks (HttpURLConnection + Proxy(SOCKS)), считаем Mbps, гасим.
 * ПОСЛЕДОВАТЕЛЬНО (каждый temp-инстанс насыщает канал — параллель исказит).
 */
object ServerSpeedTester {

    private const val TAG = "ServerSpeedTester"

    /** Сколько замеров идёт ОДНОВРЕМЕННО (диагностика: делят ли канал). При пуле 1 всегда 1. */
    private val concurrentMeasures = AtomicInteger(0)

    private const val CONNECT_TIMEOUT_MS = 8_000

    // Warmup/окно/пул/URL пробника — теперь в [SettingsStore], не константами здесь.

    /**
     * ЗАПАСНЫЕ пробники ТУННЕЛЯ (через прокси) — если пользовательский URL не отдал mbps>0.
     * Диагностика (лог http=): Cloudflare __down → 403; Hetzner https → SSL-fail; cachefly → 200.
     * HTTP-мирроры первыми (без TLS-through-tunnel проблем). Первый пробник — из настроек.
     */
    private val fallbackProbes = listOf(
        "http://speedtest.tele2.net/1GB.zip",                 // 1 ГБ — заведомо больше окна даже на 170 Мбит (нет eof)
        "http://ipv4.download.thinkbroadband.com/1GB.zip",    // 1 ГБ, открытый speedtest-миррор
        "https://proof.ovh.net/files/1Gb.dat",                // OVH https, 1 ГБ
        "http://speedtest.tele2.net/100MB.zip",               // 100 МБ — запас (eof теперь валиден, мерим по факт. окну)
    )

    /**
     * Пробники КАНАЛА (baseline «мой интернет», ПРЯМО, без туннеля) — русские незаблокированные CDN.
     * НЕ для туннеля! Сейчас НЕ используются — заложены для будущего замера канала (сигнал A монитора).
     * Cloudflare здесь нельзя (заблокирован напрямую); vk.RU (не vk.com).
     */
    @Suppress("unused")
    private val channelProbes = listOf(
        "https://mc.yandex.ru/metrika/tag.js",
        "https://yastatic.net/jquery/3.6.4/jquery.min.js",
        "https://vk.ru/js/api/openapi.js",
    )

    /** Исход замера: УСПЕХ (валидная скорость) или ПРОВАЛ (замер не состоялся — НЕ маленькая скорость).
     *  [bytes] — фактически скачано (для учёта ТЕСТОВОГО трафика, [TrafficTracker]). */
    data class Measurement(val mbps: Double, val ok: Boolean, val reason: String, val bytes: Long = 0)

    /** Минимум байт, ниже которого замер считаем несостоявшимся (backstop к «измерено==0»). */
    private const val MIN_TOTAL_BYTES = 16 * 1024

    /**
     * Скорость одного сервера, Mbps. >0 — ВАЛИДНЫЙ замер; -1.0 — ПРОВАЛ/«не измерено»
     * (ошибка сборки/connect/порт/чтения, схлопнувшееся окно, ноль измеренных байт) — это НЕ 0.0
     * и НЕ маленькая скорость. Не трогает активный прокси (свой CoreController + эфемерный порт).
     */
    fun measureSpeed(
        context: Context,
        profile: ServerProfile,
        warmupMs: Int = SettingsStore.current().speedWarmupMs,
        measureMs: Int = SettingsStore.current().speedWindowMs,
    ): Double = measureSpeedDetailed(context, profile, warmupMs, measureMs).let { if (it.ok) it.mbps else -1.0 }

    /** Как [measureSpeed], но с исходом/причиной (для диалога «Перемерить»). */
    fun measureSpeedDetailed(
        context: Context,
        profile: ServerProfile,
        warmupMs: Int = SettingsStore.current().speedWarmupMs,
        measureMs: Int = SettingsStore.current().speedWindowMs,
    ): Measurement {
        XrayController.ensureEnv(context)
        val verbose = SettingsStore.current().verboseLogs
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val probes = (listOf(SettingsStore.current().speedProbeUrl) + fallbackProbes).distinct()

        val name = profile.remarks.ifBlank { profile.address }
        val concurrent = concurrentMeasures.incrementAndGet()
        log("── measure START «$name» ${profile.address}:${profile.port} net=${profile.network} sec=${profile.security} · параллельно=$concurrent · warmup=${warmupMs}мс окно=${measureMs}мс")
        val t0 = System.nanoTime()
        try {
            val port = freePort() ?: run { log("«$name» freePort FAIL"); return Measurement(-1.0, false, "нет свободного порта") }
            val cfg = try {
                XrayConfigBuilder.buildForSpeedTest(profile, port)
            } catch (e: Exception) {
                log("«$name» buildForSpeedTest FAIL: ${e.message}")
                return Measurement(-1.0, false, "ошибка конфига: ${e.message}")
            }

            val core = Libv2ray.newCoreController(NoopCallback())
            try {
                core.startLoop(cfg, 0)
                if (!core.isRunning) { log("«$name» core not running"); return Measurement(-1.0, false, "ядро не запустилось") }
                val portReady = awaitPort(port, deadlineMs = 2_000)
                val upMs = (System.nanoTime() - t0) / 1_000_000
                if (!portReady) {
                    log("«$name» temp-инстанс порт $port НЕ готов за ${upMs}мс")
                    return Measurement(-1.0, false, "temp-инстанс не поднялся")
                }
                log("«$name» temp-инстанс поднят за ${upMs}мс (порт=$port)")
                var lastFail = Measurement(-1.0, false, "нет пробников")
                var testBytes = 0L
                for (probe in probes) {
                    val m = downloadMbps(probe, port, warmupMs, measureMs, name, verbose)
                    testBytes += m.bytes                    // тестовый трафик: скачанное каждым пробником
                    if (m.ok) {
                        TrafficTracker.addTest(testBytes)
                        log("── measure DONE «$name» = ${m.mbps} Mbps [${m.reason}] (probe=$probe) · тест-байт=$testBytes")
                        return m
                    }
                    lastFail = m
                }
                TrafficTracker.addTest(testBytes)
                log("── measure FAIL «$name» — ${lastFail.reason} · тест-байт=$testBytes")
                return lastFail
            } catch (e: Exception) {
                log("«$name» measureSpeed error: ${e.message}")
                return Measurement(-1.0, false, "исключение: ${e.message}")
            } finally {
                try { core.stopLoop() } catch (e: Exception) { /* ignore */ }
            }
        } finally {
            concurrentMeasures.decrementAndGet()
        }
    }

    interface Handle {
        fun cancel()
    }

    /**
     * Батч скорости. Пул [pool] одновременных замеров (из настроек, дефолт 1 = строго последовательно —
     * каждый temp-инстанс насыщает канал; пул>1 сделан НАСТРАИВАЕМЫМ специально, чтобы проверить
     * гипотезу деления канала параллельными замерами). Прогрессивный [onResult] сразу; [onProgress];
     * по завершении [onFinish]. Отмена ([Handle.cancel]) — флаг (текущие замеры доработают своё окно).
     * Колбэки на фоновых потоках пула — UI-маршалинг на вызывающем.
     */
    fun testAll(
        context: Context,
        servers: List<ServerProfile>,
        warmupMs: Int = SettingsStore.current().speedWarmupMs,
        measureMs: Int = SettingsStore.current().speedWindowMs,
        pool: Int = SettingsStore.current().speedPool,
        onResult: (ServerProfile, Double) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinish: () -> Unit = {},
    ): Handle {
        val appCtx = context.applicationContext
        val cancelled = AtomicBoolean(false)
        val total = servers.size
        val done = AtomicInteger(0)
        val executor = Executors.newFixedThreadPool(pool.coerceAtLeast(1))

        for (p in servers) {
            executor.execute {
                if (cancelled.get()) return@execute
                val mbps = measureSpeed(appCtx, p, warmupMs, measureMs)
                if (cancelled.get()) return@execute
                onResult(p, mbps)
                onProgress(done.incrementAndGet(), total)
            }
        }
        executor.shutdown()   // новых не принимаем; поданные доработают

        Thread {
            executor.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            if (!cancelled.get()) onFinish()
        }.start()

        return object : Handle {
            override fun cancel() {
                cancelled.set(true)
                executor.shutdownNow()
            }
        }
    }

    /** Свободный TCP-порт от ОС (ServerSocket(0)); null если не удалось. */
    private fun freePort(): Int? = try {
        ServerSocket(0).use { it.localPort }
    } catch (e: Exception) {
        null
    }

    /** Ждём, пока socks-порт temp-инстанса начнёт принимать (ретрай на гонку старта). */
    private fun awaitPort(port: Int, deadlineMs: Long): Boolean {
        val end = System.nanoTime() + deadlineMs * 1_000_000
        while (System.nanoTime() < end) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) }
                return true
            } catch (e: Exception) {
                Thread.sleep(100)
            }
        }
        return false
    }

    /**
     * TIME-BASED замер. ОДНА формула: mbps = измерено_байт × 8 / фактическое_окно / 1e6.
     * Прогрев отмеряется от ПЕРВОГО БАЙТА ТЕЛА (не от старта запроса — иначе граница уезжает),
     * окно измерения — сразу после прогрева. Никаких переключений формулы по eof/ошибке.
     *
     * Возвращает [Measurement]: ok=false (провал, mbps=-1) если — измерено_байт==0 / окно <50%
     * заданного / была ошибка чтения / всего_байт < минимума. Это НЕ маленькая скорость: провалившийся
     * замер (напр. таймаут чтения после заголовков) НЕ выдаётся за 0.1 Мбит/с. eof в пределах окна —
     * валидный результат по фактически прошедшему времени (помечается в логе).
     */
    private fun downloadMbps(probe: String, socksPort: Int, warmupMs: Int, measureMs: Int, name: String, verbose: Boolean): Measurement {
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = (URL(probe).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = warmupMs + measureMs + 2_000
            setRequestProperty("User-Agent", "v2rayNG/1.8.0")
        }
        val reqStart = System.nanoTime()
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            log("«$name» probe=$probe CONNECT-FAIL ${e.javaClass.simpleName}: ${e.message}")
            conn.disconnect()
            return Measurement(-1.0, false, "connect-fail: ${e.javaClass.simpleName}")
        }
        val headerMs = (System.nanoTime() - reqStart) / 1_000_000
        log("«$name» probe=$probe http=$code хендшейк+заголовки=${headerMs}мс contentLength=${conn.contentLengthLong}")
        if (code !in 200..299) {
            conn.disconnect()
            return Measurement(-1.0, false, "HTTP $code")
        }

        var totalBytes = 0L
        var warmupBytes = 0L
        var measuredBytes = 0L
        var firstNanos = 0L
        var warmupEnd = 0L      // фиксируется от ПЕРВОГО байта тела
        var measureEnd = 0L
        var lastMeasuredNanos = 0L
        var eof = false
        var readErr: String? = null
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = try { input.read(buf) } catch (e: Exception) { readErr = "${e.javaClass.simpleName}: ${e.message}"; break }
                    if (n < 0) { eof = true; break }
                    val now = System.nanoTime()
                    if (firstNanos == 0L) {                          // прогрев/окно стартуют от ПЕРВОГО байта
                        firstNanos = now
                        warmupEnd = now + warmupMs * 1_000_000L
                        measureEnd = warmupEnd + measureMs * 1_000_000L
                    }
                    totalBytes += n
                    if (now < warmupEnd) {
                        warmupBytes += n
                    } else {
                        measuredBytes += n
                        lastMeasuredNanos = now
                    }
                    if (now >= measureEnd) break                     // окно выработано
                }
            }
        } catch (e: Exception) {
            readErr = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
        }

        val ttfbMs = if (firstNanos != 0L) (firstNanos - reqStart) / 1_000_000 else -1
        // Фактическое окно = от конца прогрева до последнего измеренного байта.
        val actualWindowMs = if (warmupEnd != 0L && lastMeasuredNanos > warmupEnd)
            (lastMeasuredNanos - warmupEnd) / 1_000_000 else 0L

        // ПРОВАЛ: не выдаём провалившийся замер за скорость.
        val failReason = when {
            readErr != null -> "ошибка чтения: $readErr"
            measuredBytes == 0L -> "0 измеренных байт (соединение встало после заголовков)"
            actualWindowMs < measureMs / 2 -> "окно ${actualWindowMs}мс < 50% от ${measureMs}мс"
            totalBytes < MIN_TOTAL_BYTES -> "всего $totalBytes байт < минимума"
            else -> null
        }
        val eofNote = if (eof) " (eof — окно не выработано полностью)" else ""
        if (failReason != null) {
            log("«$name» probe=$probe ПРОВАЛ [$failReason]$eofNote | ttfb=${ttfbMs}мс окно=${actualWindowMs}мс всего=$totalBytes прогрев=$warmupBytes измерено=$measuredBytes eof=$eof")
            return Measurement(-1.0, false, failReason, bytes = totalBytes)
        }

        val mbps = measuredBytes * 8.0 / (actualWindowMs / 1000.0) / 1_000_000.0   // ЕДИНАЯ формула
        val rounded = (mbps * 100).roundToInt() / 100.0
        log("«$name» probe=$probe → $rounded Mbps$eofNote | ttfb=${ttfbMs}мс окно=${actualWindowMs}мс всего=$totalBytes прогрев=$warmupBytes измерено=$measuredBytes eof=$eof")
        return Measurement(rounded, true, "ok$eofNote", bytes = totalBytes)
    }

    private class NoopCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
