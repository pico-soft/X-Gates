package com.picosoft.xrayproxydroid.xray

import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.Blocklist
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.text.SimpleDateFormat
import java.util.Locale

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
    // Пр.148: живость = свежий ПИНГ (≥0) ИЛИ свежий успешный ЗАМЕР СКОРОСТИ (≤2ч). Раньше только пинг (Пр.139) →
    // быстрый сервер, не ответивший на капризный под DPI пинг, выпадал из «Живых», хотя реально работает (жалоба
    // Elyor: «в Живых 6 медленных, а во Все есть быстрые»). Замер скорости — ДОКАЗАТЕЛЬСТВО прохождения трафика,
    // сильнее пинга. Экономию не ломаем: пинг-живые с speed=null остаются видимы (Пр.139), это лишь ДОБАВЛЯет.
    private val speedTsFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
    private const val SPEED_ALIVE_FRESH_MS = 2 * 3600_000L
    private fun speedFreshAlive(p: ServerProfile): Boolean {
        if ((p.speedMbps ?: 0.0) <= 0.0) return false
        val t = runCatching { speedTsFmt.parse(p.speedTestedTs)?.time }.getOrNull() ?: return false
        return System.currentTimeMillis() - t <= SPEED_ALIVE_FRESH_MS
    }

    // ТЗ Elyor 2026-09-21: СВЕЖИЙ провал пинга (сервер только что пинговали и он не ответил) → он МЁРТВ СЕЙЧАС и
    // должен уйти из «Живых», даже если недавно был замер скорости. Иначе (полевой случай) при обрыве монитор
    // пингует недавних, никто не отвечает, а они всё равно висят в списке (их держал speedFreshAlive ≤2ч). Пр.148
    // НЕ ломаем: он про СТАРЫЙ/неизвестный пинг (DPI-флап) — там lastTestedTs старый → freshPingFailed=false.
    private const val PING_FRESH_FAIL_MS = 15 * 60_000L
    private fun freshPingFailed(p: ServerProfile): Boolean {
        if ((p.pingMs ?: 0) >= 0) return false                    // не мёртвый по пингу
        val t = runCatching { speedTsFmt.parse(p.lastTestedTs)?.time }.getOrNull() ?: return false
        return System.currentTimeMillis() - t <= PING_FRESH_FAIL_MS   // провал СВЕЖИЙ → сервер мёртв сейчас
    }

    @Suppress("UNUSED_PARAMETER")
    fun isVisible(p: ServerProfile, pingMs: Int?, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) &&
            ((pingMs != null && pingMs >= 0) || (speedFreshAlive(p) && !freshPingFailed(p)))

    /** Пригоден для автоподключения/апгрейда: не заблокирован + не на паузе + протокол разрешён + скорость ≥ порога. */
    fun isSelectable(p: ServerProfile, speedMbps: Double?, s: AppSettings, b: Blocklist): Boolean =
        !isBlocked(p, b) && !isPaused(p, b) && protocolAllowed(p, s) && speedMbps != null && speedMbps >= s.minUsableMbps
}
