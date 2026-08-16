package com.picosoft.xrayproxydroid.xray.link

/**
 * Типизированный результат разбора ссылки. «Не падать на неизвестном протоколе» —
 * это про то, что ошибка/неподдержка возвращаются значением, а не исключением.
 */
sealed interface ParseResult {
    /** Разобрали в один из наших четырёх протоколов. */
    data class Supported(val profile: ServerProfile) : ParseResult

    /** Схема распознана, но исполняется не нашим ядром (hysteria2/tuic/…), ждёт sing-box. */
    data class Unsupported(val scheme: String, val raw: String) : ParseResult

    /** Схема наша, но строка битая; либо схема вообще неизвестна. */
    data class Invalid(val reason: String, val raw: String) : ParseResult
}
