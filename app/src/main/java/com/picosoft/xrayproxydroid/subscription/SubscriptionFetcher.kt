package com.picosoft.xrayproxydroid.subscription

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection

/** Богатый результат загрузки подписки — для точной классификации отказа и диагностики. */
data class FetchResult(
    val ok: Boolean,
    val finalUrl: String,          // URL после нормализации и редиректов
    val httpCode: Int,             // -1, если до ответа не дошли (сеть/исключение)
    val contentType: String?,
    val contentLength: Long,       // из заголовка (может быть -1)
    val bodyBytes: Int,            // фактически скачано байт
    val body: String,             // тело (UTF-8); при не-2xx — тело ошибки (для диагностики)
    val exceptionClass: String?,   // напр. UnknownHostException / SocketTimeoutException
    val errorMessage: String?,
    val redirectChain: List<String> = emptyList(),   // все URL по цепочке редиректов (для диагностики)
    val retryAfterSec: Int? = null,   // заголовок Retry-After (сек), если сервер прислал (HTTP 429/503)
)

/**
 * Скачивание тела подписки. HttpURLConnection (встроен, minSdk 24).
 * САМИ проходим редиректы, В ТОМ ЧИСЛЕ http↔https (HttpURLConnection смену схемы молча НЕ проходит
 * и отдаёт пустоту). UA/таймаут — из настроек (панели по неизвестному UA часто отдают Clash YAML).
 * cleartext http:// на Android 9+ блокируется политикой → вернётся ok=false с exceptionClass.
 *
 * ВНИМАНИЕ: [fetch] синхронный — вызывать в ФОНОВОМ потоке.
 */
object SubscriptionFetcher {

    private const val MAX_REDIRECTS = 5

    /**
     * Заголовок Retry-After (HTTP 429/503): либо число секунд, либо HTTP-дата. Возвращаем секунды до
     * повтора (0..) или null, если заголовка/разобрать нет. Отрицательную дельту зажимаем в 0.
     */
    private fun parseRetryAfter(raw: String?): Int? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        v.toIntOrNull()?.let { return it.coerceAtLeast(0) }   // форма «секунды»
        return try {                                           // форма HTTP-дата (RFC 1123)
            val fmt = java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }
            val whenMs = fmt.parse(v)?.time ?: return null
            ((whenMs - System.currentTimeMillis()) / 1000L).coerceAtLeast(0L).toInt()
        } catch (e: Exception) { null }
    }

    /**
     * [open] — как открыть соединение для очередного URL: напрямую (`{ it.openConnection() }`), через
     * прокси (`{ it.openConnection(proxy) }`) или с привязкой к сети (`{ network.openConnection(it) }`).
     * Редиректы (в т.ч. http↔https) проходятся тем же способом.
     */
    fun fetch(
        url: String, userAgent: String, timeoutMs: Int,
        open: (URL) -> URLConnection = { it.openConnection() },
    ): FetchResult {
        var current = url
        var redirects = 0
        val hops = ArrayList<String>()
        try {
            while (true) {
                hops.add(current)
                val conn = (open(URL(current)) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false   // редиректы проходим сами (в т.ч. смена схемы)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                }
                val code = try {
                    conn.responseCode
                } catch (e: Exception) {
                    conn.disconnect()
                    return FetchResult(
                        ok = false, finalUrl = current, httpCode = -1, contentType = null,
                        contentLength = -1, bodyBytes = 0, body = "",
                        exceptionClass = e.javaClass.simpleName, errorMessage = e.message, redirectChain = hops,
                    )
                }

                if (code in 300..399 && redirects < MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) break
                    current = URL(URL(current), loc).toString()   // разрешаем относительный Location
                    redirects++
                    continue
                }

                val contentType = conn.contentType
                val contentLength = conn.contentLengthLong
                val retryAfter = parseRetryAfter(conn.getHeaderField("Retry-After"))
                val stream = if (code in 200..299) conn.inputStream else (conn.errorStream ?: conn.inputStream)
                val body = try {
                    stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                conn.disconnect()
                return FetchResult(
                    ok = code in 200..299,
                    finalUrl = current, httpCode = code, contentType = contentType,
                    contentLength = contentLength, bodyBytes = body.toByteArray(Charsets.UTF_8).size,
                    body = body, exceptionClass = null, errorMessage = null, redirectChain = hops,
                    retryAfterSec = retryAfter,
                )
            }
            // вышли по исчерпанию редиректов без финального ответа
            return FetchResult(
                ok = false, finalUrl = current, httpCode = -1, contentType = null,
                contentLength = -1, bodyBytes = 0, body = "",
                exceptionClass = "TooManyRedirects", errorMessage = "редиректов больше $MAX_REDIRECTS", redirectChain = hops,
            )
        } catch (e: Exception) {
            return FetchResult(
                ok = false, finalUrl = current, httpCode = -1, contentType = null,
                contentLength = -1, bodyBytes = 0, body = "",
                exceptionClass = e.javaClass.simpleName, errorMessage = e.message, redirectChain = hops,
            )
        }
    }
}
