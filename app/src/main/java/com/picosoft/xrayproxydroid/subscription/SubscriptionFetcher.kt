package com.picosoft.xrayproxydroid.subscription

import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивание тела подписки. HttpURLConnection (встроен, minSdk 24, ноль зависимостей),
 * прямое скачивание — БЕЗ каскада (через прокси/серверы) пока.
 *
 * ВНИМАНИЕ: [fetch] синхронный — вызывать в ФОНОВОМ потоке.
 * cleartext http:// на Android 9+ по умолчанию блокируется → вернётся Result.failure
 * (осознанно: обычный http пока не поддерживаем, флажок на будущее).
 */
object SubscriptionFetcher {

    private const val USER_AGENT = "XrayProxyDroid/1.0"
    private const val TIMEOUT_MS = 15_000

    /** GET по URL. Возвращает тело (UTF-8) или Result.failure с причиной. Блокирующий вызов. */
    fun fetch(url: String): Result<String> = runCatching {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) error("HTTP $code")
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
