package com.picosoft.xrayproxydroid.subscription

import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import kotlinx.serialization.Serializable

/**
 * Исход последней ПОПЫТКИ обновления источника (Промпт 81.A/C). Не «ok: Boolean» — 429 и отложенность
 * это НЕ поломка, и красным их показывать неверно: панель жива, просто ограничивает частоту.
 */
@Serializable
enum class SourceOutcome {
    OK,            // успешно загружено
    ERROR,         // реальная неудача (сеть/парсинг/40x-кроме-429)
    RATE_LIMITED,  // HTTP 429 — панель ограничивает частоту, не поломка; смена маршрута не помогает
}

/**
 * Источник серверов (подписка по URL или локальная вставка). ТОЛЬКО метаданные — серверы лежат
 * в общем реестре [ServerRecord] (склеены по serverKey между источниками), не вложены сюда.
 *
 * @param id            стабильный уникальный id (UUID) — членство сервера ссылается на него
 * @param url           адрес подписки; ПУСТОЙ для локальных источников (вставка/файл)
 * @param enabled       выключенный источник не даёт серверов и не обновляется
 * @param lastRefreshTs время ПОСЛЕДНЕЙ ПОПЫТКИ обновления ("yyyy-MM-dd HH:mm")
 * @param lastOkTs      время последней УСПЕШНОЙ загрузки (Промпт 81.C — отдельно от попытки)
 * @param lastOk        итог последней попытки: true/false/null (ни разу) — оставлен для совместимости
 * @param lastOutcome   типизированный исход последней попытки (Промпт 81) — для цвета точки и текста
 * @param lastError     текст ошибки последней НЕУДАЧНОЙ попытки; ОЧИЩАЕТСЯ при первой успешной (81.C)
 * @param retryAfterSec для RATE_LIMITED — через сколько секунд можно повторить (заголовок Retry-After)
 * @param serverCount   сколько серверов принадлежит этому источнику (денормализовано, пересчёт при записи)
 */
@Serializable
data class SubSource(
    val id: String,
    val name: String,
    val url: String = "",
    val enabled: Boolean = true,
    val lastRefreshTs: String? = null,
    val lastOkTs: String? = null,
    val lastOk: Boolean? = null,
    val lastOutcome: SourceOutcome? = null,
    val lastError: String? = null,     // суть ошибки последней НЕУДАЧИ (очищается при успехе)
    val lastDetail: String? = null,    // диагностика под тапом (URL/код/байты/тело-200/исключение)
    val retryAfterSec: Int? = null,    // для RATE_LIMITED (сколько ждать)
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
    // Промпт 85: СЫРОЙ ответ каждого источника (sourceId → тело). Позволяет ПЕРЕСОБРАТЬ реестр целиком из
    // оставшихся источников при удалении/выключении/включении/обновлении — вместо вычитания записей на месте
    // (вычитание оставляло осиротевшие профили: запись числится за источником, но данные потеряны). Реестр
    // [servers] — производный КЭШ (несёт измерения); источник истины для пересборки — эти тела.
    val rawBodies: Map<String, String> = emptyMap(),
    // Пр.136: результат ПОСЛЕДНЕЙ попытки посева дефолтной подписки на пустом списке. Нужен, чтобы показать
    // человеку «рекомендуемый список не загрузился, причина …» вместо пустого экрана. seedLastError пуст =
    // либо ещё не пробовали, либо успех (тогда список уже не пуст). Флаг УСПЕХА — seededDefaultRuBypass выше.
    val seedLastError: String = "",
    val seedLastAttemptTs: String = "",
)
