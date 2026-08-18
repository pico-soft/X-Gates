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
        return b.isBlocked(p.remarks.ifBlank { p.address }, b.customName(key), key)
    }

    /** ПЯТЫЙ отсев — «на паузе» (Промпт 91): отдельная причина от стоп-листа. Скрыт из «Живых» и автовыбора. */
    fun isPaused(p: ServerProfile, b: Blocklist): Boolean = b.isPaused(SubscriptionManager.serverKey(p))

    /**
     * Виден в «Живые» = РЕАЛЬНО доступен СЕЙЧАС: не заблокирован + не на паузе + протокол разрешён + ЖИВОЙ ПИНГ
     * (pingMs>=0) + скорость пригодна ИЛИ ещё не мерена. Пинг — свежий сигнал доступности: если подписка не
     * пингуется (серверы недоступны), они НЕ «Живые», даже если в кэше остались старые хорошие скорости, и
     * провал скорости (-1) тоже прячет (✗ не в «Живых»). ИСКЛЮЧЕНИЕ — АКТИВНЫЙ подключённый сервер: его живость
     * доказана самим туннелем (см. `alive` в MainActivity: активный добавляется отдельно, минуя этот предикат).
     */
    fun isVisible(p: ServerProfile, pingMs: Int?, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && pingMs != null && pingMs >= 0 &&
            (speedMbps == null || speedMbps >= s.minUsableMbps)

    /** Пригоден для автоподключения/апгрейда: не заблокирован + не на паузе + протокол разрешён + скорость ≥ порога. */
    fun isSelectable(p: ServerProfile, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && speedMbps != null && speedMbps >= s.minUsableMbps
}
