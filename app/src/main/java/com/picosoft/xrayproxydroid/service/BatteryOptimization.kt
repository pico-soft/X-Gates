package com.picosoft.xrayproxydroid.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Исключение приложения из энергосбережения (Промпт 117).
 *
 * ПОЧЕМУ ЭТО УСЛОВИЕ, А НЕ УДОБСТВО: перезапуск сервиса после убийства (Промпт 115, START_STICKY +
 * восстановление последнего сервера) помогает ТОЛЬКО когда система согласна перезапустить. В глубоком
 * энергосбережении Samsung приложения из «спящих»/«глубоко спящих» не поднимает вовсе — тогда нет ни
 * процесса, ни слушателя порта 10815, и Телеграм/браузер остаются без прокси. Исключение из оптимизации
 * батареи снимает это ограничение. Права [android.Manifest.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS]
 * в манифесте раньше не было вовсе — приложение даже не могло ПОПРОСИТЬ.
 *
 * Приложение ставится сайдлоадом (не из Google Play), поэтому используем ПРЯМОЙ системный диалог
 * ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS (в Play он запрещён политикой, здесь — уместен): нажал
 * «Разрешить» → isIgnoringBatteryOptimizations сразу true.
 */
object BatteryOptimization {
    private const val PREFS = "battery_opt"
    private const val KEY_BANNER_DISMISSED = "bannerDismissed"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Факт: приложение исключено из оптимизации батареи (в whitelist Doze). */
    fun isIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Прямой системный диалог «Разрешить работу без ограничений батареи?» для нашего пакета.
     * Один тап «Разрешить» → isIgnored станет true (сайдлоад, не Play — политика Play не действует).
     */
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"))

    /** Запасной путь: общий список «Оптимизация батареи» (если прямой диалог недоступен). */
    fun settingsListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    /** Страница «О приложении» в системных настройках — оттуда доступны «Батарея → Без ограничений»
     *  и (на Samsung) переход к «Спящие приложения». Гарантированно существует на любом устройстве. */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}"))

    /**
     * Экран управления фоновой активностью производителя (Samsung: «Уход за устройством» → батарея →
     * «Спящие приложения»/«Глубоко спящие приложения»). Публичного интента к точному экрану нет и
     * компоненты меняются между версиями One UI, поэтому пробуем известные компоненты по очереди, а вызов
     * ОБЯЗАН быть в try/catch (ActivityNotFoundException) с откатом на [appDetailsIntent]. null = не Samsung
     * либо экран неизвестен → используем appDetails.
     */
    fun vendorBackgroundIntent(): Intent? {
        if (!Build.MANUFACTURER.equals("samsung", ignoreCase = true)) return null
        // Известные экраны Samsung Device Care / Smart Manager (разные версии One UI).
        val candidates = listOf(
            "com.samsung.android.lool" to "com.samsung.android.sm.battery.ui.BatteryActivity",
            "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm_cn" to "com.samsung.android.sm.ui.battery.BatteryActivity",
            "com.samsung.android.sm" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        )
        val (pkg, cls) = candidates.first()
        return Intent().setClassName(pkg, cls)
    }

    /** Есть ли смысл в вендор-инструкции для текущего устройства (сейчас — только Samsung). */
    val isSamsung: Boolean
        get() = Build.MANUFACTURER.equals("samsung", ignoreCase = true)

    /** Пользователь закрыл полосу-подсказку на главной → больше не показывать. */
    fun dismissBanner(context: Context) {
        prefs(context).edit().putBoolean(KEY_BANNER_DISMISSED, true).apply()
    }

    fun isBannerDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_BANNER_DISMISSED, false)
}
