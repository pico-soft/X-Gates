package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.VlessParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пр.138: транспорты, добавленные в XrayConfigBuilder (raw/xhttp/httpupgrade/mkcp). Гоняем РЕАЛЬНЫЕ
 * VlessParser + XrayConfigBuilder (чистый Kotlin) — раньше build() бросал "unsupported transport".
 * Проверяем: (1) build НЕ падает, (2) сеть/блок настроек в конфиге верные, (3) JSON сбалансирован.
 */
class TransportBuildTest {

    /** Грубая проверка сбалансированности {} и [] вне строк — достаточно как sanity для сгенерированного JSON. */
    private fun balanced(s: String): Boolean {
        var curly = 0; var square = 0; var inStr = false; var esc = false
        for (c in s) {
            if (esc) { esc = false; continue }
            when {
                c == '\\' && inStr -> esc = true
                c == '"' -> inStr = !inStr
                !inStr && c == '{' -> curly++
                !inStr && c == '}' -> curly--
                !inStr && c == '[' -> square++
                !inStr && c == ']' -> square--
            }
            if (curly < 0 || square < 0) return false
        }
        return curly == 0 && square == 0 && !inStr
    }

    private fun vless(link: String) = VlessParser.parse(link) ?: error("parse failed: $link")

    @Test fun xhttp_reality_builds() {
        val p = vless(
            "vless://11111111-1111-1111-1111-111111111111@example.com:443" +
                "?encryption=none&mode=packet-up&path=%2Fassets%2Fv2&security=reality" +
                "&pbk=SOMEKEY&sid=ab12&sni=s.example.com&type=xhttp&fp=chrome#node"
        )
        assertEquals("xhttp", p.network)
        assertEquals("packet-up", p.mode)
        assertEquals("/assets/v2", p.path)
        val cfg = XrayConfigBuilder.build(p)   // раньше бросал unsupported transport
        assertTrue("network xhttp", cfg.contains("\"network\": \"xhttp\""))
        assertTrue("xhttpSettings", cfg.contains("\"xhttpSettings\""))
        assertTrue("mode", cfg.contains("\"mode\": \"packet-up\""))
        assertTrue("path", cfg.contains("\"path\": \"/assets/v2\""))
        assertTrue("reality", cfg.contains("\"realitySettings\""))
        assertTrue("json balanced", balanced(cfg))
    }

    @Test fun raw_normalizes_to_tcp() {
        val p = vless("vless://11111111-1111-1111-1111-111111111111@h.example.com:443?type=raw&security=none#n")
        assertEquals("raw", p.network)
        val cfg = XrayConfigBuilder.build(p)   // не должно бросать
        assertTrue("network tcp", cfg.contains("\"network\": \"tcp\""))
        assertTrue("json balanced", balanced(cfg))
    }

    @Test fun httpupgrade_builds() {
        val p = vless(
            "vless://11111111-1111-1111-1111-111111111111@h.example.com:443" +
                "?type=httpupgrade&path=%2Fhu&host=cdn.example.com&security=tls&sni=cdn.example.com#n"
        )
        assertEquals("httpupgrade", p.network)
        val cfg = XrayConfigBuilder.build(p)
        assertTrue("network httpupgrade", cfg.contains("\"network\": \"httpupgrade\""))
        assertTrue("httpupgradeSettings", cfg.contains("\"httpupgradeSettings\""))
        assertTrue("path", cfg.contains("\"path\": \"/hu\""))
        assertTrue("host", cfg.contains("\"host\": \"cdn.example.com\""))
        assertTrue("json balanced", balanced(cfg))
    }

    @Test fun mkcp_builds_with_header_and_seed() {
        val p = vless(
            "vless://11111111-1111-1111-1111-111111111111@h.example.com:2408" +
                "?type=kcp&headerType=srtp&seed=mySeed&security=none#n"
        )
        assertEquals("kcp", p.network)
        assertEquals("srtp", p.headerType)
        assertEquals("mySeed", p.seed)
        val cfg = XrayConfigBuilder.build(p)
        assertTrue("network mkcp", cfg.contains("\"network\": \"mkcp\""))
        assertTrue("kcpSettings", cfg.contains("\"kcpSettings\""))
        assertTrue("header type srtp", cfg.contains("\"type\": \"srtp\""))
        assertTrue("seed", cfg.contains("\"seed\": \"mySeed\""))
        assertTrue("json balanced", balanced(cfg))
    }

    @Test fun still_supports_ws_grpc() {
        val ws = XrayConfigBuilder.build(vless("vless://11111111-1111-1111-1111-111111111111@h:443?type=ws&path=%2Fw&host=x.com&security=tls&sni=x.com#n"))
        assertTrue(ws.contains("\"wsSettings\""))
        val grpc = XrayConfigBuilder.build(vless("vless://11111111-1111-1111-1111-111111111111@h:443?type=grpc&serviceName=gs&security=tls&sni=x.com#n"))
        assertTrue(grpc.contains("\"grpcSettings\""))
    }
}
