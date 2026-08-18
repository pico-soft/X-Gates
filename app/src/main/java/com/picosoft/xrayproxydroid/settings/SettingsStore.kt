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
    val speedMeasureMb: Int = 10,       // объём замера, МБ
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

    // Обход ЧУЖОГО системного VPN: привязать процесс к физической сети, чтобы наш туннель шёл МИМО
    // внешнего VPN (иначе туннель-в-туннеле: двойной крюк + падение внешнего VPN роняет наш прокси).
    // ДЕФОЛТ вкл; включается только когда VPN реально активен. Выкл — если у кого-то схема наоборот.
    val bypassSystemVpn: Boolean = true,

    // --- Автомониторинг (следит И переключает; ОДНА настройка) ---
    // ДЕФОЛТ ВКЛ. Выключенный монитор НЕ выполняет ничего (ни проверок, ни замеров, ни журнала, ни
    // строки состояния) — ради батареи. Режима «наблюдаю, но не переключаю» больше нет.
    val monitorEnabled: Boolean = true,
    val monitorIntervalSec: Int = 120,          // период цикла в ЗДОРОВОМ состоянии, с (мин 60)
    // Промпт 82: при ПАДЕНИИ туннеля проверять чаще (раз в минуту), чтобы быстрее переключиться на рабочий.
    val monitorProblemIntervalSec: Int = 60,
    // Промпт 82: «держать самый быстрый» — раз в [monitorOptimizeSec] перемерять top-[normalTopBatch] по
    // скорости и переключаться на быстрейший (>margin). Трафик: перемер = скачивание пробников. Дефолт — час
    // (по решению Elyor: раз/2мин ≈ десятки ГБ/сут — слишком; час = разумный компромисс). 0 = не оптимизировать.
    val monitorOptimizeSec: Int = 3600,
    val monitorDirectThreshold: Double = 1.0,   // порог прямого канала, Мбит/с (эталон direct_speed_threshold)
    val monitorTunnelThreshold: Double = 1.0,   // порог туннеля, Мбит/с (эталон tunnel_speed_threshold)
    // Сколько неудач ПОДРЯД считать падением. В эталоне решение за 1 цикл (счётчика нет) — наш анти-дребезг.
    val monitorFailuresToVerdict: Int = 2,

    // --- Экономия трафика (Промпт 77) ---
    // ВЫКЛ по умолчанию. В режиме экономии замер идёт БАТЧАМИ по [trafficSaveBatch] лучших по ПИНГУ:
    // как только набрано [trafficSaveMinAlive] живых (скорость ≥ порога) — стоп; иначе следующий батч.
    // Авто-обновление подписок — раз в [trafficSaveRefreshSec] (по умолч. час).
    val trafficSaveMode: Boolean = false,
    val trafficSaveBatch: Int = 5,             // сколько мерить за шаг (top по пингу)
    val trafficSaveMinAlive: Int = 2,          // стоп, когда столько живых найдено
    val trafficSaveRefreshSec: Int = 3600,     // авто-обновление подписок в режиме экономии, с
    // top-N по скорости: для ОПТИМИЗАЦИИ монитора «держать самый быстрый» (Промпт 82). Ручной тест
    // мерит ВСЕХ (Промпт 82: ступенчатый top-N — только монитор/экономия, не ручной запуск).
    val normalTopBatch: Int = 10,
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
    val uploadProbeUrl: String = "http://speedtest.tele2.net/upload.php",
    // Минимальная отдача, Мбит/с. Дефолт = порогу скачивания (monitorTunnelThreshold=1.0). Недобор → искать замену.
    val minUploadMbps: Double = 1.0,
    // Бюджет ПО ОБЪЁМУ на один замер отдачи, МБ (отдельный от скачивания). Дефолт скромный — отдача дороже.
    val uploadMeasureMb: Int = 2,
) {
    // Производные (в единицах, которые нужны коду).
    val speedWarmupMs: Int get() = (speedWarmupSec * 1_000).roundToInt()
    val speedWindowMs: Int get() = (speedWindowSec * 1_000).roundToInt()
    val speedWarmupBudgetBytes: Long get() = speedWarmupMb * 1_048_576L
    val speedMeasureBudgetBytes: Long get() = speedMeasureMb * 1_048_576L
    val uploadMeasureBudgetBytes: Long get() = uploadMeasureMb * 1_048_576L
    val marginRatio: Double get() = upgradeMarginPercent / 100.0
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
