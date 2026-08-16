package com.picosoft.xrayproxydroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picosoft.xrayproxydroid.subscription.ServerRecord
import com.picosoft.xrayproxydroid.subscription.SourcesFile
import com.picosoft.xrayproxydroid.subscription.SubSource
import com.picosoft.xrayproxydroid.subscription.SubscriptionStore
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trip нового хранилища мультиподписок ([SourcesFile]): save → load → сравнение.
 * Проверяет метаданные источников + общий реестр серверов (с членством и измерениями).
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roundTrip_preservesAllFields() {
        val vless = ServerProfile(
            protocol = Protocol.VLESS,
            remarks = "vless-тест 🇭🇰",
            address = "example.com", port = 443,
            credential = "13a0205c-107a-4c7a-954e-2b5fcb235449",
            method = "none", security = "tls", sni = "example.com", fingerprint = "firefox",
            network = "ws", path = "/ws", hostHeader = "example.com",
            pingMs = 123, lastTestedTs = "2026-08-16 10:00",
            speedMbps = 42.0, speedTestedTs = "2026-08-16 10:01",
            raw = "vless://...#vless",
        )
        val file = SourcesFile(
            migratedLegacy = true,
            sources = listOf(
                SubSource(id = "src-a", name = "sub.example/list", url = "https://sub.example/list", serverCount = 1, lastOk = true, lastRefreshTs = "2026-08-16 10:00"),
                SubSource(id = "src-b", name = "локальная", url = "", serverCount = 1),
            ),
            servers = listOf(ServerRecord(vless, listOf("src-a", "src-b"))),
        )

        SubscriptionStore.save(ctx, file)
        val loaded = SubscriptionStore.load(ctx)

        assertEquals(file, loaded)
        assertEquals(2, loaded.sources.size)
        assertEquals(listOf("src-a", "src-b"), loaded.servers[0].sourceIds)
        assertEquals(123, loaded.servers[0].profile.pingMs)     // измерения сохраняются
        assertEquals("vless-тест 🇭🇰", loaded.servers[0].profile.remarks)
    }
}
