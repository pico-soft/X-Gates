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

    // ─────────────────────────── Подсказки по работе в фоне для вендоров (Промпт 120) ───────────────────────────

    /**
     * Подсказка по «убийцам фона» одного производителя. [steps] — короткие шаги по порядку (нумерованные, по-русски).
     * [matchers] — ключи для сопоставления с Build.MANUFACTURER/BRAND (нижний регистр, contains). Пустые matchers
     * = общий пункт (никогда не опознаётся автоматически, показывается всегда последним).
     *
     * ПОЧЕМУ ТОЛЬКО ТЕКСТ, А НЕ КНОПКИ на вендор-экраны: экраны автозапуска/фоновой активности — ПРИВАТНЫЕ
     * компоненты оболочки (напр. com.miui.securitycenter/…AutoStartManagementActivity), их имена меняются от
     * версии к версии и всё чаще не экспортируются → intent роняет ActivityNotFoundException/SecurityException.
     * Надёжны лишь Google-экраны (запрос исключения из энергосбережения + «Сведения о приложении»). Поэтому
     * кнопок автозапуска не рисуем (не рисовать заведомо нерабочую), даём путь руками. Исключение — Samsung
     * (Device Care проверен вживую, Промпт 118) — там дополнительная кнопка с откатом на «Сведения о приложении».
     */
    data class VendorGuide(
        val id: String,
        val title: String,   // марки в списке (человек ищет свою): «Xiaomi / Redmi / POCO»
        val os: String,      // оболочки: «MIUI · HyperOS»
        val steps: String,   // многострочные шаги
        val matchers: List<String>,
    )

    /**
     * Список в порядке Промпта 120 (Xiaomi → … → OPPO/OnePlus), общий пункт добавляется последним в UI.
     * TECNO/Infinix/itel — оболочки Transsion (HiOS/XOS/itel-OS), шаги совпадают, но пункты РАЗДЕЛЬНЫЕ
     * (человек ищет свою марку). Названия пунктов зависят от версии прошивки — предупреждаем и советуем
     * искать по смыслу (единый заголовок секции). Пути сверены по свежим источникам (dontkillmyapp и др.),
     * не по памяти.
     */
    val vendorGuides: List<VendorGuide> = listOf(
        VendorGuide("xiaomi", "Xiaomi / Redmi / POCO", "MIUI · HyperOS",
            "1. Автозапуск: Настройки → Приложения → это приложение → «Автозапуск» (в MIUI также: Безопасность → Разрешения → Автозапуск) — включить.\n" +
            "2. Батарея: Настройки → Приложения → это приложение → «Экономия заряда» → «Без ограничений».\n" +
            "3. Закрепить в «Недавних»: открыть недавние, потянуть карточку вниз (или долгое нажатие) → значок замка 🔒.",
            listOf("xiaomi", "redmi", "poco")),
        VendorGuide("samsung", "Samsung", "One UI",
            "1. Батарея «Без ограничений» — кнопка ниже (или Настройки → Приложения → это приложение → Батарея → «Без ограничений»).\n" +
            "2. Убрать из «Спящих» и «Глубоко спящих»: Обслуживание устройства → Батарея → «Ограничения фоновой активности».\n" +
            "3. Автооптимизация: Обслуживание устройства → ⋮ → «Автооптимизация» — выключить закрытие приложений.\n" +
            "4. Закрепить в «Недавних»: открыть недавние → долгое нажатие на иконку приложения → «Оставить открытым» 🔒.",
            listOf("samsung")),
        VendorGuide("realme", "realme", "realme UI",
            "1. Автозапуск: Настройки → Батарея → «Экономия энергии приложений» → это приложение → «Разрешить автозапуск».\n" +
            "2. Фон: тот же экран → включить «Разрешить активность в фоне», выключить «Оптимизировать энергопотребление».\n" +
            "3. Закрепить в «Недавних»: открыть недавние, потянуть карточку вниз → значок замка 🔒.",
            listOf("realme")),
        VendorGuide("tecno", "TECNO", "HiOS",
            "1. Автозапуск: «Диспетчер телефона» (Phone Master) → «Автозапуск» — включить для приложения.\n" +
            "2. Энергосбережение: Настройки → «Battery Lab»/Батарея → снять «Управление энергосбережением» для приложения (или добавить в «Исключения»).\n" +
            "3. Закрепить в «Недавних»: открыть недавние → стрелка вниз на карточке → пункт со значком замка 🔒.",
            listOf("tecno")),
        VendorGuide("honor", "HONOR", "MagicOS",
            "1. Запуск: Настройки → поиск «Запуск приложений» → это приложение → выключить «Управлять автоматически» → включить «Автозапуск», «Дополнительный запуск», «Работа в фоне».\n" +
            "2. Батарея: Настройки → поиск «Оптимизация батареи» → это приложение → «Не разрешать».\n" +
            "3. Закрепить в «Недавних»: открыть недавние, потянуть карточку вниз → значок замка 🔒.",
            listOf("honor")),
        VendorGuide("infinix", "Infinix", "XOS",
            "1. Автозапуск: «Диспетчер телефона» (Phone Master) → «Автозапуск» — включить для приложения.\n" +
            "2. Энергосбережение: Настройки → «Battery Lab»/Батарея → снять «Управление энергосбережением» для приложения (или добавить в «Исключения»).\n" +
            "3. Закрепить в «Недавних»: открыть недавние → стрелка вниз на карточке → пункт со значком замка 🔒.",
            listOf("infinix")),
        VendorGuide("huawei", "HUAWEI", "EMUI · HarmonyOS",
            "1. Запуск: Настройки → Батарея → «Запуск приложений» → это приложение → «Управлять вручную» → включить «Автозапуск», «Дополнительный запуск», «Работа в фоне».\n" +
            "2. Оптимизация: Настройки → Батарея → «Оптимизация батареи» → это приложение → «Не разрешать».\n" +
            "3. Закрепить в «Недавних»: открыть недавние, потянуть карточку вниз → значок замка 🔒.",
            listOf("huawei")),
        VendorGuide("itel", "itel", "itel OS (HiOS/XOS)",
            "1. Автозапуск: «Диспетчер телефона» (Phone Master) → «Автозапуск» — включить для приложения.\n" +
            "2. Энергосбережение: Настройки → «Battery Lab»/Батарея → снять «Управление энергосбережением» для приложения (или добавить в «Исключения»).\n" +
            "3. Закрепить в «Недавних»: открыть недавние → стрелка вниз на карточке → пункт со значком замка 🔒.",
            listOf("itel")),
        VendorGuide("vivo", "vivo / iQOO", "Funtouch OS · OriginOS",
            "1. Автозапуск: Настройки → «Дополнительно» → Приложения → «Автозапуск» — включить (или приложение iManager → «Автозапуск»).\n" +
            "2. Фоновое энергопотребление: Настройки → Батарея → «Управление фоновым энергопотреблением» → это приложение → «Не ограничивать».\n" +
            "3. Закрепить в «Недавних»: открыть недавние → меню карточки → «Закрепить» 🔒.",
            listOf("vivo", "iqoo")),
        VendorGuide("oppo", "OPPO / OnePlus", "ColorOS · OxygenOS",
            "1. Автозапуск: Настройки → Приложения → это приложение → «Разрешить автозапуск» (в OxygenOS — «App Auto-Launch»).\n" +
            "2. Батарея: Настройки → Батарея → это приложение → «Разрешить фоновую активность» / «Не оптимизировать» (на OnePlus также ⋮ → «Расширенная оптимизация» → выключить «Глубокую оптимизацию» и «Оптимизацию в режиме сна»).\n" +
            "3. Закрепить в «Недавних»: открыть недавние → долгое нажатие / меню карточки → значок замка 🔒.",
            listOf("oppo", "oneplus", "1+")),
    )

    /** Общий пункт «Другой производитель» — ВСЕГДА последним (и запасной для опознанных, если названия разошлись). */
    val generalGuide = VendorGuide("general", "Другой производитель", "",
        "1. Исключение из энергосбережения — кнопка «Разрешить работу в фоне» выше (работает одинаково на любой прошивке).\n" +
        "2. Закрепить в «Недавних»: открыть недавние и либо долгое нажатие на карточку → значок замка 🔒, либо стрелка/меню карточки → «Закрепить».\n" +
        "3. У многих производителей есть свои ограничения под своими названиями. Ищите в настройках по словам: автозапуск, фоновая активность, энергосбережение, защищённые приложения.\n" +
        "4. «Сведения о приложении» (кнопка ниже) — единственный экран, открывающийся на любой прошивке.",
        emptyList())

    /** id оболочки текущего устройства по Build.MANUFACTURER/BRAND, либо null (неопознан → раскрывать нечего). */
    fun detectVendorId(): String? {
        val m = (Build.MANUFACTURER + " " + Build.BRAND).lowercase()
        return vendorGuides.firstOrNull { g -> g.matchers.any { m.contains(it) } }?.id
    }
}
