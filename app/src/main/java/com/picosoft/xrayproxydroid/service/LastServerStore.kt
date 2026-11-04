package com.picosoft.xrayproxydroid.service

import android.content.Context

/**
 * Персистенция ПОСЛЕДНЕГО УСПЕШНО ПОДКЛЮЧЁННОГО сервера (его serverKey).
 *
 * Раньше персистенцию выбранного сервера сознательно откладывали (см. KDoc [XrayProxyService],
 * START_NOT_STICKY). Теперь она нужна автозапуску: при старте приложения сразу коннектимся к
 * последнему рабочему серверу (мгновенная связь), пока фоном идёт обновление подписок + полный тест.
 *
 * Храним ТОЛЬКО serverKey — сам профиль ищем в SubscriptionManager.allServers по ключу (список мог
 * измениться между запусками; если сервера уже нет — просто не подключаемся, тест всё равно идёт).
 * Одна строка → SharedPreferences проще атомарного JSON-файла (как у настроек/подписок).
 */
object LastServerStore {
    private const val PREFS = "last_server"
    private const val KEY = "serverKey"
    private const val KEY_ALIVE = "aliveHeartbeatMs"   // Промпт 115: «пульс живости» сервиса — для оценки простоя
    private const val KEY_STOPPED = "stoppedByUser"    // «Стоп» или «Завершить приложение»: после обновления прокси не поднимаем
    private const val PULSE_FRESH_MS = 60_000L         // пульс пишется раз в 15 с, пока туннель поднят

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сохранить ключ успешно подключённого сервера (вызывается из сервиса при running=true). */
    fun save(context: Context, serverKey: String?) {
        prefs(context).edit().putString(KEY, serverKey).apply()
    }

    /** Ключ последнего успешного сервера или null, если ещё не подключались. */
    fun load(context: Context): String? = prefs(context).getString(KEY, null)

    /** Промпт 115: отметить, что сервис ЖИВ сейчас (периодически из опроса трафика). На восстановлении после
     *  убийства простой ≈ now − этот штамп. */
    fun heartbeat(context: Context) {
        prefs(context).edit().putLong(KEY_ALIVE, System.currentTimeMillis()).apply()
    }

    /** Время последнего «пульса» (мс) или 0, если сервис ещё ни разу не отмечался. */
    fun lastAlive(context: Context): Long = prefs(context).getLong(KEY_ALIVE, 0L)

    /** Пользователь остановил прокси (true) или прокси запущен (false). Читает [runningBeforeUpdate]. */
    fun markStopped(context: Context, stopped: Boolean) {
        prefs(context).edit().putBoolean(KEY_STOPPED, stopped).apply()
    }

    /**
     * Работал ли прокси, когда обновление приложения убило процесс. Версии до 0.64 флага [KEY_STOPPED] не писали:
     * тогда судим по пульсу, свежий пульс значит, что туннель был поднят.
     */
    fun runningBeforeUpdate(context: Context): Boolean {
        val p = prefs(context)
        if (p.contains(KEY_STOPPED)) return !p.getBoolean(KEY_STOPPED, false)
        return System.currentTimeMillis() - lastAlive(context) < PULSE_FRESH_MS
    }
}
