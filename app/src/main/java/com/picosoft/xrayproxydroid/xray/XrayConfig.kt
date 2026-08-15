package com.picosoft.xrayproxydroid.xray

/**
 * Порты и минимальный тестовый конфиг xray-core.
 *
 * Все порты — константы, по коду никаких магических чисел: и конфиг, и любые
 * будущие проверки портов ссылаются только сюда.
 */
object XrayConfig {

    /** Локальный SOCKS-inbound. */
    const val SOCKS_PORT = 10815

    /** Локальный HTTP-inbound. */
    const val HTTP_PORT = 10816

    /** Слушаем только петлю — модель локального прокси, без VpnService. */
    const val LISTEN = "127.0.0.1"

    /**
     * Минимальный конфиг для проверки «xray стартует из APK и держит порты»:
     * два локальных inbound (socks + http) и один freedom-outbound.
     * Реальный сервер не нужен — трафик уходит напрямую (freedom).
     */
    fun testConfigJson(): String = """
        {
          "log": { "loglevel": "warning" },
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
          ],
          "outbounds": [
            { "tag": "direct", "protocol": "freedom" }
          ]
        }
    """.trimIndent()
}
