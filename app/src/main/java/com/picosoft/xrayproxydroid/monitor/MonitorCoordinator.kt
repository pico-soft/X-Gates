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

    // Как прервать идущий полный тест (ставит onFullTest на время прогона, снимает по завершении).
    @Volatile
    var fullTestCancel: (() -> Unit)? = null

    /**
     * Промпт 93.E: операции с ИСТОЧНИКАМИ (добавить/обновить/удалить/выключить) и полный тест ВЗАИМНО
     * ИСКЛЮЧЕНЫ — реестр не должен перестраиваться под идущим обходом. ВЫБОР: не очередь, а ЧЕСТНОЕ
     * ПРЕРЫВАНИЕ теста перед изменением данных. ПОЧЕМУ: добавление источника — осознанное действие
     * пользователя, оно должно вступить в силу сразу; тест легко перезапустить (кнопкой/автоподбором).
     * Очередь запутала бы («добавил — ничего не происходит, пока тест идёт»). Прерываем и ЖДЁМ реальной
     * остановки (флаг снимается в onDone теста), только потом меняем данные. Вызывать из ФОНОВОГО потока.
     */
    fun abortFullTestAndWait(timeoutMs: Long = 8000L) {
        if (!fullTestRunning) return
        runCatching { fullTestCancel?.invoke() }
        val end = System.currentTimeMillis() + timeoutMs
        while (fullTestRunning && System.currentTimeMillis() < end) {
            try { Thread.sleep(50) } catch (e: InterruptedException) { break }
        }
    }

    // Идёт перебор кандидатов монитором (он уже крутит temp-инстансы). Ступень 5 каскада не должна
    // поднимать ЕЩЁ один temp-инстанс параллельно — пропускается на это время.
    private val _monitorSearch = AtomicBoolean(false)
    var monitorSearchRunning: Boolean
        get() = _monitorSearch.get()
        set(value) = _monitorSearch.set(value)

    // CONFLATED: держим максимум один сигнал; несколько wake() подряд = одно пробуждение.
    private val wakeups = Channel<Unit>(Channel.CONFLATED)

    // Промпт 98.B: событие требует ПОЛНОЙ перепроверки (смена сети/экран/действие), а не дешёвого пропуска
    // «недавно было ОК». Возврат на передний план (частое переключение приложений) — force=false: если связь
    // подтверждена совсем недавно, повторный сетевой запрос не оправдан.
    private val forceRecheck = AtomicBoolean(false)

    /** Разбудить монитор. force=true — сеть/экран/действие: перепроверить связь фактом, не пропускать по «недавно ок». */
    fun wake(force: Boolean = false) { if (force) forceRecheck.set(true); wakeups.trySend(Unit) }

    /** Прочитать и сбросить требование полной перепроверки (один раз за цикл монитора). */
    fun consumeForceRecheck(): Boolean = forceRecheck.getAndSet(false)

    /** Съесть накопленные сигналы перед началом ожидания, чтобы «спать» реально до нового события. */
    fun drainWakeups() { while (wakeups.tryReceive().isSuccess) { /* drain */ } }

    /** Ждать до [ms] или до wake(). true — разбудили досрочно, false — истекло время. */
    suspend fun awaitWake(ms: Long): Boolean =
        withTimeoutOrNull(ms) { wakeups.receive(); true } ?: false
}
