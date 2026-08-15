package com.picosoft.xrayproxydroid

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.picosoft.xrayproxydroid.ui.theme.XrayProxyDroidTheme
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.XrayController
import com.picosoft.xrayproxydroid.xray.link.ParseResult
import com.picosoft.xrayproxydroid.xray.link.ServerLinkParser
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
    var status by remember { mutableStateOf("idle") }
    var running by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf("-") }

    // Старт/стоп ядра — на фоновом потоке (нативный вызов не на main).
    fun refresh() {
        running = XrayController.isRunning
    }

    // modeName — что показать в статусе; configJson — какой конфиг поднять.
    fun onStart(modeName: String, configJson: String) {
        if (XrayController.isRunning) {
            status = "already running (mode: $mode) — press Stop first"
            return
        }
        mode = modeName
        status = "starting… (mode: $modeName)"
        Thread {
            val result = runCatching { XrayController.start(context, configJson) }
            val text = result.fold(
                onSuccess = { ok ->
                    val socks = probePort(XrayConfig.SOCKS_PORT)
                    val http = probePort(XrayConfig.HTTP_PORT)
                    "mode: $modeName\n" +
                        "isRunning=$ok\n" +
                        "socks ${XrayConfig.SOCKS_PORT}: ${mark(socks)}\n" +
                        "http  ${XrayConfig.HTTP_PORT}: ${mark(http)}"
                },
                onFailure = { "mode: $modeName\nERROR: ${it.message}" }
            )
            (context as ComponentActivity).runOnUiThread {
                status = text
                refresh()
            }
        }.start()
    }

    // Прогон реальной ссылки через весь конвейер: parse → build → start.
    fun onStartFromLink(link: String) {
        when (val r = ServerLinkParser.parse(link)) {
            is ParseResult.Supported -> {
                runCatching { XrayConfigBuilder.build(r.profile) }.fold(
                    onSuccess = { cfg ->
                        // Лог для сравнения глазами с хардкод-конфигом Этапа 2 (XrayConfig.vlessConfigJson).
                        Log.i("XrayLink", "generated config from link:\n$cfg")
                        onStart("link:${r.profile.protocol.name.lowercase()}", cfg)
                    },
                    onFailure = { status = "build error: ${it.message}" }
                )
            }
            is ParseResult.Unsupported -> status = "unsupported: ${r.scheme} (needs sing-box)"
            is ParseResult.Invalid -> status = "invalid link: ${r.reason}"
        }
    }

    fun onStop() {
        status = "stopping…"
        Thread {
            XrayController.stop()
            (context as ComponentActivity).runOnUiThread {
                status = "stopped (was: $mode)"
                mode = "-"
                refresh()
            }
        }.start()
    }

    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("xray-core boot test")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onStart("freedom", XrayConfig.freedomConfigJson()) }) {
                Text("Start freedom")
            }
            Button(onClick = { onStart("vless", XrayConfig.vlessConfigJson()) }) {
                Text("Start vless")
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { onStartFromLink(XrayConfig.TEST_VLESS_LINK) }) { Text("Start from link") }
            Button(onClick = { onStartFromLink(XrayConfig.TEST_TROJAN_LINK) }) { Text("Start trojan link") }
        }
        Button(onClick = { onStartFromLink(XrayConfig.TEST_VMESS_LINK) }) { Text("Start vmess link") }
        Button(onClick = { onStop() }) { Text("Stop") }
        Text("running: $running   mode: $mode")
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
