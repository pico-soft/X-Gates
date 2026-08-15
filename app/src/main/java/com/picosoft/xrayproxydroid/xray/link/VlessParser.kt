package com.picosoft.xrayproxydroid.xray.link

import java.net.URI

/**
 * vless://UUID@host:port?encryption=none&security=tls&type=ws&path=..&host=..&sni=..&fp=..&flow=..#remark
 *
 * Эталон полей — Python parse_vless (xproxy_lib.py) + v2rayNG VlessFmt/FmtBase.getItemFormQuery.
 * Обязательны: UUID (user-info), host, port. Остальное — query с дефолтами.
 */
object VlessParser : LinkParser {
    override fun parse(uri: String): ServerProfile? {
        val u = try { URI(uri) } catch (e: Exception) { return null }
        val host = u.host?.takeIf { it.isNotBlank() } ?: return null
        val uuid = u.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val port = if (u.port > 0) u.port else 443
        val q = LinkUtil.queryParams(u.rawQuery)

        return ServerProfile(
            protocol = Protocol.VLESS,
            remarks = u.fragment ?: "",                 // URI уже percent-decode'ит фрагмент (UTF-8)
            address = host.removeSurrounding("[", "]"), // IPv6 без скобок
            port = port,
            credential = uuid,                          // UUID
            method = q["encryption"] ?: "none",
            flow = q["flow"],
            security = q["security"] ?: "none",
            sni = q["sni"],
            fingerprint = q["fp"],
            alpn = q["alpn"],
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
            network = q["type"] ?: "tcp",
            path = q["path"],
            hostHeader = q["host"],
            serviceName = q["serviceName"],
            headerType = q["headerType"],
            publicKey = q["pbk"],
            shortId = q["sid"],
            spiderX = q["spx"],
            raw = uri,
        )
    }
}
