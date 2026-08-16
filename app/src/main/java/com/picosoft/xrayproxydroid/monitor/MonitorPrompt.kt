package com.picosoft.xrayproxydroid.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Предложение пользователю включить выключенные источники (пункт E). НЕ включаем сами — решение за ним. */
data class EnableSourcesPrompt(val sources: Int, val servers: Int)

/**
 * Состояние предложения «включить выключенные источники». Монитор ([NetworkMonitor]) выставляет запрос,
 * UI ([MainActivity]) показывает диалог; сервис дублирует уведомлением (если свёрнуто). [declined] —
 * пользователь отказался; повторно не спрашиваем до следующего явного провала (сбрасывается при
 * восстановлении/успешном переключении).
 */
object MonitorPrompt {
    private val _state = MutableStateFlow<EnableSourcesPrompt?>(null)
    val state: StateFlow<EnableSourcesPrompt?> = _state.asStateFlow()

    @Volatile var declined = false
        private set

    val pending: Boolean get() = _state.value != null

    fun request(sources: Int, servers: Int) { _state.value = EnableSourcesPrompt(sources, servers) }
    fun clear() { _state.value = null }
    fun decline() { _state.value = null; declined = true }
    fun resetDeclined() { declined = false }
}
