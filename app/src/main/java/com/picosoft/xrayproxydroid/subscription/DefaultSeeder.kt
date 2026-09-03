package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import android.os.SystemClock

/**
 * Пр.136: повтор посева дефолтной подписки. Разовая неудача НЕ означает «никогда» — пробуем снова при
 * старте, появлении сети, возврате в приложение и по кнопке «Повторить». Бэкофф, чтобы не долбить адрес.
 *
 * Замечание про «поднятие туннеля» (из промпта): для ДЕФОЛТНОГО посева это логически мёртвый триггер —
 * туннель означает наличие активного сервера, а сервер берётся из источника, значит источники уже НЕ пусты
 * и посев и так пропускается. Поэтому отдельного триггера «туннель поднялся» тут нет; реальные —
 * сеть/возврат/кнопка. Само появление сети чинит первую (прямую) ступень каскада, ради чего повтор и нужен.
 */
object DefaultSeeder {

    private const val MIN_RETRY_MS = 60_000L   // не чаще раза в минуту (кроме ручного force)

    @Volatile private var lastAttemptElapsed = 0L
    @Volatile private var running = false

    /**
     * Попробовать посеять, если ещё нужно (список пуст и не помечено «посеяно»). Бэкофф между авто-попытками;
     * [force] (ручная «Повторить») его игнорирует. Сетевой фетч — в ФОНОВОМ потоке.
     */
    fun maybeSeed(context: Context, reason: String, force: Boolean = false) {
        val app = context.applicationContext
        val f = SubscriptionStore.load(app)
        if (f.seededDefaultRuBypass || f.sources.isNotEmpty()) return   // уже нечего сеять
        val now = SystemClock.elapsedRealtime()
        synchronized(this) {
            if (running) return
            if (!force && lastAttemptElapsed != 0L && now - lastAttemptElapsed < MIN_RETRY_MS) return
            lastAttemptElapsed = now
            running = true
        }
        Thread {
            try { SubscriptionManager.trySeedDefaultSource(app) }
            catch (_: Throwable) {}
            finally { running = false }
        }.start()
    }
}
