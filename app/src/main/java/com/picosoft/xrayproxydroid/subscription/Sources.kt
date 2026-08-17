package com.picosoft.xrayproxydroid.subscription

import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import kotlinx.serialization.Serializable

/**
 * Источник серверов (подписка по URL или локальная вставка). ТОЛЬКО метаданные — серверы лежат
 * в общем реестре [ServerRecord] (склеены по serverKey между источниками), не вложены сюда.
 *
 * @param id            стабильный уникальный id (UUID) — членство сервера ссылается на него
 * @param url           адрес подписки; ПУСТОЙ для локальных источников (вставка/файл)
 * @param enabled       выключенный источник не даёт серверов и не обновляется
 * @param lastRefreshTs время последнего обновления ("yyyy-MM-dd HH:mm")
 * @param lastOk        итог последнего обновления: true/false/null (ни разу)
 * @param lastError     текст ошибки последнего обновления (для тапа по красной точке)
 * @param serverCount   сколько серверов принадлежит этому источнику (денормализовано, пересчёт при записи)
 */
@Serializable
data class SubSource(
    val id: String,
    val name: String,
    val url: String = "",
    val enabled: Boolean = true,
    val lastRefreshTs: String? = null,
    val lastOk: Boolean? = null,
    val lastError: String? = null,     // суть ошибки (видна всегда, красным)
    val lastDetail: String? = null,    // диагностика под тапом (URL/код/байты/тело-200/исключение)
    val serverCount: Int = 0,
)

/**
 * Запись сервера в общем реестре. Один serverKey = одна запись, даже если сервер пришёл из
 * нескольких источников. [sourceIds] — СПИСОК источников (не одно поле): при удалении одной
 * подписки сервер, присутствующий и в другой, не исчезает. Измерения (pingMs/speedMbps/ts) живут
 * в [profile] и СОХРАНЯЮТСЯ при склейке/переимпорте, а не обнуляются.
 */
@Serializable
data class ServerRecord(
    val profile: ServerProfile,
    val sourceIds: List<String>,
)

/**
 * Файл источников (`sources.json` в filesDir). Единая точка хранения мультиподписок.
 * @param migratedLegacy true после однократной миграции старого `subscriptions.json`.
 * @param seededDefaultRuBypass true после ОДНОКРАТНОГО посева дефолтной подписки «Обход ограничений в РФ»
 *        (и на свежих, и на существующих установках). Флаг не даёт ей «воскреснуть» после удаления юзером.
 */
@Serializable
data class SourcesFile(
    val migratedLegacy: Boolean = false,
    val seededDefaultRuBypass: Boolean = false,
    val sources: List<SubSource> = emptyList(),
    val servers: List<ServerRecord> = emptyList(),
)
