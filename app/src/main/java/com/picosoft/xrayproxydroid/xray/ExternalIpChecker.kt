package com.picosoft.xrayproxydroid.xray

import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/**
 * Внешний IP ЧЕРЕЗ активный SOCKS-туннель (127.0.0.1:SOCKS_PORT).
 * Индикатор РЕАЛЬНОЙ живости туннеля: галочки портов говорят лишь что локальный сокет слушает,
 * а полученный IP — что трафик реально ходит наружу через сервер.
 *
 * Цепочка фолбэков (каждый возвращает голый IP текстом): ipify → ifconfig.me → ipinfo.io.
 * Таймаут 5с на каждый. Возвращает IP или null (нет ответа).
 *
 * Пр.144 (КРИТИЧНО, ⭐ живость): КАЖДАЯ проба — СВЕЖИЙ сокет через SOCKS, НЕ переиспользуем keep-alive.
 * КОРЕНЬ бага «зелёный, но связи нет»: раньше проба шла через HttpURLConnection (OkHttp пул) и МОГЛА
 * переиспользовать давно открытый живой keep-alive коннект к ipify. Когда сервер переставал принимать НОВЫЕ
 * соединения (реальный трафик и спидтест мертвы), СТАРЫЙ коннект ещё отвечал → проба ложно «ок», монитор
 * не видел обрыв, восстановление НЕ запускалось. Свежий сокет каждый раз = проба ходит тем же путём, что и
 * реальный трафик (новое соединение), и честно падает, когда туннель не пропускает. Плюс remote-DNS через
 * SOCKS (как реальный трафик), Connection: close, антикэш-нонс.
 */
object ExternalIpChecker {

    private const val TAG = "ExternalIpChecker"
    private const val TIMEOUT_MS = 5_000
    private const val MAX_RESP_BYTES = 8 * 1024   // ответ крошечный (IP); больше не читаем

    // (host, path) — путь без нонса; нонс добавим при запросе, чтобы исключить любой промежуточный кэш.
    private val endpoints = listOf(
        "api.ipify.org" to "/",
        "ifconfig.me" to "/ip",
        "ipinfo.io" to "/ip",
    )
    private val nonce = AtomicLong(0)
    private val ipLike = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}|[0-9a-fA-F:]{2,}:[0-9a-fA-F:]{2,}")

    // Промпт 101.A — ОДИН запрос внешнего адреса за раз, из ЕДИНОЙ точки. РАНЬШЕ петля интерфейса и монитор
    // запрашивали независимо, не зная друг о друге; пока запрос был коротким — наложений не было, но стоило
    // ему затянуться (мёртвый канал) — запросы шли внахлёст и ДУШИЛИ туннель (вечное «проверяется связь»).
    // Теперь: пока запрос выполняется, новые НЕ создаются — пришедшие ЖДУТ его результат (coalesce) и свой
    // запрос НЕ делают. Никаких новых потоков здесь не создаём (потоки — на стороне вызывающих).
    // НЕ ТРОГАЕМ (Промпт 101.D): таймаут, цепочку фолбэков, логику вердикта — только сериализация.
    private val lock = Any()
    @Volatile private var inFlight = false
    @Volatile private var lastResult: String? = null
    private var generation = 0L

    // Тестовый шов + счётчики (Промпт 101.C): подменяемая «одна работа» и наблюдаемая параллельность/число
    // фактических запросов — чтобы ЧИСЛАМИ показать «один запрос за раз». В проде onceOverrideForTest=null.
    internal var onceOverrideForTest: ((Int) -> String?)? = null
    private val concurrentNow = java.util.concurrent.atomic.AtomicInteger(0)
    @Volatile internal var maxConcurrentSeen = 0
    @Volatile internal var onceCount = 0
    internal fun resetTestCounters() { maxConcurrentSeen = 0; onceCount = 0 }

    /**
     * Быстрая проверка прохода для подбора (Пр.146.Ф2): 1 эндпоинт × короткий таймаут. НЕ полные 3×5с — при
     * переборе кандидатов важна скорость, а один свежий проход через туннель уже доказывает живость.
     */
    fun probePass(socksPort: Int = XrayConfig.SOCKS_PORT, timeoutMs: Int = 4000): Boolean =
        fetch(socksPort, timeoutMs, maxEndpoints = 1) != null

    /** Единая точка запроса внешнего адреса. Блокирующий — запускать на фоновом потоке (как и раньше). */
    fun fetch(socksPort: Int = XrayConfig.SOCKS_PORT, timeoutMs: Int = TIMEOUT_MS, maxEndpoints: Int = Int.MAX_VALUE): String? {
        synchronized(lock) {
            if (inFlight) {
                // Запрос уже идёт — дождаться ЕГО результата, свой НЕ запускать (не плодим параллельные).
                val gen = generation
                while (inFlight && generation == gen) {
                    try { (lock as Object).wait() } catch (_: InterruptedException) { return lastResult }
                }
                Log.i(TAG, "запрос внешнего адреса уже шёл — отдан его результат (без нового)")
                return lastResult
            }
            inFlight = true
        }
        var result: String? = null
        try {
            val n = concurrentNow.incrementAndGet()
            if (n > maxConcurrentSeen) maxConcurrentSeen = n
            onceCount++
            result = onceOverrideForTest?.invoke(socksPort) ?: fetchOnce(socksPort, timeoutMs, maxEndpoints)
        } finally {
            concurrentNow.decrementAndGet()
            synchronized(lock) {
                lastResult = result
                inFlight = false
                generation++
                (lock as Object).notifyAll()
            }
        }
        return result
    }

    /**
     * Один фактический сетевой запрос (цепочка фолбэков). Вызывается ТОЛЬКО из [fetch] под флагом inFlight.
     * Пр.144: СВЕЖИЙ TCP+TLS сокет ЧЕРЕЗ SOCKS на каждый вызов (никакого пула/keep-alive). DNS — remote через
     * SOCKS (createUnresolved), как у реального трафика. Успех = реально прошёл HTTP 200 с телом-IP через туннель.
     */
    private fun fetchOnce(socksPort: Int, timeoutMs: Int = TIMEOUT_MS, maxEndpoints: Int = Int.MAX_VALUE): String? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        for ((host, path) in endpoints.take(maxEndpoints.coerceAtLeast(1))) {
            val n = nonce.incrementAndGet()
            var raw: Socket? = null
            try {
                raw = Socket(proxy)
                // createUnresolved → имя резолвит SOCKS-сервер (remote DNS, socks5h) тем же путём, что реальный трафик.
                raw.connect(InetSocketAddress.createUnresolved(host, 443), timeoutMs)
                raw.soTimeout = timeoutMs
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, host, 443, true) as SSLSocket
                ssl.startHandshake()   // реальный TLS через туннель — уже доказывает, что трафик ходит
                val req = "GET $path?_=$n HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "User-Agent: curl/8.0\r\n" +
                    "Accept: */*\r\n" +
                    "Cache-Control: no-cache, no-store\r\n" +
                    "Connection: close\r\n\r\n"
                ssl.outputStream.apply { write(req.toByteArray(Charsets.US_ASCII)); flush() }
                // читаем до EOF (Connection: close), но не более MAX_RESP_BYTES
                val buf = ByteArrayOutputStream()
                val chunk = ByteArray(2048)
                val ins = ssl.inputStream
                while (buf.size() < MAX_RESP_BYTES) {
                    val r = ins.read(chunk)
                    if (r < 0) break
                    buf.write(chunk, 0, r)
                }
                val resp = buf.toString("US-ASCII")
                val statusLine = resp.substringBefore("\r\n", "")
                if (!statusLine.contains(" 200")) { Log.i(TAG, "$host → $statusLine"); continue }
                val body = resp.substringAfter("\r\n\r\n", "")
                val ip = ipLike.find(body)?.value
                if (ip != null && ip.length in 7..45) {
                    Log.i(TAG, "IP через туннель = $ip (via $host, свежий сокет)")
                    return ip
                }
                Log.i(TAG, "$host → 200, но IP в теле не распознан")
            } catch (e: Exception) {
                Log.i(TAG, "$host FAIL ${e.javaClass.simpleName}: ${e.message}")
            } finally {
                try { raw?.close() } catch (_: Exception) {}
            }
        }
        return null
    }
}
