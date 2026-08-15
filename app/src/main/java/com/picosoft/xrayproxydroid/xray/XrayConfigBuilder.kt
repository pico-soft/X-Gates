package com.picosoft.xrayproxydroid.xray

import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

/**
 * ServerProfile → configJson (строка для XrayController.startLoop).
 *
 * Переиспользует общий inbound из [XrayConfig] (socks 10815 + http 10816) и добавляет
 * outbound по протоколу + streamSettings. Второй outbound freedom(direct) — как в Python-эталоне.
 *
 * Пока реализована только ветка VLESS; trojan/vmess/ss добавим по одному следующими этапами.
 */
object XrayConfigBuilder {

    /** Транспорты, которые умеем собирать сейчас (xhttp/kcp/quic — отложены). */
    private val SUPPORTED_NETWORKS = setOf("tcp", "ws", "grpc")

    fun build(p: ServerProfile): String {
        require(p.network in SUPPORTED_NETWORKS) { "unsupported transport: ${p.network}" }

        val outbound = when (p.protocol) {
            Protocol.VLESS -> vlessOutbound(p)
            Protocol.TROJAN -> trojanOutbound(p)
            Protocol.VMESS -> vmessOutbound(p)
            else -> throw IllegalArgumentException("protocol not implemented yet: ${p.protocol}")
        }

        return """
            {
              "log": { "loglevel": "warning" },
              ${XrayConfig.inbounds()},
              "outbounds": [
                $outbound,
                { "tag": "direct", "protocol": "freedom" }
              ]
            }
        """.trimIndent()
    }

    // --- outbound по протоколу (единый vnext/users для vless+vmess) ---

    private fun vlessOutbound(p: ServerProfile): String {
        val flowLine = if (!p.flow.isNullOrBlank()) """, "flow": ${j(p.flow)}""" else ""
        return """
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "vnext": [
                  {
                    "address": ${j(p.address)},
                    "port": ${p.port},
                    "users": [
                      { "id": ${j(p.credential)}, "encryption": ${j(p.method ?: "none")}$flowLine, "level": 0 }
                    ]
                  }
                ]
              },
              "streamSettings": ${streamSettings(p)}
            }
        """.trimIndent()
    }

    private fun trojanOutbound(p: ServerProfile): String {
        return """
            {
              "tag": "proxy",
              "protocol": "trojan",
              "settings": {
                "servers": [
                  { "address": ${j(p.address)}, "port": ${p.port}, "password": ${j(p.credential)}, "level": 0 }
                ]
              },
              "streamSettings": ${streamSettings(p)}
            }
        """.trimIndent()
    }

    private fun vmessOutbound(p: ServerProfile): String {
        // Единый vnext/users (как vless), но с alterId:0 (AEAD) и security = шифр (scy).
        return """
            {
              "tag": "proxy",
              "protocol": "vmess",
              "settings": {
                "vnext": [
                  {
                    "address": ${j(p.address)},
                    "port": ${p.port},
                    "users": [
                      { "id": ${j(p.credential)}, "alterId": 0, "security": ${j(p.method ?: "auto")}, "level": 0 }
                    ]
                  }
                ]
              },
              "streamSettings": ${streamSettings(p)}
            }
        """.trimIndent()
    }

    // --- streamSettings (общий для vless/vmess/trojan) ---

    private fun streamSettings(p: ServerProfile): String {
        val parts = mutableListOf<String>()
        parts += """"network": ${j(p.network)}"""
        if (p.security != "none") parts += """"security": ${j(p.security)}"""

        when (p.security) {
            "tls" -> parts += """"tlsSettings": ${tlsBlock(p)}"""
            "reality" -> parts += """"realitySettings": ${realityBlock(p)}"""
        }
        when (p.network) {
            "ws" -> parts += """"wsSettings": ${wsBlock(p)}"""
            "grpc" -> parts += """"grpcSettings": { "serviceName": ${j(p.serviceName ?: "")}, "multiMode": true }"""
            "tcp" -> if (p.headerType == "http") parts += """"tcpSettings": { "header": { "type": "http" } }"""
        }
        return "{ ${parts.joinToString(", ")} }"
    }

    private fun tlsBlock(p: ServerProfile): String {
        val fields = mutableListOf(
            """"allowInsecure": ${p.allowInsecure}""",
            """"serverName": ${j(p.sni ?: p.address)}""",
        )
        if (!p.fingerprint.isNullOrBlank()) fields += """"fingerprint": ${j(p.fingerprint)}"""
        if (!p.alpn.isNullOrBlank()) {
            val alpnArr = p.alpn.split(",").joinToString(", ") { j(it.trim()) }
            fields += """"alpn": [$alpnArr]"""
        }
        return "{ ${fields.joinToString(", ")} }"
    }

    private fun realityBlock(p: ServerProfile): String {
        val fields = mutableListOf(
            """"serverName": ${j(p.sni ?: "")}""",
            """"fingerprint": ${j(p.fingerprint ?: "chrome")}""",
            """"publicKey": ${j(p.publicKey ?: "")}""",
            """"shortId": ${j(p.shortId ?: "")}""",
        )
        if (!p.spiderX.isNullOrBlank()) fields += """"spiderX": ${j(p.spiderX)}"""
        return "{ ${fields.joinToString(", ")} }"
    }

    private fun wsBlock(p: ServerProfile): String {
        // ws Host: явный host из ссылки, иначе sni (как Python generate_config).
        val host = p.hostHeader?.takeIf { it.isNotBlank() } ?: p.sni ?: ""
        return """{ "path": ${j(p.path ?: "/")}, "host": ${j(host)} }"""
    }

    /** JSON-строковой литерал с экранированием — защита от кавычек/бэкслэшей в значениях ссылки. */
    private fun j(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '\\' -> sb.append("\\\\")
            '"' -> sb.append("\\\"")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            else -> sb.append(c)
        }
        return sb.append("\"").toString()
    }
}
