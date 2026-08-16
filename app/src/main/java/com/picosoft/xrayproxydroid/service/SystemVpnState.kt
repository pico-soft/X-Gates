package com.picosoft.xrayproxydroid.service

import com.picosoft.xrayproxydroid.net.VpnRelation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние по отношению к ЧУЖОМУ системному VPN (обновляет [XrayProxyService] на сетевых событиях,
 * пока прокси активен). ЕДИНСТВЕННЫЙ источник истины (сервис вычисляет relation, сняв нашу привязку) —
 * каскад и статус-бокс читают отсюда, чтобы наша же привязка не путала детекцию.
 *
 * @param relation     отношение нашего трафика к VPN (нет / внутри / исключены)
 * @param bypassed     идём ли МИМО VPN (процесс привязан к физической сети). Осмысленно при INSIDE.
 * @param bypassFailed обход не удался (lockdown) → идём ЧЕРЕЗ VPN несмотря на настройку; замер = канал VPN.
 */
data class VpnStatus(
    val relation: VpnRelation = VpnRelation.NONE,
    val bypassed: Boolean = false,
    val bypassFailed: Boolean = false,
)

object SystemVpnState {
    private val _state = MutableStateFlow(VpnStatus())
    val state: StateFlow<VpnStatus> = _state.asStateFlow()

    fun update(relation: VpnRelation, bypassed: Boolean, bypassFailed: Boolean) {
        _state.value = VpnStatus(relation, bypassed, bypassFailed)
    }

    fun reset() { _state.value = VpnStatus() }
}
