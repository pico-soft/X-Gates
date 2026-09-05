package com.picosoft.xrayproxydroid.xray

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.FetchResult
import com.picosoft.xrayproxydroid.subscription.SubscriptionFetcher
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

    // Приёмники ОТДАЧИ (POST). Промпт 104.B (проверено ФАКТОМ, POST 2МБ): cloudflare __up отвечает 200 за ~1.2с
    // (принимает тело и сразу отвечает) → БЫСТРЫЙ, ставим первым. tele2/upload.php отвечает 200 напрямую, но
    // ЧЕРЕЗ ТУННЕЛЬ отдаёт байты и НЕ шлёт ответ (http=000) → клиент ждёт весь таймаут = сток 25-75с. Оставляем
    // tele2 как запасной (distinct убирает дубль основного из настроек). httpbin отвергнут (http=100, 15с).
    // Дефолт uploadProbeUrl в SettingsStore теперь cloudflare __up; на устройствах со старым (tele2) сохранённым
    // значением сток обрезает жёсткий потолок UPLOAD_HARD_CAP_MS в uploadMbps.
    private val uploadFallbacks = listOf(
        "https://speed.cloudflare.com/__up",
        "http://speedtest.tele2.net/upload.php",
    )
    // Промпт 104.B: жёсткий потолок ОБЩЕГО времени одного замера отдачи. Кооперативное окно (measureMs) между
    // записями НЕ спасает, если приёмник не отвечает / канал насыщён (одна запись блокируется). Отдача display-only —
    // не даём ей быть 25-75с стоком в КАЖДОМ активном замере. Потолок от первой записи (коннект ограничен отдельно).
    private const val UPLOAD_HARD_CAP_MS = 6_000L

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
    // Потолок правдоподобия ОТДАЧИ: выше — это не отдача, а мгновенная запись в буфер сокета (фейк ~1864 Мбит/с).
    // Реальная отдача через мобильный туннель много ниже; 500 Мбит/с с огромным запасом отсекает буфер-фейк.
    private const val MAX_PLAUSIBLE_UPLOAD_MBPS = 500.0
    // Пр.148: «много-заходный» замер — окно измерения бьём на под-окна и берём МЕДИАНУ их скоростей. Одиночное
    // окно шумит (Пр.108/109/130): случайная просадка занижает быстрый сервер → он ошибочно уходит вниз в выборе.
    // Медиана под-окон сглаживает шум БЕЗ доп. трафика (та же загрузка). Нужно ≥3 под-окна, иначе берём общее.
    private const val SUB_WINDOW_NANOS = 300_000_000L
    private fun median(xs: List<Double>): Double {
        val s = xs.sorted(); val n = s.size
        return if (n % 2 == 1) s[n / 2] else (s[n / 2 - 1] + s[n / 2]) / 2.0
    }

    /**
     * Скорость одного сервера, Mbps. >0 — ВАЛИДНЫЙ замер; -1.0 — ПРОВАЛ/«не измерено»
     * (ошибка сборки/connect/порт/чтения, ноль измеренных байт, обрыв до минимума) — это НЕ 0.0
     * и НЕ маленькая скорость. Остановка по бюджету (время/объём) — ВАЛИДНА, не провал.
     * Не трогает активный прокси (свой CoreController + эфемерный порт).
     */
    fun measureSpeed(
        context: Context,
        profile: ServerProfile,
        warmupMs: Int = SettingsStore.current().speedWarmupMs,
        measureMs: Int = SettingsStore.current().speedWindowMs,
    ): Double = measureSpeedDetailed(context, profile, warmupMs, measureMs).let { if (it.ok) it.mbps else -1.0 }

    /**
     * Пр.133.B: замер на ДОСТАТОЧНОСТЬ — не точное число, а «держит ли [thresholdMbps]». Бюджет/окно урезаны до
     * подтверждения порога: сервер быстрее порога упрётся в маленький бюджет за доли секунды → замер оборвётся,
     * трафика на порядок меньше полного. Возврат — как обычный замер (mbps); достаточность = mbps ≥ порога.
     * Бюджет замера = порог×~1.2с (сколько нужно прокачать, чтобы подтвердить скорость), прогрев — короткий.
     */
    fun measureSufficiency(context: Context, profile: ServerProfile, thresholdMbps: Double): Double {
        val measureBudget = (thresholdMbps / 8.0 * 1_000_000.0 * 1.2).toLong().coerceIn(256 * 1024L, 4 * 1024 * 1024L)
        val warmupBudget = 512 * 1024L   // короткий прогрев — точность не нужна, нужен факт «тянет порог»
        return measureSpeedDetailed(context, profile, warmupMs = 400, measureMs = 1200,
            warmupBudgetOverride = warmupBudget, measureBudgetOverride = measureBudget).let { if (it.ok) it.mbps else -1.0 }
    }

    /** Как [measureSpeed], но с исходом/причиной (для диалога «Перемерить»). */
    fun measureSpeedDetailed(
        context: Context,
        profile: ServerProfile,
        warmupMs: Int = SettingsStore.current().speedWarmupMs,
        measureMs: Int = SettingsStore.current().speedWindowMs,
        warmupBudgetOverride: Long? = null,   // Пр.133: урезанный бюджет для замера достаточности
        measureBudgetOverride: Long? = null,
    ): Measurement {
        XrayController.ensureEnv(context)
        val verbose = SettingsStore.current().verboseLogs
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val probes = (listOf(SettingsStore.current().speedProbeUrl) + fallbackProbes).distinct()

        val name = profile.remarks.ifBlank { profile.address }
        val warmupBudget = warmupBudgetOverride ?: SettingsStore.current().speedWarmupBudgetBytes
        val measureBudget = measureBudgetOverride ?: SettingsStore.current().activeMeasureBudgetBytes   // Пр.131: бюджет per-mode
        val concurrent = concurrentMeasures.incrementAndGet()
        log("── measure START «$name» ${profile.address}:${profile.port} net=${profile.network} sec=${profile.security} · параллельно=$concurrent · warmup=${warmupMs}мс/${warmupBudget}Б окно=${measureMs}мс/${measureBudget}Б")
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
                    val m = downloadMbps(probe, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)),
                        warmupMs, measureMs, warmupBudget, measureBudget, name, verbose)
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

    // Дешёвая проверка кандидата (Промпт 52): temp-инстанс + РЕАЛЬНАЯ передача небольшого объёма.
    // Критерий — БАЙТЫ ПРИШЛИ, а не время ответа (в отличие от ServerTester.ping = measureOutboundDelay,
    // который меряет задержку до generate_204 и отбрасывал бы «не пингуется, но работает» серверы).
    private const val PROBE_ALIVE_TARGET_BYTES = 32 * 1024L
    private const val PROBE_ALIVE_MIN_BYTES = 8 * 1024L
    private const val PROBE_ALIVE_TIMEOUT_MS = 6_000

    /**
     * Жив ли сервер по РЕАЛЬНОЙ передаче: поднять temp-инстанс на эфемерном порту, скачать несколько КБ
     * через его socks. true — пришло ≥ [PROBE_ALIVE_MIN_BYTES]. Не трогает активный прокси. Трафик → «Тест».
     */
    fun probeAlive(context: Context, profile: ServerProfile): Boolean {
        XrayController.ensureEnv(context)
        val verbose = SettingsStore.current().verboseLogs
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val name = profile.remarks.ifBlank { profile.address }
        val probes = (listOf(SettingsStore.current().speedProbeUrl) + fallbackProbes).distinct()
        concurrentMeasures.incrementAndGet()
        try {
            val port = freePort() ?: return false
            val cfg = try { XrayConfigBuilder.buildForSpeedTest(profile, port) } catch (e: Exception) {
                log("«$name» probeAlive buildForSpeedTest FAIL: ${e.message}"); return false
            }
            val core = Libv2ray.newCoreController(NoopCallback())
            try {
                core.startLoop(cfg, 0)
                if (!core.isRunning || !awaitPort(port, deadlineMs = 2_000)) {
                    log("«$name» probeAlive temp-инстанс не поднялся"); return false
                }
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                var testBytes = 0L
                for (probe in probes) {
                    val got = downloadSome(probe, proxy, PROBE_ALIVE_TARGET_BYTES, PROBE_ALIVE_TIMEOUT_MS)
                    testBytes += got
                    if (got >= PROBE_ALIVE_MIN_BYTES) {
                        TrafficTracker.addTest(testBytes)
                        log("«$name» probeAlive OK ($got Б via $probe)")
                        return true
                    }
                }
                TrafficTracker.addTest(testBytes)
                log("«$name» probeAlive FAIL (пришло $testBytes Б)")
                return false
            } finally {
                try { core.stopLoop() } catch (e: Exception) { /* ignore */ }
            }
        } finally {
            concurrentMeasures.decrementAndGet()
        }
    }

    /** Скачать до [targetBytes] через socks-proxy с [timeoutMs]. Возвращает фактически принятые байты. */
    private fun downloadSome(probe: String, proxy: Proxy, targetBytes: Long, timeoutMs: Int): Long {
        return try {
            val conn = (URL(probe).openConnection(proxy) as HttpURLConnection).apply {
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = timeoutMs
                setRequestProperty("User-Agent", "v2rayNG/1.8.0")
            }
            if (conn.responseCode !in 200..299) { conn.disconnect(); return 0L }
            var total = 0L
            val start = System.nanoTime()
            conn.inputStream.use { ins ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    val n = ins.read(buf); if (n < 0) break
                    total += n
                    if (total >= targetBytes || (System.nanoTime() - start) / 1_000_000 > timeoutMs) break
                }
            }
            conn.disconnect()
            total
        } catch (e: Exception) {
            0L
        }
    }

    /**
     * Скачать ПРОИЗВОЛЬНЫЙ URL через temp-инстанс данного сервера (ступень 5 каскада [CascadeFetch]:
     * обновить подписку, когда прямой путь заблокирован, а активный туннель упал). Переиспользует
     * [SubscriptionFetcher] (редиректы/типизированный результат) через socks temp-инстанса. Не трогает
     * активный прокси. Трафик → «Тест». null — temp-инстанс не поднялся.
     */
    fun fetchViaTempInstance(context: Context, profile: ServerProfile, url: String, userAgent: String, timeoutMs: Int): FetchResult? {
        XrayController.ensureEnv(context)
        val verbose = SettingsStore.current().verboseLogs
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val name = profile.remarks.ifBlank { profile.address }
        concurrentMeasures.incrementAndGet()
        try {
            val port = freePort() ?: return null
            val cfg = try { XrayConfigBuilder.buildForSpeedTest(profile, port) } catch (e: Exception) {
                log("«$name» fetchViaTempInstance buildForSpeedTest FAIL: ${e.message}"); return null
            }
            val core = Libv2ray.newCoreController(NoopCallback())
            try {
                core.startLoop(cfg, 0)
                if (!core.isRunning || !awaitPort(port, deadlineMs = 2_000)) {
                    log("«$name» fetchViaTempInstance temp-инстанс не поднялся"); return null
                }
                val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port))
                val res = SubscriptionFetcher.fetch(url, userAgent, timeoutMs) { it.openConnection(proxy) }
                if (res.bodyBytes > 0) TrafficTracker.addTest(res.bodyBytes.toLong())
                log("«$name» fetchViaTempInstance http=${res.httpCode} bytes=${res.bodyBytes}")
                return res
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
                try {
                    if (cancelled.get()) return@execute
                    val mbps = measureSpeed(appCtx, p, warmupMs, measureMs)
                    if (cancelled.get()) return@execute
                    onResult(p, mbps)
                    onProgress(done.incrementAndGet(), total)
                } catch (e: Exception) {
                    Log.w(TAG, "задача замера упала (проглочено): ${e.message}")
                }
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
     * Замер с ДВУМЯ бюджетами на КАЖДУЮ фазу — время И объём, кончается наступивший РАНЬШЕ.
     * ОДНА формула: mbps = измерено_байт × 8 / фактическое_окно / 1e6. Прогрев — от ПЕРВОГО БАЙТА
     * ТЕЛА (фикс C), измерение — сразу после прогрева (его фактического конца, не по фикс. времени).
     * Соединение рвём сразу по достижении бюджета — не дочитываем впустую.
     *
     * ВАЖНО (Промпт 32): остановка ПО ОБЪЁМУ — ВАЛИДНЫЙ результат при любой длительности окна.
     * Правило «короткое окно = провал» БОЛЬШЕ НЕ применяется: провал только при ошибке чтения /
     * ноль измеренных байт / всего_байт < минимума. Быстрый сервер упрётся в объём за доли секунды —
     * это НЕ «не измерено».
     */
    private fun downloadMbps(
        probe: String, proxy: Proxy?, warmupMs: Int, measureMs: Int,
        warmupBudget: Long, measureBudget: Long, name: String, verbose: Boolean,
    ): Measurement {
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        // proxy=null → ПРЯМОЕ соединение (замер прямого канала, без туннеля); иначе через SOCKS.
        val conn = ((if (proxy != null) URL(probe).openConnection(proxy) else URL(probe).openConnection()) as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = warmupMs + measureMs + 2_000
            setRequestProperty("User-Agent", "v2rayNG/1.8.0")
        }
        currentMeasureConn = conn   // Промпт 101.B: чтобы «Прервать проверку» закрыл это соединение немедленно
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
        var warmupTimeEnd = 0L        // предел прогрева по времени (от первого байта)
        var warmupDoneNanos = 0L      // фактический конец прогрева (время ИЛИ объём)
        var measureTimeEnd = 0L       // предел измерения по времени (от конца прогрева)
        var lastMeasuredNanos = 0L
        var inWarmup = true
        var eof = false
        var readErr: String? = null
        var warmupEndReason = "—"     // ПО_ВРЕМЕНИ / ПО_ОБЪЁМУ / EOF / ОШИБКА
        var measureEndReason = "—"
        var subStartNanos = 0L        // Пр.148: под-окна измерения (для медианы — сглаживание шума)
        var subBytes = 0L
        val subRates = ArrayList<Double>()
        try {
            conn.inputStream.use { input ->
                val buf = ByteArray(64 * 1024)
                loop@ while (true) {
                    val n = try { input.read(buf) } catch (e: Exception) {
                        readErr = "${e.javaClass.simpleName}: ${e.message}"
                        if (inWarmup) warmupEndReason = "ОШИБКА" else measureEndReason = "ОШИБКА"
                        break@loop
                    }
                    if (n < 0) {
                        eof = true
                        if (inWarmup) warmupEndReason = "EOF" else measureEndReason = "EOF"
                        break@loop
                    }
                    val now = System.nanoTime()
                    if (firstNanos == 0L) {                          // прогрев стартует от ПЕРВОГО байта
                        firstNanos = now
                        warmupTimeEnd = now + warmupMs * 1_000_000L
                    }
                    totalBytes += n
                    if (inWarmup) {
                        warmupBytes += n
                        // прогрев кончается по ВРЕМЕНИ или по ОБЪЁМУ — что раньше
                        val byTime = now >= warmupTimeEnd
                        val byVol = warmupBytes >= warmupBudget
                        if (byTime || byVol) {
                            warmupEndReason = if (byVol) "ПО_ОБЪЁМУ" else "ПО_ВРЕМЕНИ"
                            inWarmup = false
                            warmupDoneNanos = now
                            measureTimeEnd = now + measureMs * 1_000_000L
                        }
                    } else {
                        measuredBytes += n
                        lastMeasuredNanos = now
                        // Пр.148: копим под-окно; по истечении SUB_WINDOW_NANOS — фиксируем его скорость (для медианы).
                        if (subStartNanos == 0L) subStartNanos = if (warmupDoneNanos != 0L) warmupDoneNanos else now
                        subBytes += n
                        val subDt = now - subStartNanos
                        if (subDt >= SUB_WINDOW_NANOS) {
                            subRates.add(subBytes * 8.0 / (subDt / 1_000_000_000.0) / 1_000_000.0)
                            subStartNanos = now; subBytes = 0L
                        }
                        val byTime = now >= measureTimeEnd
                        val byVol = measuredBytes >= measureBudget
                        if (byTime || byVol) {
                            measureEndReason = if (byVol && !byTime) "ПО_ОБЪЁМУ" else "ПО_ВРЕМЕНИ"
                            break@loop                                // рвём сразу — не дочитываем впустую
                        }
                    }
                }
            }
        } catch (e: Exception) {
            readErr = "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
            if (currentMeasureConn === conn) currentMeasureConn = null
        }

        val ttfbMs = if (firstNanos != 0L) (firstNanos - reqStart) / 1_000_000 else -1
        val warmupMsActual = if (firstNanos != 0L && warmupDoneNanos > firstNanos) (warmupDoneNanos - firstNanos) / 1_000_000 else -1
        // Фактическое окно измерения = от конца прогрева до последнего измеренного байта.
        val actualWindowMs = if (warmupDoneNanos != 0L && lastMeasuredNanos > warmupDoneNanos)
            (lastMeasuredNanos - warmupDoneNanos) / 1_000_000 else 0L

        // ПРОВАЛ — ТОЛЬКО ошибка/обрыв/ноль. Остановка по объёму (короткое окно) — валидна.
        val failReason = when {
            readErr != null -> "ошибка чтения: $readErr"
            measuredBytes == 0L -> "0 измеренных байт (соединение встало после заголовков)"
            totalBytes < MIN_TOTAL_BYTES -> "всего $totalBytes байт < минимума"
            actualWindowMs <= 0L -> "нулевое окно измерения"
            else -> null
        }
        val diag = "ttfb=${ttfbMs}мс прогрев[$warmupEndReason ${warmupMsActual}мс ${warmupBytes}Б/бюджет${warmupBudget}Б] " +
            "измер[$measureEndReason ${actualWindowMs}мс ${measuredBytes}Б/бюджет${measureBudget}Б] всего=$totalBytes eof=$eof"
        if (failReason != null) {
            log("«$name» probe=$probe ПРОВАЛ [$failReason] | $diag")
            return Measurement(-1.0, false, failReason, bytes = totalBytes)
        }

        val overallMbps = measuredBytes * 8.0 / (actualWindowMs / 1000.0) / 1_000_000.0   // общее окно
        // Пр.148: если под-окон ≥3 — берём МЕДИАНУ их скоростей (сглаживает шум); иначе (короткий замер/стоп по
        // объёму у быстрого сервера) — общее окно. Медиана не даёт одиночной просадке занизить хороший сервер.
        val mbps = if (subRates.size >= 3) median(subRates) else overallMbps
        val rounded = (mbps * 100).roundToInt() / 100.0
        log("«$name» probe=$probe → $rounded Mbps (медиана ${subRates.size} под-окон, общее ${(overallMbps * 100).roundToInt() / 100.0}) | $diag")
        return Measurement(rounded, true, "ok/$measureEndReason", bytes = totalBytes)
    }

    // ══════════════ Промпт 90: замеры АКТИВНОГО туннеля (сопоставимо с кандидатами) + ОТДАЧА ══════════════
    // ВАЖНО (90.A): активный туннель мерим ТЕМ ЖЕ downloadMbps с ОДНИМИ warmup/окном/пробниками, что и
    // measureSpeed кандидатов → числа сравнимы напрямую. Прежний NetworkMonitor.measureTunnelMbps (без
    // прогрева, кэп 2МБ, cloudflare, таймер со старта соединения) СИСТЕМАТИЧЕСКИ занижал → сравнивать было
    // нельзя (сидели на 8, видя 143). Идёт через активный SOCKS (XrayConfig.SOCKS_PORT), не temp-инстанс.

    // Промпт 101.B: НЕМЕДЛЕННО прервать идущий замер (кнопка «Прервать проверку»), НЕ дожидаясь таймаута —
    // закрываем текущее соединение замера, блокирующее чтение/коннект падает с ошибкой → замер вернёт провал,
    // а поток проверки увидит checkCancel и выйдет. Во время ручной проверки замеры идут ПОСЛЕДОВАТЕЛЬНО в
    // одном потоке, а монитор молчит (fullTestRunning) — гонок за этот ref нет. Таймауты/логику НЕ трогаем.
    @Volatile private var currentMeasureConn: HttpURLConnection? = null
    @Volatile private var measureAborted = false
    // Закрыть текущее соединение И поднять флаг, чтобы цикл фолбэк-пробников замера активного туннеля СРАЗУ
    // прекратился, а не перебирал оставшиеся адреса (иначе отмена ждёт весь перебор). Reset — в начале проверки.
    fun abortCurrentMeasure() { measureAborted = true; runCatching { currentMeasureConn?.disconnect() } }
    fun resetMeasureAbort() { measureAborted = false }

    /** Скорость СКАЧИВАНИЯ активного туннеля, Mbps (>0 валидно, -1 провал). Трафик → «Тест». */
    fun measureActiveDownloadMbps(context: Context): Double {
        XrayController.ensureEnv(context)
        val s = SettingsStore.current()
        val probes = (listOf(s.speedProbeUrl) + fallbackProbes).distinct()
        var testBytes = 0L
        for (probe in probes) {
            if (measureAborted) break   // Промпт 101.B: отмена — не перебираем оставшиеся пробники
            val m = downloadMbps(probe, Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT)),
                s.speedWarmupMs, s.speedWindowMs, s.speedWarmupBudgetBytes, s.activeMeasureBudgetBytes, "активный↓", s.verboseLogs)
            testBytes += m.bytes
            if (m.ok) { TrafficTracker.addTest(testBytes); return m.mbps }
        }
        TrafficTracker.addTest(testBytes)
        return -1.0
    }

    /**
     * Скорость ПРЯМОГО канала (БЕЗ туннеля), Mbps — «Напрямую» на плашке. Тем же надёжным окном/прогревом, что и
     * туннель (иначе числа несравнимы), но по ПРЯМОМУ соединению (proxy=null) и с БОЛЬШОГО файла (directProbeUrl):
     * наивный замер мелкой страницы мерил бы задержку, а не ширину канала. Трафик → «Тест». -1 = провал/недоступно.
     */
    fun measureDirectDownloadMbps(context: Context): Double {
        val s = SettingsStore.current()
        val m = downloadMbps(s.directProbeUrl, null, s.speedWarmupMs, s.speedWindowMs,
            s.speedWarmupBudgetBytes, s.activeMeasureBudgetBytes, "напрямую↓", s.verboseLogs)
        if (m.bytes > 0) TrafficTracker.addTest(m.bytes)
        return if (m.ok) m.mbps else -1.0
    }

    /** Скорость ОТДАЧИ активного туннеля, Mbps (>0 валидно, -1 провал). Трафик → «Тест». */
    fun measureActiveUploadMbps(context: Context): Double {
        XrayController.ensureEnv(context)
        val s = SettingsStore.current()
        val probes = (listOf(s.uploadProbeUrl) + uploadFallbacks).distinct()
        for (url in probes) {
            if (measureAborted) break   // Промпт 101.B: отмена — не перебираем оставшиеся приёмники
            val m = uploadMbps(url, XrayConfig.SOCKS_PORT, s.speedWarmupMs, s.speedWindowMs,
                s.uploadMeasureBudgetBytes, "активный↑", s.verboseLogs)
            if (m.ok) return m.mbps
        }
        return -1.0
    }

    /**
     * Замер ОТДАЧИ (Промпт 90.B): POST-выгрузка через socks на приёмник. Прогрев ПО ВРЕМЕНИ (skip TCP
     * slow-start), затем окно до [measureBudget] байт ИЛИ [measureMs]. Пишем в поток — блокировка записи
     * при насыщении канала = реальная скорость отдачи. Формула та же: байты×8/окно/1e6. Трафик → «Тест».
     */
    private fun uploadMbps(
        url: String, socksPort: Int, warmupMs: Int, measureMs: Int, measureBudget: Long, name: String, verbose: Boolean,
    ): Measurement {
        fun log(msg: String) { if (verbose) Log.i(TAG, msg) }
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        val conn = try {
            (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = warmupMs + measureMs + 3_000
                setRequestProperty("User-Agent", "v2rayNG/1.8.0")
                setRequestProperty("Content-Type", "application/octet-stream")
                setChunkedStreamingMode(64 * 1024)   // стримим без Content-Length → запись идёт в сокет
            }
        } catch (e: Exception) { return Measurement(-1.0, false, "upload connect-fail: ${e.javaClass.simpleName}") }
        currentMeasureConn = conn   // Промпт 101.B: «Прервать проверку» закрывает и идущую отдачу

        val buf = ByteArray(64 * 1024)
        var total = 0L; var warmupBytes = 0L; var measuredBytes = 0L
        var firstNanos = 0L; var warmupTimeEnd = 0L; var warmupDoneNanos = 0L
        var measureTimeEnd = 0L; var lastMeasuredNanos = 0L
        var inWarmup = true; var writeErr: String? = null; var code = -1
        try {
            conn.outputStream.use { out ->
                loop@ while (true) {
                    // Промпт 104.B: жёсткий потолок ОБЩЕГО времени от первой записи — приёмник может не отвечать /
                    // канал насыщён, и кооперативное окно (measureMs, проверка МЕЖДУ записями) даёт переполнение
                    // до 25-75с. Обрезаем сток независимо от настроек/буферов.
                    if (firstNanos != 0L && (System.nanoTime() - firstNanos) / 1_000_000 > UPLOAD_HARD_CAP_MS) {
                        writeErr = writeErr ?: "потолок времени отдачи ${UPLOAD_HARD_CAP_MS}мс"; break@loop
                    }
                    try { out.write(buf) } catch (e: Exception) { writeErr = "${e.javaClass.simpleName}: ${e.message}"; break@loop }
                    val now = System.nanoTime()
                    if (firstNanos == 0L) { firstNanos = now; warmupTimeEnd = now + warmupMs * 1_000_000L }
                    total += buf.size
                    if (inWarmup) {
                        warmupBytes += buf.size
                        if (now >= warmupTimeEnd) { inWarmup = false; warmupDoneNanos = now; measureTimeEnd = now + measureMs * 1_000_000L }
                    } else {
                        measuredBytes += buf.size; lastMeasuredNanos = now
                        if (now >= measureTimeEnd || measuredBytes >= measureBudget) break@loop
                    }
                }
                out.flush()
            }
            // Промпт 104.B: не блокируемся на ответе, если запись оборвалась/упёрлась в потолок (приёмник, скорее
            // всего, не ответит → ждали бы весь readTimeout). Код нужен лишь для лога/правдоподобия при УСПЕХЕ.
            code = if (writeErr == null) try { conn.responseCode } catch (e: Exception) { -1 } else -1
            log("«$name» upload=$url http=$code всего=${total}Б измер=${measuredBytes}Б")
        } catch (e: Exception) {
            writeErr = writeErr ?: "${e.javaClass.simpleName}: ${e.message}"
        } finally {
            conn.disconnect()
            if (currentMeasureConn === conn) currentMeasureConn = null
        }
        if (total > 0) TrafficTracker.addTest(total)

        val windowMs = if (warmupDoneNanos != 0L && lastMeasuredNanos > warmupDoneNanos)
            (lastMeasuredNanos - warmupDoneNanos) / 1_000_000 else 0L
        val mbps = if (windowMs > 0) measuredBytes * 8.0 / (windowMs / 1000.0) / 1_000_000.0 else -1.0
        // Фейк «буфер сокета» (2МБ за 9мс = ~1864 Мбит/с) отличаем от честного замера НЕ по http-коду:
        // флаки-сервер часто НЕ отвечает (http=-1), хотя передача реально прошла с backpressure (окно ~500мс) —
        // требование 2xx душило валидную отдачу (пользователь видел «—»). Признак фейка — НЕРЕАЛЬНАЯ скорость
        // (реальная отдача через мобильный туннель много ниже потолка); реальный замер имеет осмысленное окно.
        val failReason = when {
            writeErr != null -> "ошибка отдачи: $writeErr"
            measuredBytes == 0L -> "0 отданных байт"
            windowMs <= 0L -> "нулевое окно отдачи"
            mbps > MAX_PLAUSIBLE_UPLOAD_MBPS -> "нереально ${mbps.roundToInt()} Мбит/с — буфер сокета, не отдача (http=$code)"
            else -> null
        }
        if (failReason != null) { log("«$name» upload ПРОВАЛ [$failReason]"); return Measurement(-1.0, false, failReason, bytes = total) }
        val rounded = (mbps * 100).roundToInt() / 100.0
        log("«$name» upload → $rounded Mbps (окно ${windowMs}мс, ${measuredBytes}Б, http=$code)")
        return Measurement(rounded, true, "ok-upload", bytes = total)
    }

    private class NoopCallback : CoreCallbackHandler {
        override fun startup(): Long = 0
        override fun shutdown(): Long = 0
        override fun onEmitStatus(l: Long, s: String?): Long = 0
    }
}
