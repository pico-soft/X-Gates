package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.JsonConfigParser
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import com.picosoft.xrayproxydroid.xray.link.TrojanParser
import com.picosoft.xrayproxydroid.xray.link.VlessParser
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ядро v26 убрало allowInsecure: конфиг с true не загружается, сервер из подписки не поднимался (полевой случай Elyor
 * 2026-11-01: «Не запущен» и «The feature "allowInsecure" has been removed»). Замена — pcs и vcn из ссылок и JSON-конфигов.
 */
class TlsCertOptionsTest {

    private val hash = "a".repeat(64)

    private fun vless(query: String): ServerProfile =
        VlessParser.parse("vless://11111111-1111-1111-1111-111111111111@h.example.com:443?$query#n")!!

    private fun tls(p: ServerProfile): JSONObject =
        JSONObject(XrayConfigBuilder.build(p)).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings").getJSONObject("tlsSettings")

    @Test fun allow_insecure_is_read_but_never_reaches_the_config() {
        for (q in listOf("security=tls&allowInsecure=1", "security=tls&insecure=1")) {
            val p = vless(q)
            assertTrue(p.allowInsecure)
            assertFalse(XrayConfigBuilder.build(p).contains("allowInsecure"))
            assertFalse(XrayConfigBuilder.buildForSpeedTest(p, 23456).contains("allowInsecure"))
        }
    }

    @Test fun pcs_and_vcn_from_vless_and_trojan_reach_the_config() {
        val v = tls(vless("security=tls&pcs=$hash&vcn=n.example"))
        assertEquals(hash, v.getString("pinnedPeerCertSha256"))
        assertEquals("n.example", v.getString("verifyPeerCertByName"))

        val t = TrojanParser.parse("trojan://pw@h.example.com:443?security=tls&pcs=$hash&vcn=n.example#n")!!
        assertEquals(hash, t.pinnedPeerCertSha256)
        assertEquals("n.example", t.verifyPeerCertByName)
        assertEquals(hash, tls(t).getString("pinnedPeerCertSha256"))
    }

    @Test fun blank_pcs_and_vcn_become_null() {
        val p = vless("security=tls&pcs=&vcn=%20")
        assertNull(p.pinnedPeerCertSha256)
        assertNull(p.verifyPeerCertByName)
    }

    @Test fun openssl_format_and_comma_list_are_kept() {
        val colons = (1..32).joinToString(":") { "AB" }
        assertEquals(colons, tls(vless("security=tls&pcs=$colons")).getString("pinnedPeerCertSha256"))
        assertEquals("$hash,$colons", tls(vless("security=tls&pcs=$hash,%20$colons")).getString("pinnedPeerCertSha256"))
    }

    @Test fun bad_pin_is_dropped_because_core_would_reject_whole_config() {
        for (bad in listOf("abc", "z".repeat(64), "a".repeat(63), "a".repeat(65))) {
            assertFalse(bad, tls(vless("security=tls&pcs=$bad")).has("pinnedPeerCertSha256"))
        }
        assertEquals(hash, tls(vless("security=tls&pcs=abc,$hash")).getString("pinnedPeerCertSha256"))
    }

    @Test fun json_config_reads_pcs_and_vcn() {
        val body = """
            { "remarks": "n", "outbounds": [ { "tag": "proxy", "protocol": "vless",
              "settings": { "vnext": [ { "address": "h.example.com", "port": 443,
                "users": [ { "id": "11111111-1111-1111-1111-111111111111", "encryption": "none" } ] } ] },
              "streamSettings": { "network": "tcp", "security": "tls",
                "tlsSettings": { "serverName": "h.example.com", "allowInsecure": true,
                  "pinnedPeerCertSha256": "$hash", "verifyPeerCertByName": "n.example" } } } ] }
        """.trimIndent()
        val p = JsonConfigParser.parse(body).single()
        assertTrue(p.allowInsecure)
        assertEquals(hash, p.pinnedPeerCertSha256)
        assertEquals("n.example", p.verifyPeerCertByName)
        assertFalse(XrayConfigBuilder.build(p).contains("allowInsecure"))
    }

    @Test fun new_fields_do_not_move_server_key() {
        assertEquals(
            SubscriptionManager.serverKey(vless("security=tls")),
            SubscriptionManager.serverKey(vless("security=tls&pcs=$hash&vcn=n.example")),
        )
    }

    @Test fun empty_new_fields_are_not_written_to_the_store_but_filled_ones_survive_reload() {
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }   // как SubscriptionStore
        val plain = json.parseToJsonElement(json.encodeToString(vless("security=tls"))).jsonObject
        assertFalse(plain.containsKey("pinnedPeerCertSha256"))
        assertFalse(plain.containsKey("verifyPeerCertByName"))

        val full = vless("security=tls&pcs=$hash&vcn=n.example")
        assertEquals(full, json.decodeFromString<ServerProfile>(json.encodeToString(full)))
    }
}
