package com.picosoft.xrayproxydroid

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import com.picosoft.xrayproxydroid.service.LastServerStore
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.SystemVpnState
import com.picosoft.xrayproxydroid.service.VpnStatus
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.monitor.LogEvent
import com.picosoft.xrayproxydroid.monitor.MonitorCoordinator
import com.picosoft.xrayproxydroid.monitor.MonitorHeartbeat
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.monitor.MonitorPrompt
import com.picosoft.xrayproxydroid.monitor.MonitorStatus
import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.monitor.TunnelHealth
import com.picosoft.xrayproxydroid.monitor.TunnelSpeed
import com.picosoft.xrayproxydroid.crash.CrashContext
import com.picosoft.xrayproxydroid.crash.CrashReporter
import com.picosoft.xrayproxydroid.monitor.ServerLabels
import com.picosoft.xrayproxydroid.net.VpnRelation
import com.picosoft.xrayproxydroid.service.NotificationHelper
import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.Blocklist
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SourceOutcome
import com.picosoft.xrayproxydroid.subscription.SubSource
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.traffic.DayBucket
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.ui.TestProgress
import com.picosoft.xrayproxydroid.update.UpdateCheckResult
import com.picosoft.xrayproxydroid.update.UpdateChecker
import com.picosoft.xrayproxydroid.update.UpdateInstaller
import com.picosoft.xrayproxydroid.update.UpdateFlowController
import com.picosoft.xrayproxydroid.update.UpdateStore
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import com.picosoft.xrayproxydroid.xray.FullTestRunner
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.BlocklistLog
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN = "open"          // Промпт 93.J: интент-навигация из уведомления
        const val OPEN_UPDATE = "update"       // открыть на «О приложении»/экране обновления
        // Запрос навигации (из уведомления) — StateFlow, чтобы AppRoot среагировал и при onNewIntent (приложение уже открыто).
        val pendingOpen = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
    }

    // Промпт 95.B: приложение выведено на передний план → немедленная проверка связи (не ждать цикла).
    override fun onResume() {
        super.onResume()
        MonitorCoordinator.wake()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.getStringExtra(EXTRA_OPEN)?.let { pendingOpen.value = it }
        SettingsStore.init(applicationContext)   // загрузить настройки до первого замера
        BlocklistStore.init(applicationContext)  // стоп-лист (первый запуск → засев дефолтом RU/BY)
        MonitorLog.init(applicationContext)      // журнал автомониторинга (переживает перезапуск)
        SubscriptionManager.init(applicationContext)   // миграция старой подписки в мультиподписки (однократно)
        TrafficTracker.init(applicationContext)  // загрузить дневные корзины трафика
        UpdateStore.init(applicationContext)     // результат последней проверки обновления + время
        // Авто-проверка обновления при холодном старте, но не чаще раза в сутки (несколько КБ через каскад;
        // САМ APK без согласия не качаем). Метаданные — в поток «Тест». Ошибка не мешает запуску.
        // Промпт 80.B: ВСЁ в фоновом потоке — экран открывается сразу (setContent ниже не ждёт). Ждём подъёма
        // своего SOCKS не дольше UpdateChecker.AUTO_CHECK_PROXY_WAIT_MS (авто-проверка без туннеля на
        // заблокированной сети обречена); если туннель так и не поднялся — МОЛЧА выходим, ничего не записывая
        // (необязательная авто-проверка не должна навязывать ошибку; ручная кнопка объяснит честно).
        run {
            val app = applicationContext
            if (UpdateStore.dueForAutoCheck(System.currentTimeMillis())) Thread {
                var waited = 0
                while (waited < UpdateChecker.AUTO_CHECK_PROXY_WAIT_MS &&
                       !com.picosoft.xrayproxydroid.net.CascadeFetch.isOwnProxyUp()) {
                    Thread.sleep(1_000); waited += 1_000
                }
                if (!com.picosoft.xrayproxydroid.net.CascadeFetch.isOwnProxyUp()) return@Thread  // туннеля нет — молчим
                val r = runCatching { UpdateChecker.check(app) }.getOrNull() ?: return@Thread
                UpdateStore.apply(app, r, System.currentTimeMillis())
                com.picosoft.xrayproxydroid.update.UpdateNotifier.maybeNotify(app)   // Промпт 93.J: уведомить, если новее
            }.start()
        }
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
private val BOTTOM_BAR_HEIGHT = 60.dp   // икона + подпись минимальным шрифтом

/** Корень с компактной нижней навигацией: «Главная» (рабочий экран) + «Настройки» (что настраивают). */
@Composable
private fun AppRoot() {
    // tab — rememberSaveable: приложение НИКОГДА не переключает вкладку само. Переживает и рекомпозиции,
    // и пересоздание Activity (поворот/тёмная тема/возврат из системного экрана/память во время долгого
    // теста) — остаётся ровно там, где пользователь. Раньше был plain remember и на пересоздании прыгал
    // на «Главную» (это тоже самопроизвольная смена вкладки — убрано).
    var tab by rememberSaveable { mutableStateOf(0) }
    // Навигация по тапу уведомления об обновлении (Промпт 93.J) → вкладка «Настройки» (там «О приложении» + обновление).
    val pendingOpen by MainActivity.pendingOpen.collectAsState()
    LaunchedEffect(pendingOpen) {
        if (pendingOpen == MainActivity.OPEN_UPDATE) { tab = 2; MainActivity.pendingOpen.value = null }
    }
    val stateHolder = rememberSaveableStateHolder()   // сохраняет состояние вкладки (раскрытые секции) при переключении
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = { CompactBottomBar(selected = tab, onSelect = { tab = it }) },
    ) { innerPadding ->
        stateHolder.SaveableStateProvider(tab) {
            when (tab) {
                0 -> BootScreen(modifier = Modifier.padding(innerPadding), onOpenUpdate = { tab = 2 })
                1 -> SubscriptionsScreen(modifier = Modifier.padding(innerPadding))
                else -> SettingsTab(modifier = Modifier.padding(innerPadding))
            }
        }
    }

    // Предложение включить выключенные источники (пункт E) — поверх любой вкладки. Не включаем сами.
    val context = LocalContext.current
    val enablePrompt by MonitorPrompt.state.collectAsState()
    enablePrompt?.let { p ->
        AlertDialog(
            onDismissRequest = { MonitorPrompt.decline(); NotificationHelper.cancelEnableSources(context) },
            title = { Text("Нет живых серверов") },
            text = { Text("Все включённые серверы перебраны — живых нет. Есть ${p.sources} выключенных источников (~${p.servers} серверов). Включить их и обновить?") },
            confirmButton = {
                TextButton(onClick = {
                    MonitorPrompt.clear(); NotificationHelper.cancelEnableSources(context)
                    Thread {
                        SubscriptionManager.enableAllDisabled(context)
                        SubscriptionManager.refreshAllEnabled(context, cancelled = { false }, onEach = { _, _ -> })
                        MonitorPrompt.resetDeclined()
                        MonitorCoordinator.wake()   // продолжить перебор с начала
                    }.start()
                }) { Text("Включить") }
            },
            dismissButton = {
                TextButton(onClick = { MonitorPrompt.decline(); NotificationHelper.cancelEnableSources(context) }) { Text("Не сейчас") }
            },
        )
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
    val heartbeat by MonitorStatus.state.collectAsState()
    val vpnStatus by SystemVpnState.state.collectAsState()   // сообщение о системном VPN — здесь, на цветном поле
    // Состояние раскрытия — на уровне вкладки (стабильное позиционное scoping), все свёрнуты по умолчанию.
    var aboutExpanded by rememberSaveable { mutableStateOf(false) }
    var proxyExpanded by rememberSaveable { mutableStateOf(false) }
    var browserExpanded by rememberSaveable { mutableStateOf(false) }
    var settingsExpanded by rememberSaveable { mutableStateOf(false) }
    var blocklistExpanded by rememberSaveable { mutableStateOf(false) }
    var monitorExpanded by rememberSaveable { mutableStateOf(false) }
    var trafficExpanded by rememberSaveable { mutableStateOf(false) }
    var crashExpanded by rememberSaveable { mutableStateOf(false) }
    val updateRec by UpdateStore.record.collectAsState()   // Промпт 93.K: та же полоса «новая версия» в настройках
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Полоса «новая версия» (Промпт 93.K) — одно событие в двух местах; тап по плашке = скачать+установить.
        if (settings.notifyNewVersions && updateRec.availCode > BuildConfig.VERSION_CODE && updateRec.availCode != updateRec.dismissedCode) {
            item {
                UpdateBanner(
                    versionName = updateRec.availName,
                    onDismiss = { UpdateStore.markDismissed(context); NotificationHelper.cancelUpdate(context) },
                )
            }
        }
        // Сообщение о системном VPN — на цветном поле, только эта надпись (перенесено с Главной).
        if (vpnStatus.relation != VpnRelation.NONE) {
            item { VpnStatusCard(vpnStatus, onRetry = { XrayProxyService.retryVpnBypass(context) }) }
        }
        item {
            CollapsibleSection("О приложении", aboutExpanded, { aboutExpanded = !aboutExpanded }, icon = UiIcon.INFO) {
                AboutSection()
            }
        }
        item {
            CollapsibleSection("Локальный прокси", proxyExpanded, { proxyExpanded = !proxyExpanded }, icon = UiIcon.LINK) {
                LocalProxySection()
            }
        }
        item {
            CollapsibleSection("Настройте браузер и Telegram", browserExpanded, { browserExpanded = !browserExpanded }, icon = UiIcon.GLOBE) {
                BrowserSetupSection()
            }
        }
        item {
            CollapsibleSection("Настройки", settingsExpanded, { settingsExpanded = !settingsExpanded }, icon = UiIcon.GEAR) {
                SettingsSection(
                    settings = settings,
                    protocolCounts = protocolCounts,
                    onChange = { SettingsStore.update(context, it) },
                    onReset = { SettingsStore.resetToDefaults(context) },
                )
            }
        }
        item {
            CollapsibleSection("Стоп-лист", blocklistExpanded, { blocklistExpanded = !blocklistExpanded }, icon = UiIcon.BLOCK) {
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
            CollapsibleSection("Автомониторинг", monitorExpanded, { monitorExpanded = !monitorExpanded }, icon = UiIcon.SHIELD) {
                MonitorSection(
                    settings = settings,
                    heartbeat = heartbeat,
                    onChange = { SettingsStore.update(context, it) },
                    log = monitorLog,
                    onClearLog = { MonitorLog.clear(context) },
                )
            }
        }
        item {
            CollapsibleSection("Трафик", trafficExpanded, { trafficExpanded = !trafficExpanded }, icon = UiIcon.TRAFFIC) { TrafficSection() }
        }
        item {
            CollapsibleSection("Отчёты о сбоях", crashExpanded, { crashExpanded = !crashExpanded }, icon = UiIcon.WARN) {
                CrashReportsSection()
            }
        }
        // Проверка обновления — на виду (не в подменю), ниже «Трафик».
        item { UpdateCheckSection() }
        // Трафик замеров + режим экономии — в САМОМ НИЗУ (Промпт 77).
        item { TrafficBlock() }
    }
}

/**
 * Вкладка «Подписки» (отдельная, Промпт 68): список источников + добавить URL/вставить/из файла,
 * вкл/выкл, удалить, переименовать, обновить все. Логика перенесена сюда с Главной. Своё состояние
 * `sources`; при возврате на Главную список серверов подхватится свежим (BootScreen перечитывает).
 */
@Composable
private fun SubscriptionsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    var sources by remember { mutableStateOf(SubscriptionManager.sources(context)) }
    var pendingDelete by remember { mutableStateOf<SubSource?>(null) }
    var renameSource by remember { mutableStateOf<SubSource?>(null) }
    var status by remember { mutableStateOf("") }
    var refreshing by remember { mutableStateOf(false) }

    fun reload() { sources = SubscriptionManager.sources(context) }

    fun onImportFile(uri: android.net.Uri) {
        Thread {
            MonitorCoordinator.abortFullTestAndWait()   // Промпт 93.E: не менять реестр под идущим тестом
            val body = runCatching {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }.getOrNull().orEmpty()
            if (body.isBlank()) { activity.runOnUiThread { status = "файл пуст/не прочитан" }; return@Thread }
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "файл"
            val (_, s) = SubscriptionManager.addLocalFromBody(context, body, name)
            activity.runOnUiThread {
                status = "из файла: +${s.added}  дубли=${s.duplicates}  неподдерж=${s.unsupported}  ошибок=${s.invalid}"; reload()
            }
        }.start()
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> if (uri != null) onImportFile(uri) }

    fun onAddUrl(url: String, name: String) {
        if (url.isBlank()) { status = "введите URL"; return }
        Thread {
            MonitorCoordinator.abortFullTestAndWait()   // Промпт 93.E: сначала остановить тест, потом менять реестр
            val id = SubscriptionManager.addUrl(context, url, name)
            if (id == null) { activity.runOnUiThread { status = "дубликат URL или пусто"; reload() }; return@Thread }
            val s = SubscriptionManager.refreshOne(context, id)
            activity.runOnUiThread {
                status = if (s.ok) "добавлено: +${s.added}" else "источник добавлен, обновление: ${s.error}"; reload()
            }
        }.start()
    }
    fun onAddPaste(text: String) {
        if (text.isBlank()) { status = "вставьте ссылки"; return }
        Thread {
            MonitorCoordinator.abortFullTestAndWait()   // Промпт 93.E
            val (_, s) = SubscriptionManager.addLocalFromBody(context, text)
            activity.runOnUiThread {
                status = "вставка: +${s.added}  дубли=${s.duplicates}  неподдерж=${s.unsupported}  ошибок=${s.invalid}"; reload()
            }
        }.start()
    }
    fun onRefreshAll() {
        if (refreshing) return
        if (sources.none { it.enabled && it.url.isNotBlank() }) { status = "нет включённых подписок с URL"; return }
        refreshing = true; status = "обновление…"
        Thread {
            MonitorCoordinator.abortFullTestAndWait()   // Промпт 93.E: не обновлять реестр под идущим тестом
            val res = SubscriptionManager.refreshAllEnabled(context, cancelled = { false }, onEach = { _, _ -> activity.runOnUiThread { reload() } })
            val okN = res.values.count { it.ok }; val failN = res.values.count { !it.ok }
            activity.runOnUiThread { refreshing = false; status = "обновлено: $okN ок, $failN ошибок"; reload() }
        }.start()
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Заголовок КРУПНЕЕ + «Обновить» ИКОНКОЙ справа (Промпт 93).
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Подписки (${sources.size})",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                // Явная нажимаемая кнопка (Промпт 93: голая иконка «неубедительна»): залитый круг с ↻ + подпись.
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (refreshing) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.primary)
                        .clickable(enabled = !refreshing) { onRefreshAll() }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FlatIcon(UiIcon.REFRESH, size = 20.dp,
                        color = if (refreshing) TABLE_GRAY else MaterialTheme.colorScheme.onPrimary)
                    Text(if (refreshing) "Обновление…" else "Обновить",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        color = if (refreshing) TABLE_GRAY else MaterialTheme.colorScheme.onPrimary)
                }
            }
        }
        item {
            SubscriptionsSection(
                sources = sources,
                onAddUrl = { url, name -> onAddUrl(url, name) },
                onAddPaste = { onAddPaste(it) },
                onImportFile = { filePicker.launch("*/*") },
                onToggle = { id, en -> Thread { MonitorCoordinator.abortFullTestAndWait(); SubscriptionManager.setEnabled(context, id, en); activity.runOnUiThread { reload() } }.start() },
                onDeleteRequest = { pendingDelete = it },
                onRenameRequest = { renameSource = it },
            )
        }
        if (status.isNotEmpty()) item { Text(status, style = MaterialTheme.typography.bodySmall) }
    }

    renameSource?.let { src ->
        RenameSourceDialog(
            source = src,
            onSave = { SubscriptionManager.rename(context, src.id, it); renameSource = null; reload() },
            onDismiss = { renameSource = null },
        )
    }
    pendingDelete?.let { src ->
        val lost = remember(src) { SubscriptionManager.serversLostOnRemove(context, src.id) }
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Удалить источник?") },
            text = { Text("«${src.name}»\nИсчезнет серверов: $lost (только те, которых нет в других подписках).") },
            confirmButton = { TextButton(onClick = { pendingDelete = null; Thread { MonitorCoordinator.abortFullTestAndWait(); SubscriptionManager.remove(context, src.id); activity.runOnUiThread { reload() } }.start() }) { Text("Удалить") } },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("Отмена") } },
        )
    }
}

/**
 * Компактная нижняя панель: три вкладки ИКОНКАМИ без подписей (Промпт 68) — 🏠 Главная · 🔗 Подписки ·
 * ⚙️ Настройки. Активная: тонкая полоса-индикатор сверху + полная непрозрачность (неактивные приглушены).
 * Системный отступ навигации — отдельным инсетом. cappedDensity — чтобы эмодзи не распухали при крупном шрифте.
 */
@Composable
private fun CompactBottomBar(selected: Int, onSelect: (Int) -> Unit) {
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
        val activeColor = MaterialTheme.colorScheme.primary
        val inactiveColor = MaterialTheme.colorScheme.onSurfaceVariant
        Row(
            modifier = Modifier.fillMaxWidth().height(BOTTOM_BAR_HEIGHT),   // ФИКС. высота — панель тонкая
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (i in 0..2) {
                val active = i == selected
                Box(
                    modifier = Modifier
                        .weight(1f)                 // weight — ТОЛЬКО ширина; высоту НЕ трогаем
                        .clickable { onSelect(i) },
                    contentAlignment = Alignment.Center,
                ) {
                    // Иконка + подпись минимальным шрифтом (Промпт 93). Активность: цвет + полоса сверху.
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            modifier = Modifier
                                .width(22.dp)
                                .height(2.dp)
                                .background(if (active) activeColor else Color.Transparent),
                        )
                        Spacer(Modifier.height(4.dp))
                        NavIcon(i, if (active) activeColor else inactiveColor, size = 18.dp)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            when (i) { 0 -> "Главная"; 1 -> "Подписки"; else -> "Настройки" },
                            fontSize = 10.sp,
                            color = if (active) activeColor else inactiveColor,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Монохромные FLAT-иконки нижней навигации, нарисованы Canvas (тонируются переданным цветом → активность
 * = цветом + полосой сверху). 0 = домик, 1 = листок с текстовыми строчками (подписки), 2 = шестерёнка.
 */
@Composable
private fun NavIcon(index: Int, color: Color, size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.10f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (index) {
            0 -> {   // домик: крыша (полилиния) + корпус + дверь
                drawPath(Path().apply {
                    moveTo(s * 0.16f, s * 0.47f); lineTo(s * 0.50f, s * 0.16f); lineTo(s * 0.84f, s * 0.47f)
                }, color, style = stroke)
                drawPath(Path().apply {
                    moveTo(s * 0.27f, s * 0.44f); lineTo(s * 0.27f, s * 0.84f)
                    lineTo(s * 0.73f, s * 0.84f); lineTo(s * 0.73f, s * 0.44f)
                }, color, style = stroke)
                drawLine(color, Offset(s * 0.50f, s * 0.84f), Offset(s * 0.50f, s * 0.63f), sw)
            }
            1 -> {   // листок с текстовыми строчками (подписки)
                drawRoundRect(
                    color, topLeft = Offset(s * 0.24f, s * 0.12f), size = Size(s * 0.52f, s * 0.76f),
                    cornerRadius = CornerRadius(s * 0.07f, s * 0.07f), style = stroke,
                )
                val x1 = s * 0.34f; val x2 = s * 0.66f
                for (y in listOf(0.34f, 0.50f, 0.66f)) drawLine(color, Offset(x1, s * y), Offset(x2, s * y), sw * 0.9f)
            }
            else -> {   // шестерёнка: кольцо + отверстие + 8 зубьев
                val c = Offset(s / 2f, s / 2f); val r = s * 0.25f; val tooth = s * 0.10f
                drawCircle(color, r, c, style = stroke)
                drawCircle(color, r * 0.42f, c, style = stroke)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0)
                    val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    drawLine(color, Offset(c.x + dx * r, c.y + dy * r), Offset(c.x + dx * (r + tooth), c.y + dy * (r + tooth)), sw * 1.3f)
                }
            }
        }
    }
}

/**
 * Единый набор ПЛОСКИХ монохромных line-иконок (тот же язык, что у нижней плашки [NavIcon]): рисуются
 * Canvas'ом, тонируются переданным цветом (по умолчанию — цвет контента, т.е. подхватывают onPrimary в
 * заливочной кнопке, primary в OutlinedButton, primary в заголовке секции). Заменяют разнобой эмодзи
 * (ℹ️⚙️🚫🛡️📄) и глифов-шрифта (▶■↻✎) — чтобы ВСЕ иконки были единообразны, как на нижней плашке.
 * Инлайновые статус-маркеры таблицы (●○✗) и каретки ▸▾ — это не «иконки», их не трогаем.
 */
private enum class UiIcon { INFO, GEAR, BLOCK, SHIELD, TRAFFIC, DOC, REFRESH, PLAY, STOP, PENCIL, WARN, LINK, GLOBE, COPY, EYE, EYE_OFF }

@Composable
private fun FlatIcon(
    icon: UiIcon,
    size: androidx.compose.ui.unit.Dp = 16.dp,
    color: Color = androidx.compose.material3.LocalContentColor.current,
) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        val sw = s * 0.10f
        val stroke = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val c = Offset(s / 2f, s / 2f)
        when (icon) {
            UiIcon.INFO -> {   // кружок + точка + ножка «i»
                drawCircle(color, s * 0.36f, c, style = stroke)
                drawCircle(color, sw * 0.6f, Offset(s * 0.5f, s * 0.30f))
                drawLine(color, Offset(s * 0.5f, s * 0.44f), Offset(s * 0.5f, s * 0.72f), sw)
            }
            UiIcon.GEAR -> {   // кольцо + отверстие + 8 зубьев (как у нижней плашки)
                val r = s * 0.25f; val tooth = s * 0.10f
                drawCircle(color, r, c, style = stroke)
                drawCircle(color, r * 0.42f, c, style = stroke)
                for (k in 0 until 8) {
                    val a = Math.toRadians(k * 45.0); val dx = cos(a).toFloat(); val dy = sin(a).toFloat()
                    drawLine(color, Offset(c.x + dx * r, c.y + dy * r), Offset(c.x + dx * (r + tooth), c.y + dy * (r + tooth)), sw * 1.3f)
                }
            }
            UiIcon.BLOCK -> {   // «кирпич»: кольцо + диагональная черта
                val r = s * 0.34f; val d = r * 0.7071f
                drawCircle(color, r, c, style = stroke)
                drawLine(color, Offset(c.x - d, c.y - d), Offset(c.x + d, c.y + d), sw)
            }
            UiIcon.SHIELD -> {   // щит (контур)
                drawPath(Path().apply {
                    moveTo(s * 0.5f, s * 0.14f)
                    lineTo(s * 0.82f, s * 0.26f)
                    lineTo(s * 0.82f, s * 0.52f)
                    quadraticTo(s * 0.82f, s * 0.74f, s * 0.5f, s * 0.87f)
                    quadraticTo(s * 0.18f, s * 0.74f, s * 0.18f, s * 0.52f)
                    lineTo(s * 0.18f, s * 0.26f)
                    close()
                }, color, style = stroke)
            }
            UiIcon.TRAFFIC -> {   // две стрелки ↓↑ (приём/отдача туннеля)
                val xL = s * 0.36f; val xR = s * 0.64f; val top = s * 0.20f; val bot = s * 0.80f
                drawLine(color, Offset(xL, top), Offset(xL, bot), sw)
                drawPath(Path().apply { moveTo(xL - s * 0.11f, bot - s * 0.15f); lineTo(xL, bot); lineTo(xL + s * 0.11f, bot - s * 0.15f) }, color, style = stroke)
                drawLine(color, Offset(xR, top), Offset(xR, bot), sw)
                drawPath(Path().apply { moveTo(xR - s * 0.11f, top + s * 0.15f); lineTo(xR, top); lineTo(xR + s * 0.11f, top + s * 0.15f) }, color, style = stroke)
            }
            UiIcon.DOC -> {   // лист с загнутым уголком
                drawPath(Path().apply {
                    moveTo(s * 0.28f, s * 0.14f); lineTo(s * 0.58f, s * 0.14f); lineTo(s * 0.72f, s * 0.28f)
                    lineTo(s * 0.72f, s * 0.86f); lineTo(s * 0.28f, s * 0.86f); close()
                }, color, style = stroke)
                drawPath(Path().apply { moveTo(s * 0.58f, s * 0.14f); lineTo(s * 0.58f, s * 0.28f); lineTo(s * 0.72f, s * 0.28f) }, color, style = stroke)
            }
            UiIcon.REFRESH -> {   // круговая стрелка
                drawArc(color, startAngle = 55f, sweepAngle = 280f, useCenter = false,
                    topLeft = Offset(s * 0.22f, s * 0.22f), size = Size(s * 0.56f, s * 0.56f), style = stroke)
                val a = Math.toRadians(55.0); val r = s * 0.28f
                val p = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
                drawPath(Path().apply { moveTo(p.x - s * 0.15f, p.y - s * 0.02f); lineTo(p.x, p.y); lineTo(p.x + s * 0.02f, p.y - s * 0.16f) }, color, style = stroke)
            }
            UiIcon.PLAY -> {   // залитый треугольник
                drawPath(Path().apply { moveTo(s * 0.34f, s * 0.24f); lineTo(s * 0.34f, s * 0.76f); lineTo(s * 0.78f, s * 0.5f); close() }, color)
            }
            UiIcon.STOP -> {   // залитый квадрат
                drawRoundRect(color, topLeft = Offset(s * 0.30f, s * 0.30f), size = Size(s * 0.40f, s * 0.40f),
                    cornerRadius = CornerRadius(s * 0.06f, s * 0.06f))
            }
            UiIcon.PENCIL -> {   // карандаш по диагонали
                drawLine(color, Offset(s * 0.30f, s * 0.70f), Offset(s * 0.66f, s * 0.34f), sw * 1.4f)
                drawLine(color, Offset(s * 0.66f, s * 0.34f), Offset(s * 0.76f, s * 0.44f), sw * 1.4f)
                drawLine(color, Offset(s * 0.76f, s * 0.44f), Offset(s * 0.40f, s * 0.80f), sw * 1.4f)
                drawPath(Path().apply { moveTo(s * 0.24f, s * 0.80f); lineTo(s * 0.30f, s * 0.64f); lineTo(s * 0.40f, s * 0.74f); close() }, color)
            }
            UiIcon.WARN -> {   // треугольник + восклицательный знак
                drawPath(Path().apply {
                    moveTo(s * 0.5f, s * 0.16f); lineTo(s * 0.86f, s * 0.80f); lineTo(s * 0.14f, s * 0.80f); close()
                }, color, style = stroke)
                drawLine(color, Offset(s * 0.5f, s * 0.42f), Offset(s * 0.5f, s * 0.62f), sw)
                drawCircle(color, sw * 0.6f, Offset(s * 0.5f, s * 0.72f))
            }
            UiIcon.LINK -> {   // два узла, соединённые линией (локальный прокси/подключение)
                val rN = s * 0.13f
                drawCircle(color, rN, Offset(s * 0.28f, s * 0.34f), style = stroke)
                drawCircle(color, rN, Offset(s * 0.72f, s * 0.66f), style = stroke)
                drawLine(color, Offset(s * 0.38f, s * 0.44f), Offset(s * 0.62f, s * 0.56f), sw)
            }
            UiIcon.GLOBE -> {   // глобус: круг + меридиан + экватор
                val r = s * 0.36f
                drawCircle(color, r, c, style = stroke)
                drawLine(color, Offset(c.x - r, c.y), Offset(c.x + r, c.y), sw)
                drawArc(color, startAngle = 90f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.5f, c.y - r), size = Size(r, 2 * r), style = stroke)
                drawArc(color, startAngle = 270f, sweepAngle = 180f, useCenter = false,
                    topLeft = Offset(c.x - r * 0.5f, c.y - r), size = Size(r, 2 * r), style = stroke)
            }
            UiIcon.COPY -> {   // две наложенные страницы
                drawRoundRect(color, topLeft = Offset(s * 0.34f, s * 0.20f), size = Size(s * 0.40f, s * 0.48f),
                    cornerRadius = CornerRadius(s * 0.06f, s * 0.06f), style = stroke)
                drawRoundRect(color, topLeft = Offset(s * 0.22f, s * 0.32f), size = Size(s * 0.40f, s * 0.48f),
                    cornerRadius = CornerRadius(s * 0.06f, s * 0.06f), style = stroke)
            }
            UiIcon.EYE -> {   // глаз (миндалевидный контур + зрачок) = используется/виден
                drawPath(Path().apply {
                    moveTo(s * 0.12f, s * 0.5f)
                    quadraticTo(s * 0.5f, s * 0.20f, s * 0.88f, s * 0.5f)
                    quadraticTo(s * 0.5f, s * 0.80f, s * 0.12f, s * 0.5f)
                    close()
                }, color, style = stroke)
                drawCircle(color, s * 0.12f, c, style = stroke)
            }
            UiIcon.EYE_OFF -> {   // тот же глаз + диагональная черта = НЕ используется/скрыт
                drawPath(Path().apply {
                    moveTo(s * 0.12f, s * 0.5f)
                    quadraticTo(s * 0.5f, s * 0.20f, s * 0.88f, s * 0.5f)
                    quadraticTo(s * 0.5f, s * 0.80f, s * 0.12f, s * 0.5f)
                    close()
                }, color, style = stroke)
                drawCircle(color, s * 0.12f, c, style = stroke)
                drawLine(color, Offset(s * 0.16f, s * 0.84f), Offset(s * 0.84f, s * 0.16f), sw)
            }
        }
    }
}

/** Метка кнопки: плоская иконка + текст (внутри RowScope кнопки), единый стиль во всех кнопках. */
@Composable
private fun ButtonLabel(
    icon: UiIcon, text: String,
    iconSize: Dp = 16.dp, fontSize: TextUnit = TextUnit.Unspecified, fontWeight: FontWeight? = null,
) {
    FlatIcon(icon, size = iconSize)
    Spacer(Modifier.width(if (iconSize >= 20.dp) 8.dp else 6.dp))
    Text(text, fontSize = fontSize, fontWeight = fontWeight)
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

/** Компактная ячейка скорости (число: ≥10 → целое, <10 → 1 знак; единица «Мбит/с» — в шапке). */
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

@OptIn(ExperimentalFoundationApi::class)   // stickyHeader
@Composable
private fun BootScreen(modifier: Modifier = Modifier, onOpenUpdate: () -> Unit = {}) {
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
    var renameProfile by remember { mutableStateOf<ServerProfile?>(null) }   // диалог переименования сервера
    // Раскрытие «Все серверы» — на уровне экрана (стабильно, переживает переключение вкладок).
    var allServersExpanded by rememberSaveable { mutableStateOf(false) }

    var pingResults by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    var speedResults by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }

    var fullTesting by remember { mutableStateOf(false) }  // меняется только на старт/стоп (не на тик)
    var fullHandle by remember { mutableStateOf<FullTestRunner.Handle?>(null) }

    // Кнопка «Проверить» (зелёный блок): замер текущего туннеля → при падении ниже порога перебор списка
    // Живых до первого ≥ порога → иначе полный тест с обновлением подписок. Статус — строкой в StatusBox.
    var checking by remember { mutableStateOf(false) }
    var checkCancel by remember { mutableStateOf(false) }
    var checkStatus by remember { mutableStateOf("") }
    // Промпт 90: отдача АКТИВНОГО соединения (Мбит/с) — показываем в статус-боксе и деталях. null = не мерена.
    var activeUploadMbps by remember { mutableStateOf<Double?>(null) }
    // Промпт 93.I: полоса «было падение» на главной. Показ, если есть непоказанный отчёт; снимается крестиком.
    var showCrashBanner by remember { mutableStateOf(CrashReporter.hasUnseen()) }
    // Промпт 93.K: полоса «новая версия» — реактивно из UpdateStore (переживает перезапуск/возврат из «недавних»).
    val updateRec by UpdateStore.record.collectAsState()

    // Внешний IP через активный туннель. "" = не запрашивался, "…" = идёт запрос, ip, "нет ответа".
    var externalIp by remember { mutableStateOf("") }
    // Промпт 95: единый ФАКТ-статус туннеля (движется монитором в сервисе; один процесс — общий синглтон).
    val health by TunnelHealth.state.collectAsState()
    // Живая скорость активного туннеля для плашки (гибрид: реальный трафик / проба в простое) — источник монитор.
    val tunnelSpeed by TunnelSpeed.state.collectAsState()

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

    fun reloadServers() { servers = SubscriptionManager.allServers(context); CrashContext.registrySize = servers.size }
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

    // Подключение с записью в журнал СМЕНЫ активного сервера (причина: ручной выбор/автозапуск).
    // Полный тест и монитор логируют свои переключения сами (знают числа/причину).
    fun connectServer(p: ServerProfile, cause: String) {
        val from = ServerLabels.displayForKey(context, proxy.serverKey)
        MonitorLog.switch(context, from, displayName(p, blocklist), cause)
        startServer(p)
        MonitorCoordinator.wake()   // действие пользователя сбрасывает паузу монитора / прерывает перебор
    }

    fun onStop() {
        if (proxy.running) {
            MonitorLog.event(context, "switch", "Прокси остановлен", "ручная остановка — ${ServerLabels.displayForKey(context, proxy.serverKey) ?: proxy.label ?: "—"}")
        }
        XrayProxyService.stop(context)
    }

    // Внешний IP ЧЕРЕЗ активный SOCKS — реальная живость туннеля (не просто «сокет слушает»).
    fun refreshIp() {
        if (!ProxyState.state.value.running) return
        externalIp = "…"
        Thread {
            val ip = ExternalIpChecker.fetch()
            val nowMs = System.currentTimeMillis()
            // Промпт 95: результат — в единый ФАКТ-статус. Успех = OK (зелёный); провал = связь не подтверждена
            // (монитор запустит лестницу восстановления). Не оставляем «зелёный + прочерк».
            if (ip != null) TunnelHealth.ok(ip, nowMs)
            else if (TunnelHealth.snapshot().phase == TunnelHealth.Phase.OK)
                TunnelHealth.setPhase(TunnelHealth.Phase.VERIFYING, nowMs, "связь не подтверждена — проверяю")
            activity.runOnUiThread { externalIp = ip ?: "нет ответа" }
            MonitorCoordinator.wake()   // разбудить монитор для немедленной проверки/восстановления
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
        CrashContext.set("обновление всех источников")
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

    // Управление подписками (добавить/вставить/файл/переключить/удалить/переименовать) переехало на
    // ОТДЕЛЬНУЮ вкладку «Подписки» ([SubscriptionsScreen]). Здесь остаётся только onRefreshAll (кнопка
    // «↻ Подписки» на панели действий + автозапуск).

    // Полный адаптивный тест: ping → speed по живым → early-connect первого рабочего → апгрейд.
    fun onFullTest() {
        if (fullTesting) return
        // Промпт 101.B: идёт ручная проверка текущего соединения — двух прогонов одновременно быть не должно.
        // Не запускаем поверх неё (эскалация внутри onCheck сама снимает checking перед вызовом сюда).
        if (checking) { checkStatus = "идёт проверка — сначала прервите её"; return }
        // Свежий список из реестра (не застаревший снимок): учитывает вкл/выкл источников, сделанные на
        // вкладке «Подписки» (Промпт 82). Сервер входит, если он в ЛЮБОМ включённом источнике.
        val all = SubscriptionManager.allServers(context).also { servers = it }
        if (all.isEmpty()) { subStatus = "нет серверов"; return }
        fullTesting = true
        MonitorCoordinator.fullTestRunning = true   // монитор молчит, пока идёт ручной тест
        // Промпт 93.E: как прервать тест извне (операции с источниками) — отмена handle → onDone снимет флаги.
        MonitorCoordinator.fullTestCancel = { fullHandle?.cancel() }
        MonitorCoordinator.wake()                   // прервать возможный перебор/паузу монитора
        CrashContext.set("полный тест: старт")
        TestProgress.startIndeterminate("запуск…")   // до первого done/total — indeterminate
        pingResults = emptyMap(); speedResults = emptyMap()

        fullHandle = FullTestRunner.run(
            context = context,
            allServers = all,
            // Прогресс и текст фазы — в ОТДЕЛЬНЫЙ StateFlow (не в state экрана): тик не рекомпозит список.
            onPhase = { ph -> TestProgress.phase(ph); CrashContext.set("тест: $ph") },
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
                            MonitorCoordinator.fullTestCancel = null; CrashContext.set("простой")
                            // «Готово» БЕЗ числа скорости: единый источник правды по скорости — ЖИВАЯ плашка
                            // (иначе «готово: 47» из temp-инстанса конфликтует с живым замером туннеля на плашке).
                            TestProgress.finish(                             // бар гаснет, итог остаётся текстом
                                if (r.cancelled) "Отменено"
                                else r.connected?.let { "Готово · подключён к ${it.remarks.ifBlank { it.address }}" }
                                    ?: "Готово · рабочих серверов не нашлось"
                            )
                            reloadServers()
                            // A (обратная связь после 0.23): активный сервер ПРИОРИТЕТНО — реальную скорость на
                            // плашку СРАЗУ (не ждём цикл монитора), чтобы число совпадало с «Готово» и не было «—».
                            if (!r.cancelled && r.connected != null) {
                                Thread {
                                    Thread.sleep(1500)   // дать туннелю осесть после подключения
                                    runCatching { NetworkMonitor.probeActiveSpeedNow(context) }
                                }.start()
                            }
                        }
                    }.start()
                }
            },
        )
    }

    // Кнопка «Самый быстрый» (Промпт 91.D): ПЕРЕД тестом обновляем все включённые источники, затем полный
    // тест по свежему списку. Нет источников с URL — просто тест. onFullTest (ядро) остаётся без обновления —
    // его зовут автозапуск и эскалация «Проверить», которые уже обновились сами (без двойного рефреша).
    fun onFastest() {
        if (fullTesting || refreshingSubs) return
        if (checking) { checkStatus = "идёт проверка — сначала прервите её"; return }   // Промпт 101.B: не поверх ручной проверки
        val hasUrl = sources.any { it.enabled && it.url.isNotBlank() }
        if (hasUrl) onRefreshAll(onComplete = { onFullTest() }) else onFullTest()
    }

    fun onCancelFull() {
        fullHandle?.cancel(); fullHandle = null
        fullTesting = false
        MonitorCoordinator.fullTestRunning = false   // отмена теста — монитор снова может работать
        MonitorCoordinator.fullTestCancel = null
        TestProgress.finish("отменено")
        val pSnap = pingResults; val sSnap = speedResults
        Thread {
            SubscriptionManager.applyPingResults(context, pSnap)
            SubscriptionManager.applySpeedResults(context, sSnap)
            activity.runOnUiThread { reloadServers() }
        }.start()
    }

    // Кнопка «Проверить» (Промпт 88/90, ПЕРЕРАБОТАНО Промпт 92 — устойчивость к массовому провалу замеров).
    // ПРИНЦИП: НИКОГДА не бросаем рабочий сервер ради недостижимого. Порядок:
    //  1) замер активного туннеля; если ≥ порога («в норме») — кандидатов ищем НЕ ОТКЛЮЧАЯСЬ (temp-замер),
    //     и переключаемся ТОЛЬКО если лучший ПОДТВЕРЖДЁН кратно быстрее («держать лучший»); иначе остаёмся;
    //  2) если текущий ниже порога/не запущен/forceSwitch — ищем рабочий: кандидаты меряем temp-инстансом
    //     СВЕРХУ ВНИЗ (probeAlive→measureSpeed), к ПЕРВОМУ подтверждённому ≥ порога подключаемся ОДИН раз;
    //  3) не нашли — ВОЗВРАЩАЕМСЯ к исходному рабочему серверу (реконнект), а не виснем; если исходного нет
    //     (или он исключён) — полный тест с обновлением подписок.
    // ОТДАЧА (90.B) — только показывается, НЕ триггер переключения (приёмник не верифицирован; низкая/провальная
    // отдача НЕ должна ронять рабочий сервер). Замеры сопоставимы (90.A). Монитор молчит (fullTestRunning).
    fun onCheck(liveList: List<ServerProfile>, forceSwitch: Boolean = false) {
        if (checking) return
        if (fullTesting || refreshingSubs) { checkStatus = "идёт другой тест — подождите"; return }
        checking = true; checkCancel = false; checkStatus = "Проверка…"
        ServerSpeedTester.resetMeasureAbort()   // Промпт 101.B: снять флаг отмены прошлой проверки
        MonitorCoordinator.fullTestRunning = true
        MonitorCoordinator.wake()
        val s = SettingsStore.current()
        val threshold = s.monitorTunnelThreshold
        val multiplier = s.keepBestMultiplier
        fun key(p: ServerProfile) = SubscriptionManager.serverKey(p)
        fun nm(p: ServerProfile) = displayName(p, blocklist)
        fun fmt(v: Double) = if (v < 0) "—" else "${(v * 10).roundToInt() / 10.0}"
        fun ui(block: () -> Unit) = activity.runOnUiThread(block)
        val collected = HashMap<String, Double>()
        var escalated = false
        Thread {
            try {
                if (!NetworkMonitor.directAlive()) { ui { checkStatus = "Нет интернета — проверка невозможна" }; return@Thread }

                // Подключиться к p и замерить активный туннель (↓ для решения, ↑ только для показа). Возврат ↓.
                fun connectAndMeasure(p: ServerProfile, cause: String): Double {
                    val k = key(p)
                    ui {
                        val from = ServerLabels.displayForKey(context, ProxyState.state.value.serverKey)
                        MonitorLog.switch(context, from, nm(p), cause)
                        startServer(p)
                    }
                    var waited = 0
                    while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != k)) {
                        Thread.sleep(500); waited += 500
                    }
                    Thread.sleep(1000)
                    val down = ServerSpeedTester.measureActiveDownloadMbps(context)
                    val up = ServerSpeedTester.measureActiveUploadMbps(context)
                    if (down > 0) { collected[k] = down; ui { speedResults = speedResults + (k to down) } }
                    ui { activeUploadMbps = if (up > 0) up else null }
                    return down
                }

                val curKey = ProxyState.state.value.serverKey
                val running = ProxyState.state.value.running
                val originalProfile = if (running && curKey != null) servers.firstOrNull { key(it) == curKey } else null

                // Кандидаты сверху вниз по известной скорости, кроме текущего, живые/непровальные.
                fun candidatesTopDown() = liveList
                    .filter { key(it) != curKey }
                    .sortedByDescending { effSpeed(it) ?: 0.0 }

                // Найти рабочего кандидата НЕ ОТКЛЮЧАЯ активный: temp-замер сверху вниз, вернуть первого ≥ порога.
                // (probeAlive дёшев — быстро пропускает недостижимых при массовом провале, не тратя по 10 МБ.)
                fun findWorkingCandidate(minMbps: Double): Pair<ServerProfile, Double>? {
                    val cands = candidatesTopDown()
                    for ((i, c) in cands.withIndex()) {
                        if (checkCancel) return null
                        ui { checkStatus = "Проверяю кандидатов ${i + 1}/${cands.size}: ${nm(c)}…" }
                        if (!ServerSpeedTester.probeAlive(context, c)) continue
                        val m = ServerSpeedTester.measureSpeed(context, c)   // temp-инстанс, активный не трогаем
                        if (m > 0) { collected[key(c)] = m; ui { speedResults = speedResults + (key(c) to m) } }
                        if (m >= minMbps) return c to m
                    }
                    return null
                }

                // ── 1. Текущий рабочий и НЕ forceSwitch: остаёмся, если он «в норме» и лучший не подтверждён ──
                if (!forceSwitch && originalProfile != null) {
                    ui { checkStatus = "Замер текущего соединения…" }
                    val curDown = ServerSpeedTester.measureActiveDownloadMbps(context)
                    if (checkCancel) { ui { checkStatus = "Проверка прервана" }; return@Thread }   // Промпт 101.B: отмена во время замера
                    val curUp = ServerSpeedTester.measureActiveUploadMbps(context)
                    if (checkCancel) { ui { checkStatus = "Проверка прервана" }; return@Thread }
                    if (curDown > 0) { collected[curKey!!] = curDown; ui { speedResults = speedResults + (curKey to curDown) } }
                    ui { activeUploadMbps = if (curUp > 0) curUp else null }

                    if (curDown >= threshold) {
                        // «В норме». «Держать лучший»: только если ПОДТВЕРЖДЁННЫЙ (temp-замер) лучший кратно быстрее.
                        val bestKnown = candidatesTopDown().firstOrNull { (effSpeed(it) ?: 0.0) >= curDown * multiplier }
                        if (bestKnown == null) {
                            ui { checkStatus = "Соединение в норме: ↓${fmt(curDown)} / ↑${fmt(curUp)} Мбит/с" }; return@Thread
                        }
                        ui { checkStatus = "Проверяю более быстрый: ${nm(bestKnown)}…" }
                        val m = ServerSpeedTester.measureSpeed(context, bestKnown)   // temp — активный не трогаем
                        if (m > 0) { collected[key(bestKnown)] = m; ui { speedResults = speedResults + (key(bestKnown) to m) } }
                        if (m >= threshold && m >= curDown * multiplier) {
                            val d = connectAndMeasure(bestKnown, "проверка: держать лучший (${fmt(m)}>${fmt(curDown)})")
                            if (d >= threshold) { ui { checkStatus = "Переключён на ${nm(bestKnown)}: ↓${fmt(d)} Мбит/с" }; return@Thread }
                            // не подтвердился на активном → назад к рабочему
                            connectAndMeasure(originalProfile, "проверка: возврат (лучший не подтвердился)")
                            ui { checkStatus = "Оставил ${nm(originalProfile)}: быстрее не подтвердилось" }; return@Thread
                        }
                        ui { checkStatus = "Соединение в норме: ↓${fmt(curDown)} / ↑${fmt(curUp)} (быстрее не подтвердилось)" }; return@Thread
                    }
                    // Текущий ниже порога → ищем рабочий, НЕ отключаясь.
                    ui { checkStatus = "Текущий ↓${fmt(curDown)} < ${fmt(threshold)} — ищу рабочий…" }
                    val win = findWorkingCandidate(threshold)
                    if (checkCancel) { ui { checkStatus = "Проверка прервана" }; return@Thread }
                    if (win != null) {
                        val d = connectAndMeasure(win.first, "проверка: замена (${fmt(win.second)})")
                        if (d >= threshold) { ui { checkStatus = "Переключён на ${nm(win.first)}: ↓${fmt(d)} Мбит/с" }; return@Thread }
                    }
                    // Не нашли лучше — ОСТАЁМСЯ на текущем (он хоть и ниже порога, но рабочий), не виснем.
                    ui { checkStatus = "Рабочей замены не нашёл — оставил ${nm(originalProfile)} (↓${fmt(curDown)})" }
                    return@Thread
                }

                // ── 2. forceSwitch («Не использовать») ИЛИ прокси не запущен: ищем рабочий (не отключаясь) ──
                ui { checkStatus = "Ищу рабочий сервер…" }
                val win = findWorkingCandidate(threshold)
                if (checkCancel) { ui { checkStatus = "Проверка прервана" }; return@Thread }
                if (win != null) {
                    val d = connectAndMeasure(win.first, "проверка: подбор (${fmt(win.second)})")
                    if (d >= threshold) { ui { checkStatus = "Подключён ${nm(win.first)}: ↓${fmt(d)} Мбит/с" }; return@Thread }
                }
                // ── 3. Рабочего не нашли (и рабочего исходного нет) → полный тест с обновлением подписок ──
                escalated = true
                ui {
                    checking = false
                    MonitorCoordinator.fullTestRunning = false
                    checkStatus = "Рабочий не найден — полный тест с обновлением подписок…"
                    onRefreshAll(onComplete = { onFullTest() })
                }
            } catch (e: Exception) {
                ui { checkStatus = "Ошибка проверки: ${e.message}" }
            } finally {
                if (collected.isNotEmpty()) SubscriptionManager.applySpeedResults(context, collected)
                ui {
                    if (!escalated) { checking = false; MonitorCoordinator.fullTestRunning = false }
                    reloadServers()
                }
            }
        }.start()
    }

    // Промпт 101.B: отмена обязана срабатывать НЕМЕДЛЕННО — не только флаг, но и закрыть идущий замер
    // (иначе «Прервать» ждёт таймаута блокирующего чтения). abortCurrentMeasure рвёт соединение замера.
    fun onCancelCheck() { checkCancel = true; ServerSpeedTester.abortCurrentMeasure() }

    // Промпт 91: «Убрать» — отложить ТЕКУЩИЙ сервер («на паузе») и сразу переключиться на быстрейшего живого
    // (правило Промпта 90, сверху вниз; отложенный в кандидаты не попадает). onCheck сам берёт список без него.
    fun onRemoveCurrent(liveList: List<ServerProfile>) {
        val curKey = ProxyState.state.value.serverKey ?: return
        val cur = servers.firstOrNull { SubscriptionManager.serverKey(it) == curKey }
        val name = cur?.let { displayName(it, blocklist) } ?: (proxy.label ?: curKey)
        BlocklistStore.pauseServer(context, curKey, name, System.currentTimeMillis())
        MonitorLog.event(context, "switch", "Сервер исключён («Не использовать»)", name)
        // Список Живых без исключённого (curKey теперь «не используется» — исключаем явно на случай устаревания).
        // forceSwitch=true: не задерживаемся на «текущий в норме», сразу переключаемся на быстрейшего живого.
        onCheck(liveList.filter { SubscriptionManager.serverKey(it) != curKey }, forceSwitch = true)
    }

    // Промпт 91: переключить «использовать ↔ не использовать» сервер (глаз-переключатель в списках).
    fun toggleUse(p: ServerProfile) {
        val k = SubscriptionManager.serverKey(p)
        if (BlocklistStore.current().isPaused(k)) BlocklistStore.resumeServer(context, k)
        else BlocklistStore.pauseServer(context, k, displayName(p, blocklist), System.currentTimeMillis())
    }


    // Отсевы (единый предикат [ServerFilter]) — скрывают из ВИДИМОЙ части (Живые и Все).
    // Замер идёт по всем НЕзаблокированным; скрытые считаем, чтобы показать «скрыто настройками: N».
    // Заблокированных убираем ПЕРВЫМИ — они не в списках, не в выборе, не мерятся.
    val notBlocked = servers.filter { !ServerFilter.isBlocked(it, blocklist) }
    val allowedServers = notBlocked.filter { ServerFilter.protocolAllowed(it, settings) }
    // Отложенные («на паузе») остаются в «Все серверы», но скрыты из «Живых» → тоже в счётчик «скрыто».
    val pausedShownCount = allowedServers.count { ServerFilter.isPaused(it, blocklist) }
    val hiddenCount = servers.size - allowedServers.size + pausedShownCount   // протокол + стоп-лист + пауза

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
            TunnelHealth.setPhase(TunnelHealth.Phase.VERIFYING, System.currentTimeMillis(), "проверяю связь…")
            MonitorCoordinator.wake()      // немедленная проверка (не ждать цикла)
            kotlinx.coroutines.delay(800)
            refreshIp()
        } else {
            externalIp = ""
            activeUploadMbps = null         // прокси стоит → отдачи нет
        }
    }
    // Промпт 95: пока экран «Главная» открыт И прокси активен — ПЕРИОДИЧЕСКИ подтверждаем связь ФАКТОМ
    // (внешний IP через SOCKS), чтобы статус НЕ протухал (даже когда монитор занят полным тестом). ~12с.
    LaunchedEffect(proxy.running) {
        while (proxy.running) {
            kotlinx.coroutines.delay(12_000)
            if (ProxyState.state.value.running && !checking) refreshIp()
        }
    }
    // Промпт 95.D: «зелёный» — ТОЛЬКО при факте OK (прошедший запрос). Никаких «зелёный + прочерк».
    val ipVerified = proxy.running && health.phase == TunnelHealth.Phase.OK

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
            if (last != null) connectServer(last, "автозапуск (последний сервер)")
        }
        Thread {
            // Промпт 74: дефолтную подписку сеем ТОЛЬКО если её URL зафетчился (иначе пусто + «Добавьте вашу
            // подписку»). Фетч блокирующий → в фоне. justSeeded=true → тело уже импортировано, рефетч не нужен.
            val justSeeded = SubscriptionManager.trySeedDefaultSource(context)
            val hasSubs = SubscriptionManager.sources(context).any { it.enabled && it.url.isNotBlank() }
            activity.runOnUiThread {
                reloadSources(); reloadServers()
                when {
                    justSeeded -> onFullTest()
                    hasSubs -> onRefreshAll(onComplete = { onFullTest() })
                    else -> onFullTest()
                }
            }
        }.start()
    }

    // Основной вид = ЖИВЫЕ — через единый предикат [ServerFilter.isVisible] (стоп-лист+протокол+пинг+мин.скорость).
    // «Живые» = реально доступные сейчас (пинг жив). ИСКЛЮЧЕНИЕ: активный подключённый сервер ВСЕГДА в «Живых»
    // (его живость доказана самим туннелем, даже если temp-пинг подписки массово падает) — иначе рабочий сервер
    // выпадал бы из списка при недоступной подписке. Мёртвые/непингующиеся серверы НЕ держим по старым скоростям.
    val alive = shown.filter { p ->
        ServerFilter.isVisible(p, effPing(p), effSpeed(p), settings, blocklist) ||
            (proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p))
    }

    // ПРИБОРЫ по стоп-листу (Промпт 73.C): на КАЖДЫЙ пересчёт фильтра — подробный дамп (гейт «Подробные логи»,
    // тег Blocklist). Ключи эффекта = все входы фильтра, чтобы лог был на каждое реальное изменение.
    LaunchedEffect(servers, blocklist, settings, pingResults, speedResults) {
        BlocklistLog.dump(context, servers, settings, blocklist, { effPing(it) }, { effSpeed(it) }, cause = "recompose")
    }

    // Активный сервер скрыт настройками (протокол выключен)? Соединение НЕ рвём — только пометка в статусе.
    val activeHidden = activeServer != null && !ServerFilter.protocolAllowed(activeServer, settings)
    // Активный сервер попал под стоп-лист — соединение НЕ рвём молча, помечаем и предлагаем переключиться.
    val activeBlocked = activeServer != null && ServerFilter.isBlocked(activeServer, blocklist)

    // Пастельные фоны секций из ПАЛИТРЫ ТЕМЫ (не hex): lerp(surface, container) даёт НЕПРОЗРАЧНЫЙ
    // слабый тон → прилипший заголовок не просвечивает строками, и оттенки корректны в тёмной теме.
    val surface = MaterialTheme.colorScheme.surface
    val liveBg = lerp(surface, MaterialTheme.colorScheme.tertiaryContainer, 0.18f)   // светло-зелёный тон
    val allBg = lerp(surface, MaterialTheme.colorScheme.secondaryContainer, 0.18f)   // серо-голубой тон
    val divCol = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)             // волосок-разделитель

    // Единый LazyColumn. verticalArrangement=0: строки одной секции примыкают (сплошной фон-блок без
    // полос), зазоры между секциями задаём Spacer'ами. Заголовки секций — stickyHeader (прилипают).
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 14.dp),
        contentPadding = PaddingValues(vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        // ═══ ШАПКА + СТАТУС + ДЕЙСТВИЯ + ПРОГРЕСС (не красим — Промпт 53.D) ═══
        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                AppHeader()
                // Полоса «было падение» (Промпт 93.B) — заметно, над списками; крестик снимает для этого отчёта.
                if (showCrashBanner) {
                    CrashBanner(onDismiss = { CrashReporter.markLatestSeen(); showCrashBanner = false })
                }
                // Полоса «новая версия» (Промпт 93.K): новее текущей, не отклонена, настройка вкл.
                if (settings.notifyNewVersions && updateRec.availCode > BuildConfig.VERSION_CODE && updateRec.availCode != updateRec.dismissedCode) {
                    UpdateBanner(
                        versionName = updateRec.availName,
                        onDismiss = { UpdateStore.markDismissed(context); NotificationHelper.cancelUpdate(context) },
                    )
                }
                // Промпт 74: подписок нет (дефолт не зафетчился при первом запуске или юзер их не добавил) —
                // зовём добавить свою. Показываем ТОЛЬКО когда список источников пуст.
                if (sources.isEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            "Добавьте вашу подписку",
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Вкладка «Подписки» → вставьте ссылку или URL. Без подписки список серверов пуст.",
                            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                        )
                    }
                }
                // Промпт 95.D: явный текст, когда связь НЕ подтверждена фактом (не «зелёный молча»).
                val healthLine = when {
                    !proxy.running -> ""
                    health.phase == TunnelHealth.Phase.OK -> ""
                    health.phase == TunnelHealth.Phase.NO_INTERNET -> "Нет интернета — проверьте сеть"
                    health.phase == TunnelHealth.Phase.RECOVERING -> "Восстановление связи: ${health.detail.ifBlank { "перебор серверов" }}"
                    health.phase == TunnelHealth.Phase.NO_SERVERS ->
                        "Рабочих серверов сейчас нет" + (if (health.lastCheckMs > 0) " · посл. попытка ${monitorTimeFmt.format(Date(health.lastCheckMs))}" else "")
                    else -> "Проверяется связь…"
                }
                StatusBox(
                    running = proxy.running, verified = ipVerified,
                    ipText = if (health.ip.isNotEmpty()) health.ip else externalIp,
                    statusLine = healthLine,
                    onRefreshIp = { refreshIp() },
                    serverName = activeServer?.let { displayName(it, blocklist) } ?: proxy.label,
                    subtitle = activeServer?.let { protoNetSec(it) },
                    speedMbps = activeServer?.let { effSpeed(it) },
                    uploadMbps = activeUploadMbps,
                    // Живая скорость (гибрид) + время замера. Показываем ТОЛЬКО при OK; при обрыве говорит statusLine.
                    directDownMbps = tunnelSpeed.directDownMbps,
                    tunnelDownMbps = tunnelSpeed.tunnelDownMbps,
                    tunnelUpMbps = tunnelSpeed.tunnelUpMbps,
                    liveMeasuredAtMs = tunnelSpeed.measuredAtMs,
                    tunnelOk = health.phase == TunnelHealth.Phase.OK,
                    hidden = activeHidden, blocked = activeBlocked,
                    problem = proxy.running && (
                        health.phase == TunnelHealth.Phase.NO_INTERNET ||
                        health.phase == TunnelHealth.Phase.RECOVERING ||
                        health.phase == TunnelHealth.Phase.NO_SERVERS
                    ),
                    message = proxy.message,
                    checking = checking, checkStatus = checkStatus,
                    onCheck = { onCheck(alive) }, onCancelCheck = { onCancelCheck() },
                    onExclude = { onRemoveCurrent(alive) },
                )
                ActionsBar(
                    fullTesting = fullTesting, running = proxy.running, refreshingSubs = refreshingSubs,
                    onFullTest = { onFastest() },
                    onCancelFull = { if (refreshingSubs) subRefreshCancel = true else onCancelFull() },
                    onStop = { onStop() },
                )
                FullTestProgressBar()
                if (subStatus.isNotEmpty()) Text(subStatus, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ═══ ЖИВЫЕ СЕРВЕРЫ (основной вид) — светло-зелёный, заголовок прилипает ═══
        stickyHeader(key = "h-live") {
            SectionHeader("Живые серверы (${alive.size})", liveBg, roundedBottom = false, arrow = null, onClick = null)
        }
        if (hiddenCount > 0) item {
            Box(Modifier.fillMaxWidth().background(liveBg).padding(horizontal = 12.dp, vertical = 2.dp)) {
                Text("скрыто настройками: $hiddenCount", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
            }
        }
        if (settings.allowedProtocols.isEmpty() && servers.isNotEmpty()) item {
            Box(Modifier.fillMaxWidth().background(liveBg).padding(horizontal = 12.dp, vertical = 6.dp)) {
                Text("Все протоколы отключены в Настройках → Протоколы.", style = MaterialTheme.typography.bodyMedium, color = Color(0xFFD32F2F))
            }
        }
        item { Box(Modifier.fillMaxWidth().background(liveBg).bottomHairline(divCol)) { ServerTableHeader() } }
        itemsIndexed(alive, key = { _, it -> "live-" + SubscriptionManager.serverKey(it) }) { index, p ->
            val isActive = proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p)
            val rowMod = if (index < alive.lastIndex) Modifier.bottomHairline(divCol) else Modifier
            Box(Modifier.fillMaxWidth().background(liveBg).then(rowMod)) {
                ServerRow(
                    profile = p, name = displayName(p, blocklist), isActive = isActive,
                    speedMbps = effSpeed(p), caption = discriminators[SubscriptionManager.serverKey(p)] ?: "",
                    onConnect = { connectServer(p, "ручной выбор") },
                    onDetails = { detailProfile = p; remeasureStatus = "" },
                    showUseToggle = true,
                    paused = ServerFilter.isPaused(p, blocklist),
                    onToggleUse = { toggleUse(p) },
                )
            }
        }
        if (alive.isEmpty()) item {
            Box(Modifier.fillMaxWidth().background(liveBg).padding(horizontal = 12.dp, vertical = 8.dp)) {
                Text(
                    if (servers.isEmpty()) "Список пуст — обновите подписку." else "Запусти тест кнопкой «Самый быстрый».",
                    style = MaterialTheme.typography.bodyMedium, color = Color(0xFF9E9E9E),
                )
            }
        }
        item { SectionBottomCap(liveBg) }
        item { Spacer(Modifier.height(12.dp)) }

        // ═══ СВОРАЧИВАЕМЫЕ СЕКЦИИ (порядок как эталон) ═══
        // Все серверы (вкл. мёртвые ✗ и не тестированные —) — свёрнут, не мозолит глаза. Серо-голубой.
        stickyHeader(key = "h-all") {
            SectionHeader("Все серверы (${shown.size})", allBg, roundedBottom = !allServersExpanded,
                arrow = if (allServersExpanded) "▾" else "▸", onClick = { allServersExpanded = !allServersExpanded })
        }
        if (allServersExpanded) {
            item { Box(Modifier.fillMaxWidth().background(allBg).bottomHairline(divCol)) { ServerTableHeader() } }
            itemsIndexed(shown, key = { _, it -> "all-" + SubscriptionManager.serverKey(it) }) { index, p ->
                val isActive = proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p)
                val rowMod = if (index < shown.lastIndex) Modifier.bottomHairline(divCol) else Modifier
                Box(Modifier.fillMaxWidth().background(allBg).then(rowMod)) {
                    ServerRow(
                        profile = p, name = displayName(p, blocklist), isActive = isActive,
                        speedMbps = effSpeed(p), caption = discriminators[SubscriptionManager.serverKey(p)] ?: "",
                        onConnect = { connectServer(p, "ручной выбор") },
                        onDetails = { detailProfile = p; remeasureStatus = "" },
                        showUseToggle = true,
                        paused = ServerFilter.isPaused(p, blocklist),
                        onToggleUse = { toggleUse(p) },
                    )
                }
            }
            item { SectionBottomCap(allBg) }
        }
        // Подписки — отдельная вкладка ([SubscriptionsScreen]). Настройки/Стоп-лист/Автомониторинг/Трафик —
        // во вкладке «Настройки».

        // ═══ ФУТЕР ═══
        item {
            Text(
                "pico-soft/XrayProxyDroid · v${BuildConfig.VERSION_NAME}",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9E9E9E),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
            )
        }
    }

    // Диалог деталей сервера (долгое нажатие) + отладочный «Перемерить» + блокировка + переименование.
    detailProfile?.let { p ->
        val pKey = SubscriptionManager.serverKey(p)
        val pActive = proxy.running && proxy.serverKey == pKey
        ServerDetailDialog(
            profile = p,
            name = displayName(p, blocklist),
            originalName = providerName(p),
            hasCustomName = blocklist.customName(pKey) != null,
            remeasureStatus = remeasureStatus,
            remeasuring = remeasuring,
            blockedByKey = blocklist.isServerBlocked(pKey),
            blockedByWord = blocklist.matchesWord(providerName(p), blocklist.customName(pKey)),
            paused = blocklist.isPaused(pKey),
            uploadMbps = if (pActive) activeUploadMbps else null,   // отдача — только у активного соединения
            onTogglePause = {
                if (blocklist.isPaused(pKey)) BlocklistStore.resumeServer(context, pKey)
                else BlocklistStore.pauseServer(context, pKey, displayName(p, blocklist), System.currentTimeMillis())
                detailProfile = null; remeasureStatus = ""
            },
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBox(
    running: Boolean,
    verified: Boolean,
    ipText: String,
    statusLine: String,
    onRefreshIp: () -> Unit,
    serverName: String?,
    subtitle: String?,
    speedMbps: Double?,
    uploadMbps: Double?,
    directDownMbps: Double?,
    tunnelDownMbps: Double?,
    tunnelUpMbps: Double?,
    liveMeasuredAtMs: Long,
    tunnelOk: Boolean,
    hidden: Boolean,
    blocked: Boolean,
    problem: Boolean,
    message: String,
    checking: Boolean,
    checkStatus: String,
    onCheck: () -> Unit,
    onCancelCheck: () -> Unit,
    onExclude: () -> Unit,
) {
    // Цвет плашки рабочего сервера — по СКОРОСТИ скачивания (правило Elyor): >5 зелёный, 1–5 сине-серый,
    // 0.2–1 коричневый, <0.2 красно-коричневый. Порядок оценки — сверху вниз по убыванию скорости.
    // ВАЖНО (принцип непрерывности связи): при ПОЛНОМ обрыве (нет интернета/восстановление/нет серверов)
    // трафик НЕ идёт — плашка ТЁМНО-КРАСНАЯ независимо от прошлой скорости. Зелёный остаётся признаком
    // живого быстрого туннеля, а не молча-зелёным при поломке.
    val (bg, fg) = when {
        !running -> Color(0xFF3A3A3A) to Color(0xFFBDBDBD)             // серый — выключен
        problem -> Color(0xFF7F1D1D) to Color(0xFFFFCDD2)             // тёмно-красный — полный обрыв связи
        speedMbps != null && speedMbps > 0.0 -> when {
            speedMbps > 5.0  -> Color(0xFF1B5E20) to Color(0xFFA5D6A7) // зелёный — быстро
            speedMbps >= 1.0 -> Color(0xFF37474F) to Color(0xFFB0BEC5) // сине-серый — средне
            speedMbps >= 0.2 -> Color(0xFF5D4037) to Color(0xFFD7CCC8) // коричневый — медленно
            else             -> Color(0xFF5D2A2A) to Color(0xFFEF9A9A) // красно-коричневый — очень медленно
        }
        verified -> Color(0xFF1B5E20) to Color(0xFFA5D6A7)           // подтверждён, скорость ещё не мерена — зелёный
        else -> Color(0xFF6D4C00) to Color(0xFFFFE082)               // подключаемся/проверяем — янтарный
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bg)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            if (running) "● ${serverName ?: "Активен"}" else "○ Не запущен",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = fg,
        )
        // ЖИВАЯ скорость интернета (не «скорость подбора»), ВСЁ в Мбит/с (без смешения КБ/МБ), + мелко время замера.
        // Две группы: НАПРЯМУЮ ↓ (прямой канал без туннеля) и В ТУННЕЛЕ ↓/↑. Показываем ТОЛЬКО при OK; при обрыве —
        // statusLine ниже сам скажет «нет интернета/восстановление».
        if (running && tunnelOk) {
            fun r1(v: Double) = (v * 10).roundToInt() / 10.0
            val direct = directDownMbps?.takeIf { it >= 0 }
            val tDn = tunnelDownMbps?.takeIf { it >= 0 }
            val tUp = tunnelUpMbps?.takeIf { it >= 0 }
            val ageStr = when {
                liveMeasuredAtMs <= 0L -> null
                else -> {
                    val ageMin = ((System.currentTimeMillis() - liveMeasuredAtMs) / 60_000L).toInt()
                    if (ageMin <= 0) "сейчас" else "$ageMin мин назад"
                }
            }
            // Прямой канал: показываем «—», если замер не удался (а не прячем строку молча).
            Text("Напрямую: ↓${direct?.let { r1(it).toString() } ?: "—"} Мбит/с",
                style = MaterialTheme.typography.bodySmall, color = fg)
            // Туннель: ВСЕГДА оба слота ↓/↑ («—» при неудаче/недоступности отдачи — чтобы отдача не «пропадала»).
            // «замеряю…» — только пока ещё ни разу не мерили (measuredAtMs==0).
            val tunLine = if (liveMeasuredAtMs <= 0L) "замеряю скорость…" else {
                val dnStr = tDn?.let { "↓${r1(it)}" } ?: "↓—"
                val upStr = tUp?.let { "↑${r1(it)}" } ?: "↑—"
                "Туннель: $dnStr / $upStr Мбит/с" + (ageStr?.let { " · $it" } ?: "")
            }
            Text(tunLine, style = MaterialTheme.typography.bodySmall, color = fg)
        }
        // Промпт 95.D: связь НЕ подтверждена фактом → ЯВНЫЙ текст (проверяется/восстанавливается/нет серверов).
        // Не бывает «зелёный молча»: если тут есть строка — блок НЕ зелёный (verified=false).
        if (running && statusLine.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatIcon(UiIcon.WARN, size = 14.dp, color = fg)
                Text(statusLine, style = MaterialTheme.typography.bodyMedium, color = fg, fontWeight = FontWeight.Bold)
            }
        }
        if (running) {
            // Активный сервер попал под стоп-лист — туннель не рвём молча, помечаем и предлагаем переключиться.
            if (blocked) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                    FlatIcon(UiIcon.BLOCK, size = 14.dp, color = fg)
                    Text(
                        "Активный сервер в стоп-листе. Живое соединение НЕ разрываем — переключиться: кнопка «Самый быстрый».",
                        style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Bold,
                    )
                }
            }
            // Активный сервер скрыт фильтром протоколов — туннель не рвём, но помечаем.
            if (hidden) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    FlatIcon(UiIcon.WARN, size = 14.dp, color = fg)
                    Text("протокол скрыт настройками", style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Bold)
                }
            }
            // Сообщение о системном VPN переехало на вкладку «Настройки» ([VpnStatusCard]).
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
        // Кнопки блока (Промпт 88/90/91): «Проверить» (оценка+подбор) и «Не использовать» (исключить текущий
        // сервер из автовыбора/поиска/«Живых» + сразу переключиться). FlowRow переносит их в две строки при
        // крупных системных шрифтах, подписи НЕ сокращаем. Светлые на тёмном блоке — явно нажимаемые.
        Spacer(Modifier.height(4.dp))
        val solid = ButtonDefaults.buttonColors(containerColor = fg, contentColor = bg)
        val subtle = ButtonDefaults.buttonColors(containerColor = fg.copy(alpha = 0.22f), contentColor = fg)
        // КОМПАКТНЫЕ и В ОДИН РЯД (просьба Elyor): мелкие паддинги/иконка/шрифт, оба чипа рядом в одной строке —
        // не отвлекают от активного сервера и от главной кнопки «Самый быстрый».
        val pad = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { if (checking) onCancelCheck() else onCheck() }, colors = solid, contentPadding = pad,
                modifier = Modifier.heightIn(min = 0.dp)) {
                ButtonLabel(if (checking) UiIcon.STOP else UiIcon.REFRESH, if (checking) "Прервать проверку" else "Проверить",
                    iconSize = 12.dp, fontSize = 12.sp)
            }
            if (running && !checking) {
                Button(onClick = onExclude, colors = subtle, contentPadding = pad, modifier = Modifier.heightIn(min = 0.dp)) {
                    ButtonLabel(UiIcon.EYE_OFF, "Не использовать", iconSize = 12.dp, fontSize = 12.sp)
                }
            }
        }
        // Тонкий индикатор процесса замера текущего соединения (кнопка «Проверить»). Неопределённый: проверка —
        // это последовательность шагов (замер активного туннеля → перебор кандидатов), чистой доли нет.
        if (checking) {
            Spacer(Modifier.height(6.dp))
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = fg,
                trackColor = fg.copy(alpha = 0.20f),
                strokeCap = StrokeCap.Round,
            )
        }
        if (checkStatus.isNotEmpty()) {
            Text(checkStatus, style = MaterialTheme.typography.bodySmall, color = fg, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * Блок «Трафик замеров» (Промпт 77) — в САМОМ НИЗУ «Настроек»: КРАТКОЕ предупреждение на зелёном поле +
 * переключатель РЕЖИМА ЭКОНОМИИ и его редактируемые параметры (размер батча, минимум живых, авто-обновление).
 * Замер = скачивание пробника (до 13 МБ/сервер), режим экономии мерит батчами до нескольких живых.
 */
@Composable
private fun TrafficBlock() {
    val context = LocalContext.current
    val settings by SettingsStore.state.collectAsState()
    val d = SettingsStore.DEFAULTS
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        val bg = Color(0xFF1B5E20); val fg = Color(0xFFA5D6A7)
        Column(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                FlatIcon(UiIcon.TRAFFIC, size = 16.dp, color = fg)
                Text("Замер тратит трафик", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = fg)
            }
            Text(
                "Замер скорости = скачивание пробника, до 13 МБ на сервер. Полный тест при старте, ~50 адресов ≈ 300–650 МБ (меряются все живые). Режим экономии ниже мерит батчами и останавливается на нескольких живых.",
                style = MaterialTheme.typography.bodySmall, color = fg,
            )
        }
        SettingsGroupLabel("Экономия трафика")
        BoolSettingRow("Режим экономии трафика", settings.trafficSaveMode, d.trafficSaveMode) {
            SettingsStore.update(context, settings.copy(trafficSaveMode = it))
        }
        if (settings.trafficSaveMode) {
            Text(
                "Мерим лучших по пингу батчами; набрали нужное число живых — стоп. Полный тест при старте больше не гоняет всех.",
                style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
            )
            IntSettingRow("Мерить за шаг (top по пингу)", "", settings.trafficSaveBatch, d.trafficSaveBatch, 1, 50) {
                SettingsStore.update(context, settings.copy(trafficSaveBatch = it))
            }
            IntSettingRow("Достаточно живых — стоп", "", settings.trafficSaveMinAlive, d.trafficSaveMinAlive, 1, 20) {
                SettingsStore.update(context, settings.copy(trafficSaveMinAlive = it))
            }
            IntSettingRow("Авто-обновление подписок", "ч", settings.trafficSaveRefreshSec / 3600, d.trafficSaveRefreshSec / 3600, 1, 24) {
                SettingsStore.update(context, settings.copy(trafficSaveRefreshSec = it * 3600))
            }
        }
    }
}

/**
 * Сообщение о ЧУЖОМ системном VPN — на ЦВЕТНОМ поле (только эта надпись), вкладка «Настройки».
 * Цвет по серьёзности: зелёный (мы мимо / нас не касается), янтарный (идём через VPN), красный (наружу
 * никто). Кнопка «Повторить обход» — при неудавшемся обходе/lockdown. Показывается вызывающим только при
 * relation != NONE.
 */
@Composable
private fun VpnStatusCard(vpn: VpnStatus, onRetry: () -> Unit) {
    val text: String; val bg: Color; val fg: Color; val icon: UiIcon
    when {
        vpn.relation == VpnRelation.EXCLUDED -> {
            icon = UiIcon.SHIELD; text = "системный VPN активен, но нас не касается (мы вне его)"; bg = Color(0xFF1B5E20); fg = Color(0xFFA5D6A7)
        }
        vpn.relation == VpnRelation.INSIDE && vpn.bypassed -> {
            icon = UiIcon.SHIELD; text = "системный VPN активен — идём мимо него"; bg = Color(0xFF1B5E20); fg = Color(0xFFA5D6A7)
        }
        vpn.relation == VpnRelation.INSIDE && vpn.noExit -> {
            icon = UiIcon.BLOCK; text = "системный VPN не пропускает трафик, а обход запрещён его настройками (lockdown) — наружу не выходит никто"; bg = Color(0xFF7F1D1D); fg = Color(0xFFFFCDD2)
        }
        vpn.relation == VpnRelation.INSIDE && vpn.bypassFailed -> {
            icon = UiIcon.WARN; text = "обход не удался (lockdown) — идём ЧЕРЕЗ системный VPN, замер = его канал"; bg = Color(0xFF6D4C00); fg = Color(0xFFFFE082)
        }
        vpn.relation == VpnRelation.INSIDE -> {
            icon = UiIcon.WARN; text = "системный VPN активен — трафик и замеры идут ЧЕРЕЗ него (двойной туннель, замер = канал VPN)"; bg = Color(0xFF6D4C00); fg = Color(0xFFFFE082)
        }
        else -> return   // NONE — не показываем (гейт в вызывающем)
    }
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(bg).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            FlatIcon(icon, size = 16.dp, color = fg)
            Text(text, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = fg)
        }
        if (vpn.bypassFailed || vpn.noExit) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { onRetry() }.padding(vertical = 2.dp),
            ) {
                FlatIcon(UiIcon.REFRESH, size = 14.dp, color = fg)
                Text("Повторить обход", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = fg)
            }
        }
    }
}

/**
 * Панель действий. Кнопки крупные; FlowRow переносит их при крупных системных шрифтах.
 * Промпт 91.D: кнопка «Подписки» убрана — «Самый быстрый» сам обновляет источники перед тестом;
 * ручное обновление осталось во вкладке «Настройки» → секция подписок.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActionsBar(
    fullTesting: Boolean,
    running: Boolean,
    refreshingSubs: Boolean,
    onFullTest: () -> Unit,
    onCancelFull: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ГЛАВНОЕ ДЕЙСТВИЕ (просьба Elyor: заметнее) — «Самый быстрый» занимает БОЛЬШУЮ часть ряда (weight),
        // высокая, крупный жирный текст+иконка. «Стоп» — компактная справа, В ОДНОМ РЯДУ.
        if (fullTesting || refreshingSubs) {
            Button(onClick = onCancelFull, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 12.dp)) {
                ButtonLabel(UiIcon.STOP, if (refreshingSubs) "Обновление…" else "Прервать", iconSize = 20.dp, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }
        } else {
            Button(onClick = onFullTest, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 14.dp)) {
                ButtonLabel(UiIcon.PLAY, "Самый быстрый", iconSize = 24.dp, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
        OutlinedButton(onClick = onStop, enabled = running, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)) {
            ButtonLabel(UiIcon.STOP, "Стоп")
        }
    }
}

// Ширины колонок таблицы серверов — общие для шапки и строк (чтобы колонки совпадали).
// Колонки: Сервер | Мбит/с | ▶ (#, Пров., Пинг убраны по решению — на телефоне зажимали имя).
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

/** Цвет разделителя = onSurface с низкой альфой: выводится из фона секции (не серым), в тёмной теме
 *  onSurface светлый → линия светлее фона. Ориентир 0.10. */
@Composable
private fun dividerColor(): Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)

/**
 * Тонкий разделитель ВНИЗУ элемента: ровно 1 физический пиксель (strokeWidth=1px, НЕ 1.dp — на плотности
 * ~3.5 стал бы трёхпиксельным). Рисуется в существующем отступе (высоту строки не увеличивает), с
 * горизонтальным отступом от краёв (не касается скруглений). drawBehind → под контентом строки, поэтому
 * подсветка активного сервера (непрозрачный primaryContainer поверх) перекрывает линию у своих границ.
 */
private fun Modifier.bottomHairline(color: Color): Modifier = this.drawBehind {
    val inset = 12.dp.toPx()
    val y = size.height - 0.5f
    drawLine(color, Offset(inset, y), Offset(size.width - inset, y), strokeWidth = 1f)
}

/**
 * Прилипающий заголовок секции с фоном секции (непрозрачным — не просвечивает строками) и счётчиком.
 * [roundedBottom]=true (свёрнутая секция) — скруглены все углы; иначе только верхние (снизу примыкают строки).
 */
@Composable
private fun SectionHeader(title: String, bg: Color, roundedBottom: Boolean, arrow: String?, onClick: (() -> Unit)?) {
    val shape = if (roundedBottom) RoundedCornerShape(14.dp) else RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)
    // Линия под прилипшим заголовком — только у РАЗВЁРНУТОЙ секции (снизу идут строки); у свёрнутой
    // (roundedBottom) её нет, чтобы не пересекать скруглённый низ пилюли-заголовка.
    val dc = dividerColor()
    val divider = if (!roundedBottom) Modifier.bottomHairline(dc) else Modifier
    Row(
        modifier = Modifier.fillMaxWidth().clip(shape).background(bg).then(divider)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (arrow != null) Text(arrow, style = MaterialTheme.typography.titleMedium, color = TABLE_GRAY)
    }
}

/** Нижняя «крышка» секции: скругляет нижние углы цветного блока. */
@Composable
private fun SectionBottomCap(bg: Color) {
    Box(Modifier.fillMaxWidth().height(12.dp).clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp)).background(bg))
}

/** Шапка таблицы серверов: Сервер | Мбит/с | (кнопка). Колонки как у строк. */
@Composable
private fun ServerTableHeader() {
    CompositionLocalProvider(LocalDensity provides cappedDensity()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Сервер", modifier = Modifier.weight(1f), fontSize = TABLE_FONT_SUB, color = TABLE_GRAY, maxLines = 1)
            Text("Мбит/с", modifier = Modifier.width(COL_SPEED), fontSize = TABLE_FONT_SUB, color = TABLE_GRAY, textAlign = TextAlign.End, maxLines = 1)
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
    paused: Boolean = false,           // Промпт 91: исключён из использования («Не использовать»)
    onToggleUse: () -> Unit = {},      // тап по глазу: используется ↔ не используется
    showUseToggle: Boolean = false,    // показывать глаз-переключатель перед скоростью (в листингах)
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
            // Имя полностью (перенос при длинном) + мелко caption под ним. Исключённый — приглушён (без подписей).
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    (if (isActive) "● " else "") + name,
                    fontSize = TABLE_FONT,
                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                    color = if (paused) TABLE_GRAY else Color.Unspecified,
                )
                if (caption.isNotEmpty()) {
                    Text(caption, fontSize = TABLE_FONT_SUB, color = TABLE_GRAY)
                }
            }
            // Глаз-переключатель ДЕЙСТВИЯ (Промпт 91, как кнопка «Не использовать»): иконка = ЧТО СДЕЛАЕТ тап.
            // Включённый сервер → ПЕРЕЧЁРКНУТЫЙ глаз (тап = «Выключить, не использовать»), ЯРКИЙ (primary).
            // Исключённый сервер → ОТКРЫТЫЙ глаз (тап = «Включить обратно»), светло-серый (приглушён).
            if (showUseToggle) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onToggleUse() }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                ) {
                    FlatIcon(
                        if (paused) UiIcon.EYE else UiIcon.EYE_OFF,
                        size = 18.dp,
                        color = if (paused) TABLE_GRAY.copy(alpha = 0.55f) else MaterialTheme.colorScheme.primary,
                    )
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
            // Запуск сервера — ЗАМЕТНАЯ залитая круглая кнопка (Промпт 93): треугольник Play на фоне primary;
            // активный — точка (подключён). ЕДИНСТВЕННЫЙ элемент, по которому тап подключает.
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable { onConnect() },
                contentAlignment = Alignment.Center,
            ) {
                if (isActive) {
                    Box(Modifier.size(10.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.onPrimary))
                } else {
                    FlatIcon(UiIcon.PLAY, size = 16.dp, color = MaterialTheme.colorScheme.onPrimary)
                }
            }
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
    icon: UiIcon? = null,
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
            if (icon != null) FlatIcon(icon, size = 18.dp, color = MaterialTheme.colorScheme.primary)
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
    paused: Boolean,
    uploadMbps: Double?,
    onTogglePause: () -> Unit,
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
            // Промпт 96.B: контент ПРОКРУЧИВАЕМЫЙ — при крупных системных шрифтах он не влезает в высоту
            // диалога и нижние кнопки («Переименовать»/«В стоп-лист») обрезались (выглядели как «пустая кнопка»).
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Промпт 96.D: «Имя от сервера» имеет смысл ТОЛЬКО когда задано своё имя (сверху своё в заголовке,
                // тут исходное). Без переименования строка дублировала заголовок — скрываем.
                if (hasCustomName) DetailRow("Имя от сервера", originalName)
                DetailRow("Протокол", profile.protocol.name)
                DetailRow("Адрес", "${profile.address}:${profile.port}")
                DetailRow("Транспорт", profile.network)
                DetailRow("Безопасность", profile.security)
                DetailRow("SNI", v(profile.sni))
                DetailRow("Fingerprint", v(profile.fingerprint))
                DetailRow("Flow", v(profile.flow))
                // Отдача активного соединения (Промпт 90.B) — только когда это активный сервер и она измерена.
                if (uploadMbps != null && uploadMbps > 0) {
                    DetailRow("Отдача", "${(uploadMbps * 10).roundToInt() / 10.0} Мбит/с")
                }
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
                // «Не использовать» (отложить) — скрыт из «Живых»/автовыбора, ВИДЕН и возвращается в «Все серверы».
                OutlinedButton(onClick = onTogglePause, modifier = Modifier.fillMaxWidth()) {
                    if (paused) ButtonLabel(UiIcon.EYE, "Использовать снова") else ButtonLabel(UiIcon.EYE_OFF, "Не использовать")
                }
                Text(
                    if (paused) "Отложен: не в «Живых» и автовыборе; вернётся в список." else "Отложить: убрать из «Живых» и автовыбора (виден в «Все серверы»).",
                    style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                )
                // Переименование (оверрайд по serverKey; remarks провайдера не трогаем).
                OutlinedButton(onClick = onRename, modifier = Modifier.fillMaxWidth()) {
                    ButtonLabel(UiIcon.PENCIL, "Переименовать")
                }
                if (hasCustomName) {
                    TextButton(onClick = onResetName, modifier = Modifier.fillMaxWidth()) {
                        Text("Вернуть имя от сервера")
                    }
                }
                // Точечная блокировка ИМЕННО этого serverKey (не всех одноимённых) — ОТЛИЧАЕТСЯ от «Не использовать».
                OutlinedButton(onClick = onToggleBlock, modifier = Modifier.fillMaxWidth()) {
                    if (blockedByKey) Text("Убрать из стоп-листа") else ButtonLabel(UiIcon.BLOCK, "В стоп-лист")
                }
                Text(
                    if (blockedByKey) "В стоп-листе: скрыт отовсюду; снять — здесь или Настройки → Стоп-лист."
                    else "Стоп-лист: скрыть ОТОВСЮДУ (возврат через Настройки → Стоп-лист).",
                    style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                )
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
        // Ступенчатый повтор (Промпт 77): после ПЕРВОГО полного топа замеряем только top-N по скорости.
        IntSettingRow("Повторный замер: top-N по скорости", "", settings.normalTopBatch, d.normalTopBatch, 1, 100) {
            onChange(settings.copy(normalTopBatch = it))
        }
        // Правило «держать лучший» (Промпт 90): переключаться, если лучший быстрее текущего КРАТНО (в разы).
        DoubleSettingRow("Держать лучший: во сколько раз быстрее", "×", settings.keepBestMultiplier, d.keepBestMultiplier, 1.0, 100.0) {
            onChange(settings.copy(keepBestMultiplier = it))
        }

        SettingsGroupLabel("Соединение и отдача")
        IntSettingRow("Интервал проверки соединения", "с", settings.connectionCheckIntervalSec, d.connectionCheckIntervalSec, 30, 3600) {
            onChange(settings.copy(connectionCheckIntervalSec = it))
        }
        DoubleSettingRow("Минимальная отдача", "Мбит/с", settings.minUploadMbps, d.minUploadMbps, 0.0, 1000.0) {
            onChange(settings.copy(minUploadMbps = it))
        }
        IntSettingRow("Объём замера отдачи", "МБ", settings.uploadMeasureMb, d.uploadMeasureMb, 1, 100) {
            onChange(settings.copy(uploadMeasureMb = it))
        }
        UrlSettingRow("URL приёма отдачи", settings.uploadProbeUrl, d.uploadProbeUrl) {
            onChange(settings.copy(uploadProbeUrl = it))
        }

        SettingsGroupLabel("Прочее")
        BoolSettingRow("Автозапуск при старте", settings.autoStartOnLaunch, d.autoStartOnLaunch) {
            onChange(settings.copy(autoStartOnLaunch = it))
        }
        BoolSettingRow("Сообщать о новых версиях", settings.notifyNewVersions, d.notifyNewVersions) {
            onChange(settings.copy(notifyNewVersions = it))
        }
        BoolSettingRow("Обходить системный VPN", settings.bypassSystemVpn, d.bypassSystemVpn) {
            onChange(settings.copy(bypassSystemVpn = it))
        }
        Text(
            "Если включён другой (платный) VPN — вести наш туннель и замеры МИМО него (иначе туннель-в-туннеле: медленнее, и падение внешнего VPN роняет наш прокси). Выключите, если нужно наоборот.",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
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
    // Подтверждение, когда слово скрывает почти все серверы (провайдер мог назвать их все одинаково) —
    // защита от неожиданного «скрылось всё». Хранит (слово, сколько скроет, всего).
    var confirm by remember { mutableStateOf<Triple<String, Int, Int>?>(null) }
    val total = serverNames.size

    fun tryAdd() {
        val w = input.trim()
        if (w.isBlank()) return
        val n = blocklist.countForWord(w, serverNames)
        // «почти все» = ≥80% и больше одного; при малых списках (≤2) не мешаем.
        if (total > 2 && n >= 2 && n >= (total * 4 + 4) / 5) confirm = Triple(w, n, total)
        else { onAddWord(w); input = "" }
    }

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
            Button(onClick = { tryAdd() }, enabled = input.isNotBlank()) { Text("+") }
        }

        Spacer(Modifier.height(4.dp))
        SettingsGroupLabel("Персонально заблокированные")
        if (blocklist.servers.isEmpty()) {
            Text(
                "Пусто. Заблокировать конкретный сервер: тап по строке → «В стоп-лист».",
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

    confirm?.let { (w, n, m) ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Скрыть почти все?") },
            text = {
                Text(
                    "Слово «$w» скроет $n из $m серверов — почти все. Похоже, провайдер называет их все так. " +
                        "Чтобы скрыть только часть, используйте более узкое слово или точечно (тап по строке → «В стоп-лист»).",
                )
            },
            confirmButton = { TextButton(onClick = { onAddWord(w); input = ""; confirm = null }) { Text("Всё равно добавить") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Отмена") } },
        )
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
 * Секция «🛡️ Автомониторинг». Признак жизни в шапке (обновляется на месте, не в журнал) + настройки +
 * журнал ТОЛЬКО событий (смены состояния/происшествия/смены сервера). Описание режима зависит от того,
 * включено ли автопереключение.
 */
@Composable
private fun MonitorSection(
    settings: AppSettings,
    heartbeat: MonitorHeartbeat,
    onChange: (AppSettings) -> Unit,
    log: List<LogEvent>,
    onClearLog: () -> Unit,
) {
    val d = SettingsStore.DEFAULTS
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Честное описание текущего режима.
        Text(
            if (settings.monitorEnabled)
                "Монитор следит за туннелем и при падении сам переключает на живого кандидата (только при активном прокси)."
            else
                "Выключен — не выполняет ничего (ни проверок, ни замеров), бережём батарею.",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
        // Признак жизни: время последней проверки, состояние, число циклов.
        if (settings.monitorEnabled) {
            val t = if (heartbeat.lastCheckMs > 0) monitorTimeFmt.format(Date(heartbeat.lastCheckMs)) else "—"
            Text(
                "● проверка: $t · ${heartbeat.state} · циклов: ${heartbeat.cycles}",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
            )
        }

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
            SettingsGroupLabel("Журнал событий (${log.size})")
            if (log.isNotEmpty()) TextButton(onClick = onClearLog) { Text("Очистить") }
        }
        if (log.isEmpty()) {
            Text(
                "Происшествий не было — журнал пуст. Здесь появятся падения, восстановления и смены сервера.",
                style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY,
            )
        } else {
            log.asReversed().forEach { MonitorLogRow(it) }   // свежие сверху
        }
    }
}

/** Одна запись журнала: время + заголовок (цвет по виду), под ним — детали/числа/причина. */
@Composable
private fun MonitorLogRow(e: LogEvent) {
    val color = when (e.kind) {
        "switch" -> MaterialTheme.colorScheme.primary
        "error" -> Color(0xFFD32F2F)
        "net" -> Color(0xFFF9A825)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(
            "${monitorTimeFmt.format(Date(e.ts))} · ${e.text}",
            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = color,
        )
        if (e.detail.isNotEmpty()) {
            Text(e.detail, style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
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
    var shownDetailId by remember { mutableStateOf<String?>(null) }   // тап по точке разворачивает ПОДРОБНОСТИ
    var expandedErrId by remember { mutableStateOf<String?>(null) }   // тап по ошибке разворачивает её ЦЕЛИКОМ
    val red = Color(0xFFD32F2F)
    val green = Color(0xFF2E7D32)
    val amber = Color(0xFFF9A825)
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (sources.isEmpty()) {
            Text("Нет источников.", style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
        }
        sources.forEach { s ->
            val hasData = s.serverCount > 0
            val lastFailed = s.lastOutcome != null && s.lastOutcome != SourceOutcome.OK
            // Промпт 81.C: КРАСНАЯ только если последняя попытка неудачна И данных нет; данные есть, а попытка
            // не удалась → ЖЁЛТАЯ (показаны прежние серверы); успех → зелёная; ни разу/выключена → серая.
            val dotColor = when {
                !s.enabled -> TABLE_GRAY
                s.lastOutcome == SourceOutcome.OK -> green
                s.lastOutcome == null -> TABLE_GRAY
                hasData -> amber
                else -> red
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Тап по точке = свернуть/развернуть ПОДРОБНОСТИ (постадийная диагностика).
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
                        "${s.serverCount} серв" + (if (s.url.isBlank()) " · локальная" else ""),
                        style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                    )
                    // Промпт 81.C: время последней УДАЧИ и последней ПОПЫТКИ — РАЗДЕЛЬНО (раньше одно на двоих).
                    Text(
                        "удача: ${s.lastOkTs ?: "—"} · попытка: ${s.lastRefreshTs ?: "не обновлялась"}",
                        style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                    )
                    // Ошибка последней НЕУДАЧНОЙ попытки. Свёрнута в одну строку, тап — развернуть (81.C).
                    // Цвет: красный, если данных нет; янтарный, если показаны прежние серверы / это лимит частоты.
                    if (lastFailed && s.lastError != null) {
                        val errColor = if (hasData || s.lastOutcome == SourceOutcome.RATE_LIMITED) amber else red
                        val errExpanded = expandedErrId == s.id
                        Text(
                            (if (errExpanded) "▾ " else "▸ ") + s.lastError,
                            style = MaterialTheme.typography.bodySmall, color = errColor,
                            maxLines = if (errExpanded) Int.MAX_VALUE else 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { expandedErrId = if (errExpanded) null else s.id }
                                .padding(vertical = 1.dp),
                        )
                        // Время, когда случилась ошибка (= время последней попытки), + пояснение про прежние серверы.
                        Text(
                            "когда: ${s.lastRefreshTs ?: "—"}" +
                                (if (hasData) " · показаны прежние серверы (${s.serverCount})" else ""),
                            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                        )
                    }
                    // Подробности (постадийно) — по тапу на точку.
                    if (shownDetailId == s.id && s.lastDetail != null) {
                        Text(
                            s.lastDetail,
                            style = MaterialTheme.typography.bodySmall,
                            color = TABLE_GRAY,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                    }
                }
                // Копировать URL источника (Промпт 82) — только для подписок с адресом (локальные без URL).
                if (s.url.isNotBlank()) {
                    TextButton(
                        onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(s.url))
                            android.widget.Toast.makeText(ctx, "URL скопирован", android.widget.Toast.LENGTH_SHORT).show()
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                    ) { Text("⧉") }
                }
                Switch(checked = s.enabled, onCheckedChange = { onToggle(s.id, it) })
                TextButton(onClick = { onDeleteRequest(s) }, contentPadding = PaddingValues(horizontal = 6.dp)) { Text("✗") }
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
            ButtonLabel(UiIcon.DOC, "Импорт из файла")
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

private val UPDATE_DATE_FMT get() = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US)

/** Метрическая (мобильная) сеть — на ней спрашиваем согласие перед скачиванием ~50 МБ APK. */
private fun isMeteredNetwork(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered ?: false

/** Строка «подпись … значение» для блока версий. */
@Composable
private fun InfoLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
    }
}

/**
 * Секция «ℹ️ О приложении» — ТОЛЬКО версии (приложение/ядро). Кнопка «Проверить обновление» и её
 * результат вынесены на верхний уровень вкладки, ниже «Трафик» ([UpdateCheckSection]) — по просьбе:
 * держать проверку обновлений на виду, а не в подменю.
 */
@Composable
private fun AboutSection() {
    val coreVersion = remember { UpdateStore.coreVersion() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel("Версия")
        InfoLine("Приложение", UpdateStore.appVersion())
        InfoLine("Ядро xray", coreVersion ?: "недоступна")
    }
}

private fun copyToast(ctx: Context, msg: String) =
    android.widget.Toast.makeText(ctx, msg, android.widget.Toast.LENGTH_SHORT).show()

/** Кликабельный текст: тап копирует [copy] в буфер + Toast. */
@Composable
private fun CopyText(text: String, copy: String = text, mono: Boolean = false, modifier: Modifier = Modifier) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontFamily = if (mono) FontFamily.Monospace else null,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { clip.setText(AnnotatedString(copy)); copyToast(ctx, "Скопировано: $copy") }
            .padding(vertical = 2.dp, horizontal = 2.dp),
    )
}

/**
 * Секция «Локальный прокси» — адрес нашего SOCKS/HTTP для приложений НА ЭТОМ ЖЕ телефоне (127.0.0.1).
 * Заменяет отсутствующую «плашку про туннель»: она (VpnStatusCard) появляется только при ЧУЖОМ системном
 * VPN, а этот блок в Настройках виден всегда и даёт адрес+порт (тап — скопировать).
 */
@Composable
private fun LocalProxySection() {
    val proxy by ProxyState.state.collectAsState()
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (proxy.running) "Прокси запущен — приложения на этом телефоне могут ходить через него."
            else "Прокси сейчас не запущен (адрес заработает после запуска на «Главной»).",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
        SettingsGroupLabel("Адрес прокси (тап — скопировать)")
        ProxyAddrRow("SOCKS5 (рекомендуется)", "${XrayConfig.LISTEN}:${XrayConfig.SOCKS_PORT}")
        ProxyAddrRow("HTTP", "${XrayConfig.LISTEN}:${XrayConfig.HTTP_PORT}")
        Text(
            "Хост 127.0.0.1 — это сам телефон. Для полного обхода включите DNS через прокси (см. «Настройте браузер»).",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )
    }
}

@Composable
private fun ProxyAddrRow(label: String, value: String) {
    val clip = LocalClipboardManager.current
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .clickable { clip.setText(AnnotatedString(value)); copyToast(ctx, "Скопировано: $value") }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
        }
        FlatIcon(UiIcon.COPY, size = 16.dp, color = MaterialTheme.colorScheme.primary)
    }
}

/** Кликабельная ссылка (подчёркнута, primary): открывает URL во внешнем браузере/приложении. */
@Composable
private fun LinkText(text: String, url: String) {
    val ctx = LocalContext.current
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable {
                try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (_: Exception) {}
            }
            .padding(vertical = 2.dp, horizontal = 2.dp),
    )
}

/**
 * Секция «Настройте браузер и Telegram» — направить приложения в наш локальный SOCKS5-прокси. Максимум
 * удобства (Промпт 75): ПЕРВОЙ строкой адрес+порт (копируется тапом), ссылки на авторитетные сборки
 * браузеров/ТГ, about:config отдельной копируемой строкой, все значения копируются по тапу.
 */
@Composable
private fun BrowserSetupSection() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // ПЕРВАЯ строка — адрес+порт + краткая инструкция. Тип прокси везде SOCKS5.
        ProxyAddrRow("Прокси (SOCKS5)", "${XrayConfig.LISTEN}:${XrayConfig.SOCKS_PORT}")
        Text(
            "Впишите этот адрес и порт (тип SOCKS5) в прокси-настройки приложения. ВСЕ значения ниже копируются по тапу.",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )

        SettingsGroupLabel("Браузер (движок Firefox)")
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LinkText("Iceraven ↗", "https://github.com/fork-maintainers/iceraven-browser/releases")
            LinkText("Fennec ↗", "https://f-droid.org/packages/org.mozilla.fennec_fdroid/")
        }
        Text("1. Новая вкладка → вставьте в адресную строку и откройте:", style = MaterialTheme.typography.bodySmall)
        CopyText("about:config", mono = true)
        Text("Примите предупреждение. 2. Для каждой строки: скопируйте ключ, вставьте в поиск, задайте значение:",
            style = MaterialTheme.typography.bodySmall)
        ConfigRow("network.proxy.type", "1")
        ConfigRow("network.proxy.socks", XrayConfig.LISTEN)
        ConfigRow("network.proxy.socks_port", XrayConfig.SOCKS_PORT.toString())
        ConfigRow("network.proxy.socks_version", "5")
        ConfigRow("network.proxy.socks_remote_dns", "true")
        ConfigRow("network.proxy.allow_hijacking_localhost", "true")
        Text(
            "Готово — перезапустите вкладку. Проверка: откройте 2ip.ru, адрес и страна должны быть зарубежными. Выключить обход — network.proxy.type = 0.",
            style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
        )

        SettingsGroupLabel("Telegram")
        LinkText("Telegram ↗", "https://telegram.org/dl/android")
        Text(
            "Настройки → Данные и память → Настройка прокси → Добавить прокси → SOCKS5, затем впишите Сервер и Порт (ниже — копируются тапом):",
            style = MaterialTheme.typography.bodySmall,
        )
        ProxyAddrRow("Сервер", XrayConfig.LISTEN)
        ProxyAddrRow("Порт", XrayConfig.SOCKS_PORT.toString())
        Text("Логин и пароль оставьте пустыми.", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
    }
}

@Composable
private fun ConfigRow(key: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        CopyText(key, mono = true, modifier = Modifier.weight(1f))
        Text("→", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
        CopyText(value, mono = true)
    }
}

/**
 * Проверка обновления (Промпт 70) — ВЕРХНЕУРОВНЕВЫЙ блок вкладки «Настройки», ниже «Трафик»: кнопка
 * «Проверить обновление», результат последней проверки с временем; при доступном обновлении — размер,
 * изменения, скачивание с прогрессом/отменой и «Установить» (СИСТЕМНЫЙ установщик — подтверждает
 * пользователь). Проверки суммы и подписи делает [UpdateInstaller] ДО передачи файла установщику.
 */
// ─────────────────────── Отчёты о сбоях (Промпт 93.I) ───────────────────────

private fun copyToClipboard(context: Context, text: String) {
    runCatching {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("Отчёт о сбое", text))
    }
}

private fun shareText(context: Context, text: String) {
    runCatching {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "XrayProxyDroid — отчёт о сбое")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(send, "Поделиться отчётом").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Промпт 97.A/B: открыть почтовый клиент с готовым письмом (адрес получателя в mailto: — задан даже если
 * приложение игнорирует EXTRA_EMAIL). Отправку жмёт человек. Возвращает false, если почтового клиента нет
 * или он не открылся — тогда UI показывает адрес текстом и предлагает «Скопировать» (НЕ системную ошибку).
 * Полагаемся на перехват ActivityNotFoundException, а не на resolveActivity: в манифесте нет <queries>,
 * и resolveActivity на Android 11+ вернул бы null даже при наличии клиента.
 */
private fun emailReport(context: Context, subject: String, body: String): Boolean =
    try {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = android.net.Uri.parse("mailto:" + android.net.Uri.encode(CrashReporter.DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_EMAIL, arrayOf(CrashReporter.DEVELOPER_EMAIL))
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, body)
        }
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (_: Throwable) {
        false
    }

/**
 * Промпт 97.A/D/E: перед отправкой показать человеку, ЧТО именно уйдёт (полный текст, прокрутка), дать
 * необязательное поле «что делал перед сбоем» (уходит блоком сверху), показать адрес получателя ТЕКСТОМ.
 * «Открыть почту» формирует письмо; если клиента нет — понятное сообщение + «Скопировать» с видимым адресом.
 * «Поделиться» (мессенджер) остаётся на виду — запасной путь мимо туннеля (Промпт 97.C). Автоотправки нет.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SendToDeveloperDialog(report: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var description by remember { mutableStateOf("") }
    var noMailClient by remember { mutableStateOf(false) }
    val subject = remember { CrashReporter.emailSubject() }
    val body = CrashReporter.emailBody(description, report)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Отправить разработчику") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("Отчёт отправляется на адрес:", style = MaterialTheme.typography.bodyMedium)
                Text(
                    CrashReporter.DEVELOPER_EMAIL,
                    style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { copyToClipboard(context, CrashReporter.DEVELOPER_EMAIL); copyToast(context, "Адрес скопирован") }
                        .padding(vertical = 2.dp),
                )
                Text(
                    "Ничего не отправляется автоматически — письмо отправляете вы вручную одним нажатием в почте.",
                    style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it },
                    label = { Text("Что вы делали перед сбоем (необязательно)") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                Text("Будет отправлено:", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
                Text(
                    body, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(8.dp),
                )
                if (noMailClient) {
                    Text(
                        "Почтовое приложение не открылось. Скопируйте отчёт (адрес выше) и отправьте удобным способом, либо нажмите «Поделиться».",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    )
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = {
                        if (emailReport(context, subject, body)) onDismiss() else noMailClient = true
                    }) { Text("Открыть почту") }
                    OutlinedButton(onClick = {
                        copyToClipboard(context, "${CrashReporter.DEVELOPER_EMAIL}\n\n$body"); copyToast(context, "Скопировано")
                    }) { Text("Скопировать") }
                    OutlinedButton(onClick = { shareText(context, body) }) { Text("Поделиться") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

/**
 * Заметная полоса на ГЛАВНОЙ после падения (Промпт 93.B): приложение аварийно завершилось, есть отчёт.
 * Кнопки «Скопировать»/«Поделиться» действуют на ПОСЛЕДНИЙ отчёт. Никакой автоотправки. Крестик — снять
 * (пометить показанным; для следующего падения покажется снова).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrashBanner(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var showSend by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFB71C1C))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "Приложение аварийно завершилось — сохранён отчёт о сбое.",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val btn = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFFB71C1C))
                val pad = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                Button(onClick = { if (CrashReporter.reports().firstOrNull() != null) showSend = true },
                    colors = btn, contentPadding = pad) { Text("Отправить разработчику") }
                Button(onClick = { CrashReporter.reports().firstOrNull()?.let { copyToClipboard(context, CrashReporter.read(it)) } },
                    colors = btn, contentPadding = pad) { Text("Скопировать") }
                Button(onClick = { CrashReporter.reports().firstOrNull()?.let { shareText(context, CrashReporter.read(it)) } },
                    colors = btn, contentPadding = pad) { Text("Поделиться") }
            }
            Text("Данные пересылаются только по вашему решению.", style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFCDD2))
        }
        Text("✕", color = Color.White, fontWeight = FontWeight.Bold,
            modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDismiss() }.padding(8.dp))
    }
    if (showSend) {
        val report = remember { CrashReporter.reports().firstOrNull()?.let { CrashReporter.read(it) } }
        if (report != null) SendToDeveloperDialog(report = report, onDismiss = { showSend = false })
        else showSend = false
    }
}

/**
 * Полоса «доступна новая версия» (Промпт 93.K) — в потоке экрана НАД списками (не модальное окно). Номер
 * версии + «Обновить» (на экран обновления) + крестик (снять для ЭТОЙ версии; для следующей появится снова).
 * Одно событие в двух местах (главная+настройки) — состояние в UpdateStore, отклонение снимает обе + уведомление.
 */
@Composable
private fun UpdateBanner(versionName: String, onDismiss: () -> Unit) {
    // ОДИН ТАП по плашке = скачать и установить (без лишних тапов): контроллер проверит при необходимости,
    // скачает, сверит сумму+подпись и откроет системный установщик. Прогресс/отмена — прямо на плашке.
    val context = LocalContext.current
    val phase by UpdateFlowController.phase.collectAsState()
    val dl = phase as? UpdateFlowController.Phase.Downloading
    val busy = dl != null || phase is UpdateFlowController.Phase.Checking
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(enabled = !busy) { UpdateFlowController.oneTap(context) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val fg = MaterialTheme.colorScheme.onPrimaryContainer
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Доступна новая версия", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = fg)
                val sub = when (val p = phase) {
                    is UpdateFlowController.Phase.Checking -> "Проверяю обновление…"
                    is UpdateFlowController.Phase.Downloading ->
                        if (p.total > 0) "Скачиваю ${p.done * 100 / p.total}%" else "Скачиваю…"
                    is UpdateFlowController.Phase.NeedPermission -> "Разрешите установку и нажмите ещё раз"
                    is UpdateFlowController.Phase.Failed -> p.message
                    else -> "$versionName · нажмите, чтобы обновить"
                }
                Text(sub, style = MaterialTheme.typography.bodySmall, color = fg)
            }
            if (busy) {
                Text("Отмена", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = fg,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { UpdateFlowController.cancel() }.padding(8.dp))
            } else {
                FlatIcon(UiIcon.PLAY, size = 18.dp, color = fg)   // намёк «тапни, чтобы обновить»
                Text("✕", color = fg, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(6.dp)).clickable { onDismiss() }.padding(8.dp))
            }
        }
        if (dl != null && dl.total > 0) {
            LinearProgressIndicator(
                progress = { (dl.done.toFloat() / dl.total).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Раздел «Отчёты о сбоях» в настройках (Промпт 93.B): список сохранённых, просмотр, «Скопировать»/«Поделиться»,
 * удаление. Данные уже вычищены от адресов/учёток при записи (Sanitizer).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CrashReportsSection() {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    val reports = remember(refresh) { CrashReporter.reports() }
    var openText by remember { mutableStateOf<String?>(null) }
    var sendText by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (reports.isEmpty()) {
            Text("Отчётов о сбоях нет.", style = MaterialTheme.typography.bodyMedium, color = TABLE_GRAY)
        } else {
            for (f in reports) {
                val firstLines = CrashReporter.read(f).lineSequence().drop(1).take(2).joinToString(" · ")
                Column(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .background(lerp(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.errorContainer, 0.25f))
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(f.name.removePrefix("crash-").removeSuffix(".txt"), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                    Text(firstLines, style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        TextButton(onClick = { openText = CrashReporter.read(f) }) { Text("Показать") }
                        TextButton(onClick = { sendText = CrashReporter.read(f) }) { Text("Отправить разработчику") }
                        TextButton(onClick = { copyToClipboard(context, CrashReporter.read(f)) }) { Text("Скопировать") }
                        TextButton(onClick = { shareText(context, CrashReporter.read(f)) }) { Text("Поделиться") }
                        TextButton(onClick = { CrashReporter.delete(f); refresh++ }) { Text("Удалить") }
                    }
                }
            }
            OutlinedButton(onClick = { CrashReporter.clearAll(); refresh++ }, modifier = Modifier.fillMaxWidth()) {
                Text("Очистить все")
            }
        }
    }
    val dlg = openText
    if (dlg != null) {
        AlertDialog(
            onDismissRequest = { openText = null },
            title = { Text("Отчёт о сбое") },
            text = {
                Text(dlg, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()))
            },
            confirmButton = { TextButton(onClick = { copyToClipboard(context, dlg) }) { Text("Скопировать") } },
            dismissButton = { TextButton(onClick = { openText = null }) { Text("Закрыть") } },
        )
    }
    val send = sendText
    if (send != null) SendToDeveloperDialog(report = send, onDismiss = { sendText = null })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpdateCheckSection() {
    val context = LocalContext.current
    val activity = context as ComponentActivity
    val record by UpdateStore.record.collectAsState()
    val live by UpdateStore.live.collectAsState()

    var checking by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var downloaded by remember { mutableStateOf(0L) }
    var totalBytes by remember { mutableStateOf(-1L) }
    var cancelFlag by remember { mutableStateOf(false) }
    var readyFile by remember { mutableStateOf<File?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var detailsExpanded by remember { mutableStateOf(false) }

    fun startCheck() {
        if (checking || downloading) return
        checking = true; message = null; readyFile = null
        Thread {
            val report = runCatching { UpdateChecker.check(context) }
                .getOrElse { com.picosoft.xrayproxydroid.update.CheckReport(
                    UpdateCheckResult.Error(com.picosoft.xrayproxydroid.update.UpdateErrorKind.API_UNAVAILABLE, it.message ?: ""),
                    "исключение: ${it.javaClass.simpleName}: ${it.message}") }
            UpdateStore.apply(context, report, System.currentTimeMillis())
            activity.runOnUiThread { checking = false }
        }.start()
    }

    fun install(file: File) {
        if (!UpdateInstaller.canInstall(context)) {
            message = "Нужно разрешить установку приложений из этого источника — открываю системный экран. После разрешения нажмите «Установить»."
            UpdateInstaller.openInstallPermissionSettings(context)
            return
        }
        UpdateInstaller.launchInstaller(context, file)
        // Промпт 93.J: обновление устанавливается → снять уведомление и полосу для этой версии.
        NotificationHelper.cancelUpdate(context); UpdateStore.markDismissed(context)
    }

    fun startDownload(available: UpdateCheckResult.Available) {
        if (downloading) return
        downloading = true; cancelFlag = false; downloaded = 0L
        totalBytes = available.sizeBytes; message = null; readyFile = null
        Thread {
            var lastShown = 0L
            val outcome = UpdateInstaller.download(
                context, available,
                isCancelled = { cancelFlag },
                onProgress = { d, t ->
                    if (d == t || d - lastShown >= 512 * 1024) {   // не флудим рекомпозицией на каждый чанк
                        lastShown = d; downloaded = d
                        if (t > 0) totalBytes = t
                    }
                },
            )
            activity.runOnUiThread {
                downloading = false
                when (outcome) {
                    is UpdateInstaller.DownloadOutcome.Ok -> {
                        readyFile = outcome.file
                        // Промпт: «Скачать и установить» без второго тапа — сумма и подпись уже сверены, сразу
                        // запускаем системный установщик (подтверждение установки — уже в системном окне Android).
                        message = "Файл проверен (сумма и подпись). Открывается системный установщик — подтвердите установку."
                        install(outcome.file)
                    }
                    is UpdateInstaller.DownloadOutcome.Fail -> {
                        readyFile = null
                        message = if (outcome.kind == com.picosoft.xrayproxydroid.update.UpdateErrorKind.CANCELLED) null
                        else outcome.kind.text + if (outcome.detail.isNotBlank()) "\n${outcome.detail}" else ""
                    }
                }
            }
        }.start()
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel("Обновление")
        if (record.checkedAtMs > 0) {
            Text(record.summary, style = MaterialTheme.typography.bodyMedium)
            // Честный случай (80.C): прямой путь к GitHub заблокирован, а туннель не запущен — узел жив,
            // недоступен ПУТЬ. Предлагаем подключиться и повторить, а не показываем «GitHub недоступен».
            if ((live as? UpdateCheckResult.Error)?.kind == com.picosoft.xrayproxydroid.update.UpdateErrorKind.NO_TUNNEL) {
                Text(
                    "Запустите прокси на главном экране (▶), затем нажмите «Проверить обновление» ещё раз — проверка пойдёт через туннель.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                )
            }
            Text("Проверено: ${UPDATE_DATE_FMT.format(Date(record.checkedAtMs))}",
                style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
            // Полная постадийная диагностика по адресам обновления (77.E) — разбор в одно нажатие.
            if (record.details.isNotBlank()) {
                Text(
                    if (detailsExpanded) "▾ Подробности (ступени, host, редиректы)" else "▸ Подробности (ступени, host, редиректы)",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clip(RoundedCornerShape(4.dp)).clickable { detailsExpanded = !detailsExpanded }.padding(vertical = 2.dp),
                )
                if (detailsExpanded) {
                    Text(record.details, style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace, color = TABLE_GRAY)
                }
            }
        } else {
            Text("Проверка ещё не выполнялась.", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
        }

        // ЕДИНАЯ основная кнопка НА ОДНОМ МЕСТЕ (Промпт): нет обновления → «Проверить обновление»; после
        // проверки, если обновление есть → «Скачать и установить» (качает и СРАЗУ запускает системный
        // установщик, без второго тапа в приложении); файл скачан, но установка не запустилась (не выдано
        // разрешение) → «Установить» для повтора после выдачи разрешения.
        val avail = live as? UpdateCheckResult.Available
        Button(
            onClick = {
                when {
                    checking || downloading -> {}
                    readyFile != null -> install(readyFile!!)
                    avail != null -> startDownload(avail)
                    else -> startCheck()
                }
            },
            enabled = !checking && !downloading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            when {
                checking -> Text("Проверяю…")
                downloading -> Text("Скачиваю…")
                readyFile != null -> ButtonLabel(UiIcon.PLAY, "Установить")
                avail != null -> ButtonLabel(UiIcon.PLAY, "Скачать и установить")
                else -> ButtonLabel(UiIcon.REFRESH, "Проверить обновление")
            }
        }

        // Подтверждённое обновление (update.json): версия, размер, сеть, изменения + прогресс скачивания.
        // Сами кнопки скачивания/установки убраны — действие на ЕДИНОЙ кнопке выше.
        if (avail != null) {
            Text("Новая версия: ${avail.versionName}",
                style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            if (avail.notes.isNotBlank()) Text(avail.notes, style = MaterialTheme.typography.bodySmall)

            if (downloading) {
                val frac = if (totalBytes > 0) (downloaded.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
                if (totalBytes > 0) LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(if (totalBytes > 0) "Скачиваю… ${(frac * 100).roundToInt()}%" else "Скачиваю…",
                    style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
                OutlinedButton(onClick = { cancelFlag = true }, modifier = Modifier.fillMaxWidth()) { Text("Отменить") }
            } else if (readyFile == null) {
                // Простая подсказка про системные окна Android (без размеров/деталей процесса — просьба Elyor).
                Text(
                    "При установке Android спросит разрешение — разрешите (можно «Установить без проверки» или с проверкой). После обновления откройте приложение — тоже просто разрешите.",
                    style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY,
                )
            }
        }

        // Новее по метке, но без update.json — только сообщаем (менее надёжный путь).
        (live as? UpdateCheckResult.AvailableUnverified)?.let { u ->
            Text(
                "Есть версия новее (метка ${u.tag}), но в релизе нет update.json — надёжно проверить и установить нельзя. Обновите вручную со страницы релизов.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
            )
        }

        message?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
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
