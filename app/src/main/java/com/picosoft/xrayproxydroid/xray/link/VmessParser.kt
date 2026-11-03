package com.picosoft.xrayproxydroid.xray.link

import android.util.Base64
import org.json.JSONObject

/**
 * vmess:// + base64(JSON). Принципиально отличается от vless/trojan — НЕ query-ссылка.
 * Эталон — v2rayNG VmessFmt (в Python vmess не было).
 *
 * JSON-поля (VmessQRCode): add, port, id, net, scy, aid, type, host, path, tls, sni, fp, alpn, ps.
 * Маппинг из карты: id→credential(UUID), scy→method(шифр), tls→security, net→network.
 * alterId в конфиге всегда 0 (AEAD) — поле aid из ссылки игнорируем.
 *
 * Второй, редкий формат `vmess://uuid@host?params` (query) — пока НЕ поддержан (TODO),
 * но не падаем: помечаем как невалидный и идём дальше.
 */
object VmessParser : LinkParser {
    override fun parse(uri: String): ServerProfile? {
        val body = uri.removePrefix("vmess://").trim()

        // Детект query-формата (vmess-std) по наличию ? и & — откладываем, не крашим.
        if (body.contains('?') && body.contains('&')) return null
        // Находка порта Windows №2: ~1.5% реальных ссылок — `vmess://<base64>=?хвост` (без &): хвост ломал Base64 и
        // сервер отбрасывался. Base64-часть — до «?», хвост не нужен (имя — в "ps" JSON).
        val b64 = body.substringBefore('?')

        val json = try {
            val padded = b64 + "=".repeat((4 - b64.length % 4) % 4) // паддинг до кратности 4
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
        } catch (e: Exception) {
            return null
        }

        val o = try { JSONObject(json) } catch (e: Exception) { return null }

        val address = o.optString("add").takeIf { it.isNotBlank() } ?: return null
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val port = o.opt("port")?.toString()?.toIntOrNull() ?: return null

        val net = o.optString("net").ifBlank { "tcp" }
        val tls = o.optString("tls")                         // "" | "tls" | "reality"
        val path = o.optString("path").ifBlank { null }

        return ServerProfile(
            protocol = Protocol.VMESS,
            remarks = o.optString("ps"),
            address = address,
            port = port,
            credential = id,                                 // UUID
            method = o.optString("scy").ifBlank { "auto" },  // шифр vmess
            flow = null,
            security = if (tls.isBlank()) "none" else tls,
            sni = o.optString("sni").ifBlank { null },
            fingerprint = o.optString("fp").ifBlank { null },
            alpn = o.optString("alpn").ifBlank { null },
            allowInsecure = false,
            network = net,
            path = path,
            hostHeader = o.optString("host").ifBlank { null },
            serviceName = if (net == "grpc") path else null, // grpc: serviceName лежит в path
            headerType = o.optString("type").ifBlank { null },
            mode = o.optString("mode").ifBlank { null },     // xhttp mode (vmess-json)
            raw = uri,
        )
    }
}
