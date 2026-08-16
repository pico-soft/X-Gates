package com.picosoft.xrayproxydroid.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Снимок состояния прокси для UI. */
data class ProxyStatus(
    val running: Boolean = false,
    val label: String? = null,      // remark/адрес активного сервера — ТОЛЬКО для показа
    val serverKey: String? = null,  // полная идентичность активного профиля — для подсветки (не label!)
    val message: String = "idle",   // человекочитаемый статус (ошибка/этап); порты — отдельно
    val socksOk: Boolean = false,   // локальный SOCKS-сокет слушает
    val httpOk: Boolean = false,    // локальный HTTP-сокет слушает
)

/**
 * Общее состояние прокси между [XrayProxyService] и Activity.
 * Один процесс → обычный singleton со StateFlow, без binding/broadcast.
 */
object ProxyState {
    private val _state = MutableStateFlow(ProxyStatus())
    val state: StateFlow<ProxyStatus> = _state.asStateFlow()

    /** serverKey пишется АТОМАРНО вместе с running (одним ProxyStatus) — без промежуточных состояний. */
    fun update(
        running: Boolean,
        label: String?,
        serverKey: String?,
        message: String,
        socksOk: Boolean = false,
        httpOk: Boolean = false,
    ) {
        _state.value = ProxyStatus(running, label, serverKey, message, socksOk, httpOk)
    }
}
