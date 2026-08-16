package com.picosoft.xrayproxydroid.xray

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.settings.SettingsStore
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
        "http://speedtest.tele2.net/100MB.zip",               // HTTP, классический открытый миррор
        "http://ipv4.download.thinkbroadband.com/100MB.zip",  // HTTP, открытый speedtest-миррор
        "https://proof.ovh.net/files/100Mb.dat",              // OVH https
        "https://cachefly.cachefly.net/10mb.test",            // подтверждён 200 (10 МБ — мал, но замер починен)
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

    /**
     * Скорость одного сервера. Возвращает Mbps (≥0) или -1.0 при ошибке/недоступности.
     * Не трогает активный прокси (свой CoreController + эфемерный порт).
     */
    fun measureSpeed(
        context: Context,
        profile: ServerProfile,
        warmupMs: Int = SettingsStore.current().speedWarmupMs,
        measureMs: Int = SettingsStore.current().speedWindowMs,
    ): Double {
        XrayController.ensureEnv(context)
        val verbose = SettingsStore.current().verboseLogs
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        // Пробники: пользовательский URL из настроек первым, затем запасные (если он не отдал mbps>0).
        val probes = (listOf(SettingsStore.current().speedProbeUrl) + fallbackProbes).distinct()

        val name = profile.remarks.ifBlank { profile.address }
        val concurrent = concurrentMeasures.incrementAndGet()   // сколько замеров идёт параллельно ПРЯМО СЕЙЧАС
        log("── measure START «$name» ${profile.address}:${profile.port} net=${profile.network} sec=${profile.security} · параллельно=$concurrent")
        val t0 = System.nanoTime()
        try {
            val port = freePort() ?: run { log("«$name» freePort FAIL"); return -1.0 }
            val cfg = try {
                XrayConfigBuilder.buildForSpeedTest(profile, port)
            } catch (e: Exception) {
                log("«$name» buildForSpeedTest FAIL: ${e.message}")
                return -1.0
            }

            val core = Libv2ray.newCoreController(NoopCallback())
            try {
                core.startLoop(cfg, 0)
                if (!core.isRunning) { log("«$name» core not running"); return -1.0 }
                val portReady = awaitPort(port, deadlineMs = 2_000)
                val upMs = (System.nanoTime() - t0) / 1_000_000
                if (!portReady) {
                    log("«$name» temp-инстанс порт $port НЕ готов за ${upMs}мс")
                    return -1.0
                }
                log("«$name» temp-инстанс поднят за ${upMs}мс (порт=$port)")
                for (probe in probes) {
                    val mbps = downloadMbps(probe, port, warmupMs, measureMs, name, verbose)
                    if (mbps > 0) {
                        log("── measure DONE «$name» = $mbps Mbps (probe=$probe)")
                        return mbps
                    }
                }
                log("── measure DONE «$name» = 0.0 (все пробники впустую)")
                return 0.0
            } catch (e: Exception) {
                log("«$name» measureSpeed error: ${e.message}")
                return -1.0
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
     * TIME-BASED замер: качаем большой файл, отбрасываем [warmupMs] (TCP slow-start),
     * затем считаем байты за [measureMs] и обрываем. Mbps = байты × 8 / реальное_время_окна / 1e6.
     * Замер длится ~warmup+measure НЕЗАВИСИМО от скорости: быстрый накачает много, медленный мало —
     * но каждый покажет СВОЮ реальную скорость, а не таймаут. Файл заведомо не докачивается за окно.
     * 0.0 — недоступен/ничего не пришло.
     */
    private fun downloadMbps(probe: String, socksPort: Int, warmupMs: Int, measureMs: Int, name: String, verbose: Boolean): Double {
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = (URL(probe).openConnection(proxy) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = warmupMs + measureMs + 2_000
            setRequestProperty("User-Agent", "v2rayNG/1.8.0")
        }
        // ДИАГНОСТИКА: время до заголовков (TLS/REALITY-хендшейк через туннель) + HTTP-код.
        val reqStart = System.nanoTime()
        val code = try {
            conn.responseCode
        } catch (e: Exception) {
            log("«$name» probe=$probe CONNECT-FAIL ${e.javaClass.simpleName}: ${e.message}")
            conn.disconnect()
            return 0.0
        }
        val headerMs = (System.nanoTime() - reqStart) / 1_000_000
        log("«$name» probe=$probe http=$code хендшейк+заголовки=${headerMs}мс contentLength=${conn.contentLengthLong}")
        if (code !in 200..299) {
            conn.disconnect()
            return 0.0
        }
        var totalBytes = 0L
        var warmupBytes = 0L
        var firstNanos = 0L
        var lastNanos = 0L
        var eof = false
        var readErr: String? = null
        val start = System.nanoTime()
        val warmupEnd = start + warmupMs * 1_000_000L
        val measureEnd = warmupEnd + measureMs * 1_000_000L
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                while (System.nanoTime() < measureEnd) {
                    val n = try { input.read(buf) } catch (e: Exception) { readErr = "${e.javaClass.simpleName}: ${e.message}"; break }
                    if (n < 0) { eof = true; break }               // файл кончился раньше окна
                    val now = System.nanoTime()
                    if (firstNanos == 0L) firstNanos = now
                    lastNanos = now
                    totalBytes += n
                    if (now < warmupEnd) warmupBytes += n           // прогрев — учтём отдельно
                }
            }
        } catch (e: Exception) {
            readErr = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
        }

        val ttfbMs = if (firstNanos != 0L) (firstNanos - start) / 1_000_000 else -1
        val measuredBytes = totalBytes - warmupBytes
        val windowMs: Long
        val mbps = when {
            // Нормально: окно заполнено (big-file), прогрев отброшен.
            !eof && measuredBytes > 0 && lastNanos > warmupEnd -> {
                windowMs = (lastNanos - warmupEnd) / 1_000_000
                measuredBytes * 8.0 / ((lastNanos - warmupEnd) / 1e9) / 1_000_000.0
            }
            // Файл кончился раньше окна (мал/быстрый сервер) → мерим ВЕСЬ скачанный за фактическое время.
            totalBytes > 0 && lastNanos > firstNanos -> {
                windowMs = (lastNanos - firstNanos) / 1_000_000
                totalBytes * 8.0 / ((lastNanos - firstNanos) / 1e9) / 1_000_000.0
            }
            else -> { windowMs = 0; 0.0 }
        }
        val rounded = (mbps * 100).roundToInt() / 100.0
        log(
            "«$name» probe=$probe → $rounded Mbps | ttfb=${ttfbMs}мс окно=${windowMs}мс " +
                "всего_байт=$totalBytes прогрев_байт=$warmupBytes измерено_байт=$measuredBytes eof=$eof" +
                (readErr?.let { " ошибка_чтения=[$it]" } ?: "")
        )
        return rounded
    }

    private class NoopCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
