package com.picosoft.xrayproxydroid.settings

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import com.picosoft.xrayproxydroid.xray.link.Protocol
import java.io.File
import kotlin.math.roundToInt

/**
 * ЕДИНЫЙ ИСТОЧНИК ПРАВДЫ для всех порогов/таймаутов. Раньше они были константами в разных файлах
 * (FullTestRunner.MIN_USABLE_MBPS/DEFAULT_MARGIN_RATIO, warmup/окно в ServerSpeedTester, пулы/таймауты
 * в ServerTester) — теперь только ЗДЕСЬ, как дефолты. Модули читают [current] на момент замера →
 * смена значения действует со следующего замера без перезапуска. Двух копий порога быть не должно.
 *
 * ЗНАЧЕНИЯ = дефолты (перенесены 1:1 из бывших констант). Persist в filesDir (как подписки).
 */
@Serializable
data class AppSettings(
    // --- Замер скорости (прямо влияет на открытый вопрос 0.1 vs 4.0) ---
    // Секунды ДРОБНЫЕ (Double): нужен суб-секундный прогрев (напр. 0.5). Старые целые из JSON читаются как X.0.
    val speedWarmupSec: Double = 0.5,   // предел прогрева ПО ВРЕМЕНИ (что раньше — время ИЛИ объём)
    val speedWindowSec: Double = 2.0,   // предел измерения ПО ВРЕМЕНИ (что раньше — время ИЛИ объём)
    // Бюджеты ПО ОБЪЁМУ (что раньше — время ИЛИ объём): на быстром сервере фаза кончается по МБ, не по времени.
    val speedWarmupMb: Int = 3,         // объём прогрева, МБ (без него прогрев-по-времени тянул бы больше всего лимита)
    val speedMeasureMb: Int = 10,       // УСТАРЕЛО (Пр.131): не читается — бюджет замера теперь per-mode (measureMb*/activeMeasureMb)
    val speedPool: Int = 1,             // одновременных замеров скорости; ОТ 1 (1 = строго последовательно)
    val speedProbeUrl: String = "http://speedtest.tele2.net/1GB.zip", // 1 ГБ: заведомо больше окна (нет eof); редактируемый (Cloudflare 403 / Hetzner TLS)

    // --- Пинг ---
    val pingTimeoutMs: Int = 5_000,     // мягкий таймаут одного пинга
    val pingPool: Int = 8,              // одновременных пингов

    // --- Подписки ---
    val subUserAgent: String = "v2rayNG/1.8.0",  // многие панели по неизвестному UA отдают Clash YAML вместо base64
    val subTimeoutSec: Int = 15,                 // таймаут загрузки подписки

    // --- Выбор сервера ---
    val minUsableMbps: Double = 0.05,   // порог видимости в списке / «живой»
    val upgradeMarginPercent: Int = 10, // запас для апгрейда на более быстрый
    // Разрешённые протоколы (фильтр ОТОБРАЖЕНИЯ+ВЫБОРА, НЕ замера). ДЕФОЛТ обязателен: старый JSON
    // без этого поля должен разобраться (иначе все настройки слетят) → все 4 по умолчанию.
    val allowedProtocols: Set<Protocol> = setOf(Protocol.VLESS, Protocol.VMESS, Protocol.TROJAN, Protocol.SHADOWSOCKS),

    // --- Прочее ---
    val verboseLogs: Boolean = true,    // детальный лог ServerSpeedTester (промпт 18)
    // При старте приложения: авто-обновить подписки и сразу запустить полный тест (подключиться к
    // быстрейшему). ДЕФОЛТ включён — это ожидаемое поведение; отключаемо здесь для ручного контроля.
    val autoStartOnLaunch: Boolean = true,
    // Пр.134: запускать прокси ПОСЛЕ ПЕРЕЗАГРУЗКИ телефона (BootReceiver поднимает последний рабочий сервер
    // из LastServerStore, без полного теста). ДЕФОЛТ ВЫКЛ — предлагаем один раз, ненавязчиво, после того как
    // связь уже поработала (не при первом запуске). На многих телефонах требует снятия энергоограничений.
    val startOnBoot: Boolean = false,

    // Обход ЧУЖОГО системного VPN: привязать процесс к физической сети, чтобы наш туннель шёл МИМО
    // внешнего VPN (иначе туннель-в-туннеле: двойной крюк + падение внешнего VPN роняет наш прокси).
    // ДЕФОЛТ вкл; включается только когда VPN реально активен. Выкл — если у кого-то схема наоборот.
    val bypassSystemVpn: Boolean = true,

    // Промпт 93.M: сообщать о новых версиях (полоса на главной/в настройках + уведомление по итогу
    // самостоятельной проверки). ДЕФОЛТ ВКЛ; дефолт при десериализации обязателен (старый JSON без поля).
    val notifyNewVersions: Boolean = true,

    // --- Автомониторинг (следит И переключает; ОДНА настройка) ---
    // ДЕФОЛТ ВКЛ. Выключенный монитор НЕ выполняет ничего (ни проверок, ни замеров, ни журнала, ни
    // строки состояния) — ради батареи. Режима «наблюдаю, но не переключаю» больше нет.
    val monitorEnabled: Boolean = true,
    val monitorIntervalSec: Int = 120,          // период цикла в ЗДОРОВОМ состоянии, с (мин 60)
    // Промпт 82: при ПАДЕНИИ туннеля проверять чаще (раз в минуту), чтобы быстрее переключиться на рабочий.
    val monitorProblemIntervalSec: Int = 60,
    // Пр.131: интервал перемера / число кандидатов / бюджет замера — РАЗДЕЛЬНЫЕ наборы по режимам (обычный/
    // экономия), активный выбирается по [trafficSaveMode] (см. active* ниже). Старое одиночное monitorOptimizeSec
    // УСТАРЕЛО (оставлено для совместимости старого JSON; код читает activeOptimizeSec).
    // Пр.131.D: сопоставимость замеров ДОКАЗАНА (Пр.130 — активный меряем тем же temp-инстансом, что кандидатов;
    // числами temp≈живой в пределах шума) → дефолт ОБЫЧНОГО режима «раз в час». Экономия — ВЫКЛ.
    val monitorOptimizeSec: Int = 0,   // УСТАРЕЛО (Пр.131): не читается, см. optimizeSecNormal/Save + activeOptimizeSec
    // Пр.132: «Размер топа» = сколько быстрых серверов поддерживаем актуальными (перемеряем при перемере топа),
    // НЕ «мерить всех». Интервал/бюджет подобраны под ≤6 ГБ/мес (H): обычный топ5 / 6ч / 5МБ → ≈5.6 ГБ.
    val optimizeSecNormal: Int = 21600,  // обычный: перемер топа раз в 6 ч (Пр.132.H — укладываемся в 5-6 ГБ/мес)
    val optimizeSecSave: Int = 0,        // экономия: перемер топа ВЫКЛ (только реактивно при потере связи)
    val topBatchNormal: Int = 5,         // обычный: размер топа
    val topBatchSave: Int = 2,           // экономия: топ 1-2 (Пр.132.F)
    val measureMbNormal: Int = 5,        // обычный: бюджет ОДНОГО замера, МБ (без прогрева) — 5 МБ ≈ 5.6 ГБ/мес
    val measureMbSave: Int = 5,          // экономия: тот же скромный бюджет
    // Пр.132.D: ПРЕДЕЛ ДАВНОСТИ замера. Старее — скорость считается НЕизвестной: сервер уходит из топа и
    // подлежит перемеру (старое число ≠ действующее). По умолч. = интервалу перемера, чтобы топ обновлялся полностью.
    val topFreshSec: Int = 21600,
    // Пр.132.B: сколько ещё НЕ измеренных живых подмешивать в перемер (иначе быстрый сервер с плохим пингом
    // никогда не найдётся). 0 = не подмешивать.
    val topMixUnmeasured: Int = 1,
    // Пр.133.B: ПОРОГ ДОСТАТОЧНОСТИ. Цель — не точное число, а «хватает на видео/Телеграм». Замер кандидата
    // ОСТАНАВЛИВАЕТСЯ, как только подтверждено, что сервер держит этот порог (дальше качать незачем) → на порядок
    // меньше трафика, чем полный замер (верхняя граница 13 МБ на быстрый сервер). Ориентир 5-10 Мбит/с — хватает на видео.
    val sufficientMbps: Double = 8.0,
    // Пр.126.A: предохранители перемера (работают, когда активный интервал > 0). ОБЩИЕ для обоих режимов.
    val monitorOptimizeWifiOnly: Boolean = true,      // не перемерять на мобильной/платной сети — только Wi-Fi
    val monitorOptimizeIdleSkipSec: Int = 1800,       // не перемерять, если через туннель давно нет трафика, с (0=не пропускать)
    val monitorOptimizeSkipAboveMbps: Double = 10.0,  // не перемерять, если текущий уже быстрый (≥), Мбит/с (0=не пропускать)
    val monitorDirectThreshold: Double = 1.0,   // порог прямого канала, Мбит/с (эталон direct_speed_threshold)
    val monitorTunnelThreshold: Double = 1.0,   // порог туннеля, Мбит/с (эталон tunnel_speed_threshold)
    // Сколько неудач ПОДРЯД считать падением. В эталоне решение за 1 цикл (счётчика нет) — наш анти-дребезг.
    val monitorFailuresToVerdict: Int = 2,

    // --- Экономия трафика (Промпт 77; ПЕРЕРАБОТАНО Пр.125) ---
    // ВЫКЛ по умолчанию. Режим НЕ отсеивает серверы — он МЕНЯЕТ СПОСОБ ВЫБОРА, опираясь на СОХРАНЁННЫЕ замеры:
    //  • пинг живых — остаётся (стоит килобайты/сервер; замер скорости — мегабайты);
    //  • кандидаты упорядочены по ИЗВЕСТНОЙ скорости [speedMbps] от быстрых к медленным;
    //  • подключаемся к первому и мерим ТОЛЬКО его; ниже порога — следующий; МАССОВОГО замера НЕТ никогда;
    //  • прошлых замеров нет (первый запуск / список только обновлён) — честно сказать и померить первых
    //    [trafficSaveBlindProbe] по пингу, чтобы было из чего выбрать;
    //  • монитор в этом режиме РЕЗКО тише: перемер «держать лучший» ОТКЛЮЧЁН, плановая проба плашки в простое
    //    ОТКЛЮЧЕНА, остаются только дешёвые проверки живости (см. NetworkMonitor).
    // ГРАБЛЯ (Пр.125): раньше режим упорядочивал по ПИНГУ и мерил батчами → подключался к случайному/медленному
    //   (пинг ≠ скорость), а у кого замеров не было — «живых 0». Поля batch/minAlive/refreshSec БОЛЬШЕ НЕ
    //   используются (оставлены только для совместимости старого JSON; refreshSec был мёртв и раньше).
    val trafficSaveMode: Boolean = false,      // ЕДИНЫЙ источник правды режима (его читают все); автоматика ниже пишет в него
    // Пр.129: авто-переключение экономии по ТИПУ СЕТИ (по событию смены сети, не по опросу). ДЕФОЛТ ВКЛ:
    // Wi-Fi/Ethernet → экономия ВЫКЛ (обычный режим), мобильная → ВКЛ. Выкл — тип сети ни на что не влияет.
    val trafficSaveAutoByNetwork: Boolean = true,
    // Пр.129.C: пользователь переключил режим ВРУЧНУЮ при активной автоматике → его выбор держится ДО следующей
    // смены типа сети, потом автоматика снова берёт своё. Флаг снимается при смене сети (EconomyNet).
    val trafficSaveManualUntilNetChange: Boolean = false,
    val trafficSaveBlindProbe: Int = 3,        // Пр.125.C: сколько кандидатов мерить, когда прошлых замеров нет
    val trafficSaveBatch: Int = 5,             // УСТАРЕЛО (Пр.125): не используется
    val trafficSaveMinAlive: Int = 2,          // УСТАРЕЛО (Пр.125): не используется
    val trafficSaveRefreshSec: Int = 3600,     // УСТАРЕЛО (Пр.125): не используется
    // top-N по скорости: для ОПТИМИЗАЦИИ монитора «держать самый быстрый» (Промпт 82). Ручной тест
    // мерит ВСЕХ (Промпт 82: ступенчатый top-N — только монитор/экономия, не ручной запуск).
    val normalTopBatch: Int = 5,   // УСТАРЕЛО (Пр.131): не читается — число кандидатов теперь per-mode (topBatch*/activeTopBatch)
    // Промпт 82: общий БЮДЖЕТ ВРЕМЕНИ ручного полного теста, с. Мерим всех живых по очереди, но не дольше
    // этого (на ~100 серверах ×2с ≈ 3–4 мин; лимит защищает от зависания на медленных). Дефолт 10 мин.
    val fullTestBudgetSec: Int = 600,

    // --- Промпт 90: «держать лучший» + отдача + периодичность дешёвой проверки ---
    // ВТОРОЕ правило выбора (независимо от порога): если известный лучший живой сервер быстрее текущего
    // В [keepBestMultiplier] РАЗ (не на проценты — кратно), переключаемся, даже когда текущий выше порога.
    // ПОЧЕМУ кратно, а не 10%: небольшая разница шумит (±джиттер замера), кратная — реальный выигрыш.
    val keepBestMultiplier: Double = 3.0,
    // Дешёвая проверка соединения раз в [connectionCheckIntervalSec] (жив ли туннель/идёт ли трафик). Полный
    // замер (скачивание+отдача) — ТОЛЬКО по подозрению/кнопке/часовому перемеру: замер отдачи раз в минуту
    // круглосуточно ≈ 3 ГБ/сут при 8 Мбит — так нельзя. Новое поле (дефолт применится и на старых устройствах).
    val connectionCheckIntervalSec: Int = 60,
    // ОТДАЧА (Промпт 90.B): измеряется у АКТИВНОГО соединения (выгрузка через туннель на приёмник).
    // Приёмник редактируемый (как для скачивания). НЕ Cloudflare (403 на прокси-адреса) и НЕ Hetzner (рвёт TLS).
    // Промпт 104.B: cloudflare __up отвечает 200 за ~1.2с (факт, POST 2МБ) — быстрый приёмник отдачи. tele2/upload.php
    // ЧЕРЕЗ ТУННЕЛЬ не отвечает (http=000) → ждали весь таймаут (сток 25-75с). tele2 остался запасным (uploadFallbacks).
    val uploadProbeUrl: String = "https://speed.cloudflare.com/__up",
    // Минимальная отдача, Мбит/с. Дефолт = порогу скачивания (monitorTunnelThreshold=1.0). Недобор → искать замену.
    val minUploadMbps: Double = 1.0,
    // Бюджет ПО ОБЪЁМУ на один замер отдачи, МБ (отдельный от скачивания). Дефолт скромный — отдача дороже.
    val uploadMeasureMb: Int = 2,

    // --- Деградация «живой, но медленный» (Пр.127.C) ---
    // Проверка живости говорит «связь есть», но не видит МЕДЛЕННЫЙ туннель (пингом скорость не измерить).
    // Признак деградации берём БЕСПЛАТНО из пассивных счётчиков УЖЕ идущего трафика: если через туннель реально
    // качается и наблюдаемая скорость ниже [degradationMinMbps] — делаем ОДИН замер лучшего по сохранённым
    // данным кандидата и переходим, если он приемлемо быстрее. Один шаг, не перебор. Тишину (нет трафика) НЕ
    // трактуем как ноль. Не чаще раза в [degradationCheckSec]. Работает в ОБОИХ режимах (в экономии цель —
    // приемлемый туннель, не лучший). degradationMinMbps=0 — фича выключена.
    val degradationMinMbps: Double = 3.0,
    val degradationCheckSec: Int = 1800,      // мин. интервал между шагами по деградации, с (по умолч. 30 мин)

    // --- Живая скорость на плашке (гибрид): показывать актуальную ↓/↑ активного туннеля, а не «скорость подбора» ---
    // ВКЛ по умолчанию. Когда идёт трафик пользователя — скорость берётся БЕСПЛАТНО из счётчиков ядра (дельта
    // туннельных байт). Когда простой — раз в [liveSpeedActiveProbeSec] маленькая активная проба (download+upload),
    // и ТОЛЬКО при включённом экране (нет смысла тратить трафик на замер для плашки, которую никто не видит).
    val liveSpeedEnabled: Boolean = true,
    val liveSpeedActiveProbeSec: Int = 300,   // интервал активной пробы в простое, с (по умолч. 5 мин)
    // «Напрямую» ↓ (прямой канал без туннеля): БОЛЬШОЙ файл на быстром RU-зеркале, доступном НАПРЯМУЮ (Yandex
    // mirror, ISO ~1ГБ — заведомо больше окна, нет EOF; путь /latest/ стабилен). ПОЧЕМУ большой и RU: замер
    // мелкой страницы мерил бы задержку, а не ширину канала (фейковые 0.4 Мбит/с при живом туннеле 100+);
    // foreign-хост напрямую может быть заблокирован → провал. Проверено на устройстве: ~130 Мбит/с. Редактируемо.
    val directProbeUrl: String = "https://mirror.yandex.ru/archlinux/iso/latest/archlinux-x86_64.iso",
) {
    // Производные (в единицах, которые нужны коду).
    val speedWarmupMs: Int get() = (speedWarmupSec * 1_000).roundToInt()
    val speedWindowMs: Int get() = (speedWindowSec * 1_000).roundToInt()
    val speedWarmupBudgetBytes: Long get() = speedWarmupMb * 1_048_576L
    val speedMeasureBudgetBytes: Long get() = speedMeasureMb * 1_048_576L
    val uploadMeasureBudgetBytes: Long get() = uploadMeasureMb * 1_048_576L
    val marginRatio: Double get() = upgradeMarginPercent / 100.0

    // Пр.131: АКТИВНЫЙ набор (обычный/экономия) по [trafficSaveMode] — его читают все потребители.
    val activeOptimizeSec: Int get() = if (trafficSaveMode) optimizeSecSave else optimizeSecNormal
    val activeTopBatch: Int get() = if (trafficSaveMode) topBatchSave else topBatchNormal
    val activeMeasureMb: Int get() = if (trafficSaveMode) measureMbSave else measureMbNormal
    val activeMeasureBudgetBytes: Long get() = activeMeasureMb * 1_048_576L
}

object SettingsStore {

    private const val TAG = "SettingsStore"
    private const val FILE_NAME = "settings.json"
    private const val TMP_NAME = "settings.json.tmp"

    /** Дефолты — единственное место, где живут значения по умолчанию. */
    val DEFAULTS = AppSettings()

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(DEFAULTS)
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    /** Текущий снимок — модули читают ЭТО на момент замера (живое применение). */
    fun current(): AppSettings = _state.value

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Загрузить из файла в state. Вызвать один раз при старте (MainActivity.onCreate). */
    @Synchronized
    fun init(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        try {
            _state.value = json.decodeFromString<AppSettings>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "load failed, keeping defaults", e)
        }
    }

    /** Обновить и атомарно сохранить (temp→rename). Валидацию делает вызывающий (UI). */
    @Synchronized
    fun update(context: Context, new: AppSettings) {
        _state.value = new
        val target = file(context)
        val tmp = File(context.filesDir, TMP_NAME)
        try {
            tmp.writeText(json.encodeToString(new))
            if (tmp.renameTo(target)) return
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    /** Сбросить всё к дефолтам. */
    fun resetToDefaults(context: Context) = update(context, DEFAULTS)
}
