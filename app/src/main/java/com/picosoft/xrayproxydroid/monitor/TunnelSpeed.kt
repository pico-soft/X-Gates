package com.picosoft.xrayproxydroid.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ЖИВАЯ скорость интернета для цветной плашки (НЕ «скорость, с которой соединился при подборе»). Две группы:
 *  • НАПРЯМУЮ — скорость прямого канала (без туннеля), только скачивание ↓.
 *  • В ТУННЕЛЕ — скорость через активный прокси: скачивание ↓ и отдача ↑.
 *
 * ГИБРИД (решение Elyor): когда идёт реальный трафик пользователя — ТУННЕЛЬНУЮ скорость берём БЕСПЛАТНО из
 * счётчиков ядра (дельта туннельных байт) → source=LIVE (прямую при этом сохраняем от последней пробы). Когда
 * простой — раз в несколько минут одна активная проба меряет ВСЁ: прямой канал + туннель ↓/↑ → source=PROBE.
 * Заполняется из [NetworkMonitor]. UI берёт ТОЛЬКО отсюда + время замера.
 *
 * Единицы — Мбит/с ВЕЗДЕ (без смешения КБ/МБ). measuredAtMs — wall-clock (System.currentTimeMillis) для «N мин назад».
 */
object TunnelSpeed {
    enum class Source {
        NONE,   // ещё не замеряли (только подключились)
        LIVE,   // туннель из реального трафика пользователя — бесплатно
        PROBE,  // активная проба в простое — стоит трафика
    }

    data class Speed(
        val directDownMbps: Double? = null,   // НАПРЯМУЮ ↓ (прямой канал, без туннеля); null = не замерено
        val tunnelDownMbps: Double? = null,   // В ТУННЕЛЕ ↓; null = не замерено
        val tunnelUpMbps: Double? = null,     // В ТУННЕЛЕ ↑; null = не замерено
        val measuredAtMs: Long = 0L,          // wall-clock последнего замера (0 = не было)
        val source: Source = Source.NONE,
        // Промпт 123.B: serverKey сервера, чья это скорость. Сменился активный — числа НЕ его, UI их не показывает.
        val serverKey: String = "",
        // ЖИВОЙ пинг активного (мс; -1/✗; null неизвестно) — монитор ставит КАЖДЫЙ цикл (через активный туннель). Плашка
        // берёт пинг ОТСЮДА, а не из сессионного pingResults (тот затеняет свежий монитора старым -1 из полного теста).
        val pingMs: Int? = null,
        val pingAtMs: Long = 0L,              // wall-clock живого пинга (для «N сек/мин назад» на плашке)
    )

    private val _state = MutableStateFlow(Speed())
    val state: StateFlow<Speed> = _state.asStateFlow()

    /** Активная проба: свежие прямой канал + туннель ↓/↑ для сервера [serverKey]. Живой пинг сохраняем (если тот же сервер). */
    fun setProbe(directDownMbps: Double?, tunnelDownMbps: Double?, tunnelUpMbps: Double?, nowMs: Long, serverKey: String) {
        val sameSrv = _state.value.serverKey == serverKey
        val prevPing = if (sameSrv) _state.value.pingMs else null
        val prevPingAt = if (sameSrv) _state.value.pingAtMs else 0L
        _state.value = Speed(directDownMbps, tunnelDownMbps, tunnelUpMbps, nowMs, Source.PROBE, serverKey, prevPing, prevPingAt)
    }

    /** Живой пинг активного (мс; -1 ✗; null неизвестно) + время — монитор ставит каждый цикл. Смена сервера сбрасывает прочие поля. */
    fun setPing(pingMs: Int?, nowMs: Long, serverKey: String) {
        val s = _state.value
        _state.value = if (s.serverKey == serverKey || s.serverKey.isEmpty()) s.copy(pingMs = pingMs, pingAtMs = nowMs, serverKey = serverKey)
                       else Speed(pingMs = pingMs, pingAtMs = nowMs, serverKey = serverKey)
    }

    /** Живой трафик: обновляем только ТУННЕЛЬ для сервера [serverKey], прямую сохраняем от прошлой пробы. */
    fun setLive(tunnelDownMbps: Double?, tunnelUpMbps: Double?, nowMs: Long, serverKey: String) {
        _state.value = _state.value.copy(
            tunnelDownMbps = tunnelDownMbps, tunnelUpMbps = tunnelUpMbps,
            measuredAtMs = nowMs, source = Source.LIVE, serverKey = serverKey,
        )
    }

    /** Прокси остановлен / статус сброшен — живой скорости нет. */
    fun clear() { _state.value = Speed() }

    fun snapshot(): Speed = _state.value
}
