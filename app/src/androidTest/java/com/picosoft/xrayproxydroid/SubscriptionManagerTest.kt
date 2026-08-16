package com.picosoft.xrayproxydroid

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Оффлайн-ядро Manager: base64(vless+vmess+ss+hysteria2+мусор) → addLocalFromBody →
 * added=3, unsupported=1 (hysteria2), invalid>=1, локальный источник с serverCount=3.
 * Без сети (refresh проверим на шаге UI с реальной подпиской).
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionManagerTest {

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun importFromBody_parsesCountsAndStores() {
        val vless = "vless://13a0205c-107a-4c7a-954e-2b5fcb235449@example.com:443?type=ws&security=tls&sni=example.com#vl"

        val vmessJson = """{"add":"hk.example","port":"443","id":"f23bb427c1f94373876c2f43e9f790f3","net":"ws","tls":"tls","path":"/ws","host":"hk.example","scy":"auto"}"""
        val vmess = "vmess://" + Base64.encodeToString(vmessJson.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val ssUser = Base64.encodeToString(
            "chacha20-ietf-poly1305:pw".toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
        val ss = "ss://$ssUser@1.2.3.4:8388#ss"

        val hysteria2 = "hysteria2://pw@hy.example:443?sni=hy.example#hy"   // → Unsupported
        val garbage = "this is not a link"                                 // → Invalid

        val plain = listOf(vless, vmess, ss, hysteria2, garbage).joinToString("\n")
        val body = Base64.encodeToString(plain.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        val (id, summary) = SubscriptionManager.addLocalFromBody(ctx, body, name = "test-import")

        assertTrue("ok", summary.ok)
        assertEquals("added (vless+vmess+ss)", 3, summary.added)
        assertEquals("unsupported (hysteria2)", 1, summary.unsupported)
        assertTrue("invalid >= 1 (garbage)", summary.invalid >= 1)

        val src = SubscriptionManager.sources(ctx).first { it.id == id }
        assertEquals("serverCount источника", 3, src.serverCount)
    }
}
