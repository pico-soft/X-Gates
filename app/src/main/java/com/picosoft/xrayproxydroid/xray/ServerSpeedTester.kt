package com.picosoft.xrayproxydroid.xray

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import libv2ray.CoreCallbackHandler
import libv2ray.Libv2ray
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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

    /** Сколько замеров идёт ОДНОВРЕМЕННО (диагностика: делят ли канал). В батче должно быть 1. */
    private val concurrentMeasures = AtomicInteger(0)

    // Time-based замер: качаем ~фиксированное ВРЕМЯ, не до конца файла (подход Б).
    /** Прогрев — отбрасываем (TCP slow-start ещё не разогнался). */
    const val DEFAULT_WARMUP_MS = 1_000
    /** Окно измерения — считаем байты за это время. */
    const val DEFAULT_MEASURE_MS = 5_000
    private const val CONNECT_TIMEOUT_MS = 8_000

    /** __down?bytes=BIG (100 МБ) — заведомо НЕ докачается за окно, поэтому мерим ПО ВРЕМЕНИ, а не по размеру. */
    const val DEFAULT_DOWNLOAD_BYTES = 100_000_000

    /**
     * Пробники ТУННЕЛЯ (throughput/выбор, ЧЕРЕЗ прокси) — ЗАРУБЕЖНЫЕ, большие файлы.
     * Диагностика на устройстве (лог http=):
     *   - Cloudflare __down → 403 (блокирует датацентр/прокси-IP) — УБРАН.
     *   - Hetzner https → SSLHandshakeException через туннель — УБРАН.
     *   - cachefly https → 200 (работает).
     * HTTP-мирроры первыми: без TLS-through-tunnel проблем (как у Hetzner); открыты для любых IP.
     * Порядок: кандидаты сверху (быстрый отсев по http=), заведомо рабочий cachefly последним.
     * Возвращаем первый с mbps>0.
     */
    private val tunnelProbes = listOf(
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
        warmupMs: Int = DEFAULT_WARMUP_MS,
        measureMs: Int = DEFAULT_MEASURE_MS,
    ): Double {
        XrayController.ensureEnv(context)

        val name = profile.remarks.ifBlank { profile.address }
        val concurrent = concurrentMeasures.incrementAndGet()   // сколько замеров идёт параллельно ПРЯМО СЕЙЧАС
        Log.i(TAG, "── measure START «$name» ${profile.address}:${profile.port} net=${profile.network} sec=${profile.security} · параллельно=$concurrent")
        val t0 = System.nanoTime()
        try {
            val port = freePort() ?: run { Log.i(TAG, "«$name» freePort FAIL"); return -1.0 }
            val cfg = try {
                XrayConfigBuilder.buildForSpeedTest(profile, port)
            } catch (e: Exception) {
                Log.i(TAG, "«$name» buildForSpeedTest FAIL: ${e.message}")
                return -1.0
            }

            val core = Libv2ray.newCoreController(NoopCallback())
            try {
                core.startLoop(cfg, 0)
                if (!core.isRunning) { Log.i(TAG, "«$name» core not running"); return -1.0 }
                val portReady = awaitPort(port, deadlineMs = 2_000)
                val upMs = (System.nanoTime() - t0) / 1_000_000
                if (!portReady) {
                    Log.i(TAG, "«$name» temp-инстанс порт $port НЕ готов за ${upMs}мс")
                    return -1.0
                }
                Log.i(TAG, "«$name» temp-инстанс поднят за ${upMs}мс (порт=$port)")
                for (probe in tunnelProbes) {
                    val mbps = downloadMbps(probe, port, warmupMs, measureMs, name)
                    if (mbps > 0) {
                        Log.i(TAG, "── measure DONE «$name» = $mbps Mbps (probe=$probe)")
                        return mbps
                    }
                }
                Log.i(TAG, "── measure DONE «$name» = 0.0 (все пробники впустую)")
                return 0.0
            } catch (e: Exception) {
                Log.i(TAG, "«$name» measureSpeed error: ${e.message}")
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
     * Батч скорости — ПОСЛЕДОВАТЕЛЬНО (каждый замер насыщает канал → параллель исказит).
     * Прогрессивный [onResult] на каждый сервер сразу; [onProgress]; по завершении [onFinish].
     * Отмена ([Handle.cancel]) — флаг между серверами (текущий замер доработает свои ~6с).
     * Колбэки на фоновом потоке — UI-маршалинг на вызывающем.
     */
    fun testAll(
        context: Context,
        servers: List<ServerProfile>,
        warmupMs: Int = DEFAULT_WARMUP_MS,
        measureMs: Int = DEFAULT_MEASURE_MS,
        onResult: (ServerProfile, Double) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinish: () -> Unit = {},
    ): Handle {
        val appCtx = context.applicationContext
        val cancelled = AtomicBoolean(false)
        val total = servers.size
        Thread {
            var done = 0
            for (p in servers) {
                if (cancelled.get()) break
                val mbps = measureSpeed(appCtx, p, warmupMs, measureMs)
                if (cancelled.get()) break
                onResult(p, mbps)
                onProgress(++done, total)
            }
            if (!cancelled.get()) onFinish()
        }.start()
        return object : Handle {
            override fun cancel() { cancelled.set(true) }
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
    private fun downloadMbps(probe: String, socksPort: Int, warmupMs: Int, measureMs: Int, name: String): Double {
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
            Log.i(TAG, "«$name» probe=$probe CONNECT-FAIL ${e.javaClass.simpleName}: ${e.message}")
            conn.disconnect()
            return 0.0
        }
        val headerMs = (System.nanoTime() - reqStart) / 1_000_000
        Log.i(TAG, "«$name» probe=$probe http=$code хендшейк+заголовки=${headerMs}мс contentLength=${conn.contentLengthLong}")
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
        Log.i(
            TAG,
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
