package com.picosoft.xrayproxydroid.monitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock

/**
 * Промпт 118: цикл монитора на ТОЧНОМ БУДИЛЬНИКЕ вместо корутинной паузы.
 *
 * ПОЧЕМУ: корутинный `delay`/`awaitWake` засыпает вместе с процессором в Doze — foreground-сервис сам CPU
 * НЕ будит. Диагностика 111: 142 цикла за 46ч при интервале ~60с (один раз в 19 мин вместо ~1380). Падение
 * туннеля обнаруживалось через десятки минут. `setExactAndAllowWhileIdle` срабатывает и в Doze → цикл идёт
 * по расписанию.
 *
 * ГРАНИ:
 *  - Точные будильники (Android 12+) гейтятся `canScheduleExactAlarms()`. Исключение из энергосбережения
 *    (Промпт 117) авто-выдаёт это право — потому 117 сделан РАНЬШЕ 118. Если точные всё же недоступны —
 *    деградируем до `setAndAllowWhileIdle` (НЕточный, но тоже просыпается в Doze; система батчит его не чаще
 *    ~раза в 9 мин). НЕ молчим и НЕ падаем.
 *  - Частые `*AndAllowWhileIdle` в Doze система ограничивает (≈раз в 9 мин) — НО приложения в whitelist
 *    энергосбережения от этого ограничения освобождены. Ещё одна причина, почему 117 — предпосылка 118.
 *  - `ELAPSED_REALTIME_WAKEUP`: монотонно, будит CPU, не зависит от смены системных часов.
 *
 * Будильник — лишь ТАЙМЕР следующего тика; событийные триггеры Промпта 95 (экран/сеть/выход из простоя)
 * работают параллельно и будят монитор досрочно тем же [MonitorCoordinator.wake].
 */
object MonitorAlarm {
    const val ACTION_TICK = "com.picosoft.xrayproxydroid.action.MONITOR_TICK"
    private const val REQ = 0x11801   // произвольный стабильный requestCode PendingIntent'а

    private fun alarmManager(context: Context): AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** Может ли планировать ТОЧНЫЕ будильники (Android 12+ гейтит; whitelist энергосбережения авто-выдаёт). */
    fun canExact(context: Context): Boolean {
        val am = alarmManager(context) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) am.canScheduleExactAlarms() else true
    }

    private fun pendingIntent(context: Context): PendingIntent {
        val i = Intent(ACTION_TICK).setPackage(context.packageName)
        var flags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) flags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(context.applicationContext, REQ, i, flags)
    }

    /**
     * Поставить следующий тик через [delayMs]. Точный+в-Doze, если можно; иначе неточный+в-Doze.
     * Возвращает true, если использован ТОЧНЫЙ будильник (для лога/диагностики).
     */
    fun scheduleNext(context: Context, delayMs: Long): Boolean {
        val am = alarmManager(context) ?: return false
        val at = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(1_000L)
        val pi = pendingIntent(context)
        val exact = canExact(context)
        return try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            exact
        } catch (e: SecurityException) {
            // Точный отозвали прямо в момент вызова (гонка) — деградируем до неточного, а не падаем/молчим.
            runCatching { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi) }
            false
        }
    }

    /** Снять запланированный тик (проснулись досрочно по событию — старый будильник не нужен). */
    fun cancel(context: Context) {
        runCatching { alarmManager(context)?.cancel(pendingIntent(context)) }
    }

    /**
     * Partial wake-lock, удерживающий CPU на время ОДНОГО цикла проверки: после срабатывания будильника
     * временное разрешение системы коротко, а сетевая проба может длиться секунды — без лока CPU уснёт
     * посреди неё. Не reference-counted; держать во время цикла, отпускать перед сном. Таймаут при acquire —
     * страховка от утечки.
     */
    fun newWakeLock(context: Context): PowerManager.WakeLock? {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
        return runCatching {
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "xray:monitor-cycle").apply { setReferenceCounted(false) }
        }.getOrNull()
    }
}
