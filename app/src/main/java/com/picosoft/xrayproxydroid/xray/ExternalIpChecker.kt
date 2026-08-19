package com.picosoft.xrayproxydroid.xray

import android.util.Log
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * Внешний IP ЧЕРЕЗ активный SOCKS-туннель (127.0.0.1:SOCKS_PORT).
 * Индикатор РЕАЛЬНОЙ живости туннеля: галочки портов говорят лишь что локальный сокет слушает,
 * а полученный IP — что трафик реально ходит наружу через сервер.
 *
 * Цепочка фолбэков (каждый возвращает голый IP текстом): ipify → ifconfig.me → ipinfo.io.
 * Таймаут 5с на каждый. Возвращает IP или null (нет ответа).
 */
object ExternalIpChecker {

    private const val TAG = "ExternalIpChecker"
    private const val TIMEOUT_MS = 5_000

    private val endpoints = listOf(
        "https://api.ipify.org",
        "https://ifconfig.me/ip",
        "https://ipinfo.io/ip",
    )

    /** Блокирующий вызов — запускать на фоновом потоке. */
    fun fetch(socksPort: Int = XrayConfig.SOCKS_PORT): String? {
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", socksPort))
        for (url in endpoints) {
            try {
                val conn = (URL(url).openConnection(proxy) as HttpURLConnection).apply {
                    connectTimeout = TIMEOUT_MS
                    readTimeout = TIMEOUT_MS
                    setRequestProperty("User-Agent", "curl/8.0")
                }
                val code = conn.responseCode
                if (code in 200..299) {
                    val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    conn.disconnect()
                    if (ip.isNotEmpty() && ip.length <= 45) {   // IPv4/IPv6 разумной длины
                        Log.i(TAG, "IP через туннель = $ip (via $url)")
                        return ip
                    }
                } else {
                    conn.disconnect()
                    Log.i(TAG, "$url → http=$code")
                }
            } catch (e: Exception) {
                Log.i(TAG, "$url FAIL ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }
}
