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
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.ServerTester
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

/** Подпись задержки: "123 ms" / "✗" (мёртвый) / "—" (не тестирован). */
private fun pingLabel(ms: Int?): String = when {
    ms == null -> "—"
    ms < 0 -> "✗"
    else -> "$ms ms"
}

/** Цвет задержки по порогам. */
private fun pingColor(ms: Int?): Color = when {
    ms == null -> Color(0xFF9E9E9E)   // серый — не тестирован
    ms < 0 -> Color(0xFFD32F2F)       // красный — мёртвый
    ms < 500 -> Color(0xFF2E7D32)     // зелёный
    ms < 1000 -> Color(0xFFF57C00)    // оранжевый
    else -> Color(0xFFBF360C)         // тёмно-оранжевый
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
    var testing by remember { mutableStateOf(false) }
    var testProgress by remember { mutableStateOf("") }
    var testHandle by remember { mutableStateOf<ServerTester.TestHandle?>(null) }
    val pending = remember { LinkedHashMap<String, Int>() }

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

    fun startServer(p: ServerProfile) {
        val cfg = runCatching { XrayConfigBuilder.build(p) }.getOrElse {
            subStatus = "build error: ${it.message}"
            return
        }
        ensureNotifPermission()
        XrayProxyService.start(context, cfg, serverLabel(p))
    }

    fun onStop() { XrayProxyService.stop(context) }

    fun onAddRefresh() {
        val url = subUrl.trim()
        if (url.isEmpty()) { subStatus = "enter subscription url"; return }
        subStatus = "fetching…"
        Thread {
            SubscriptionManager.add(context, url)
            val s = SubscriptionManager.refresh(context, url)
            activity.runOnUiThread {
                subStatus = if (s.ok) "added=${s.added}  dup=${s.duplicates}  unsupported=${s.unsupported}  invalid=${s.invalid}"
                            else "error: ${s.error}"
                reloadServers()
            }
        }.start()
    }

    fun flushPending() {
        val batch = synchronized(pending) { HashMap(pending).also { pending.clear() } }
        if (batch.isNotEmpty()) Thread { SubscriptionManager.applyPingResults(context, batch) }.start()
    }

    // ВРЕМЕННО (диагностика): меряет ЛУЧШИЙ по пингу живой сервер (min pingMs, >=0),
    // а не первый в списке — нужно для чистой диагностики пробников на заведомо живом.
    fun onSpeedTestBest() {
        val best = servers.filter { (effPing(it) ?: -1) >= 0 }.minByOrNull { effPing(it)!! }
        if (best == null) { subStatus = "no alive server — run Test all (ping) first"; return }
        val ping = effPing(best)
        subStatus = "speed test (best ping ${ping}ms): ${best.remarks.ifBlank { best.address }} …"
        Thread {
            val mbps = ServerSpeedTester.measureSpeed(context, best)
            activity.runOnUiThread {
                subStatus = "speed(${best.address}:${best.port}, ping=${ping}ms) = $mbps Mbps · active running=${proxy.running}"
            }
        }.start()
    }

    fun onTestAll() {
        if (testing) return
        val list = servers
        if (list.isEmpty()) { subStatus = "no servers"; return }
        testing = true
        testProgress = "0 / ${list.size}"
        pingResults = emptyMap()
        synchronized(pending) { pending.clear() }

        testHandle = ServerTester.testAll(
            context = context,
            servers = list,
            concurrency = 8,
            onResult = { p, ms ->
                val key = SubscriptionManager.serverKey(p)
                val v = ms.toInt()
                activity.runOnUiThread { pingResults = pingResults + (key to v) }
                val flush = synchronized(pending) { pending[key] = v; pending.size >= 20 }
                if (flush) flushPending()
            },
            onProgress = { done, total ->
                activity.runOnUiThread { testProgress = "$done / $total" }
            },
            onFinish = {
                flushPending()
                Thread.sleep(150)
                activity.runOnUiThread {
                    testing = false; testProgress = "done"; testHandle = null
                    reloadServers()
                }
            }
        )
    }

    fun onCancelTest() {
        testHandle?.cancel()
        testHandle = null
        testing = false
        testProgress = "cancelled"
        flushPending()
        Thread { Thread.sleep(150); activity.runOnUiThread { reloadServers() } }.start()
    }

    // Живая сортировка: живые по возрастанию мс, затем мёртвые, затем непротестированные.
    val shown = servers.sortedWith(
        compareBy(
            { val m = effPing(it); if (m == null) 2 else if (m < 0) 1 else 0 },
            { effPing(it)?.takeIf { v -> v >= 0 } ?: Int.MAX_VALUE }
        )
    )

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
            label = { Text("subscription url") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onAddRefresh() }) { Text("Add & refresh") }
            Button(onClick = { reloadServers() }) { Text("Reload") }
        }
        if (subStatus.isNotEmpty()) Text(subStatus, style = MaterialTheme.typography.bodySmall)

        // Test all / Cancel — кнопка отдельно, прогресс/счётчик отдельным текстом.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (testing) {
                Button(onClick = { onCancelTest() }) { Text("Cancel") }
                Text(testProgress, style = MaterialTheme.typography.titleMedium)
            } else {
                Button(onClick = { onTestAll() }) { Text("Test all") }
                Button(onClick = { onSpeedTestBest() }) { Text("Speed(best)") }
                Text("servers: ${servers.size}", style = MaterialTheme.typography.bodyMedium)
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { onStop() }) { Text("Stop") }
            Text(
                "running: ${proxy.running}" + (proxy.label?.let { "  ·  $it" } ?: ""),
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
                        p.lastTestedTs?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF9E9E9E)
                            )
                        }
                    }
                    // Пинг — крупный, жирный, цветной.
                    Text(
                        text = pingLabel(ms),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = pingColor(ms)
                    )
                    Button(onClick = { startServer(p) }) {
                        Text(if (isActive) "running" else "Start")
                    }
                }
            }
        }
    }
}
