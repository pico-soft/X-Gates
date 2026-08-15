package com.picosoft.xrayproxydroid.xray

/**
 * Порты, тестовый сервер и конфиги xray-core.
 *
 * Все порты и параметры сервера — константы, по коду никаких магических значений:
 * конфиги и проверки портов ссылаются только сюда.
 */
object XrayConfig {

    /** Локальный SOCKS-inbound. */
    const val SOCKS_PORT = 10815

    /** Локальный HTTP-inbound. */
    const val HTTP_PORT = 10816

    /** Слушаем только петлю — модель локального прокси, без VpnService. */
    const val LISTEN = "127.0.0.1"

    /** Тестовая vless-ссылка для проверки парсера end-to-end (тот же сервер, что в Этапе 2). */
    const val TEST_VLESS_LINK =
        "vless://13a0205c-107a-4c7a-954e-2b5fcb235449@polniybak.info:443" +
            "?encryption=none&security=tls&sni=polniybak.info&fp=firefox" +
            "&type=ws&path=%2Fnotvlessklyanus#polniybak-test"

    /** Тестовая trojan-ссылка для проверки TrojanParser end-to-end. */
    const val TEST_TROJAN_LINK =
        "trojan://XZixM2ikOov3k4fSfAEF4Z6d4@hl-freedom-0.undef.network:443" +
            "?sni=HL-FREEDOM-0.UNDEF.NETWORK&host=hl-freedom-0.undef.network" +
            "&security=tls&type=ws&path=%2Ff2fc2a1f#HLVPN_08-15EU_FREE"

    // --- Захардкоженный тестовый VLESS-сервер (парсер vless:// будет отдельным этапом) ---
    // vless://…@polniybak.info:443?encryption=none&security=tls&sni=polniybak.info
    //          &fp=firefox&type=ws&path=%2Fnotvlessklyanus
    private object Vless {
        const val ADDRESS = "polniybak.info"
        const val PORT = 443
        const val ID = "13a0205c-107a-4c7a-954e-2b5fcb235449"
        const val ENCRYPTION = "none"
        const val SNI = "polniybak.info"
        const val FINGERPRINT = "firefox"
        const val WS_PATH = "/notvlessklyanus"
        const val WS_HOST = "polniybak.info"
        const val ALLOW_INSECURE = false
    }

    /**
     * Общая inbound-часть: socks на SOCKS_PORT + http на HTTP_PORT, оба на 127.0.0.1.
     * Идентична для обоих режимов, чтобы сравнивать только outbound.
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

    /**
     * Режим freedom: прямой выход, трафик НЕ проксируется. Эталон для сравнения —
     * внешний IP должен остаться оператора.
     */
    fun freedomConfigJson(): String = """
        {
          "log": { "loglevel": "warning" },
          ${inbounds()},
          "outbounds": [
            { "tag": "direct", "protocol": "freedom" }
          ]
        }
    """.trimIndent()

    /**
     * Режим vless: трафик идёт через тестовый сервер (VLESS over WS over TLS).
     * Плоский формат settings — сверен с парсером xray-core из нашего AAR.
     * Внешний IP через прокси должен стать IP сервера.
     */
    fun vlessConfigJson(): String = """
        {
          "log": { "loglevel": "warning" },
          ${inbounds()},
          "outbounds": [
            {
              "tag": "proxy",
              "protocol": "vless",
              "settings": {
                "address": "${Vless.ADDRESS}",
                "port": ${Vless.PORT},
                "id": "${Vless.ID}",
                "encryption": "${Vless.ENCRYPTION}"
              },
              "streamSettings": {
                "network": "ws",
                "security": "tls",
                "tlsSettings": {
                  "allowInsecure": ${Vless.ALLOW_INSECURE},
                  "serverName": "${Vless.SNI}",
                  "fingerprint": "${Vless.FINGERPRINT}"
                },
                "wsSettings": {
                  "path": "${Vless.WS_PATH}",
                  "host": "${Vless.WS_HOST}"
                }
              }
            }
          ]
        }
    """.trimIndent()
}
