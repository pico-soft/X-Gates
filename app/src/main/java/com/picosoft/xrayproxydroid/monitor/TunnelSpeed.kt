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
    )

    private val _state = MutableStateFlow(Speed())
    val state: StateFlow<Speed> = _state.asStateFlow()

    /** Активная проба: свежие прямой канал + туннель ↓/↑ для сервера [serverKey]. */
    fun setProbe(directDownMbps: Double?, tunnelDownMbps: Double?, tunnelUpMbps: Double?, nowMs: Long, serverKey: String) {
        _state.value = Speed(directDownMbps, tunnelDownMbps, tunnelUpMbps, nowMs, Source.PROBE, serverKey)
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
