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

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Сохранить ключ успешно подключённого сервера (вызывается из сервиса при running=true). */
    fun save(context: Context, serverKey: String?) {
        prefs(context).edit().putString(KEY, serverKey).apply()
    }

    /** Ключ последнего успешного сервера или null, если ещё не подключались. */
    fun load(context: Context): String? = prefs(context).getString(KEY, null)
}
