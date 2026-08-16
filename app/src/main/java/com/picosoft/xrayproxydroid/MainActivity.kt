package com.picosoft.xrayproxydroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.picosoft.xrayproxydroid.service.LastServerStore
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.monitor.MonitorCoordinator
import com.picosoft.xrayproxydroid.monitor.MonitorEvent
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.Blocklist
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubSource
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.traffic.DayBucket
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.ui.TestProgress
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import com.picosoft.xrayproxydroid.xray.FullTestRunner
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(applicationContext)   // загрузить настройки до первого замера
        BlocklistStore.init(applicationContext)  // стоп-лист (первый запуск → засев дефолтом RU/BY)
        MonitorLog.init(applicationContext)      // журнал автомониторинга (переживает перезапуск)
        SubscriptionManager.init(applicationContext)   // миграция старой подписки в мультиподписки (однократно)
        TrafficTracker.init(applicationContext)  // загрузить дневные корзины трафика
        enableEdgeToEdge()
        setContent {
            XrayProxyDroidTheme {
                AppRoot()
            }
        }
    }
}

/** Высота собственной нижней панели (стандартный NavigationBar фиксирован 80dp — не годится).
 *  Подбирать здесь; системный отступ навигации сюда НЕ входит (он добавляется отдельно инсетом). */
private val BOTTOM_BAR_HEIGHT = 42.dp

/** Корень с компактной нижней навигацией: «Главная» (рабочий экран) + «Настройки» (что настраивают). */
@Composable
private fun AppRoot() {
    // tab — plain remember (НЕ saveable): переживает переключение вкладок и рекомпозиции, но при
    // пересоздании Activity (блокировка экрана/память во время долгого теста) возвращается на ГЛАВНУЮ —
    // рабочий экран, чтобы приложение НЕ оказывалось само на «Настройки». Раскрытие секций — saveable.
    var tab by remember { mutableStateOf(0) }
    val stateHolder = rememberSaveableStateHolder()   // сохраняет состояние вкладки (раскрытые секции) при переключении
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { CompactBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { innerPadding ->
        stateHolder.SaveableStateProvider(tab) {
            when (tab) {
                0 -> BootScreen(modifier = Modifier.padding(innerPadding))
                else -> SettingsTab(modifier = Modifier.padding(innerPadding))
            }
        }
    }
}

/**
 * Вкладка «Настройки» — всё, что настраивают и оставляют: сворачиваемые секции
 * Настройки → Стоп-лист → Автомониторинг → Трафик. Все свёрнуты по умолчанию (экран = оглавление).
 * Механика сворачивания общая с главной ([CollapsibleSection]).
 */
@Composable
private fun SettingsTab(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val blocklist by BlocklistStore.state.collectAsState()
    val allServers = remember(settings, blocklist) { SubscriptionManager.allServers(context) }
    val protocolCounts = remember(allServers) { allServers.groupingBy { it.protocol }.eachCount() }
    // Пары (исходное имя, пользовательское) всех серверов — для счётчика «сколько блокирует слово»
    // у чипов стоп-листа: учитываем ОБА имени (D3), иначе счётчик разойдётся с фактической блокировкой.
    val serverNames = remember(allServers, blocklist) {
        allServers.map { providerName(it) to blocklist.customName(SubscriptionManager.serverKey(it)) }
    }
    val monitorLog by MonitorLog.state.collectAsState()
    // Состояние раскрытия — на уровне вкладки (стабильное позиционное scoping), все свёрнуты по умолчанию.
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var blocklistExpanded by rememberSaveable { mutableStateOf(false) }
    var monitorExpanded by rememberSaveable { mutableStateOf(false) }
    var trafficExpanded by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            CollapsibleSection("⚙️ Настройки", settingsExpanded, { settingsExpanded = !settingsExpanded }) {
                SettingsSection(
                    settings = settings,
                    protocolCounts = protocolCounts,
                    onChange = { SettingsStore.update(context, it) },
                    onReset = { SettingsStore.resetToDefaults(context) },
                )
            }
        }
        item {
            CollapsibleSection("🚫 Стоп-лист", blocklistExpanded, { blocklistExpanded = !blocklistExpanded }) {
                BlocklistSection(
                    blocklist = blocklist,
                    serverNames = serverNames,
                    onAddWord = { BlocklistStore.addWord(context, it) },
                    onRemoveWord = { BlocklistStore.removeWord(context, it) },
                    onUnblockServer = { BlocklistStore.unblockServer(context, it) },
                )
            }
        }
        item {
            CollapsibleSection("🛡️ Автомониторинг", monitorExpanded, { monitorExpanded = !monitorExpanded }) {
                MonitorSection(
                    settings = settings,
                    onChange = { SettingsStore.update(context, it) },
                    log = monitorLog,
                    onClearLog = { MonitorLog.clear(context) },
                )
            }
        }
        item {
            CollapsibleSection("Трафик", trafficExpanded, { trafficExpanded = !trafficExpanded }) { TrafficSection() }
        }
    }
}

/**
 * Компактная нижняя панель: Row из двух равных элементов, высота ~[BOTTOM_BAR_HEIGHT].
 * Мелкие подписи, без иконок; активная вкладка — цвет текста + тонкая полоса сверху (без заливки).
 * Системный отступ навигации (WindowInsets.navigationBars) добавляется ОТДЕЛЬНО, не входит в 28dp.
 * cappedDensity НЕ применяем; при крупных шрифтах высота растёт (heightIn min), подпись не обрезается.
 */
@Composable
private fun CompactBottomBar(selected: Int, onSelect: (Int) -> Unit) {
    val labels = listOf("Главная", "Настройки")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)   // отдельный цвет — панель заметна на фоне контента
            .windowInsetsPadding(WindowInsets.navigationBars),      // системный жест-бар — отдельным отступом
    ) {
        // Тонкая линия-разделитель сверху — визуально отделяет панель от списка.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
        // Подпись при крупных системных шрифтах не режем высотой — ограничиваем масштаб (cappedDensity).
        CompositionLocalProvider(LocalDensity provides cappedDensity()) {
            Row(
                modifier = Modifier.fillMaxWidth().height(BOTTOM_BAR_HEIGHT),   // ФИКС. высота — панель тонкая
                verticalAlignment = Alignment.CenterVertically,
            ) {
                labels.forEachIndexed { i, label ->
                    val active = i == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)                 // weight — ТОЛЬКО ширина; высоту НЕ трогаем
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            // тонкая полоса-индикатор ВНУТРИ вкладки (прозрачна у неактивной — layout не прыгает)
                            Box(
                                modifier = Modifier
                                    .width(24.dp)
                                    .height(2.dp)
                                    .background(if (active) MaterialTheme.colorScheme.primary else Color.Transparent),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                label,
                                fontSize = 11.sp,
                                maxLines = 1,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun serverLabel(p: ServerProfile, bl: Blocklist): String =
    "${p.protocol}  ·  ${displayName(p, bl)}  ·  ${p.address}:${p.port}"

/** Имя ОТ ПРОВАЙДЕРА, как пришло (без оверрайда). Модель не мутируем — remarks остаются исходными. */
private fun providerName(p: ServerProfile): String = p.remarks.ifBlank { p.address }

/**
 * ЕДИНАЯ точка показа имени: пользовательское, если задано, иначе имя провайдера. Через неё обязаны
 * идти ВСЕ листинги/статус/нотификация/журнал — прямое чтение remarks для показа не заводить.
 */
private fun displayName(p: ServerProfile, bl: Blocklist): String =
    bl.customName(SubscriptionManager.serverKey(p)) ?: providerName(p)

/** Мелкая подпись: протокол · network · security (для строк с одинаковым именем и для статус-бокса). */
private fun protoNetSec(p: ServerProfile): String = "${p.protocol} · ${p.network} · ${p.security}"

/**
 * Подпись-дискриминатор для ОДИНАКОВЫХ имён (иначе строки визуально неотличимы, выбрать нельзя).
 * Возвращает serverKey → «протокол · network · security». Только для имён, встречающихся >1 раза.
 * Если и эта подпись совпала внутри группы — добавляем последние 4 символа serverKey (гарантия уникальности).
 * Уникальные имена в map не попадают.
 */
private fun buildDiscriminators(servers: List<ServerProfile>, bl: Blocklist): Map<String, String> {
    val result = HashMap<String, String>()
    // Повторы считаем по ОТОБРАЖАЕМОМУ имени (D1): переименовал один из одноимённых — их стало меньше,
    // у переименованного суффикс уже не нужен.
    for ((_, group) in servers.groupBy { displayName(it, bl) }) {
        if (group.size < 2) continue
        val ambiguous = group.map(::protoNetSec).toSet().size < group.size
        for (p in group) {
            val key = SubscriptionManager.serverKey(p)
            result[key] = if (ambiguous) "${protoNetSec(p)} · ${key.takeLast(4)}" else protoNetSec(p)
        }
    }
    return result
}

/** Компактная ячейка скорости (число: ≥10 → целое, <10 → 1 знак; единица «Мб/с» — в шапке). */
private fun speedCell(mbps: Double?): String = when {
    mbps == null -> "—"
    mbps <= 0 -> "✗"
    mbps >= 10 -> mbps.roundToInt().toString()
    else -> ((mbps * 10).roundToInt() / 10.0).toString()
}

/** Цвет скорости по порогам (Mbps): >5 зелёный, 1–5 оранжевый, <1 тёмно-оранжевый, ≤0 красный. */
private fun speedColor(mbps: Double?): Color = when {
    mbps == null -> Color(0xFF9E9E9E)  // серый — не тестирован
    mbps <= 0 -> Color(0xFFD32F2F)     // красный — нет throughput/ошибка
    mbps < 1 -> Color(0xFFBF360C)      // тёмно-оранжевый
    mbps < 5 -> Color(0xFFF57C00)      // оранжевый
    else -> Color(0xFF2E7D32)          // зелёный
}

// Автозапуск выполняем РОВНО ОДИН РАЗ за процесс, а не на каждую рекомпозицию/пересоздание Activity
// при переключении вкладок. Флаг уровня процесса переживает и то, и другое (сбрасывается только при
// перезапуске приложения — тогда автозапуск повторится, что и требуется).
private var autoStartDone = false

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    val proxy by ProxyState.state.collectAsState()
    val settings by SettingsStore.state.collectAsState()   // живое применение порогов в UI
    val blocklist by BlocklistStore.state.collectAsState() // стоп-лист: пересчёт списков при изменении

    var subStatus by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(SubscriptionManager.allServers(context)) }
    var sources by remember { mutableStateOf(SubscriptionManager.sources(context)) }
    var refreshingSubs by remember { mutableStateOf(false) }
    var subRefreshCancel by remember { mutableStateOf(false) }
    var pendingDelete by remember { mutableStateOf<SubSource?>(null) }
    var renameSource by remember { mutableStateOf<SubSource?>(null) }
    var renameProfile by remember { mutableStateOf<ServerProfile?>(null) }   // диалог переименования сервера
    // Раскрытие сворачиваемых секций главной — на уровне экрана (стабильно, переживает переключение вкладок).
    var allServersExpanded by rememberSaveable { mutableStateOf(false) }
    var subscriptionsExpanded by rememberSaveable { mutableStateOf(false) }

    var pingResults by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    var speedResults by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }

    var fullTesting by remember { mutableStateOf(false) }  // меняется только на старт/стоп (не на тик)
    var fullHandle by remember { mutableStateOf<FullTestRunner.Handle?>(null) }

    // Внешний IP через активный туннель. "" = не запрашивался, "…" = идёт запрос, ip, "нет ответа".
    var externalIp by remember { mutableStateOf("") }

    // Диалог деталей сервера (долгое нажатие) + отладочный «Перемерить».
    var detailProfile by remember { mutableStateOf<ServerProfile?>(null) }
    var remeasureStatus by remember { mutableStateOf("") }
    var remeasuring by remember { mutableStateOf(false) }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun reloadServers() { servers = SubscriptionManager.allServers(context) }
    fun reloadSources() { sources = SubscriptionManager.sources(context) }

    fun effPing(p: ServerProfile): Int? = pingResults[SubscriptionManager.serverKey(p)] ?: p.pingMs
    fun effSpeed(p: ServerProfile): Double? = speedResults[SubscriptionManager.serverKey(p)] ?: p.speedMbps

    fun startServer(p: ServerProfile) {
        val cfg = runCatching { XrayConfigBuilder.build(p) }.getOrElse {
            subStatus = "ошибка конфига: ${it.message}"
            return
        }
        ensureNotifPermission()
        // Нотификация сервиса показывает label → передаём displayName (учитывает переименование).
        XrayProxyService.start(context, cfg, serverLabel(p, blocklist), SubscriptionManager.serverKey(p))
    }

    fun onStop() { XrayProxyService.stop(context) }

    // Внешний IP ЧЕРЕЗ активный SOCKS — реальная живость туннеля (не просто «сокет слушает»).
    fun refreshIp() {
        if (!ProxyState.state.value.running) return
        externalIp = "…"
        Thread {
            val ip = ExternalIpChecker.fetch()
            activity.runOnUiThread { externalIp = ip ?: "нет ответа" }
        }.start()
    }

    // Отладка расхождения скорости: перемерить ОДИН сервер temp-инстансом, без параллели, с полным логом.
    fun onRemeasure(p: ServerProfile) {
        if (remeasuring) return
        remeasuring = true
        remeasureStatus = "меряю… (лог: тег ServerSpeedTester)"
        val key = SubscriptionManager.serverKey(p)
        Thread {
            val m = ServerSpeedTester.measureSpeedDetailed(context, p)
            SubscriptionManager.applySpeedResults(context, mapOf(key to m.mbps))   // -1 при провале
            activity.runOnUiThread {
                speedResults = speedResults + (key to m.mbps)
                remeasureStatus = if (m.ok) "результат: ${m.mbps} Мбит/с"
                                  else "НЕ ИЗМЕРЕНО — ${m.reason}"
                remeasuring = false
                reloadServers()
            }
        }.start()
    }

    // Обновить ВСЕ включённые источники. Одна упавшая не роняет прочие. Отменяемо, не блокирует UI.
    // onComplete вызывается на main-потоке ПОСЛЕ обновления и reloadServers() (нужно автозапуску, чтобы
    // тест шёл по СВЕЖЕМУ списку серверов). НЕ вызывается при раннем выходе (нет URL / уже идёт).
    fun onRefreshAll(onComplete: (() -> Unit)? = null) {
        if (refreshingSubs) return
        val hasUrl = sources.any { it.enabled && it.url.isNotBlank() }
        if (!hasUrl) { subStatus = "нет включённых подписок с URL"; return }
        refreshingSubs = true; subRefreshCancel = false
        TestProgress.startIndeterminate("обновление подписок…")
        Thread {
            val res = SubscriptionManager.refreshAllEnabled(
                context,
                cancelled = { subRefreshCancel },
                onEach = { src, sum ->
                    activity.runOnUiThread {
                        TestProgress.phase("${src.name}: " + if (sum.ok) "+${sum.added}" else "ошибка")
                        reloadSources()
                    }
                },
            )
            val okN = res.values.count { it.ok }; val failN = res.values.count { !it.ok }
            activity.runOnUiThread {
                refreshingSubs = false
                // Одна статус-строка (в баре) — вторую (subStatus) НЕ дублируем. Детали ошибок — в секции подписок.
                TestProgress.finish(if (subRefreshCancel) "обновление прервано" else "подписки обновлены: $okN ок, $failN ошибок")
                reloadSources(); reloadServers()
                if (!subRefreshCancel) onComplete?.invoke()   // цепочка автозапуска: тест по свежему списку
            }
        }.start()
    }

    fun onAddUrl(url: String, name: String) {
        if (url.isBlank()) { subStatus = "введите URL"; return }
        Thread {
            val id = SubscriptionManager.addUrl(context, url, name)
            if (id == null) { activity.runOnUiThread { subStatus = "дубликат URL или пусто"; reloadSources() }; return@Thread }
            val s = SubscriptionManager.refreshOne(context, id)
            activity.runOnUiThread {
                subStatus = if (s.ok) "добавлено: +${s.added}" else "источник добавлен, обновление: ${s.error}"
                reloadSources(); reloadServers()
            }
        }.start()
    }

    fun onAddPaste(text: String) {
        if (text.isBlank()) { subStatus = "вставьте ссылки"; return }
        Thread {
            val (_, s) = SubscriptionManager.addLocalFromBody(context, text)
            activity.runOnUiThread {
                subStatus = "вставка: +${s.added}  дубли=${s.duplicates}  неподдерж=${s.unsupported}  ошибок=${s.invalid}"
                reloadSources(); reloadServers()
            }
        }.start()
    }

    fun onImportFile(uri: android.net.Uri) {
        Thread {
            val body = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (body.isBlank()) { activity.runOnUiThread { subStatus = "файл пуст/не прочитан" }; return@Thread }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
            val (_, s) = SubscriptionManager.addLocalFromBody(context, body, name)
            activity.runOnUiThread {
                subStatus = "из файла: +${s.added}  дубли=${s.duplicates}  неподдерж=${s.unsupported}  ошибок=${s.invalid}"
                reloadSources(); reloadServers()
            }
        }.start()
    }

    fun onToggleSource(id: String, enabled: Boolean) {
        SubscriptionManager.setEnabled(context, id, enabled)
        reloadSources(); reloadServers()
    }

    fun onDeleteSource(src: SubSource) {
        SubscriptionManager.remove(context, src.id)
        pendingDelete = null
        reloadSources(); reloadServers()
    }

    fun onRenameSource(id: String, name: String) {
        SubscriptionManager.rename(context, id, name)
        renameSource = null
        reloadSources()
    }

    // Пикер файла (объявлен после onImportFile — локальные функции без forward-reference).
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> if (uri != null) onImportFile(uri) }

    // Полный адаптивный тест: ping → speed по живым → early-connect первого рабочего → апгрейд.
    fun onFullTest() {
        if (fullTesting) return
        val all = servers
        if (all.isEmpty()) { subStatus = "нет серверов"; return }
        fullTesting = true
        MonitorCoordinator.fullTestRunning = true   // монитор молчит, пока идёт ручной тест
        TestProgress.startIndeterminate("запуск…")   // до первого done/total — indeterminate
        pingResults = emptyMap(); speedResults = emptyMap()

        fullHandle = FullTestRunner.run(
            context = context,
            allServers = all,
            // Прогресс и текст фазы — в ОТДЕЛЬНЫЙ StateFlow (не в state экрана): тик не рекомпозит список.
            onPhase = { ph -> TestProgress.phase(ph) },
            emitProgress = { done, total -> TestProgress.progress(done, total) },
            onPingResult = { p, ms ->
                activity.runOnUiThread { pingResults = pingResults + (SubscriptionManager.serverKey(p) to ms) }
            },
            onSpeedResult = { p, mbps ->
                activity.runOnUiThread { speedResults = speedResults + (SubscriptionManager.serverKey(p) to mbps) }
            },
            connect = { p -> activity.runOnUiThread { startServer(p) } },
            onDone = { r ->
                activity.runOnUiThread {
                    val pSnap = pingResults; val sSnap = speedResults        // снимок state на main
                    Thread {
                        SubscriptionManager.applyPingResults(context, pSnap) // персист пинга
                        SubscriptionManager.applySpeedResults(context, sSnap) // персист скорости
                        activity.runOnUiThread {
                            fullTesting = false; fullHandle = null
                            MonitorCoordinator.fullTestRunning = false   // тест закончился — монитор снова может работать
                            TestProgress.finish(                             // бар гаснет, итог остаётся текстом
                                if (r.cancelled) "отменено"
                                else "готово: быстрейший ${r.fastest?.remarks?.ifBlank { r.fastest.address } ?: "—"} ${r.fastestMbps} Мбит/с"
                            )
                            reloadServers()
                        }
                    }.start()
                }
            },
        )
    }

    fun onCancelFull() {
        fullHandle?.cancel(); fullHandle = null
        fullTesting = false
        MonitorCoordinator.fullTestRunning = false   // отмена теста — монитор снова может работать
        TestProgress.finish("отменено")
        val pSnap = pingResults; val sSnap = speedResults
        Thread {
            SubscriptionManager.applyPingResults(context, pSnap)
            SubscriptionManager.applySpeedResults(context, sSnap)
            activity.runOnUiThread { reloadServers() }
        }.start()
    }

    // Отсевы (единый предикат [ServerFilter]) — скрывают из ВИДИМОЙ части (Живые и Все).
    // Замер идёт по всем НЕзаблокированным; скрытые считаем, чтобы показать «скрыто настройками: N».
    // Заблокированных убираем ПЕРВЫМИ — они не в списках, не в выборе, не мерятся.
    val notBlocked = servers.filter { !ServerFilter.isBlocked(it, blocklist) }
    val allowedServers = notBlocked.filter { ServerFilter.protocolAllowed(it, settings) }
    val hiddenCount = servers.size - allowedServers.size   // скрыто протоколом + стоп-листом

    // Сортировка как Termux sort_servers_by_speed: скорость>0 (убыв.) → живые по пингу (возр.) → остальные.
    val shown = allowedServers.sortedWith(Comparator { a, b ->
        fun rank(p: ServerProfile): Int {
            val s = effSpeed(p) ?: 0.0
            val pg = effPing(p) ?: -1
            return if (s > 0) 0 else if (pg >= 0) 1 else 2
        }
        val ra = rank(a); val rb = rank(b)
        if (ra != rb) ra - rb
        else when (ra) {
            0 -> effSpeed(b)!!.compareTo(effSpeed(a)!!)   // скорость убыв.
            1 -> effPing(a)!!.compareTo(effPing(b)!!)     // пинг возр.
            else -> 0
        }
    })

    // Активный сервер — по serverKey (полная идентичность), НЕ по label: несколько профилей
    // делят одинаковые remarks → сравнение по имени подсвечивало сразу две строки.
    val activeServer = servers.firstOrNull {
        proxy.running && proxy.serverKey != null && SubscriptionManager.serverKey(it) == proxy.serverKey
    }

    // Дискриминаторы для одинаковых имён (serverKey → суффикс).
    val discriminators = remember(servers, blocklist) { buildDiscriminators(servers, blocklist) }

    // Внешний IP запрашиваем при появлении подключения / смене активного сервера (даём туннелю осесть).
    LaunchedEffect(proxy.running, proxy.serverKey) {
        if (proxy.running) {
            kotlinx.coroutines.delay(800)
            refreshIp()
        } else {
            externalIp = ""
        }
    }
    val ipVerified = externalIp.isNotEmpty() && externalIp != "…" && externalIp != "нет ответа"

    // Автозапуск при старте (по умолчанию, отключаемо в Настройках). Один раз за процесс. Два шага:
    //  1) МГНОВЕННАЯ СВЯЗЬ — сразу подключиться к последнему успешному серверу (если он ещё в списке),
    //     чтобы интернет был, пока идёт тест.
    //  2) ФОНОМ — обновить подписки → полный тест → подключиться к быстрейшему (ровно как нажатие
    //     кнопки «▶ Самый быстрый»; early-connect/апгрейд теста перекроют соединение из шага 1).
    LaunchedEffect(Unit) {
        if (autoStartDone || !SettingsStore.current().autoStartOnLaunch) return@LaunchedEffect
        autoStartDone = true
        if (!proxy.running) {
            val lastKey = LastServerStore.load(context)
            val last = lastKey?.let { k -> servers.firstOrNull { SubscriptionManager.serverKey(it) == k } }
            if (last != null) startServer(last)
        }
        val hasSubs = sources.any { it.enabled && it.url.isNotBlank() }
        if (hasSubs) onRefreshAll(onComplete = { onFullTest() }) else onFullTest()
    }

    // Основной вид = ЖИВЫЕ — через единый предикат [ServerFilter.isVisible] (стоп-лист+протокол+пинг+мин.скорость).
    val alive = shown.filter { ServerFilter.isVisible(it, effPing(it), effSpeed(it), settings, blocklist) }

    // Активный сервер скрыт настройками (протокол выключен)? Соединение НЕ рвём — только пометка в статусе.
    val activeHidden = activeServer != null && !ServerFilter.protocolAllowed(activeServer, settings)
    // Активный сервер попал под стоп-лист — соединение НЕ рвём молча, помечаем и предлагаем переключиться.
    val activeBlocked = activeServer != null && ServerFilter.isBlocked(activeServer, blocklist)

    // Весь экран — одна прокручиваемая лента (как веб-морда): шапка → статус → действия →
    // список серверов → сворачиваемые секции. Единый LazyColumn, чтобы раскрытые секции
    // прокручивались вместе со всем холстом (не упирались в фикс. высоту списка).
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ═══ 1. ШАПКА + СТАТУС-БОКС ═══
        item {
            AppHeader()
        }
        item {
            StatusBox(
                running = proxy.running,
                verified = ipVerified,
                ipText = externalIp,
                onRefreshIp = { refreshIp() },
                serverName = activeServer?.let { displayName(it, blocklist) } ?: proxy.label,
                subtitle = activeServer?.let { protoNetSec(it) },
                speedMbps = activeServer?.let { effSpeed(it) },
                hidden = activeHidden,
                blocked = activeBlocked,
                message = proxy.message,
            )
        }

        // ═══ 2. ДЕЙСТВИЯ ═══
        item {
            ActionsBar(
                fullTesting = fullTesting,
                running = proxy.running,
                refreshingSubs = refreshingSubs,
                onFullTest = { onFullTest() },
                onCancelFull = { onCancelFull() },
                onStop = { onStop() },
                onRefreshSubs = { onRefreshAll() },
                onCancelRefreshSubs = { subRefreshCancel = true },
            )
        }
        // Прогресс-бар Полного теста — сразу под кнопками, над «Живые серверы». Свой item со
        // стабильным ключом → структура списка не меняется; collectAsState внутри бара → на тик
        // рекомпозится только бар, не строки.
        item(key = "full-test-progress") { FullTestProgressBar() }
        if (subStatus.isNotEmpty()) {
            item { Text(subStatus, style = MaterialTheme.typography.bodySmall) }
        }

        // ═══ 3. ЖИВЫЕ СЕРВЕРЫ (основной вид — плотная таблица) ═══
        item {
            Text(
                "Живые серверы (${alive.size})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        // Чтобы серверы не пропадали молча — сколько скрыто (протокол + стоп-лист).
        if (hiddenCount > 0) {
            item {
                Text(
                    "скрыто настройками: $hiddenCount",
                    style = MaterialTheme.typography.bodySmall,
                    color = TABLE_GRAY,
                )
            }
        }
        if (settings.allowedProtocols.isEmpty() && servers.isNotEmpty()) {
            item {
                Text(
                    "Все протоколы отключены в Настройках → Протоколы.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD32F2F),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }
        item { ServerTableHeader() }
        items(alive) { p ->
            val isActive = proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p)
            ServerRow(
                profile = p,
                name = displayName(p, blocklist),
                isActive = isActive,
                speedMbps = effSpeed(p),
                caption = discriminators[SubscriptionManager.serverKey(p)] ?: "",
                onConnect = { startServer(p) },
                onDetails = { detailProfile = p; remeasureStatus = "" },
            )
        }
        if (alive.isEmpty()) {
            item {
                Text(
                    if (servers.isEmpty()) "Список пуст — обновите подписку."
                    else "Запусти тест (🔍)",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF9E9E9E),
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
        }

        // ═══ 4. СВОРАЧИВАЕМЫЕ СЕКЦИИ (порядок как эталон) ═══
        // Все серверы (вкл. мёртвые ✗ и не тестированные —) — свёрнут, не мозолит глаза.
        item {
            CollapsibleSection("Все серверы (${shown.size})", allServersExpanded, { allServersExpanded = !allServersExpanded }) {
                ServerTableHeader()
                shown.forEach { p ->
                    val isActive = proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p)
                    ServerRow(
                        profile = p,
                        name = displayName(p, blocklist),
                        isActive = isActive,
                        speedMbps = effSpeed(p),
                        caption = discriminators[SubscriptionManager.serverKey(p)] ?: "",
                        onConnect = { startServer(p) },
                        onDetails = { detailProfile = p; remeasureStatus = "" },
                    )
                }
            }
        }
        // Подписки — рабочее действие, остаётся на главной (список источников/добавление/вставка/файл).
        item {
            CollapsibleSection("Подписки (${sources.size})", subscriptionsExpanded, { subscriptionsExpanded = !subscriptionsExpanded }) {
                SubscriptionsSection(
                    sources = sources,
                    onAddUrl = { url, name -> onAddUrl(url, name) },
                    onAddPaste = { onAddPaste(it) },
                    onImportFile = { filePickerLauncher.launch("*/*") },
                    onToggle = { id, en -> onToggleSource(id, en) },
                    onDeleteRequest = { pendingDelete = it },
                    onRenameRequest = { renameSource = it },
                )
            }
        }
        // Настройки / Стоп-лист / Автомониторинг / Трафик переехали во вкладку «Настройки».

        // ═══ ФУТЕР ═══
        item {
            Text(
                "pico-soft/XrayProxyDroid · v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9E9E9E),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }

    // Диалог деталей сервера (долгое нажатие) + отладочный «Перемерить» + блокировка + переименование.
    detailProfile?.let { p ->
        val pKey = SubscriptionManager.serverKey(p)
        ServerDetailDialog(
            profile = p,
            name = displayName(p, blocklist),
            originalName = providerName(p),
            hasCustomName = blocklist.customName(pKey) != null,
            remeasureStatus = remeasureStatus,
            remeasuring = remeasuring,
            blockedByKey = blocklist.isServerBlocked(pKey),
            blockedByWord = blocklist.matchesWord(providerName(p), blocklist.customName(pKey)),
            onToggleBlock = {
                if (blocklist.isServerBlocked(pKey)) BlocklistStore.unblockServer(context, pKey)
                else BlocklistStore.blockServer(context, pKey, displayName(p, blocklist), System.currentTimeMillis())
                detailProfile = null; remeasureStatus = ""
            },
            onRename = { renameProfile = p; detailProfile = null; remeasureStatus = "" },
            onResetName = { BlocklistStore.clearName(context, pKey); detailProfile = null; remeasureStatus = "" },
            onRemeasure = { onRemeasure(p) },
            onDismiss = { detailProfile = null; remeasureStatus = "" },
        )
    }

    // Переименование сервера: поле с текущим именем + опция «применить ко всем с таким же исходным именем».
    renameProfile?.let { p ->
        val pKey = SubscriptionManager.serverKey(p)
        val original = providerName(p)
        val sameOriginal = servers.filter { providerName(it) == original }
        RenameServerDialog(
            currentName = displayName(p, blocklist),
            originalName = original,
            sameNameCount = sameOriginal.size,
            onSave = { newName, applyAll ->
                val targets = if (applyAll) sameOriginal else listOf(p)
                val keysWithOriginal = targets.map { SubscriptionManager.serverKey(it) to providerName(it) }
                BlocklistStore.rename(context, keysWithOriginal, newName, System.currentTimeMillis())
                renameProfile = null
            },
            onDismiss = { renameProfile = null },
        )
    }

    // Переименование источника: имя + URL целиком.
    renameSource?.let { src ->
        RenameSourceDialog(
            source = src,
            onSave = { onRenameSource(src.id, it) },
            onDismiss = { renameSource = null },
        )
    }

    // Подтверждение удаления источника — с числом серверов, которые ИСЧЕЗНУТ (нет в других источниках).
    pendingDelete?.let { src ->
        val lost = remember(src) { SubscriptionManager.serversLostOnRemove(context, src.id) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить источник?") },
            text = { Text("«${src.name}»\nИсчезнет серверов: $lost (только те, которых нет в других подписках).") },
            confirmButton = { TextButton(onClick = { onDeleteSource(src) }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

/** Шапка: имя приложения + место под будущий подзаголовок. */
@Composable
private fun AppHeader() {
    Column {
        Text(
            "XrayProxyDroid",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

/**
 * Прогресс-бар Полного теста. Тонкая линия (3dp) primary-цветом, трек — primary alpha 0.12.
 * Determinate по done/total фазы (сбрасывается между пингом и скоростью), indeterminate пока total нет.
 * collectAsState — ТОЛЬКО здесь: на тик рекомпозится лишь бар. Появление/исчезновение анимировано.
 * cappedDensity НЕ применяем (бар в dp). Под баром — мелкий серый текст фазы (без процентов-цифр).
 */
@Composable
private fun FullTestProgressBar() {
    val p by TestProgress.state.collectAsState()
    // Значение ползёт, а не прыгает.
    val fraction by animateFloatAsState(targetValue = p.fraction, label = "fullTestProgress")
    val barColor = MaterialTheme.colorScheme.primary
    val trackColor = barColor.copy(alpha = 0.12f)

    Column(modifier = Modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = p.active,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically(),
        ) {
            if (p.indeterminate) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = barColor,
                    trackColor = trackColor,
                    strokeCap = StrokeCap.Round,
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = barColor,
                    trackColor = trackColor,
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,             // без разрыва у бегунка
                    drawStopIndicator = {},     // без точки-стопа — просто линия-доля
                )
            }
        }
        // Текст фазы под баром — мелкий серый; бар = доля, текст = что происходит (без дублей).
        if (p.phase.isNotEmpty()) {
            Text(
                p.phase,
                style = MaterialTheme.typography.bodySmall,
                color = TABLE_GRAY,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

/**
 * Статус-бокс: к чему подключены + активен/выключен + мелко протокол·network·security + внешний IP.
 * ВАЖНО про цвет: зелёный — ТОЛЬКО когда IP получен (туннель реально гоняет трафик). Пока IP нет —
 * янтарный (подключаемся/не подтверждено). Тап по строке IP — ручное обновление.
 */
@Composable
private fun StatusBox(
    running: Boolean,
    verified: Boolean,
    ipText: String,
    onRefreshIp: () -> Unit,
    serverName: String?,
    subtitle: String?,
    speedMbps: Double?,
    hidden: Boolean,
    blocked: Boolean,
    message: String,
) {
    val bg = when {
        running && verified -> Color(0xFF1B5E20)  // зелёный — туннель подтверждён (есть IP)
        running -> Color(0xFF6D4C00)              // янтарный — подключаемся/не подтверждено
        else -> Color(0xFF3A3A3A)                 // серый — выключен
    }
    val fg = when {
        running && verified -> Color(0xFFA5D6A7)
        running -> Color(0xFFFFE082)
        else -> Color(0xFFBDBDBD)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        val speedSuffix = if (running && speedMbps != null && speedMbps > 0)
            "  (${(speedMbps * 10).roundToInt() / 10.0} Мб/с)" else ""
        Text(
            if (running) "● ${serverName ?: "Активен"}$speedSuffix" else "○ Не запущен",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
        if (running) {
            // Активный сервер попал под стоп-лист — туннель не рвём молча, помечаем и предлагаем переключиться.
            if (blocked) {
                Text(
                    "🚫 активный сервер в стоп-листе — нажмите «▶ Самый быстрый»",
                    style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Bold,
                )
            }
            // Активный сервер скрыт фильтром протоколов — туннель не рвём, но помечаем.
            if (hidden) {
                Text("⚠ протокол скрыт настройками", style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Bold)
            }
            // Мелко: протокол · network · security активного сервера (вместо портов).
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            val ipDisplay = if (ipText.isEmpty()) "…" else ipText
            // Тап по строке IP → ручное обновление. IP = индикатор реальной живости туннеля.
            Text(
                "IP: $ipDisplay",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = fg,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable { onRefreshIp() }
                    .padding(vertical = 2.dp),
            )
        }
        if (message.isNotEmpty() && message != "idle") {
            Text(message, style = MaterialTheme.typography.bodySmall, color = fg)
        }
    }
}

/** Панель действий. Кнопки крупные; FlowRow переносит их при крупных системных шрифтах. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsBar(
    fullTesting: Boolean,
    running: Boolean,
    refreshingSubs: Boolean,
    onFullTest: () -> Unit,
    onCancelFull: () -> Unit,
    onStop: () -> Unit,
    onRefreshSubs: () -> Unit,
    onCancelRefreshSubs: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fullTesting) {
            Button(onClick = onCancelFull) { Text("Прервать") }
        } else {
            Button(onClick = onFullTest) { Text("▶ Самый быстрый") }
        }
        OutlinedButton(onClick = onStop, enabled = running) { Text("■ Стоп") }
        if (refreshingSubs) {
            OutlinedButton(onClick = onCancelRefreshSubs) { Text("⏹ Обновление") }
        } else {
            OutlinedButton(onClick = onRefreshSubs) { Text("↻ Подписки") }
        }
    }
}

// Ширины колонок таблицы серверов — общие для шапки и строк (чтобы колонки совпадали).
// Колонки: Сервер | Мб/с | ▶ (#, Пров., Пинг убраны по решению — на телефоне зажимали имя).
private val COL_SPEED = 58.dp
private val COL_BTN = 28.dp
private val TABLE_FONT = 14.sp     // базовый шрифт таблицы (плотный)
private val TABLE_FONT_SUB = 12.sp // шрифт шапки
private val TABLE_GRAY = Color(0xFF9E9E9E)

/**
 * Плотность с ОГРАНИЧЕННЫМ масштабом шрифта — чтобы при крупных системных шрифтах Elyor
 * таблица серверов не растягивалась в высоту и оставалась плотной (влезает 10+).
 */
@Composable
private fun cappedDensity(): Density {
    val d = LocalDensity.current
    return Density(d.density, d.fontScale.coerceAtMost(1.15f))
}

/** Шапка таблицы серверов: Сервер | Мб/с | (кнопка). Колонки как у строк. */
@Composable
private fun ServerTableHeader() {
    CompositionLocalProvider(LocalDensity provides cappedDensity()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Сервер", modifier = Modifier.weight(1f), fontSize = TABLE_FONT_SUB, color = TABLE_GRAY, maxLines = 1)
            Text("Мб/с", modifier = Modifier.width(COL_SPEED), fontSize = TABLE_FONT_SUB, color = TABLE_GRAY, textAlign = TextAlign.End, maxLines = 1)
            Spacer(Modifier.width(COL_BTN))
        }
    }
}

/**
 * Одна строка-таблица: [●/полное имя + мелко caption] [скорость] [▶/● запуск].
 * Имя ВСЕГДА полное (не обрезается); длинное — переносится, подпись caption уходит ниже.
 * ТАП по строке = детали (диалог). ТАП по ▶ = запустить сервер (единственное «нажатие запуска»).
 * Шрифт ограничен по масштабу (cappedDensity), чтобы строки не разъезжались при крупном системном.
 */
@Composable
private fun ServerRow(
    profile: ServerProfile,
    name: String,
    isActive: Boolean,
    speedMbps: Double?,
    caption: String,
    onConnect: () -> Unit,
    onDetails: () -> Unit,
) {
    CompositionLocalProvider(LocalDensity provides cappedDensity()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .then(
                    if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                    else Modifier
                )
                .clickable { onDetails() }   // тап по строке = детали
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Имя полностью (перенос при длинном) + мелко caption под ним.
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    (if (isActive) "● " else "") + name,
                    fontSize = TABLE_FONT,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                )
                if (caption.isNotEmpty()) {
                    Text(caption, fontSize = TABLE_FONT_SUB, color = TABLE_GRAY)
                }
            }
            Text(
                speedCell(speedMbps),
                modifier = Modifier.width(COL_SPEED),
                fontSize = TABLE_FONT,
                fontWeight = FontWeight.Bold,
                color = speedColor(speedMbps),
                textAlign = TextAlign.End,
                maxLines = 1,
            )
            // ▶ запуск сервера — ЕДИНСТВЕННЫЙ элемент, по которому тап подключает.
            Text(
                if (isActive) "●" else "▶",
                fontSize = TABLE_FONT,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onConnect() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/** Сворачиваемая секция (аналог <details> веб-морды). Заголовок-строка с ▸/▾.
 *  STATELESS: состояние раскрытия хоистится в экран (rememberSaveable на верхнем уровне
 *  BootScreen/SettingsTab, НЕ внутри item LazyColumn — иначе позиционное scoping путается).
 *  В паре с SaveableStateProvider(tab) переживает уход на другую вкладку и обратно. */
@Composable
private fun CollapsibleSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { onToggle() }
                .padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(if (expanded) "▾" else "▸", style = MaterialTheme.typography.titleMedium)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (expanded) {
            Column(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                content()
            }
        }
    }
}

/** Текст-заглушка для пустых секций (функционал добавим фичами позже). */
@Composable
private fun StubText() {
    Text(
        "Появится позже.",
        style = MaterialTheme.typography.bodyMedium,
        color = Color(0xFF9E9E9E),
    )
}

/** Диалог полных деталей сервера (долгое нажатие) + отладочный «Перемерить этот сервер». */
@Composable
private fun ServerDetailDialog(
    profile: ServerProfile,
    name: String,
    originalName: String,
    hasCustomName: Boolean,
    remeasureStatus: String,
    remeasuring: Boolean,
    blockedByKey: Boolean,
    blockedByWord: Boolean,
    onToggleBlock: () -> Unit,
    onRename: () -> Unit,
    onResetName: () -> Unit,
    onRemeasure: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun v(s: String?) = s?.ifBlank { "—" } ?: "—"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // «Имя от сервера» — ВСЕГДА и НЕИЗМЕННО, даже когда задано своё (требование Elyor).
                DetailRow("Имя от сервера", originalName)
                DetailRow("Протокол", profile.protocol.name)
                DetailRow("Адрес", "${profile.address}:${profile.port}")
                DetailRow("Транспорт", profile.network)
                DetailRow("Безопасность", profile.security)
                DetailRow("SNI", v(profile.sni))
                DetailRow("Fingerprint", v(profile.fingerprint))
                DetailRow("Flow", v(profile.flow))
                if (remeasureStatus.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        remeasureStatus,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(8.dp))
                // Переименование (оверрайд по serverKey; remarks провайдера не трогаем).
                OutlinedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    Text("✎ Переименовать")
                }
                if (hasCustomName) {
                    TextButton(onClick = onResetName, modifier = Modifier.fillMaxWidth()) {
                        Text("Вернуть имя от сервера")
                    }
                }
                // Точечная блокировка ИМЕННО этого serverKey (не всех одноимённых).
                OutlinedButton(onClick = onToggleBlock, modifier = Modifier.fillMaxWidth()) {
                    Text(if (blockedByKey) "Убрать из стоп-листа" else "🚫 В стоп-лист")
                }
                // Если сервер УЖЕ скрыт правилом-словом — точечное снятие не поможет, объясняем куда идти.
                if (blockedByWord && !blockedByKey) {
                    Text(
                        "Скрыт правилом-словом стоп-листа. Снять — Настройки → Стоп-лист.",
                        style = MaterialTheme.typography.bodySmall,
                        color = TABLE_GRAY,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onRemeasure, enabled = !remeasuring) {
                Text("Перемерить этот сервер")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/**
 * Диалог переименования сервера. Пустое имя = сброс к имени от провайдера. При группе одноимённых
 * (>1 с тем же ИСХОДНЫМ именем) — опция «применить ко всем» (например, четыре гонконгских варианта).
 */
@Composable
private fun RenameServerDialog(
    currentName: String,
    originalName: String,
    sameNameCount: Int,
    onSave: (name: String, applyAll: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(currentName) }
    var applyAll by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать сервер") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Имя от сервера: $originalName", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(originalName) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Пустое имя = вернуть имя от сервера.", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
                if (sameNameCount > 1) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                        Text("Применить ко всем с таким же именем ($sameNameCount)", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text, applyAll) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

/** Строка «ключ: значение» в диалоге деталей. */
@Composable
private fun DetailRow(key: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("$key:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

// ═══════════════════════ НАСТРОЙКИ ═══════════════════════

/**
 * Содержимое секции «⚙️ Настройки»: все пороги/таймауты из [SettingsStore] единым плотным списком.
 * Каждая строка валидирует диапазон (не даёт окно 0 / пул 0 / отрицательные); невалидное не сохраняется
 * и подсвечивается. Изменённое значение помечено дефолтом мелким серым. Внизу — сброс к дефолтам.
 */
@Composable
private fun SettingsSection(
    settings: AppSettings,
    protocolCounts: Map<Protocol, Int>,
    onChange: (AppSettings) -> Unit,
    onReset: () -> Unit,
) {
    val d = SettingsStore.DEFAULTS
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel("Протоколы")
        // Фильтр отображения+выбора (НЕ замера): выключенный протокол исчезает из списка и Авто.
        Protocol.entries.forEach { proto ->
            val on = proto in settings.allowedProtocols
            val n = protocolCounts[proto] ?: 0
            SettingRowScaffold("${proto.name} ($n серв.)", "", changed = on != (proto in d.allowedProtocols), defaultText = "вкл") {
                Switch(checked = on, onCheckedChange = { enabled ->
                    val next = if (enabled) settings.allowedProtocols + proto else settings.allowedProtocols - proto
                    onChange(settings.copy(allowedProtocols = next))
                })
            }
        }

        SettingsGroupLabel("Замер скорости")
        DoubleSettingRow("Прогрев перед замером", "с", settings.speedWarmupSec, d.speedWarmupSec, 0.0, 60.0) {
            onChange(settings.copy(speedWarmupSec = it))
        }
        DoubleSettingRow("Окно замера", "с", settings.speedWindowSec, d.speedWindowSec, 0.5, 120.0) {
            onChange(settings.copy(speedWindowSec = it))
        }
        IntSettingRow("Объём прогрева", "МБ", settings.speedWarmupMb, d.speedWarmupMb, 1, 500) {
            onChange(settings.copy(speedWarmupMb = it))
        }
        IntSettingRow("Объём замера", "МБ", settings.speedMeasureMb, d.speedMeasureMb, 1, 1000) {
            onChange(settings.copy(speedMeasureMb = it))
        }
        IntSettingRow("Одновременных замеров (пул)", "", settings.speedPool, d.speedPool, 1, 32) {
            onChange(settings.copy(speedPool = it))
        }
        UrlSettingRow("URL пробника скорости", settings.speedProbeUrl, d.speedProbeUrl) {
            onChange(settings.copy(speedProbeUrl = it))
        }

        SettingsGroupLabel("Пинг")
        IntSettingRow("Таймаут пинга", "мс", settings.pingTimeoutMs, d.pingTimeoutMs, 500, 30_000) {
            onChange(settings.copy(pingTimeoutMs = it))
        }
        IntSettingRow("Одновременных пингов (пул)", "", settings.pingPool, d.pingPool, 1, 32) {
            onChange(settings.copy(pingPool = it))
        }

        SettingsGroupLabel("Подписки")
        UrlSettingRow("User-Agent загрузки", settings.subUserAgent, d.subUserAgent, requireHttp = false) {
            onChange(settings.copy(subUserAgent = it))
        }
        IntSettingRow("Таймаут загрузки", "с", settings.subTimeoutSec, d.subTimeoutSec, 3, 120) {
            onChange(settings.copy(subTimeoutSec = it))
        }

        SettingsGroupLabel("Выбор сервера")
        DoubleSettingRow("Минимальная полезная скорость", "Мбит/с", settings.minUsableMbps, d.minUsableMbps, 0.0, 100.0) {
            onChange(settings.copy(minUsableMbps = it))
        }
        IntSettingRow("Запас для апгрейда", "%", settings.upgradeMarginPercent, d.upgradeMarginPercent, 0, 100) {
            onChange(settings.copy(upgradeMarginPercent = it))
        }

        SettingsGroupLabel("Прочее")
        BoolSettingRow("Автозапуск при старте", settings.autoStartOnLaunch, d.autoStartOnLaunch) {
            onChange(settings.copy(autoStartOnLaunch = it))
        }
        BoolSettingRow("Подробные логи", settings.verboseLogs, d.verboseLogs) {
            onChange(settings.copy(verboseLogs = it))
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Сбросить всё к дефолтам")
        }
    }
}

/**
 * Секция «🚫 Стоп-лист» — два подраздела:
 *  1) правила-слова: чипы «слово (N) ✗»; серый чип = никого не блокирует (N=0), вероятно опечатка;
 *  2) персонально заблокированные серверы: имя + «Разблокировать» — без него точечную блокировку не снять.
 * Оба типа применяются через единый предикат [ServerFilter]; здесь только управление списком.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlocklistSection(
    blocklist: Blocklist,
    serverNames: List<Pair<String, String?>>,   // (исходное, пользовательское) — счётчик учитывает оба
    onAddWord: (String) -> Unit,
    onRemoveWord: (String) -> Unit,
    onUnblockServer: (String) -> Unit,
) {
    var input by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel("Правила-слова (по имени сервера)")
        Text(
            "Слово (часть имени) скрывает совпавшие серверы. Регистр не важен.",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
        if (blocklist.words.isEmpty()) {
            Text("Правил нет — добавьте слово ниже.", style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                blocklist.words.forEach { w ->
                    WordChip(word = w, count = blocklist.countForWord(w, serverNames), onRemove = { onRemoveWord(w) })
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                singleLine = true,
                placeholder = { Text("Слово") },
                modifier = Modifier.weight(1f),
            )
            Button(onClick = { onAddWord(input); input = "" }, enabled = input.isNotBlank()) { Text("+") }
        }

        Spacer(Modifier.height(4.dp))
        SettingsGroupLabel("Персонально заблокированные")
        if (blocklist.servers.isEmpty()) {
            Text(
                "Пусто. Заблокировать конкретный сервер: тап по строке → «🚫 В стоп-лист».",
                style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY,
            )
        } else {
            blocklist.servers.forEach { s ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.name.ifBlank { s.serverKey },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    TextButton(onClick = { onUnblockServer(s.serverKey) }) { Text("Разблокировать") }
                }
            }
        }
    }
}

/** Чип правила-слова: «слово (N) ✗». Серый, если N==0 (никого не блокирует — вероятно опечатка). */
@Composable
private fun WordChip(word: String, count: Int, onRemove: () -> Unit) {
    val useless = count == 0
    val bg = if (useless) Color(0xFF2A2A2A) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (useless) TABLE_GRAY else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(bg)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("$word ($count)", style = MaterialTheme.typography.bodySmall, color = fg)
        Text(
            "✗",
            color = Color(0xFFEF5350),
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { onRemove() }.padding(horizontal = 4.dp),
        )
    }
}

// Формат времени для журнала монитора (только main-поток композиции → один экземпляр безопасен).
private val monitorTimeFmt = SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault())

/**
 * Секция «🛡️ Автомониторинг» — этап 1: наблюдение + журнал, БЕЗ переключений.
 * Настройки (через SettingsStore) + журнал последних событий (свежие сверху).
 */
@Composable
private fun MonitorSection(
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    log: List<MonitorEvent>,
    onClearLog: () -> Unit,
) {
    val d = SettingsStore.DEFAULTS
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Этап 1: монитор только НАБЛЮДАЕТ и пишет журнал (что сделал бы). Серверы НЕ переключает.",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
        BoolSettingRow("Автомониторинг", settings.monitorEnabled, d.monitorEnabled) {
            onChange(settings.copy(monitorEnabled = it))
        }
        IntSettingRow("Интервал цикла", "с", settings.monitorIntervalSec, d.monitorIntervalSec, 60, 3600) {
            onChange(settings.copy(monitorIntervalSec = it))
        }
        DoubleSettingRow("Порог прямого канала", "Мбит/с", settings.monitorDirectThreshold, d.monitorDirectThreshold, 0.1, 100.0) {
            onChange(settings.copy(monitorDirectThreshold = it))
        }
        DoubleSettingRow("Порог туннеля", "Мбит/с", settings.monitorTunnelThreshold, d.monitorTunnelThreshold, 0.1, 100.0) {
            onChange(settings.copy(monitorTunnelThreshold = it))
        }
        IntSettingRow("Неудач подряд = падение", "", settings.monitorFailuresToVerdict, d.monitorFailuresToVerdict, 1, 10) {
            onChange(settings.copy(monitorFailuresToVerdict = it))
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SettingsGroupLabel("Журнал (${log.size})")
            if (log.isNotEmpty()) TextButton(onClick = onClearLog) { Text("Очистить") }
        }
        if (log.isEmpty()) {
            Text(
                "Пока пусто. Включите автомониторинг — события появятся здесь.",
                style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY,
            )
        } else {
            log.asReversed().forEach { MonitorLogRow(it) }   // свежие сверху
        }
    }
}

/** Одна запись журнала: время + вердикт, под ним состояния сигналов и (если есть) гипотетическое действие. */
@Composable
private fun MonitorLogRow(e: MonitorEvent) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "${monitorTimeFmt.format(Date(e.ts))} · ${e.verdict}",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold,
        )
        Text(
            "прямой: ${e.direct} · туннель: ${e.tunnel}",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
        if (e.wouldDo.isNotEmpty()) {
            Text("→ ${e.wouldDo}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun SettingsGroupLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 6.dp),
    )
}

/** Каркас строки: подпись (+ дефолт мелким серым, если изменено) слева, контрол справа. */
@Composable
private fun SettingRowScaffold(
    label: String,
    unit: String,
    changed: Boolean,
    defaultText: String,
    control: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(if (unit.isEmpty()) label else "$label, $unit", style = MaterialTheme.typography.bodyMedium)
            if (changed) {
                Text("деф. $defaultText", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
            }
        }
        control()
    }
}

@Composable
private fun IntSettingRow(
    label: String, unit: String, value: Int, default: Int, min: Int, max: Int,
    onCommit: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.toIntOrNull()
    val valid = parsed != null && parsed in min..max
    SettingRowScaffold(label, unit, changed = value != default, defaultText = default.toString()) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.toIntOrNull()?.let { if (it in min..max) onCommit(it) }   // сохраняем только валидное
            },
            isError = !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp),
        )
    }
}

@Composable
private fun DoubleSettingRow(
    label: String, unit: String, value: Double, default: Double, min: Double, max: Double,
    onCommit: (Double) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    val parsed = text.replace(',', '.').toDoubleOrNull()
    val valid = parsed != null && parsed in min..max
    SettingRowScaffold(label, unit, changed = value != default, defaultText = default.toString()) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                t.replace(',', '.').toDoubleOrNull()?.let { if (it in min..max) onCommit(it) }
            },
            isError = !valid,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.width(110.dp),
        )
    }
}

/** Текст во всю ширину (длинный: URL/UA). [requireHttp] — для URL требуем схему http(s), иначе просто не пусто. */
@Composable
private fun UrlSettingRow(label: String, value: String, default: String, requireHttp: Boolean = true, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    fun ok(s: String): Boolean {
        val v = s.trim()
        return v.isNotBlank() && (!requireHttp || v.startsWith("http://") || v.startsWith("https://"))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (value != default) {
            Text("деф. $default", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                if (ok(t)) onCommit(t.trim())
            },
            isError = !ok(text),
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun BoolSettingRow(label: String, value: Boolean, default: Boolean, onCommit: (Boolean) -> Unit) {
    SettingRowScaffold(label, "", changed = value != default, defaultText = if (default) "вкл" else "выкл") {
        Switch(checked = value, onCheckedChange = onCommit)
    }
}

// ═══════════════════════ ПОДПИСКИ ═══════════════════════

/**
 * Содержимое секции «Подписки»: список источников (точка · имя · N · время · вкл/выкл · удалить),
 * добавление по URL+имя, вставка ссылок текстом, импорт из файла. Плотно, под крупные шрифты.
 */
@Composable
private fun SubscriptionsSection(
    sources: List<SubSource>,
    onAddUrl: (String, String) -> Unit,
    onAddPaste: (String) -> Unit,
    onImportFile: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onDeleteRequest: (SubSource) -> Unit,
    onRenameRequest: (SubSource) -> Unit,
) {
    var newUrl by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf("") }
    var shownDetailId by remember { mutableStateOf<String?>(null) }   // тап по точке разворачивает подробности
    val red = Color(0xFFD32F2F)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sources.isEmpty()) {
            Text("Нет источников.", style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
        }
        sources.forEach { s ->
            val dotColor = when {
                !s.enabled -> TABLE_GRAY                 // выключена — серая
                s.lastOk == true -> Color(0xFF2E7D32)    // ок — зелёная
                s.lastOk == false -> red                 // ошибка — красная
                else -> TABLE_GRAY                       // ни разу — серая
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Тап по точке = свернуть/развернуть ПОДРОБНОСТИ (суть ошибки видна и без тапа).
                Text(
                    "●",
                    color = dotColor,
                    fontSize = TABLE_FONT,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { shownDetailId = if (shownDetailId == s.id) null else s.id }
                        .padding(4.dp),
                )
                // Тап по имени = переименовать.
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { onRenameRequest(s) }
                        .padding(vertical = 2.dp),
                ) {
                    Text(s.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${s.serverCount} серв · ${s.lastRefreshTs ?: "не обновлялась"}" +
                            (if (s.url.isBlank()) " · локальная" else ""),
                        style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                    )
                    // Суть ошибки — ВСЕГДА видна, красным, с переносом (без тапа).
                    if (s.lastError != null) {
                        Text(s.lastError, style = MaterialTheme.typography.bodySmall, color = red)
                    }
                    // Подробности — по тапу на точку.
                    if (shownDetailId == s.id && s.lastDetail != null) {
                        Text(
                            s.lastDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TABLE_GRAY,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                Switch(checked = s.enabled, onCheckedChange = { onToggle(s.id, it) })
                TextButton(onClick = { onDeleteRequest(s) }) { Text("✗") }
            }
        }

        SettingsGroupLabel("Добавить подписку (URL)")
        OutlinedTextField(
            value = newUrl, onValueChange = { newUrl = it },
            label = { Text("URL подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = newName, onValueChange = { newName = it },
            label = { Text("Имя (необязательно)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onAddUrl(newUrl.trim(), newName.trim()); newUrl = ""; newName = "" },
            enabled = newUrl.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить и обновить") }

        SettingsGroupLabel("Вставить ссылки текстом")
        OutlinedTextField(
            value = paste, onValueChange = { paste = it },
            label = { Text("vless:// vmess:// trojan:// ss:// или base64-подписка") },
            minLines = 3, modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = { onAddPaste(paste); paste = "" },
            enabled = paste.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Добавить из текста") }

        OutlinedButton(onClick = onImportFile, modifier = Modifier.fillMaxWidth()) {
            Text("📄 Импорт из файла")
        }
    }
}

/** Диалог переименования источника: поле имени + URL целиком (в списке он обрезан). */
@Composable
private fun RenameSourceDialog(
    source: SubSource,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(source) { mutableStateOf(source.name) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Переименовать источник") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Имя") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    if (source.url.isBlank()) "URL: — (локальный источник)" else "URL: ${source.url}",
                    style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim()) }) { Text("Сохранить") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ═══════════════════════ ТРАФИК ═══════════════════════

/** КБ/МБ/ГБ с одним знаком (точка), без сырых байтов. */
private fun fmtBytes(b: Long): String = when {
    b < 1024L * 1024 -> String.format(Locale.US, "%.1f КБ", b / 1024.0)
    b < 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f МБ", b / 1_048_576.0)
    else -> String.format(Locale.US, "%.1f ГБ", b / 1_073_741_824.0)
}

/** ч:мм:сс. */
private fun fmtUptime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
}

/**
 * Секция «Трафик» (внутри вкладки «Настройки»): два потока раздельно (туннель ↓/↑ и тест),
 * сумма «итого» отдельной строкой. Три блока: сессия, за сутки, за 30 дней (+ список по дням).
 * Тот же состав/тексты/поведение, что были на отдельной вкладке — перенос без переделки.
 */
@Composable
private fun TrafficSection() {
    val t by TrafficTracker.state.collectAsState()

    // Живой таймер: тикаем раз в секунду ВСЕГДА (сессия идёт с запуска приложения).
    var nowElapsed by remember { mutableStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(1000)
        }
    }
    // Время работы блока = время СЕССИИ приложения (от той же точки, что и байты).
    val uptimeMs = if (t.sessionStartElapsed == 0L) 0L else nowElapsed - t.sessionStartElapsed
    // Отдельно — время работы ПРОКСИ (не смешивать с сессией приложения).
    val proxyUptime = when {
        t.proxyActive -> nowElapsed - t.proxyStartElapsed
        t.proxyEndElapsed > t.proxyStartElapsed && t.proxyStartElapsed > 0L -> t.proxyEndElapsed - t.proxyStartElapsed
        else -> -1L
    }
    var confirmToday by remember { mutableStateOf(false) }
    var confirmAll by remember { mutableStateOf(false) }
    val daysWithData = t.days.count { !it.second.isEmpty() }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // 1. С последнего запуска — сброс без подтверждения (сессия + таймер).
        TrafficBlock("С последнего запуска") {
            if (t.sessionStartElapsed == 0L) {
                Text("Нет данных", style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
            } else {
                MetricRow("Время работы", fmtUptime(uptimeMs))
                MetricRow("Прокси активен", if (proxyUptime < 0) "—" else fmtUptime(proxyUptime))
                MetricRow("Туннель ↓ (приём)", fmtBytes(t.sessionRx))
                MetricRow("Туннель ↑ (отдача)", fmtBytes(t.sessionTx))
                MetricRow("Тест", fmtBytes(t.sessionTest))
                MetricRow("Итого", fmtBytes(t.sessionRx + t.sessionTx + t.sessionTest), bold = true)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { TrafficTracker.resetSession() }) { Text("Сбросить") }
            }
        }
        // 2. За сутки — сброс корзины сегодняшнего дня, с подтверждением.
        TrafficBlock("За сутки") {
            BucketRows(t.today)
            if (!t.today.isEmpty()) {
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { confirmToday = true }) { Text("Сбросить") }
            }
        }
        // 3. За 30 дней + список по дням — стереть всю историю, с подтверждением.
        TrafficBlock("За 30 дней") {
            BucketRows(t.total30)
            val nonEmpty = t.days.filter { !it.second.isEmpty() }
            if (nonEmpty.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Text("По дням:", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
                nonEmpty.forEach { (date, b) ->
                    MetricRow(date, fmtBytes(b.total()))
                }
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { confirmAll = true }) { Text("Сбросить") }
            }
        }
    }

    if (confirmToday) {
        AlertDialog(
            onDismissRequest = { confirmToday = false },
            title = { Text("Сбросить за сутки?") },
            text = { Text("Трафик за сегодня (${fmtBytes(t.today.total())}) будет удалён безвозвратно.") },
            confirmButton = { TextButton(onClick = { TrafficTracker.resetToday(); confirmToday = false }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { confirmToday = false }) { Text("Отмена") } },
        )
    }
    if (confirmAll) {
        AlertDialog(
            onDismissRequest = { confirmAll = false },
            title = { Text("Стереть всю историю?") },
            text = { Text("Будет удалена вся история трафика за $daysWithData дн. (${fmtBytes(t.total30.total())}), включая список по дням. Безвозвратно.") },
            confirmButton = { TextButton(onClick = { TrafficTracker.resetAll(); confirmAll = false }) { Text("Стереть") } },
            dismissButton = { TextButton(onClick = { confirmAll = false }) { Text("Отмена") } },
        )
    }
}

/** Строки одной корзины: туннель ↓/↑, тест, итого — или «Нет данных». */
@Composable
private fun BucketRows(b: DayBucket) {
    if (b.isEmpty()) {
        Text("Нет данных", style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
    } else {
        MetricRow("Туннель ↓ (приём)", fmtBytes(b.tunnelRx))
        MetricRow("Туннель ↑ (отдача)", fmtBytes(b.tunnelTx))
        MetricRow("Тест", fmtBytes(b.test))
        MetricRow("Итого", fmtBytes(b.total()), bold = true)
    }
}

/** Блок-карточка с заголовком. */
@Composable
private fun TrafficBlock(title: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        content()
    }
}

/** Строка «метка … значение». */
@Composable
private fun MetricRow(label: String, value: String, bold: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label, modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        )
    }
}
