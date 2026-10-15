package com.picosoft.xrayproxydroid.xray

import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.Blocklist
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

/**
 * ЕДИНАЯ точка всех отсевов сервера: живость (пинг), минимальная скорость, протокол и стоп-лист.
 * Через неё обязаны идти список «Живые», секция «Все», счётчики N, автоподключение и апгрейд —
 * ни одного условия «по месту».
 *
 * РАЗНИЦА двух отсевов настроек:
 *  - протокол выключают ВРЕМЕННО → числа должны быть готовы к обратному включению → замер идёт по всем;
 *  - блокировка = «не нужен вовсе» → заблокированных НЕ мерят (см. FullTestRunner, фильтр ДО ping/speed),
 *    иначе тратили бы трафик и время прогона впустую.
 */
object ServerFilter {

    /** Протокол разрешён настройками. */
    fun protocolAllowed(p: ServerProfile, s: AppSettings): Boolean = p.protocol in s.allowedProtocols

    /**
     * ЧЕТВЁРТЫЙ отсев — стоп-лист: заблокирован по слову в ИМЕНИ или точечно по serverKey.
     * Слово ищем И в исходном имени провайдера, И в пользовательском (D2): иначе переименование
     * молча снимало бы блокировку.
     */
    fun isBlocked(p: ServerProfile, b: Blocklist): Boolean {
        val key = SubscriptionManager.serverKey(p)
        // Пр.147: временно сняты стоп-СЛОВА → блокировка только по точечному serverKey (слова игнорируем).
        if (com.picosoft.xrayproxydroid.settings.BlocklistStore.stopWordsSuspended.value) return b.isServerBlocked(key)
        return b.isBlocked(p.remarks.ifBlank { p.address }, b.customName(key), key)
    }

    /** ПЯТЫЙ отсев — «на паузе» (Промпт 91): отдельная причина от стоп-листа. Скрыт из «Живых» и автовыбора. */
    fun isPaused(p: ServerProfile, b: Blocklist): Boolean = b.isPaused(SubscriptionManager.serverKey(p))

    /**
     * Виден в «Живые» = РЕАЛЬНО доступен СЕЙЧАС: не заблокирован + не на паузе + протокол разрешён + ЖИВОЙ ПИНГ
     * (pingMs>=0). ТЗ Elyor 2026-10-14: ПИНГ ОБЯЗАТЕЛЕН — сервер без живого пинга в «Живых» НЕ показываем. Прежний
     * эскейп «жив по свежей скорости ≤2ч даже без пинга» (Пр.148) УБРАН: он давал «призраков» с высокой скоростью,
     * но ПУСТЫМ пингом, которые всплывали в списке и путали («сверху быстрые без пинга, ниже медленные с пингом»).
     * Не пингуется → уходит из «Живых» (фоновый пере-пинг раз в 15 мин ставит pingMs=-1). Топ строим по РЕАЛЬНОЙ
     * замеренной скорости (сортировка в MainActivity), а живость гарантирует пинг.
     * ИСКЛЮЧЕНИЕ — АКТИВНЫЙ подключённый сервер: его живость доказана туннелем (в MainActivity добавляется отдельно).
     */
    @Suppress("UNUSED_PARAMETER")
    fun isVisible(p: ServerProfile, pingMs: Int?, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && (pingMs != null && pingMs >= 0)

    /** Пригоден для автоподключения/апгрейда: не заблокирован + не на паузе + протокол разрешён + скорость ≥ порога. */
    fun isSelectable(p: ServerProfile, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && speedMbps != null && speedMbps >= s.minUsableMbps

    // ═══ ДВА ВИДИМЫХ СПИСКА (ТЗ Elyor 2026-10-15) ═══════════════════════════════════════════════════════════
    // Формат метки времени замера (ServerProfile.speedTestedTs). Locale.US — формат фиксированный, не локалезависим.
    private val speedTsParser = ThreadLocal.withInitial { java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US) }

    /** Момент последнего замера скорости в эпоха-мс из persisted-метки; null — не тестировался/метки нет. */
    fun speedMeasuredAtMs(p: ServerProfile): Long? =
        p.speedTestedTs?.takeIf { it.isNotBlank() }?.let { runCatching { speedTsParser.get()!!.parse(it)?.time }.getOrNull() }

    /** Замер считается СВЕЖИМ (список «Живые»), если он сделан не старее окна [freshSec]. null-метка = не свежий. */
    fun isSpeedFresh(atMs: Long?, nowMs: Long, freshSec: Int): Boolean =
        atMs != null && atMs <= nowMs && nowMs - atMs <= freshSec.coerceAtLeast(60) * 1000L

    /** Классификация сервера для видимых списков. */
    enum class Tier { LIVE, PREVIOUS, NONE }

    /**
     * Куда сервер идёт на экране (ТЗ Elyor 2026-10-15 «после полного замера перестраивай видимый топ»):
     *  LIVE     — «Живые серверы»: доступен (не блок/пауза/протокол ок) + ЖИВОЙ ПИНГ (≥0) + измеренная СКОРОСТЬ>0 +
     *             замер СВЕЖИЙ (не старше окна свежести). Это «отозвался и померился».
     *  PREVIOUS — «Ранее измеренные»: то же, но замер СТАРЫЙ (мерили раньше, пинг ещё жив, не переподтвердили).
     *  NONE     — ни в одном списке (только во «Все»): нет живого пинга ЛИБО пинг есть, но скорость не измерена/0
     *             («при последнем замере не показали пинг / показали пинг, но не отдали скорость» — Elyor).
     * [speedFreshAtMs] — момент последнего замера в мс (сессионный или из persisted-метки); null → трактуем как старый.
     * АКТИВНЫЙ подключённый сервер — исключение (его живость доказана туннелем), добавляется в «Живые» вызывающим.
     */
    fun tier(
        p: ServerProfile, pingMs: Int?, speedMbps: Double?, speedFreshAtMs: Long?, nowMs: Long,
        s: AppSettings, b: Blocklist,
    ): Tier {
        if (isBlocked(p, b) || isPaused(p, b) || !protocolAllowed(p, s)) return Tier.NONE
        if (pingMs == null || pingMs < 0) return Tier.NONE                 // нет пинга → только «Все»
        if ((speedMbps ?: 0.0) <= 0.0) return Tier.NONE                    // пинг-без-скорости → только «Все»
        return if (isSpeedFresh(speedFreshAtMs, nowMs, s.liveFreshSec)) Tier.LIVE else Tier.PREVIOUS
    }
}
