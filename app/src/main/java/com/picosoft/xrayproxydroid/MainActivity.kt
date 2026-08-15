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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
import com.picosoft.xrayproxydroid.xray.FullTestRunner
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

/** Подпись задержки: "123 мс" / "✗" (мёртвый) / "—" (не тестирован). */
private fun pingLabel(ms: Int?): String = when {
    ms == null -> "—"
    ms < 0 -> "✗"
    else -> "$ms мс"
}

/** Цвет задержки по порогам. */
private fun pingColor(ms: Int?): Color = when {
    ms == null -> Color(0xFF9E9E9E)   // серый — не тестирован
    ms < 0 -> Color(0xFFD32F2F)       // красный — мёртвый
    ms < 500 -> Color(0xFF2E7D32)     // зелёный
    ms < 1000 -> Color(0xFFF57C00)    // оранжевый
    else -> Color(0xFFBF360C)         // тёмно-оранжевый
}

/** Подпись скорости: "178.0 Мбит/с" / "✗" (0/ошибка) / "—" (не тестирован). */
private fun speedLabel(mbps: Double?): String = when {
    mbps == null -> "—"
    mbps <= 0 -> "✗"
    else -> "$mbps Мбит/с"
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

    var subUrl by remember { mutableStateOf("https://maxim-zodchy.ru/sub-black.php") }
    var subStatus by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(SubscriptionManager.allServers(context)) }

    var pingResults by remember { mutableStateOf<Map<String, Int>>(emptyMap()) }

    var speedResults by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }

    var fullTesting by remember { mutableStateOf(false) }
    var fullPhase by remember { mutableStateOf("") }
    var fullHandle by remember { mutableStateOf<FullTestRunner.Handle?>(null) }

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
        XrayProxyService.start(context, cfg, serverLabel(p))
    }

    fun onStop() { XrayProxyService.stop(context) }

    fun onAddRefresh() {
        val url = subUrl.trim()
        if (url.isEmpty()) { subStatus = "введите адрес подписки"; return }
        subStatus = "загрузка…"
        Thread {
            SubscriptionManager.add(context, url)
            val s = SubscriptionManager.refresh(context, url)
            activity.runOnUiThread {
                subStatus = if (s.ok) "добавлено=${s.added}  дубли=${s.duplicates}  неподдерж=${s.unsupported}  ошибок=${s.invalid}"
                            else "ошибка: ${s.error}"
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
        fullPhase = "запуск…"
        pingResults = emptyMap(); speedResults = emptyMap()

        fullHandle = FullTestRunner.run(
            context = context,
            allServers = all,
            onPhase = { ph -> activity.runOnUiThread { fullPhase = ph } },
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
                            fullPhase = if (r.cancelled) "отменено"
                                else "готово: быстрейший ${r.fastest?.remarks?.ifBlank { r.fastest.address } ?: "—"} ${r.fastestMbps} Мбит/с"
                            reloadServers()
                        }
                    }.start()
                }
            },
        )
    }

    fun onCancelFull() {
        fullHandle?.cancel(); fullHandle = null
        fullTesting = false; fullPhase = "отменено"
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // --- КОНТРОЛЫ (наверху, всегда доступны) ---
        OutlinedTextField(
            value = subUrl,
            onValueChange = { subUrl = it },
            label = { Text("Адрес подписки") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { onAddRefresh() }) { Text("Обновить подписку") }
            Text("серверов: ${servers.size}", style = MaterialTheme.typography.bodyMedium)
        }
        if (subStatus.isNotEmpty()) Text(subStatus, style = MaterialTheme.typography.bodySmall)

        // Полный адаптивный тест — одной кнопкой (пинг → скорость → авто-подключение → апгрейд).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (fullTesting) {
                Button(onClick = { onCancelFull() }) { Text("Прервать тест") }
            } else {
                Button(onClick = { onFullTest() }) { Text("Полный тест") }
            }
            Text(fullPhase, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { onStop() }) { Text("Стоп") }
            Text(
                "статус: ${if (proxy.running) "активен" else "остановлен"}" + (proxy.label?.let { "  ·  $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
        }
        if (proxy.message.isNotEmpty() && proxy.message != "idle") {
            Text(proxy.message, style = MaterialTheme.typography.bodySmall)
        }

        HorizontalDivider()

        // --- СПИСОК серверов на всю оставшуюся высоту ---
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(shown) { p ->
                val label = serverLabel(p)
                val isActive = proxy.running && proxy.label == label
                val ms = effPing(p)
                val sp = effSpeed(p)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                            else Modifier
                        )
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text((if (isActive) "● " else "") + label)
                        // Метрики: пинг + скорость (крупные, жирные, цветные) + время.
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(
                                pingLabel(ms),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = pingColor(ms)
                            )
                            Text(
                                speedLabel(sp),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = speedColor(sp)
                            )
                            (p.speedTestedTs ?: p.lastTestedTs)?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = Color(0xFF9E9E9E))
                            }
                        }
                    }
                    Button(onClick = { startServer(p) }) {
                        Text(if (isActive) "активен" else "Подключить")
                    }
                }
            }
        }
    }
}
