package com.picosoft.xrayproxydroid.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Признак жизни монитора для ШАПКИ секции (не журнал!): время последней проверки, текущее состояние,
 * сколько циклов прошло. Обновляется на месте каждый цикл, в журнал НЕ пишется (иначе забьёт рутиной).
 * В памяти (не persist) — это индикатор «жив сейчас», после перезапуска обнуляется, и это правильно.
 */
data class MonitorHeartbeat(
    val enabled: Boolean = false,
    val state: String = "—",
    val lastCheckMs: Long = 0L,
    val cycles: Int = 0,
)

object MonitorStatus {
    private val _state = MutableStateFlow(MonitorHeartbeat())
    val state: StateFlow<MonitorHeartbeat> = _state.asStateFlow()

    fun update(enabled: Boolean, state: String, lastCheckMs: Long, cycles: Int) {
        _state.value = MonitorHeartbeat(enabled, state, lastCheckMs, cycles)
    }

    fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }
}
