package com.picosoft.xrayproxydroid.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Промпт 95 — ЕДИНЫЙ ФАКТ-СТАТУС ТУННЕЛЯ. ОСНОВНОЕ ТРЕБОВАНИЕ приложения: либо держим рабочую связь, либо
 * прямо говорим, что рабочих серверов нет. Третьего состояния (молчаливое «соединён, но не работает») НЕТ.
 *
 * ЖИВОСТЬ = ФАКТ ПРОХОЖДЕНИЯ ТРАФИКА (реальный запрос через SOCKS вернул внешний IP), а НЕ флаг/слушающий порт/
 * «ядро запущено». Это состояние — единственный источник, из которого UI решает «зелёный/не зелёный», и по
 * которому запускается восстановление. Обновляется и по расписанию, и по событиям (см. NetworkMonitor).
 */
object TunnelHealth {
    enum class Phase {
        UNKNOWN,      // ещё не проверяли (прокси только поднялся)
        VERIFYING,    // идёт проверка связи
        OK,           // ФАКТ: запрос прошёл (есть внешний IP) — ТОЛЬКО это = зелёный
        RECOVERING,   // связи нет — идёт лестница восстановления (перезапуск/перебор/обновление)
        NO_SERVERS,   // лестница пройдена, рабочих серверов нет
        NO_INTERNET,  // нет интернета вообще (прямой канал недоступен) — туннель не виноват
    }

    data class State(
        val phase: Phase = Phase.UNKNOWN,
        val ip: String = "",           // внешний IP последней УСПЕШНОЙ проверки (факт)
        val lastOkMs: Long = 0L,       // когда связь в последний раз подтверждена фактом
        val lastCheckMs: Long = 0L,    // когда последний раз проверяли
        val detail: String = "",       // что происходит сейчас (перебор 3/10, обновляю подписки…)
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** ФАКТ подтверждён: запрос прошёл, есть внешний IP. Единственный путь к «зелёному». */
    fun ok(ip: String, nowMs: Long) { _state.value = State(Phase.OK, ip, nowMs, nowMs) }

    /** Сменить фазу (проверка/восстановление/нет серверов/нет интернета), сохранив последний ОК-IP/время. */
    fun setPhase(p: Phase, nowMs: Long, detail: String = "") {
        _state.value = _state.value.copy(phase = p, lastCheckMs = nowMs, detail = detail)
    }

    /** Прокси остановлен — статус неизвестен (не зелёный). */
    fun reset() { _state.value = State() }

    fun snapshot(): State = _state.value
}
