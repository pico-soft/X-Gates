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

    // Транспорты, которые собираем И которые умеет наше ядро (проверено по бинарю AAR, xray-core v26.x:
    // rawSettings/xhttpSettings/httpupgradeSettings/kcpSettings присутствуют). quic ядром НЕ поддержан (нет пакета).
    //  • raw   — новое имя tcp (нормализуем raw→tcp);
    //  • xhttp — новое имя splithttp (ядро принимает оба имени + xhttpSettings);
    //  • kcp/mkcp — одно и то же (нормализуем kcp→mkcp).
    private val SUPPORTED_NETWORKS = setOf("tcp", "raw", "ws", "grpc", "xhttp", "httpupgrade", "kcp", "mkcp")

    /** Боевой конфиг: общий inbound (socks 10815 + http 10816) + outbound сервера. */
    fun build(p: ServerProfile): String = """
        {
          "log": { "loglevel": "warning" },
          "stats": {},
          "policy": { "system": { "statsOutboundUplink": true, "statsOutboundDownlink": true } },
          ${XrayConfig.inbounds()},
          "outbounds": [
            ${outboundFor(p)},
            { "tag": "direct", "protocol": "freedom" }
          ]
        }
    """.trimIndent()

    /**
     * Конфиг для throughput-теста: ЕДИНСТВЕННЫЙ socks-inbound на переданном ЭФЕМЕРНОМ порту
     * (НЕ 10815/10816 — чтобы temp-инстанс не конфликтовал с активным прокси) + outbound сервера.
     * Поднимается локальным CoreController, качаем пробник через этот socks, затем stopLoop.
     */
    fun buildForSpeedTest(p: ServerProfile, socksPort: Int): String = """
        {
          "log": { "loglevel": "warning" },
          "inbounds": [
            {
              "tag": "socks-in",
              "listen": "127.0.0.1",
              "port": $socksPort,
              "protocol": "socks",
              "settings": { "auth": "noauth", "udp": true }
            }
          ],
          "outbounds": [
            ${outboundFor(p)},
            { "tag": "direct", "protocol": "freedom" }
          ]
        }
    """.trimIndent()

    /** Outbound сервера по протоколу (единый для боевого и speed-test конфигов). */
    private fun outboundFor(p: ServerProfile): String {
        require(p.network in SUPPORTED_NETWORKS) { "unsupported transport: ${p.network}" }
        return when (p.protocol) {
            Protocol.VLESS -> vlessOutbound(p)
            Protocol.TROJAN -> trojanOutbound(p)
            Protocol.VMESS -> vmessOutbound(p)
            Protocol.SHADOWSOCKS -> shadowsocksOutbound(p)
        }
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

    private fun shadowsocksOutbound(p: ServerProfile): String {
        // settings.servers с method+password; транспорт — чистый tcp.
        return """
            {
              "tag": "proxy",
              "protocol": "shadowsocks",
              "settings": {
                "servers": [
                  { "address": ${j(p.address)}, "port": ${p.port}, "method": ${j(p.method ?: "")}, "password": ${j(p.credential)}, "level": 0 }
                ]
              },
              "streamSettings": ${streamSettings(p)}
            }
        """.trimIndent()
    }

    // --- streamSettings (общий для vless/vmess/trojan) ---

    private fun streamSettings(p: ServerProfile): String {
        // Нормализация имён-синонимов транспорта: raw→tcp (raw = переименованный tcp), kcp→mkcp.
        val net = when (p.network) {
            "raw" -> "tcp"
            "kcp" -> "mkcp"
            else -> p.network
        }
        val parts = mutableListOf<String>()
        parts += """"network": ${j(net)}"""
        if (p.security != "none") parts += """"security": ${j(p.security)}"""

        when (p.security) {
            "tls" -> parts += """"tlsSettings": ${tlsBlock(p)}"""
            "reality" -> parts += """"realitySettings": ${realityBlock(p)}"""
        }
        when (net) {
            "ws" -> parts += """"wsSettings": ${wsBlock(p)}"""
            "grpc" -> parts += """"grpcSettings": { "serviceName": ${j(p.serviceName ?: "")}, "multiMode": true }"""
            "tcp" -> if (p.headerType == "http") parts += """"tcpSettings": { "header": { "type": "http" } }"""
            "xhttp" -> parts += """"xhttpSettings": ${xhttpBlock(p)}"""
            "httpupgrade" -> parts += """"httpupgradeSettings": ${httpUpgradeBlock(p)}"""
            "mkcp" -> parts += """"kcpSettings": ${kcpBlock(p)}"""
        }
        return "{ ${parts.joinToString(", ")} }"
    }

    // xhttp (бывш. splithttp): path + host (как ws) + необязательный mode (пусто → ядро выбирает auto).
    private fun xhttpBlock(p: ServerProfile): String {
        val host = p.hostHeader?.takeIf { it.isNotBlank() } ?: p.sni ?: ""
        val fields = mutableListOf(
            """"path": ${j(p.path ?: "/")}""",
            """"host": ${j(host)}""",
        )
        if (!p.mode.isNullOrBlank()) fields += """"mode": ${j(p.mode)}"""
        return "{ ${fields.joinToString(", ")} }"
    }

    // httpupgrade: path + host (та же схема, что ws).
    private fun httpUpgradeBlock(p: ServerProfile): String {
        val host = p.hostHeader?.takeIf { it.isNotBlank() } ?: p.sni ?: ""
        return """{ "path": ${j(p.path ?: "/")}, "host": ${j(host)} }"""
    }

    // mkcp: тип обфускации заголовка (headerType, деф. none) + необязательный seed.
    private fun kcpBlock(p: ServerProfile): String {
        val fields = mutableListOf(
            """"header": { "type": ${j(p.headerType?.takeIf { it.isNotBlank() } ?: "none")} }""",
        )
        if (!p.seed.isNullOrBlank()) fields += """"seed": ${j(p.seed)}"""
        return "{ ${fields.joinToString(", ")} }"
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
