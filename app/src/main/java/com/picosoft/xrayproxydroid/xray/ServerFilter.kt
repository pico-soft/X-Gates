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
     * (pingMs>=0). ЖИВОСТЬ ОПРЕДЕЛЯЕТ ТОЛЬКО ПИНГ (Пр.139) — одинаково во всех режимах. Скорость сюда НЕ входит:
     *  • режим экономии мерит лишь пару серверов → раньше остальные (speed=null/провал) молча выпадали из «Живых»,
     *    хотя пинговались — это и был баг «в экономии живых 0, выключил — появились»;
     *  • провал/низкая скорость — сигнал для ВЫБОРА (isSelectable), а НЕ для «жив ли сервер». Пинг жив ⇒ в списке.
     * Скорость в строке всё равно видна (ячейка: «не изм.»/«✗»/число/«хватает»). [speedMbps] оставлен в сигнатуре
     * для совместимости вызова и возможной диагностики — на видимость НЕ влияет.
     * ИСКЛЮЧЕНИЕ — АКТИВНЫЙ подключённый сервер: его живость доказана туннелем (в MainActivity добавляется отдельно).
     */
    @Suppress("UNUSED_PARAMETER")
    fun isVisible(p: ServerProfile, pingMs: Int?, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && pingMs != null && pingMs >= 0

    /** Пригоден для автоподключения/апгрейда: не заблокирован + не на паузе + протокол разрешён + скорость ≥ порога. */
    fun isSelectable(p: ServerProfile, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && speedMbps != null && speedMbps >= s.minUsableMbps
}
