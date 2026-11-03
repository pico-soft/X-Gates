package com.picosoft.xrayproxydroid.xray.link

import org.json.JSONObject

/**
 * xhttpSettings.extra (XMUX, паддинг, downloadSettings) из ссылки `extra=` или JSON-конфига подписки.
 * Подписка — чужие данные, а в конфиг ядра это идёт JSON-ом, поэтому берём только перечисленные поля и только после
 * разбора. Весь StreamConfig из downloadSettings пускать нельзя: masterKeyLog пишет файл, certificates читает файлы,
 * sockopt меняет маршрут. Список — по SplitHTTPConfig ядра v26.7.28 (при обновлении ядра сверить).
 * Перенос XhttpExtra.cs из X-Gates-Win: списки и поведение должны совпадать.
 */
internal object XhttpExtra {

    private val XHTTP_FIELDS = setOf(
        "headers", "xPaddingBytes", "xPaddingObfsMode", "xPaddingKey", "xPaddingHeader", "xPaddingPlacement", "xPaddingMethod",
        "uplinkHTTPMethod", "sessionIDPlacement", "sessionIDKey", "sessionIDTable", "sessionIDLength", "seqPlacement", "seqKey",
        "uplinkDataPlacement", "uplinkDataKey", "uplinkChunkSize", "noGRPCHeader", "noSSEHeader", "scMaxEachPostBytes",
        "scMinPostsIntervalMs", "scMaxBufferedPosts", "scStreamUpServerSecs", "xmux",
    )
    private val DOWNLOAD_FIELDS = setOf("address", "port", "network", "security")
    private val TLS_FIELDS = setOf("serverName", "alpn", "fingerprint", "pinnedPeerCertSha256", "verifyPeerCertByName")
    private val REALITY_FIELDS = setOf("serverName", "fingerprint", "publicKey", "password", "shortId", "spiderX", "mldsa65Verify")
    private val DOWNLOAD_XHTTP_FIELDS = XHTTP_FIELDS + setOf("path", "host", "mode")

    /** Разрешённое подмножество как JSON-объект; null — не JSON-объект или брать нечего. */
    fun sanitize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return try {
            val src = JSONObject(raw)
            val res = pick(src, XHTTP_FIELDS)
            if (res.has("headers")) res.put("headers", withoutHost(res.optJSONObject("headers")))
            src.optJSONObject("downloadSettings")?.let(::download)?.let { res.put("downloadSettings", it) }
            if (res.length() > 0) res.toString() else null
        } catch (e: Exception) {
            null
        } catch (e: StackOverflowError) {   // глубокая вложенность в чужой строке не должна ронять разбор подписки
            null
        }
    }

    /**
     * Значение extra для конфига. stream-one с downloadSettings ядро отвергает, поэтому такой блок убираем.
     * [dialerProxy]: канал загрузки sockopt основного соединения не наследует, поэтому ставим явно.
     */
    fun forConfig(saved: String?, mode: String?, dialerProxy: String? = null): String? {
        val safe = sanitize(saved) ?: return null
        var o = JSONObject(safe)
        val d = o.optJSONObject("downloadSettings")
        if (d != null) {
            if (mode == "stream-one") o = pick(o, XHTTP_FIELDS)
            else if (dialerProxy != null) d.put("sockopt", JSONObject().put("dialerProxy", dialerProxy))
        }
        return if (o.length() > 0) o.toString() else null
    }

    private fun pick(src: JSONObject, allowed: Set<String>): JSONObject {
        val res = JSONObject()
        for (k in src.keys()) if (k in allowed) res.put(k, src.opt(k))
        return res
    }

    /** Заголовок Host ядро в headers не принимает: конфиг не загрузится. */
    private fun withoutHost(headers: JSONObject?): JSONObject {
        val res = JSONObject()
        if (headers == null) return res
        for (k in headers.keys()) if (!k.equals("host", ignoreCase = true)) res.put(k, headers.opt(k))
        return res
    }

    /** downloadSettings без адреса и порта отбрасываем: конфиг ядро примет, а на первом соединении упадёт (panic). */
    private fun download(d: JSONObject): JSONObject? {
        if (d.optString("address").isBlank() || d.optInt("port") <= 0) return null
        val res = pick(d, DOWNLOAD_FIELDS)
        d.optJSONObject("tlsSettings")?.let { res.put("tlsSettings", pick(it, TLS_FIELDS)) }
        d.optJSONObject("realitySettings")?.let { res.put("realitySettings", pick(it, REALITY_FIELDS)) }
        (d.optJSONObject("xhttpSettings") ?: d.optJSONObject("splithttpSettings"))?.let { x ->
            val xhttp = pick(x, DOWNLOAD_XHTTP_FIELDS)
            if (xhttp.has("headers")) xhttp.put("headers", withoutHost(xhttp.optJSONObject("headers")))
            res.put("xhttpSettings", xhttp)
        }
        return res
    }
}
