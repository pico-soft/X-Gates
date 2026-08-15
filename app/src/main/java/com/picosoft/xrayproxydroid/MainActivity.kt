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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
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

/** Стабильная метка сервера — и для отображения в списке, и как label активного в ProxyState. */
private fun serverLabel(p: ServerProfile): String =
    "${p.protocol}  ·  ${p.remarks.ifBlank { p.address }}  ·  ${p.address}:${p.port}"

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    // Состояние прокси едет из сервиса через общий StateFlow (один процесс).
    val proxy by ProxyState.state.collectAsState()

    var subUrl by remember { mutableStateOf("https://maxim-zodchy.ru/sub-black.php") }
    var subStatus by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(SubscriptionManager.allServers(context)) }

    // Runtime-разрешение на нотификацию (Android 13+). Отказ не блокирует — прокси headless.
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — всё равно стартуем */ }

    fun ensureNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun reloadServers() { servers = SubscriptionManager.allServers(context) }

    // Запуск сервера ЧЕРЕЗ сервис. Повторный вызов с другим сервером = авто-переключение
    // (сервис сам stop→start по новому ACTION_START) — здесь никакого guard-а «already running».
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
                subStatus = if (s.ok) "added=${s.added}  unsupported=${s.unsupported}  invalid=${s.invalid}"
                            else "error: ${s.error}"
                reloadServers()
            }
        }.start()
    }

    Column(
        modifier = modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("xray-core — subscriptions")

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
        Text(subStatus)

        Text("servers: ${servers.size}")
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            items(servers) { p ->
                val label = serverLabel(p)
                val isActive = proxy.running && proxy.label == label
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isActive) Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                            else Modifier
                        )
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = (if (isActive) "● " else "") + label,
                        modifier = Modifier.weight(1f)
                    )
                    Button(onClick = { startServer(p) }) {
                        Text(if (isActive) "running" else "Start")
                    }
                }
            }
        }

        HorizontalDivider()

        Button(onClick = { onStop() }) { Text("Stop") }
        Text("running: ${proxy.running}" + (proxy.label?.let { "   ·   $it" } ?: ""))
        Text(proxy.message)
    }
}
