package com.picosoft.xrayproxydroid.xray.link

import java.net.URI

/**
 * trojan://password@host:port?security=tls&type=ws&path=..&host=..&sni=..&fp=..#remark
 *
 * Эталон — Python parse_trojan (xproxy_lib.py) + v2rayNG TrojanFmt.
 * Отличия от vless: credential = password (не UUID); security по умолчанию = tls (не none);
 * нет encryption/flow; outbound через settings.servers (см. XrayConfigBuilder).
 */
object TrojanParser : LinkParser {
    override fun parse(uri: String): ServerProfile? {
        val u = try { URI(uri) } catch (e: Exception) { return null }
        val host = u.host?.takeIf { it.isNotBlank() } ?: return null
        val password = u.userInfo?.takeIf { it.isNotBlank() } ?: return null
        val port = if (u.port > 0) u.port else 443
        val q = LinkUtil.queryParams(u.rawQuery)

        return ServerProfile(
            protocol = Protocol.TROJAN,
            remarks = u.fragment ?: "",
            address = host.removeSurrounding("[", "]"),
            port = port,
            credential = password,                      // пароль, НЕ UUID
            method = null,                              // trojan не использует
            flow = null,
            security = q["security"] ?: "tls",          // ← по умолчанию tls, не none
            sni = q["sni"],
            fingerprint = q["fp"],
            alpn = q["alpn"],
            allowInsecure = q["allowInsecure"] == "1" || q["insecure"] == "1",
            network = q["type"] ?: "tcp",
            path = q["path"],
            hostHeader = q["host"],
            serviceName = q["serviceName"],
            headerType = q["headerType"],
            mode = q["mode"],
            seed = q["seed"],
            publicKey = q["pbk"],
            shortId = q["sid"],
            spiderX = q["spx"],
            raw = uri,
        )
    }
}
