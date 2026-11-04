package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.link.JsonConfigParser
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** mKCP из JSON-подписок: старый формат (kcpSettings) и новый (finalmask) дают один и тот же конфиг ядра. */
class KcpJsonConfigTest {

    private fun body(stream: String) = """
        { "remarks": "n", "outbounds": [ { "tag": "proxy", "protocol": "vless",
          "settings": { "vnext": [ { "address": "h.example.com", "port": 2408,
            "users": [ { "id": "11111111-1111-1111-1111-111111111111", "encryption": "none" } ] } ] },
          "streamSettings": $stream } ] }
    """.trimIndent()

    private fun masks(stream: String) = JSONObject(XrayConfigBuilder.build(JsonConfigParser.parse(body(stream)).single()))
        .getJSONArray("outbounds").getJSONObject(0).getJSONObject("streamSettings")
        .getJSONObject("finalmask").getJSONArray("udp").toString()

    @Test fun old_and_new_format_give_same_masks() {
        val old = masks("""{ "network": "kcp", "kcpSettings": { "header": { "type": "wechat-video" }, "seed": "s3" } }""")
        val new = masks("""{ "network": "kcp", "kcpSettings": {}, "finalmask": { "udp": [
            { "type": "mkcp-legacy", "settings": { "value": "s3" } },
            { "type": "mkcp-legacy", "settings": { "header": "wechat" } } ] } }""")
        assertEquals(old.replace("\\", ""), new.replace("\\", ""))
    }

    @Test fun dns_domain_is_kept_and_foreign_masks_are_ignored() {
        val m = masks("""{ "network": "mkcp", "finalmask": { "udp": [ { "type": "xicmp", "settings": { "value": "evil" } },
            { "type": "mkcp-legacy" }, { "type": "mkcp-legacy", "settings": { "header": "dns", "value": "a.example.com" } } ] } }""")
        assertEquals(2, org.json.JSONArray(m).length())
        val hdr = org.json.JSONArray(m).getJSONObject(1).getJSONObject("settings")
        assertEquals("dns", hdr.getString("header"))
        assertEquals("a.example.com", hdr.getString("value"))
    }
}
