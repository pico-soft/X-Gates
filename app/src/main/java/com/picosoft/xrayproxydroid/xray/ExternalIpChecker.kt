package com.picosoft.xrayproxydroid.xray

import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Внешний IP ЧЕРЕЗ активный SOCKS-туннель (127.0.0.1:SOCKS_PORT).
 * Индикатор РЕАЛЬНОЙ живости туннеля: галочки портов говорят лишь что локальный сокет слушает,
 * а полученный IP — что трафик реально ходит наружу через сервер.
 *
 * Цепочка фолбэков (каждый возвращает голый IP текстом): ipify → ifconfig.me → ipinfo.io.
 * Таймаут 5с на каждый. Возвращает IP или null (нет ответа).
 */
object ExternalIpChecker {

    private const val TAG = "ExternalIpChecker"
    private const val TIMEOUT_MS = 5_000

    private val endpoints = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://ipinfo.io/ip",
    )

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

    /** Единая точка запроса внешнего адреса. Блокирующий — запускать на фоновом потоке (как и раньше). */
    fun fetch(socksPort: Int = XrayConfig.SOCKS_PORT): String? {
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
            result = onceOverrideForTest?.invoke(socksPort) ?: fetchOnce(socksPort)
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

    /** Один фактический сетевой запрос (цепочка фолбэков). Вызывается ТОЛЬКО из [fetch] под флагом inFlight. */
    private fun fetchOnce(socksPort: Int): String? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        for (url in endpoints) {
            try {
                val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "curl/8.0")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    conn.disconnect()
                    if (ip.isNotEmpty() && ip.length <= 45) {   // IPv4/IPv6 разумной длины
                        Log.i(TAG, "IP через туннель = $ip (via $url)")
                        return ip
                    }
                } else {
                    conn.disconnect()
                    Log.i(TAG, "$url → http=$code")
                }
            } catch (e: Exception) {
                Log.i(TAG, "$url FAIL ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }
}
