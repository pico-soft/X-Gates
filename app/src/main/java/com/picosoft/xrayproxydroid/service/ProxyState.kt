package com.picosoft.xrayproxydroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Снимок состояния прокси для UI. */
data class ProxyStatus(
    val running: Boolean = false,
    val label: String? = null,     // remark/адрес активного сервера
    val message: String = "idle",  // человекочитаемый статус (порты/ошибка)
)

/**
 * Общее состояние прокси между [XrayProxyService] и Activity.
 * Один процесс → обычный singleton со StateFlow, без binding/broadcast.
 */
object ProxyState {
    private val _state = MutableStateFlow(ProxyStatus())
    val state: StateFlow<ProxyStatus> = _state.asStateFlow()

    fun update(running: Boolean, label: String?, message: String) {
        _state.value = ProxyStatus(running, label, message)
    }
}
