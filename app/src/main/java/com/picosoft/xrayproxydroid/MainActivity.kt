package com.picosoft.xrayproxydroid

import android.os.Bundle
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
import com.picosoft.xrayproxydroid.xray.XrayController
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

    // Старт/стоп ядра — на фоновом потоке (нативный вызов не на main).
    fun refresh() {
        running = XrayController.isRunning
    }

    fun onStart() {
        status = "starting…"
        Thread {
            val result = runCatching { XrayController.start(context) }
            val text = result.fold(
                onSuccess = { ok ->
                    val socks = probePort(XrayConfig.SOCKS_PORT)
                    val http = probePort(XrayConfig.HTTP_PORT)
                    "isRunning=$ok\n" +
                        "socks ${XrayConfig.SOCKS_PORT}: ${mark(socks)}\n" +
                        "http  ${XrayConfig.HTTP_PORT}: ${mark(http)}"
                },
                onFailure = { "ERROR: ${it.message}" }
            )
            (context as ComponentActivity).runOnUiThread {
                status = text
                refresh()
            }
        }.start()
    }

    fun onStop() {
        status = "stopping…"
        Thread {
            XrayController.stop()
            (context as ComponentActivity).runOnUiThread {
                status = "stopped"
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
            Button(onClick = { onStart() }) { Text("Start") }
            Button(onClick = { onStop() }) { Text("Stop") }
        }
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
