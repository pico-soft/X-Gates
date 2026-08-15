package com.picosoft.xrayproxydroid

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picosoft.xrayproxydroid.subscription.Subscription
import com.picosoft.xrayproxydroid.subscription.SubscriptionStore
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Round-trip хранилища подписок: save → load → сравнение (data class equals покрывает все поля).
 * Без сети и UI. Пишет в filesDir целевого приложения.
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionStoreTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun roundTrip_preservesAllFields() {
        val vless = ServerProfile(
            protocol = Protocol.VLESS,
            remarks = "vless-тест 🇭🇰",          // кириллица/эмодзи в remark
            address = "example.com", port = 443,
            credential = "13a0205c-107a-4c7a-954e-2b5fcb235449",
            method = "none", flow = null,
            security = "tls", sni = "example.com", fingerprint = "firefox",
            network = "ws", path = "/ws", hostHeader = "example.com",
            raw = "vless://...#vless",
        )
        val ss = ServerProfile(
            protocol = Protocol.SHADOWSOCKS,
            remarks = "ss-тест",
            address = "1.2.3.4", port = 8388,
            credential = "WSyL4XTwNsdv",
            method = "chacha20-ietf-poly1305",
            security = "none", network = "tcp",
            raw = "ss://...#ss",
        )
        val subs = listOf(
            Subscription(
                url = "https://sub.example/list",
                name = "sub.example/list",
                lastUpdateOk = true,
                lastUpdateTs = "2026-08-15 16:30",
                servers = listOf(vless, ss),
            )
        )

        SubscriptionStore.save(ctx, subs)
        val loaded = SubscriptionStore.load(ctx)

        // data class equals → сравнивает url/name/lastUpdate* и все поля каждого сервера.
        assertEquals(subs, loaded)
        // Явные точечные проверки для наглядности отчёта.
        assertEquals(2, loaded[0].servers.size)
        assertEquals(Protocol.VLESS, loaded[0].servers[0].protocol)
        assertEquals("chacha20-ietf-poly1305", loaded[0].servers[1].method)
        assertEquals("vless-тест 🇭🇰", loaded[0].servers[0].remarks)
    }
}
