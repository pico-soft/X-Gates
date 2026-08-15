package com.picosoft.xrayproxydroid.xray

/**
 * Порты и общая inbound-часть конфига xray-core.
 * Порты — константы, по коду никаких магических значений.
 */
object XrayConfig {

    /** Локальный SOCKS-inbound. */
    const val SOCKS_PORT = 10815

    /** Локальный HTTP-inbound. */
    const val HTTP_PORT = 10816

    /** Слушаем только петлю — модель локального прокси, без VpnService. */
    const val LISTEN = "127.0.0.1"

    /**
     * Общая inbound-часть: socks на SOCKS_PORT + http на HTTP_PORT, оба на 127.0.0.1.
     * Переиспользуется билдером ([XrayConfigBuilder]) для любого сервера.
     */
    internal fun inbounds(): String = """
        "inbounds": [
          {
            "tag": "socks-in",
            "listen": "$LISTEN",
            "port": $SOCKS_PORT,
            "protocol": "socks",
            "settings": { "auth": "noauth", "udp": true }
          },
          {
            "tag": "http-in",
            "listen": "$LISTEN",
            "port": $HTTP_PORT,
            "protocol": "http",
            "settings": {}
          }
        ]
    """.trimIndent()
}
