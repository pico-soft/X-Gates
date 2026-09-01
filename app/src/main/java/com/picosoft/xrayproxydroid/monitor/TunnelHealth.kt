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
        // Промпт 123.B/C: serverKey сервера, для которого подтверждён IP. Внешний адрес и «зелёный»
        // ПРИНАДЛЕЖАТ этому серверу: сменился активный serverKey — прежнее подтверждение (ip/OK) НЕ его,
        // UI не показывает ни зелёный, ни чужой адрес. Пустой = ещё не подтверждали ни для кого.
        val serverKey: String = "",
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** ФАКТ подтверждён: запрос прошёл, есть внешний IP — для сервера [serverKey]. Единственный путь к «зелёному». */
    fun ok(ip: String, nowMs: Long, serverKey: String) { _state.value = State(Phase.OK, ip, nowMs, nowMs, "", serverKey) }

    /**
     * Промпт 123.C: подтверждена ли связь ИМЕННО для [activeKey] И не позже [freshMs] назад. Зелёный только тут:
     * ФАКТ прохождения запроса через туннель ЭТОГО сервера, свежий. Устарело/сменился сервер → не зелёный.
     */
    fun isConfirmedFor(activeKey: String?, nowMs: Long, freshMs: Long): Boolean {
        val s = _state.value
        return s.phase == Phase.OK && activeKey != null && s.serverKey == activeKey && (nowMs - s.lastOkMs) <= freshMs
    }

    /** Сменить фазу (проверка/восстановление/нет серверов/нет интернета), сохранив последний ОК-IP/время. */
    fun setPhase(p: Phase, nowMs: Long, detail: String = "") {
        _state.value = _state.value.copy(phase = p, lastCheckMs = nowMs, detail = detail)
    }

    /** Прокси остановлен — статус неизвестен (не зелёный). */
    fun reset() { _state.value = State() }

    fun snapshot(): State = _state.value
}
