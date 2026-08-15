package com.picosoft.xrayproxydroid.xray.link

import kotlinx.serialization.Serializable

/** Протоколы, которые исполняет наш xray-core. Hysteria2/TUIC сюда НЕ входят (sing-box, будущий этап). */
@Serializable
enum class Protocol { VLESS, VMESS, TROJAN, SHADOWSOCKS }

/**
 * Нормализованный сервер: результат разбора одной ссылки, вход для [XrayConfigBuilder].
 * Поля — общий знаменатель ss/vless/vmess/trojan; лишние для конкретного протокола остаются null.
 * @Serializable — сохраняется внутри подписки в subscriptions.json.
 */
@Serializable
data class ServerProfile(
    val protocol: Protocol,
    val remarks: String,
    val address: String,
    val port: Int,

    /**
     * Единый секрет сервера. Смысл зависит от протокола:
     *  - VLESS / VMESS       → UUID пользователя (→ settings.vnext[].users[].id)
     *  - TROJAN / SHADOWSOCKS → пароль          (→ settings.servers[].password)
     * Имя намеренно нейтральное: чтобы UUID никогда не лежал в поле с именем «password».
     */
    val credential: String,

    /** VLESS: "none"; VMESS: шифр (scy, дефолт auto); SHADOWSOCKS: cipher; TROJAN: не используется. */
    val method: String? = null,

    /** Только VLESS (xtls-vision и т.п.). */
    val flow: String? = null,

    // --- security ---
    val security: String = "none",     // none | tls | reality
    val sni: String? = null,
    val fingerprint: String? = null,
    val alpn: String? = null,
    val allowInsecure: Boolean = false,

    // --- транспорт ---
    val network: String = "tcp",       // поддержаны: tcp | ws | grpc
    val path: String? = null,
    val hostHeader: String? = null,    // ws Host; если пусто — билдер берёт sni (как Python-эталон)
    val serviceName: String? = null,   // grpc
    val headerType: String? = null,    // tcp http-header

    // --- reality ---
    val publicKey: String? = null,
    val shortId: String? = null,
    val spiderX: String? = null,

    /** Исходная ссылка — для отладки/переэкспорта. */
    val raw: String = "",

    // --- результаты теста задержки (динамика; backward-compat: дефолты + ignoreUnknownKeys) ---
    /** Задержка real ping в мс; ≥0 — жив, -1 — мёртвый, null — не тестировался. */
    val pingMs: Int? = null,
    /** Метка времени последнего замера ("yyyy-MM-dd HH:mm"); null — не тестировался. */
    val lastTestedTs: String? = null,
)
