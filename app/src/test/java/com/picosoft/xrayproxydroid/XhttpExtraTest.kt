package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.JsonConfigParser
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import com.picosoft.xrayproxydroid.xray.link.TrojanParser
import com.picosoft.xrayproxydroid.xray.link.VlessParser
import com.picosoft.xrayproxydroid.xray.link.XhttpExtra
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLEncoder

/**
 * extra приходит из подписки и попадает в конфиг ядра: проходят только разрешённые поля (XhttpExtra), а JSON из чужой
 * строки не может выйти за пределы своего значения. Перенос XhttpExtraTests.cs из X-Gates-Win.
 */
class XhttpExtraTest {

    private fun clean(raw: String) = JSONObject(XhttpExtra.sanitize(raw)!!)
    private fun esc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun keys(o: JSONObject) = o.keys().asSequence().toSortedSet().toList()

    private fun xhttp(p: ServerProfile): JSONObject =
        JSONObject(XrayConfigBuilder.build(p)).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("xhttpSettings")

    private fun extra(p: ServerProfile) = xhttp(p).getJSONObject("extra")

    private fun vless(query: String) = VlessParser.parse("vless://u@h.example.com:443?$query#n")!!

    @Test fun keeps_padding_and_xmux() {
        val c = clean("""{"xPaddingBytes":"100-1000","noGRPCHeader":false,"xmux":{"maxConcurrency":"16-32","hMaxRequestTimes":"600-900"}}""")
        assertEquals("100-1000", c.getString("xPaddingBytes"))
        assertFalse(c.getBoolean("noGRPCHeader"))
        assertEquals("16-32", c.getJSONObject("xmux").getString("maxConcurrency"))
    }

    @Test fun nothing_allowed_gives_null() {
        for (raw in listOf(
            """{"masterKeyLog":"C:\\x.log"}""",
            """{"sockopt":{"dialerProxy":"direct"}}""",
            """{"MasterKeyLog":"/tmp/x"}""",          // Go сравнивает имена без учёта регистра
            """{"extra":{"xPaddingBytes":"1-2"}}""",
            """{"host":"a","path":"/b","mode":"auto"}""",
            "{}", "[]", "not json", "",
        )) assertNull(raw, XhttpExtra.sanitize(raw))
    }

    @Test fun dangerous_download_settings_are_cut_out() {
        val c = clean("""
            {"downloadSettings":{"address":"d.example.com","port":443,"network":"xhttp","security":"tls",
              "sockopt":{"dialerProxy":"direct","interface":"eth0"},
              "finalmask":{"tcp":[]},
              "tlsSettings":{"serverName":"d.example.com","fingerprint":"chrome","allowInsecure":true,"masterKeyLog":"/tmp/keys",
                "certificates":[{"certificateFile":"/etc/passwd"}],"echSockopt":{"mark":1}},
              "realitySettings":{"publicKey":"pk","shortId":"ab","masterKeyLog":"/tmp/keys"},
              "xhttpSettings":{"path":"/d","mode":"auto","xPaddingBytes":"100-200","downloadSettings":{"address":"x","port":1},"extra":{}}}}
        """.trimIndent())
        val d = c.getJSONObject("downloadSettings")
        assertEquals("d.example.com", d.getString("address"))
        assertEquals(443, d.getInt("port"))
        assertFalse(d.has("sockopt"))
        assertFalse(d.has("finalmask"))
        assertEquals(listOf("fingerprint", "serverName"), keys(d.getJSONObject("tlsSettings")))
        assertEquals(listOf("publicKey", "shortId"), keys(d.getJSONObject("realitySettings")))
        assertEquals(listOf("mode", "path", "xPaddingBytes"), keys(d.getJSONObject("xhttpSettings")))
    }

    @Test fun download_settings_without_address_or_port_are_dropped() {
        for (raw in listOf(
            """{"downloadSettings":{"network":"xhttp"}}""",
            """{"downloadSettings":{"address":"d.example.com"}}""",
            """{"downloadSettings":{"address":"","port":443}}""",
        )) assertNull(raw, XhttpExtra.sanitize(raw))
    }

    @Test fun host_header_is_removed() {
        val c = clean("""{"headers":{"Host":"evil","X-Ok":"1","hOsT":"evil"}}""")
        assertEquals(listOf("X-Ok"), keys(c.getJSONObject("headers")))
    }

    @Test fun link_cannot_break_out_of_extra() {
        // Хвост после первого объекта разбор отбрасывает, в конфиг идёт только пересобранный JSON.
        val p = vless("type=xhttp&security=tls&extra=" + esc("""{"xPaddingBytes":"1-2"}}, "evil": {"y":{"""))
        assertEquals(listOf("extra", "host", "path"), keys(xhttp(p)))
        assertEquals(listOf("xPaddingBytes"), keys(extra(p)))
    }

    @Test fun deep_nesting_does_not_crash_parsing() {
        assertNull(XhttpExtra.sanitize("{\"xmux\":" + "[".repeat(200_000)))
    }

    @Test fun value_saved_on_disk_is_cleaned_again_when_config_is_built() {
        val p = vless("type=xhttp&security=tls").copy(xhttpExtra = """{"xPaddingBytes":"1-2","masterKeyLog":"/tmp/x"}""")
        assertEquals(listOf("xPaddingBytes"), keys(extra(p)))
    }

    @Test fun stream_one_mode_loses_download_settings_because_core_rejects_them() {
        val e = esc("""{"xPaddingBytes":"1-2","downloadSettings":{"address":"d.example.com","port":443}}""")
        assertFalse(extra(vless("type=xhttp&mode=stream-one&security=tls&extra=$e")).has("downloadSettings"))
        assertTrue(extra(vless("type=xhttp&security=tls&extra=$e")).has("downloadSettings"))
    }

    @Test fun trojan_link_reads_the_same_parameters() {
        val e = esc("""{"xPaddingBytes":"1-2"}""")
        val p = TrojanParser.parse("trojan://pw@h.example.com:443?type=xhttp&security=tls&extra=$e#n")!!
        assertEquals("1-2", extra(p).getString("xPaddingBytes"))
    }

    @Test fun plain_xhttp_config_has_no_extra() {
        assertFalse(xhttp(vless("type=xhttp&security=tls&path=%2Fx")).has("extra"))
    }

    @Test fun json_config_takes_extra_or_xhttp_settings_themselves() {
        fun body(xhttp: String) = """
            { "remarks": "n", "outbounds": [ { "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example.com", "port": 443,
                "users": [ { "id": "11111111-1111-1111-1111-111111111111", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "xhttp", "security": "tls", "tlsSettings": { "serverName": "h.example.com" },
                "xhttpSettings": $xhttp } } ] }
        """.trimIndent()
        // Как в ядре: при extra соседние поля не действуют.
        val withExtra = JsonConfigParser.parse(body("""{"path":"/x","xPaddingBytes":"9-9","extra":{"xPaddingBytes":"1-2"}}""")).single()
        assertEquals("1-2", extra(withExtra).getString("xPaddingBytes"))
        val inline = JsonConfigParser.parse(body("""{"path":"/x","mode":"auto","xPaddingBytes":"9-9"}""")).single()
        assertEquals(listOf("xPaddingBytes"), keys(extra(inline)))
        assertEquals("9-9", extra(inline).getString("xPaddingBytes"))
    }

    @Test fun empty_field_is_not_written_to_the_store_but_filled_one_survives_reload() {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }   // как SubscriptionStore
        assertFalse(json.parseToJsonElement(json.encodeToString(vless("security=tls"))).jsonObject.containsKey("xhttpExtra"))
        val full = vless("type=xhttp&security=tls&extra=" + esc("""{"xPaddingBytes":"1-2"}"""))
        assertEquals(full, json.decodeFromString<ServerProfile>(json.encodeToString(full)))
    }
}
