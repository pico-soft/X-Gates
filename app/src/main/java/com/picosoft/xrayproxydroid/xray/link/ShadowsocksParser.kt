package com.picosoft.xrayproxydroid.xray.link

import android.util.Base64
import java.net.URI

/**
 * Shadowsocks. Эталон — v2rayNG ShadowsocksFmt (в Python ss не было). Два формата:
 *  - SIP002:  ss://base64url(method:password)@host:port?plugin=..#remark
 *             (user-info может быть base64 ИЛИ plain "method:password")
 *  - legacy:  ss://base64(method:password@host:port)#remark
 *
 * credential = password, method = cipher (aes-256-gcm / chacha20-poly1305 / …).
 * Обычно без tls и транспорта — чистый tcp. plugin=obfs пока не поддержан (отложено, не падаем).
 */
object ShadowsocksParser : LinkParser {

    override fun parse(uri: String): ServerProfile? =
        parseSip002(uri) ?: parseLegacy(uri)

    /** ss://[base64|plain method:password]@host:port#remark */
    private fun parseSip002(str: String): ServerProfile? {
        val u = try { URI(str) } catch (e: Exception) { return null }
        val host = u.host?.takeIf { it.isNotBlank() } ?: return null
        if (u.port <= 0) return null
        val userInfo = u.userInfo?.takeIf { it.isNotBlank() } ?: return null

        // user-info: plain "method:password" (есть ':') ИЛИ base64(method:password)
        val parts = if (userInfo.contains(":")) {
            userInfo.split(":", limit = 2)
        } else {
            (decodeBase64(userInfo) ?: return null).split(":", limit = 2)
        }
        if (parts.size != 2) return null

        return build(
            remarks = u.fragment ?: "",
            host = host,
            port = u.port,
            method = parts[0],
            password = parts[1],
            raw = str,
        )
    }

    /** ss://base64(method:password@host:port)#remark */
    private fun parseLegacy(str: String): ServerProfile? {
        var result = str.removePrefix("ss://")

        var remarks = ""
        val hashIdx = result.indexOf('#')
        if (hashIdx >= 0) {
            remarks = LinkUtil.urlDecode(result.substring(hashIdx + 1))
            result = result.substring(0, hashIdx)
        }

        // Декодируем только левую часть (до @), если @ уже в открытом виде; иначе — всю строку.
        val atIdx = result.indexOf('@')
        result = if (atIdx > 0) {
            (decodeBase64(result.substring(0, atIdx)) ?: return null) + result.substring(atIdx)
        } else {
            decodeBase64(result) ?: return null
        }

        val m = Regex("^(.+?):(.*)@(.+?):(\\d+?)/?$").matchEntire(result) ?: return null
        val (method, password, host, portStr) = m.destructured
        val port = portStr.toIntOrNull() ?: return null

        return build(remarks, host, port, method.lowercase(), password, str)
    }

    private fun build(
        remarks: String, host: String, port: Int,
        method: String, password: String, raw: String,
    ): ServerProfile? {
        // Без cipher/пароля профиль бессмысленен → Invalid на парсинге (лучше, чем "method":"").
        if (method.isBlank() || password.isBlank()) return null
        return ServerProfile(
            protocol = Protocol.SHADOWSOCKS,
            remarks = remarks,
            address = host.removeSurrounding("[", "]"),
            port = port,
            credential = password,     // пароль
            method = method,           // cipher
            security = "none",
            network = "tcp",           // чистый tcp; plugin=obfs отложен
            raw = raw,
        )
    }

    /** base64 c нормализацией url-safe (-_ → +/) и дополнением паддинга; null при ошибке. */
    private fun decodeBase64(s: String): String? = try {
        val norm = s.replace('-', '+').replace('_', '/')
        val padded = norm + "=".repeat((4 - norm.length % 4) % 4)
        String(Base64.decode(padded, Base64.NO_WRAP), Charsets.UTF_8)
    } catch (e: Exception) {
        null
    }
}
