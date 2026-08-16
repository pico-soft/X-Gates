package com.picosoft.xrayproxydroid

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.ui.TestProgress
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import com.picosoft.xrayproxydroid.xray.FullTestRunner
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsStore.init(applicationContext)   // загрузить настройки до первого замера
        enableEdgeToEdge()
        setContent {
            XrayProxyDroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    BootScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private fun serverLabel(p: ServerProfile): String =
    "${p.protocol}  ·  ${p.remarks.ifBlank { p.address }}  ·  ${p.address}:${p.port}"

/** Короткое имя сервера для строки списка (без адреса/порта — они уходят во вторую строку). */
private fun serverName(p: ServerProfile): String = p.remarks.ifBlank { p.address }

/** Мелкая подпись: протокол · network · security (для строк с одинаковым именем и для статус-бокса). */
private fun protoNetSec(p: ServerProfile): String = "${p.protocol} · ${p.network} · ${p.security}"

/**
 * Подпись-дискриминатор для ОДИНАКОВЫХ имён (иначе строки визуально неотличимы, выбрать нельзя).
 * Возвращает serverKey → «протокол · network · security». Только для имён, встречающихся >1 раза.
 * Если и эта подпись совпала внутри группы — добавляем последние 4 символа serverKey (гарантия уникальности).
 * Уникальные имена в map не попадают.
 */
private fun buildDiscriminators(servers: List<ServerProfile>): Map<String, String> {
    val result = HashMap<String, String>()
    for ((_, group) in servers.groupBy { serverName(it) }) {
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

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    val proxy by ProxyState.state.collectAsState()
    val settings by SettingsStore.state.collectAsState()   // живое применение порогов в UI

    var subUrl by remember { mutableStateOf("https://maxim-zodchy.ru/sub-black.php") }
    var subStatus by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(SubscriptionManager.allServers(context)) }

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

    fun effPing(p: ServerProfile): Int? = pingResults[SubscriptionManager.serverKey(p)] ?: p.pingMs
    fun effSpeed(p: ServerProfile): Double? = speedResults[SubscriptionManager.serverKey(p)] ?: p.speedMbps

    fun startServer(p: ServerProfile) {
        val cfg = runCatching { XrayConfigBuilder.build(p) }.getOrElse {
            subStatus = "ошибка конфига: ${it.message}"
            return
        }
        ensureNotifPermission()
        XrayProxyService.start(context, cfg, serverLabel(p), SubscriptionManager.serverKey(p))
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
            val mbps = ServerSpeedTester.measureSpeed(context, p)
            SubscriptionManager.applySpeedResults(context, mapOf(key to mbps))
            activity.runOnUiThread {
                speedResults = speedResults + (key to mbps)
                remeasureStatus = "результат: $mbps Мбит/с"
                remeasuring = false
                reloadServers()
            }
        }.start()
    }

    fun onAddRefresh() {
        val url = subUrl.trim()
        if (url.isEmpty()) { subStatus = "введите адрес подписки"; return }
        subStatus = "загрузка…"
        TestProgress.startIndeterminate("обновление подписки…")   // total неизвестен → indeterminate
        Thread {
            SubscriptionManager.add(context, url)
            val s = SubscriptionManager.refresh(context, url)
            activity.runOnUiThread {
                subStatus = if (s.ok) "добавлено=${s.added}  дубли=${s.duplicates}  неподдерж=${s.unsupported}  ошибок=${s.invalid}"
                            else "ошибка: ${s.error}"
                TestProgress.clear()
                reloadServers()
            }
        }.start()
    }

    // Полный адаптивный тест: ping → speed по живым → early-connect первого рабочего → апгрейд.
    fun onFullTest() {
        if (fullTesting) return
        val all = servers
        if (all.isEmpty()) { subStatus = "нет серверов"; return }
        fullTesting = true
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
        TestProgress.finish("отменено")
        val pSnap = pingResults; val sSnap = speedResults
        Thread {
            SubscriptionManager.applyPingResults(context, pSnap)
            SubscriptionManager.applySpeedResults(context, sSnap)
            activity.runOnUiThread { reloadServers() }
        }.start()
    }

    // Сортировка как Termux sort_servers_by_speed: скорость>0 (убыв.) → живые по пингу (возр.) → остальные.
    val shown = servers.sortedWith(Comparator { a, b ->
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
    val discriminators = remember(servers) { buildDiscriminators(servers) }

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

    // Основной вид = ЖИВЫЕ (ping ответил, >=0) И с полезной скоростью (не «0.0»/«✗»).
    // Не тестированные по скорости (—) остаются видны. Непригодные и мёртвые — в «Все серверы».
    // Порог общий с Авто (settings.minUsableMbps): что скрыто — то и не выбирается в Авто.
    val alive = shown.filter {
        val pg = effPing(it)
        val sp = effSpeed(it)
        pg != null && pg >= 0 && (sp == null || sp >= settings.minUsableMbps)
    }

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
                serverName = activeServer?.let { serverName(it) } ?: proxy.label,
                subtitle = activeServer?.let { protoNetSec(it) },
                speedMbps = activeServer?.let { effSpeed(it) },
                message = proxy.message,
            )
        }

        // ═══ 2. ДЕЙСТВИЯ ═══
        item {
            ActionsBar(
                fullTesting = fullTesting,
                running = proxy.running,
                onFullTest = { onFullTest() },
                onCancelFull = { onCancelFull() },
                onStop = { onStop() },
                onRefreshSubs = { onAddRefresh() },
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
        item { ServerTableHeader() }
        items(alive) { p ->
            val isActive = proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p)
            ServerRow(
                profile = p,
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
            CollapsibleSection(title = "Все серверы (${servers.size})", initiallyExpanded = false) {
                ServerTableHeader()
                shown.forEach { p ->
                    val isActive = proxy.running && proxy.serverKey == SubscriptionManager.serverKey(p)
                    ServerRow(
                        profile = p,
                        isActive = isActive,
                        speedMbps = effSpeed(p),
                        caption = discriminators[SubscriptionManager.serverKey(p)] ?: "",
                        onConnect = { startServer(p) },
                        onDetails = { detailProfile = p; remeasureStatus = "" },
                    )
                }
            }
        }
        item {
            CollapsibleSection(title = "Подписки", initiallyExpanded = false) {
                OutlinedTextField(
                    value = subUrl,
                    onValueChange = { subUrl = it },
                    label = { Text("Адрес подписки") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onAddRefresh() }, modifier = Modifier.fillMaxWidth()) {
                    Text("↻ Подписки")
                }
            }
        }
        // Заглушки под будущие фичи — порядок эталона: Автомониторинг → Настройки → Стоп-лист.
        item { CollapsibleSection(title = "🛡️ Автомониторинг", initiallyExpanded = false) { StubText() } }
        item {
            CollapsibleSection(title = "⚙️ Настройки", initiallyExpanded = false) {
                SettingsSection(
                    settings = settings,
                    onChange = { SettingsStore.update(context, it) },
                    onReset = { SettingsStore.resetToDefaults(context) },
                )
            }
        }
        item { CollapsibleSection(title = "🚫 Стоп-лист", initiallyExpanded = false) { StubText() } }

        // ═══ ФУТЕР ═══
        item {
            Text(
                "pico-soft/XrayProxyDroid",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF9E9E9E),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            )
        }
    }

    // Диалог деталей сервера (долгое нажатие) + отладочный «Перемерить». Отдельное окно — не в ленте.
    detailProfile?.let { p ->
        ServerDetailDialog(
            profile = p,
            remeasureStatus = remeasureStatus,
            remeasuring = remeasuring,
            onRemeasure = { onRemeasure(p) },
            onDismiss = { detailProfile = null; remeasureStatus = "" },
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
    onFullTest: () -> Unit,
    onCancelFull: () -> Unit,
    onStop: () -> Unit,
    onRefreshSubs: () -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (fullTesting) {
            Button(onClick = onCancelFull) { Text("Прервать") }
        } else {
            Button(onClick = onFullTest) { Text("🔍 Полный тест") }
        }
        OutlinedButton(onClick = onStop, enabled = running) { Text("■ Стоп") }
        OutlinedButton(onClick = onRefreshSubs) { Text("↻ Подписки") }
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
                    (if (isActive) "● " else "") + serverName(profile),
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

/** Сворачиваемая секция (аналог <details> веб-морды). Заголовок-строка с ▸/▾. */
@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { expanded = !expanded }
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
    remeasureStatus: String,
    remeasuring: Boolean,
    onRemeasure: () -> Unit,
    onDismiss: () -> Unit,
) {
    fun v(s: String?) = s?.ifBlank { "—" } ?: "—"
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(serverName(profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
    onChange: (AppSettings) -> Unit,
    onReset: () -> Unit,
) {
    val d = SettingsStore.DEFAULTS
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingsGroupLabel("Замер скорости")
        IntSettingRow("Прогрев перед замером", "с", settings.speedWarmupSec, d.speedWarmupSec, 0, 60) {
            onChange(settings.copy(speedWarmupSec = it))
        }
        IntSettingRow("Окно замера", "с", settings.speedWindowSec, d.speedWindowSec, 1, 120) {
            onChange(settings.copy(speedWindowSec = it))
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

        SettingsGroupLabel("Выбор сервера")
        DoubleSettingRow("Минимальная полезная скорость", "Мбит/с", settings.minUsableMbps, d.minUsableMbps, 0.0, 100.0) {
            onChange(settings.copy(minUsableMbps = it))
        }
        IntSettingRow("Запас для апгрейда", "%", settings.upgradeMarginPercent, d.upgradeMarginPercent, 0, 100) {
            onChange(settings.copy(upgradeMarginPercent = it))
        }

        SettingsGroupLabel("Прочее")
        BoolSettingRow("Подробные логи", settings.verboseLogs, d.verboseLogs) {
            onChange(settings.copy(verboseLogs = it))
        }

        Spacer(Modifier.height(4.dp))
        OutlinedButton(onClick = onReset, modifier = Modifier.fillMaxWidth()) {
            Text("Сбросить всё к дефолтам")
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

/** URL — во всю ширину (длинный), валиден если начинается с http(s):// и не пуст. */
@Composable
private fun UrlSettingRow(label: String, value: String, default: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    val v = text.trim()
    val valid = v.isNotBlank() && (v.startsWith("http://") || v.startsWith("https://"))
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (value != default) {
            Text("деф. $default", style = MaterialTheme.typography.bodySmall, color = TABLE_GRAY)
        }
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                val tv = t.trim()
                if (tv.isNotBlank() && (tv.startsWith("http://") || tv.startsWith("https://"))) onCommit(tv)
            },
            isError = !valid,
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
