package com.picosoft.xrayproxydroid.monitor

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Координация монитора с остальным приложением (один процесс).
 *
 * 1) Взаимное исключение с РУЧНЫМ полным тестом ([fullTestRunning]): он сам переключает активный
 *    сервер, монитор в это время молчит.
 * 2) ПРОБУЖДЕНИЕ монитора из паузы ([wake]/[awaitWake]): при появлении сети (ConnectivityManager) или
 *    ручном действии пользователя пауза «нет интернета» сбрасывается немедленно — иначе возврат в зону
 *    Wi-Fi не помог бы до истечения таймера.
 */
object MonitorCoordinator {
    private val _fullTest = AtomicBoolean(false)

    var fullTestRunning: Boolean
        get() = _fullTest.get()
        set(value) = _fullTest.set(value)

    // Идёт перебор кандидатов монитором (он уже крутит temp-инстансы). Ступень 5 каскада не должна
    // поднимать ЕЩЁ один temp-инстанс параллельно — пропускается на это время.
    private val _monitorSearch = AtomicBoolean(false)
    var monitorSearchRunning: Boolean
        get() = _monitorSearch.get()
        set(value) = _monitorSearch.set(value)

    // CONFLATED: держим максимум один сигнал; несколько wake() подряд = одно пробуждение.
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    /** Разбудить монитор (сеть появилась / пользователь подключился или запустил тест). */
    fun wake() { wakeups.trySend(Unit) }

    /** Съесть накопленные сигналы перед началом ожидания, чтобы «спать» реально до нового события. */
    fun drainWakeups() { while (wakeups.tryReceive().isSuccess) { /* drain */ } }

    /** Ждать до [ms] или до wake(). true — разбудили досрочно, false — истекло время. */
    suspend fun awaitWake(ms: Long): Boolean =
        withTimeoutOrNull(ms) { wakeups.receive(); true } ?: false
}
