package com.picosoft.xrayproxydroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.XrayController
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.net.InetSocketAddress
import java.net.Socket

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

@Composable
private fun BootScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val activity = context as ComponentActivity

    var status by remember { mutableStateOf("idle") }
    var running by remember { mutableStateOf(false) }
    var activeServer by remember { mutableStateOf<ServerProfile?>(null) }

    var subUrl by remember { mutableStateOf("https://maxim-zodchy.ru/sub-black.php") }
    var subStatus by remember { mutableStateOf("") }
    var servers by remember { mutableStateOf(SubscriptionManager.allServers(context)) }

    fun refresh() { running = XrayController.isRunning }
    fun reloadServers() { servers = SubscriptionManager.allServers(context) }

    // Запуск выбранного сервера. Если что-то уже работает — авто-переключение: stop старого → start нового.
    fun startServer(p: ServerProfile) {
        val cfg = runCatching { XrayConfigBuilder.build(p) }.getOrElse {
            subStatus = "build error: ${it.message}"
            return
        }
        status = "starting ${p.remarks.ifBlank { p.address }}…"
        Thread {
            if (XrayController.isRunning) XrayController.stop()      // переключение
            val res = runCatching { XrayController.start(context, cfg) }
            val socks = probePort(XrayConfig.SOCKS_PORT)
            val http = probePort(XrayConfig.HTTP_PORT)
            activity.runOnUiThread {
                res.fold(
                    onSuccess = { ok ->
                        activeServer = if (ok) p else null
                        status = "isRunning=$ok\n" +
                            "socks ${XrayConfig.SOCKS_PORT}: ${mark(socks)}\n" +
                            "http  ${XrayConfig.HTTP_PORT}: ${mark(http)}"
                    },
                    onFailure = { activeServer = null; status = "ERROR: ${it.message}" }
                )
                refresh()
            }
        }.start()
    }

    fun onStop() {
        status = "stopping…"
        Thread {
            XrayController.stop()
            activity.runOnUiThread { activeServer = null; status = "stopped"; refresh() }
        }.start()
    }

    // Добавить подписку и обновить (скачать→декод→парс→сохранить) — в фоне.
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
                val isActive = running && p == activeServer
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
                        text = (if (isActive) "● " else "") +
                            "${p.protocol}  ·  ${p.remarks.ifBlank { p.address }}  ·  ${p.address}:${p.port}",
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
        Text("running: $running")
        Text(status)
    }
}

private fun mark(open: Boolean) = if (open) "LISTENING ✓" else "closed ✗"

/** Быстрая проверка, что порт на 127.0.0.1 действительно слушается. */
private fun probePort(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(XrayConfig.LISTEN, port), 500); true }
} catch (e: Exception) {
    false
}
