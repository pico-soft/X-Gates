package com.picosoft.xrayproxydroid.service

import android.content.Context

/**
 * Пр.134.C: состояние НЕНАВЯЗЧИВОГО предложения автозагрузки.
 *  • [firstHealthyMs] — когда связь ВПЕРВЫЕ подтвердилась (ставим один раз). Предлагаем автозагрузку не при
 *    первом запуске, а после того как связь уже поработала — прошло ≥ [OFFER_DELAY_MS] с первого OK.
 *  • [offerDismissed] — человек закрыл полосу → больше не предлагаем.
 */
object BootPrefs {
    private const val PREFS = "boot_offer"
    private const val KEY_FIRST_OK = "firstHealthyMs"
    private const val KEY_DISMISSED = "offerDismissed"
    const val OFFER_DELAY_MS = 10 * 60_000L   // связь должна проработать хотя бы ~10 минут (не первый запуск)

    private fun p(c: Context) = c.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Отметить первый подтверждённый OK (только если ещё не отмечали). */
    fun markHealthyOnce(c: Context) {
        val pr = p(c)
        if (pr.getLong(KEY_FIRST_OK, 0L) == 0L) pr.edit().putLong(KEY_FIRST_OK, System.currentTimeMillis()).apply()
    }

    fun firstHealthyMs(c: Context): Long = p(c).getLong(KEY_FIRST_OK, 0L)
    fun offerDismissed(c: Context): Boolean = p(c).getBoolean(KEY_DISMISSED, false)
    fun dismissOffer(c: Context) { p(c).edit().putBoolean(KEY_DISMISSED, true).apply() }

    /** Пора ли показать предложение: связь была OK ≥10 мин назад, не закрыто, автозагрузка ещё выключена. */
    fun shouldOffer(c: Context, startOnBoot: Boolean): Boolean {
        if (startOnBoot || offerDismissed(c)) return false
        val first = firstHealthyMs(c)
        return first > 0L && System.currentTimeMillis() - first >= OFFER_DELAY_MS
    }
}
