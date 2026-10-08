package com.picosoft.xrayproxydroid.xray.link

import org.json.JSONArray
import org.json.JSONObject

/**
 * Подписки формата «готовый JSON-конфиг(и) Xray / экспорт v2rayN(G)» — тело не ссылки vless://…, а JSON:
 * либо МАССИВ полных конфигов, либо один конфиг. Полевой случай: платный Furk отдаёт по URL именно такой JSON
 * (массив из ~30 конфигов, у каждого свой `outbounds`→proxy). Наш [ServerLinkParser] читает только ссылки, поэтому
 * из таких подписок импортировалось НОЛЬ серверов (в логе «ссылок не найдено (JSON)»), хотя серверы живые.
 *
 * Достаём из каждого конфига outbound с tag=="proxy" (иначе первый proxy-протокол) и мапим в [ServerProfile]
 * ТЕМИ ЖЕ полями, что [VlessParser]/[VmessParser] (чтобы serverKey и XrayConfigBuilder работали одинаково).
 * Конфиги-плейсхолдеры (address 0.0.0.0 / пусто — напр. запись «Автовыбор») пропускаем.
 */
object JsonConfigParser {

    /** Распарсить тело подписки как JSON-конфиг(и). Пусто — тело не JSON-конфиг (откат к ссылочному парсеру). */
    fun parse(body: String): List<ServerProfile> {
        val t = body.trim()
        if (t.isEmpty() || (t[0] != '[' && t[0] != '{')) return emptyList()   // быстрый отказ для не-JSON
        val configs: List<JSONObject> = try {
            when (t[0]) {
                '[' -> JSONArray(t).let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
                else -> listOf(JSONObject(t))
            }
        } catch (e: Exception) { return emptyList() }
        val out = ArrayList<ServerProfile>()
        for (cfg in configs) profileFromConfig(cfg)?.let { out.add(it) }
        return out
    }

    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks")

    private fun profileFromConfig(cfg: JSONObject): ServerProfile? {
        val outs = cfg.optJSONArray("outbounds") ?: return null
        // Предпочитаем tag=="proxy"; иначе первый outbound с нашим протоколом (не freedom/blackhole).
        var proxy: JSONObject? = null
        for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("tag") == "proxy" && o.optString("protocol") in PROXY_PROTOCOLS) { proxy = o; break }
        }
        if (proxy == null) for (i in 0 until outs.length()) {
            val o = outs.optJSONObject(i) ?: continue
            if (o.optString("protocol") in PROXY_PROTOCOLS) { proxy = o; break }
        }
        val p = proxy ?: return null
        val protocol = when (p.optString("protocol")) {
            "vless" -> Protocol.VLESS; "vmess" -> Protocol.VMESS
            "trojan" -> Protocol.TROJAN; "shadowsocks" -> Protocol.SHADOWSOCKS
            else -> return null
        }
        val settings = p.optJSONObject("settings") ?: return null

        var address = ""; var port = 0; var credential = ""; var method: String? = null; var flow: String? = null
        when (protocol) {
            Protocol.VLESS, Protocol.VMESS -> {
                val v = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val user = v.optJSONArray("users")?.optJSONObject(0) ?: return null
                address = v.optString("address"); port = v.optInt("port")
                credential = user.optString("id")
                method = if (protocol == Protocol.VMESS) user.optString("security", "auto").ifBlank { "auto" }
                         else user.optString("encryption", "none").ifBlank { "none" }
                flow = user.optString("flow").ifBlank { null }
            }
            Protocol.TROJAN, Protocol.SHADOWSOCKS -> {
                val s = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                address = s.optString("address"); port = s.optInt("port")
                credential = s.optString("password")
                method = if (protocol == Protocol.SHADOWSOCKS) s.optString("method").ifBlank { null } else null
            }
        }
        // Плейсхолдер/битый конфиг (напр. запись «Автовыбор» с 0.0.0.0) — пропускаем.
        if (address.isBlank() || address == "0.0.0.0" || port <= 0 || credential.isBlank()) return null

        val st = p.optJSONObject("streamSettings") ?: JSONObject()
        val network = st.optString("network", "tcp").ifBlank { "tcp" }
        val security = st.optString("security", "none").ifBlank { "none" }
        val tls = st.optJSONObject("tlsSettings")
        val reality = st.optJSONObject("realitySettings")
        val sec = reality ?: tls   // reality и tls несут serverName/fingerprint в одном месте
        val sni = sec?.optString("serverName")?.ifBlank { null }
        val fingerprint = sec?.optString("fingerprint")?.ifBlank { null }
        val alpn = tls?.optJSONArray("alpn")?.let { a -> (0 until a.length()).joinToString(",") { a.optString(it) } }?.ifBlank { null }
        val allowInsecure = tls?.optBoolean("allowInsecure", false) ?: false
        val publicKey = reality?.optString("publicKey")?.ifBlank { null }
        val shortId = reality?.optString("shortId")?.ifBlank { null }
        val spiderX = reality?.optString("spiderX")?.ifBlank { null }

        var path: String? = null; var hostHeader: String? = null; var serviceName: String? = null
        var headerType: String? = null; var mode: String? = null; var seed: String? = null
        when (network) {
            "ws" -> st.optJSONObject("wsSettings")?.let { w ->
                path = w.optString("path").ifBlank { null }
                hostHeader = w.optJSONObject("headers")?.optString("Host")?.ifBlank { null } ?: w.optString("host").ifBlank { null }
            }
            "httpupgrade" -> st.optJSONObject("httpupgradeSettings")?.let { h ->
                path = h.optString("path").ifBlank { null }; hostHeader = h.optString("host").ifBlank { null }
            }
            "xhttp", "splithttp" -> (st.optJSONObject("xhttpSettings") ?: st.optJSONObject("splithttpSettings"))?.let { x ->
                path = x.optString("path").ifBlank { null }; hostHeader = x.optString("host").ifBlank { null }
                mode = x.optString("mode").ifBlank { null }
            }
            "grpc" -> st.optJSONObject("grpcSettings")?.let { g -> serviceName = g.optString("serviceName").ifBlank { null } }
            "kcp", "mkcp" -> st.optJSONObject("kcpSettings")?.let { k ->
                headerType = k.optJSONObject("header")?.optString("type")?.ifBlank { null }
                seed = k.optString("seed").ifBlank { null }
            }
            "tcp", "raw" -> (st.optJSONObject("tcpSettings") ?: st.optJSONObject("rawSettings"))?.let { tcp ->
                val hdr = tcp.optJSONObject("header")
                headerType = hdr?.optString("type")?.ifBlank { null }
                hdr?.optJSONObject("request")?.let { req ->
                    path = req.optJSONArray("path")?.optString(0)?.ifBlank { null }
                    hostHeader = req.optJSONObject("headers")?.optJSONArray("Host")?.optString(0)?.ifBlank { null }
                }
            }
        }

        return ServerProfile(
            protocol = protocol,
            remarks = cfg.optString("remarks").ifBlank { address },
            address = address.removeSurrounding("[", "]"),
            port = port,
            credential = credential,
            method = method,
            flow = flow,
            security = security,
            sni = sni,
            fingerprint = fingerprint,
            alpn = alpn,
            allowInsecure = allowInsecure,
            network = network,
            path = path,
            hostHeader = hostHeader,
            serviceName = serviceName,
            headerType = headerType,
            mode = mode,
            seed = seed,
            publicKey = publicKey,
            shortId = shortId,
            spiderX = spiderX,
            raw = "json-config",
        )
    }
}
