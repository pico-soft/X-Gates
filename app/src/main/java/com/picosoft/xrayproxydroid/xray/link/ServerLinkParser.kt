package com.picosoft.xrayproxydroid.xray.link

/**
 * Диспетчер: строка → [ParseResult]. Определяет протокол по scheme-префиксу.
 *
 * SUPPORTED — исполняются нашим xray-core. KNOWN_UNSUPPORTED — распознаём и помечаем
 * (ждут sing-box), но не падаем. Всё прочее → Invalid.
 */
object ServerLinkParser {

    private val SUPPORTED: Map<String, LinkParser> = mapOf(
        "vless://" to VlessParser,
        "trojan://" to TrojanParser,
        "vmess://" to VmessParser,
        "ss://" to ShadowsocksParser,
    )

    private val KNOWN_UNSUPPORTED = setOf(
        "hysteria2://", "hy2://", "tuic://", "wireguard://", "socks://",
    )

    fun parse(line: String): ParseResult {
        val s = line.trim()

        SUPPORTED.entries.firstOrNull { s.startsWith(it.key, ignoreCase = true) }?.let { (scheme, parser) ->
            val profile = parser.parse(s) ?: return ParseResult.Invalid("failed to parse $scheme", s)
            return ParseResult.Supported(profile)
        }

        KNOWN_UNSUPPORTED.firstOrNull { s.startsWith(it, ignoreCase = true) }?.let { scheme ->
            return ParseResult.Unsupported(scheme.removeSuffix("://"), s)
        }

        return ParseResult.Invalid("unknown scheme", s)
    }
}
