package com.picosoft.xrayproxydroid

import android.content.ContextWrapper
import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.subscription.ServerRecord
import com.picosoft.xrayproxydroid.subscription.SubSource
import com.picosoft.xrayproxydroid.subscription.SourcesFile
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.subscription.SubscriptionStore
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Решение Elyor 2026-11-02: ядро не приняло сервер при автопереключении — идём по живым от самого быстрого к самому медленному. */
class FailedSwitchNextServerTest {

    private val dir: File = Files.createTempDirectory("xgates-next").toFile()
    private val ctx = object : ContextWrapper(null) { override fun getFilesDir(): File = dir }

    private fun server(name: String, mbps: Double?, ping: Int?, network: String = "tcp") =
        ServerProfile(Protocol.VLESS, name, "$name.example.com", 443, "u", network = network, speedMbps = mbps, pingMs = ping)

    private val fast = server("fast", 50.0, 40)
    private val middle = server("middle", 20.0, 30)
    private val slow = server("slow", 5.0, 20)
    private val dead = server("dead", null, -1)

    private fun key(p: ServerProfile) = SubscriptionManager.serverKey(p)

    private fun store(vararg servers: ServerProfile) = SubscriptionStore.save(ctx, SourcesFile(
        sources = listOf(SubSource(id = "s", name = "s", url = "https://example.com", enabled = true)),
        servers = servers.map { ServerRecord(it, listOf("s")) },
    ))

    @Before fun setUp() = store(slow, dead, fast, middle)
    @After fun tearDown() { dir.deleteRecursively() }

    @Test fun walks_from_fastest_to_slowest_skipping_rejected_and_dead() {
        val rejected = hashSetOf(key(fast))
        assertEquals("middle", NetworkMonitor.nextAliveAfterFailedStart(ctx, rejected)!!.first.remarks)
        rejected += key(middle)
        assertEquals("slow", NetworkMonitor.nextAliveAfterFailedStart(ctx, rejected)!!.first.remarks)
        rejected += key(slow)
        assertNull(NetworkMonitor.nextAliveAfterFailedStart(ctx, rejected))
    }

    @Test fun server_whose_config_does_not_build_is_skipped_and_remembered() {
        val broken = server("broken", 90.0, 10, network = "quic")
        store(broken, slow)
        val rejected = hashSetOf<String>()
        assertEquals("slow", NetworkMonitor.nextAliveAfterFailedStart(ctx, rejected)!!.first.remarks)
        assertTrue(key(broken) in rejected)
    }
}
