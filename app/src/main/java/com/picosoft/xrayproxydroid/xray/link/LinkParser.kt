package com.picosoft.xrayproxydroid.xray.link

import java.net.URLDecoder

/** Контракт парсера одной схемы: строка ссылки → нормализованный профиль (или null при ошибке разбора). */
interface LinkParser {
    fun parse(uri: String): ServerProfile?
}

/** Общие помощники разбора (query, url-decode) — переиспользуются всеми парсерами. */
internal object LinkUtil {

    /** rawQuery ("a=1&b=%2Fx") → map; значения percent-decoded в UTF-8. */
    fun queryParams(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrEmpty()) return emptyMap()
        return rawQuery.split("&").mapNotNull { pair ->
            val i = pair.indexOf('=')
            if (i <= 0) null else pair.substring(0, i) to urlDecode(pair.substring(i + 1))
        }.toMap()
    }

    fun urlDecode(s: String): String =
        try { URLDecoder.decode(s, "UTF-8") } catch (e: Exception) { s }
}
