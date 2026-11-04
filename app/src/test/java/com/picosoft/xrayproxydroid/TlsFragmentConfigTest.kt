package com.picosoft.xrayproxydroid

import android.content.ContextWrapper
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import com.picosoft.xrayproxydroid.xray.link.VlessParser
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URLEncoder
import java.nio.file.Files

/** Опция фрагментации: выключена по умолчанию, конфиг без неё не меняется; включается только там, где есть TLS поверх TCP. */
class TlsFragmentConfigTest {

    private val dir: File = Files.createTempDirectory("xgates-fragment").toFile()
    private val ctx = object : ContextWrapper(null) { override fun getFilesDir(): File = dir }

    @After fun tearDown() {
        SettingsStore.resetToDefaults(ctx)
        dir.deleteRecursively()
    }

    private fun on() = SettingsStore.update(ctx, SettingsStore.current().copy(tlsFragment = true))

    private fun p(query: String): ServerProfile = VlessParser.parse("vless://u@h.example.com:443?$query#n")!!

    private fun applied(config: String): Boolean {
        val c = JSONObject(config)
        val outs = c.getJSONArray("outbounds")
        val fragment = (0 until outs.length()).map { outs.getJSONObject(it) }.firstOrNull { it.getString("tag") == "fragment" }
        val sock = outs.getJSONObject(0).getJSONObject("streamSettings").optJSONObject("sockopt")
        assertEquals(fragment != null, sock != null)
        if (fragment != null) {
            assertEquals("fragment", sock!!.getString("dialerProxy"))
            val f = fragment.getJSONObject("settings").getJSONObject("fragment")
            assertEquals("tlshello", f.getString("packets"))
            assertEquals("100-200", f.getString("length"))
            assertEquals("10-20", f.getString("interval"))
        }
        return fragment != null
    }

    @Test fun off_by_default() {
        assertFalse(SettingsStore.current().tlsFragment)
        assertFalse(applied(XrayConfigBuilder.build(p("security=tls"))))
        assertFalse(applied(XrayConfigBuilder.buildForSpeedTest(p("security=tls"), 23456)))
    }

    @Test fun on_for_tls_and_reality_in_both_configs() {
        on()
        for (q in listOf(
            "type=tcp&security=tls", "type=ws&security=tls&path=%2Fw", "type=grpc&security=tls&serviceName=s",
            "type=xhttp&security=reality&pbk=k&sid=ab", "type=httpupgrade&security=tls",
        )) {
            assertTrue(q, applied(XrayConfigBuilder.build(p(q))))
            assertTrue(q, applied(XrayConfigBuilder.buildForSpeedTest(p(q), 23456)))
        }
    }

    @Test fun not_applied_where_there_is_no_tls_over_tcp() {
        on()
        for (q in listOf("type=tcp&security=none", "type=ws&security=false&path=%2Fw", "type=kcp&security=tls", "type=mkcp&security=tls")) {
            assertFalse(q, applied(XrayConfigBuilder.build(p(q))))
        }
    }

    @Test fun download_channel_gets_dialer_proxy_only_with_option() {
        val extra = URLEncoder.encode("""{"downloadSettings":{"address":"d.example.com","port":443}}""", "UTF-8")
        val server = p("type=xhttp&security=tls&extra=$extra")
        fun sock() = JSONObject(XrayConfigBuilder.build(server)).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings").getJSONObject("extra")
            .getJSONObject("downloadSettings").optJSONObject("sockopt")
        assertNull(sock())
        on()
        assertEquals("fragment", sock()!!.getString("dialerProxy"))
    }

    @Test fun option_survives_restart_and_old_settings_file_means_off() {
        on()
        SettingsStore.init(ctx)
        assertTrue(SettingsStore.current().tlsFragment)

        SettingsStore.resetToDefaults(ctx)
        File(dir, "settings.json").writeText("""{ "connectionCheckIntervalSec": 60 }""")
        SettingsStore.init(ctx)
        assertFalse(SettingsStore.current().tlsFragment)
    }
}
