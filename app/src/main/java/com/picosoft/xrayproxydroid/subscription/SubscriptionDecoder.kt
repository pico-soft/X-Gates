package com.picosoft.xrayproxydroid.subscription

import android.util.Base64

/**
 * Тело подписки → список строк-ссылок.
 *
 * Тело обычно закодировано base64 целиком (после декода каждая строка = ссылка), реже — плейн-текст.
 * Эвристика ЧИНЕНАЯ относительно Python-эталона: считаем декод «похожим на ссылки», если он содержит
 * ЛЮБУЮ известную схему (vless/vmess/trojan/ss) ИЛИ хотя бы "://" — а не только vless/trojan
 * (иначе подписки только из vmess/ss не распознавались).
 *
 * Строки НЕ фильтруются на валидность — это делает ServerLinkParser на следующем шаге (Manager).
 * Возвращаются только непустые (обрезанные) строки.
 */
object SubscriptionDecoder {

    private val KNOWN_SCHEMES = listOf("vless://", "vmess://", "trojan://", "ss://")

    fun decode(body: String): List<String> {
        val text = maybeBase64(body) ?: body
        return text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
    }

    /** Пытается декодировать ВСЁ тело как base64; возвращает текст, только если он «похож на ссылки». */
    private fun maybeBase64(body: String): String? {
        val compact = body.trim().replace("\n", "").replace("\r", "")
        if (compact.isEmpty()) return null
        return try {
            val norm = compact.replace('-', '+').replace('_', '/')          // url-safe → std
            val padded = norm + "=".repeat((4 - norm.length % 4) % 4)        // паддинг
            val decoded = String(Base64.decode(padded, Base64.NO_WRAP), Charsets.UTF_8)
            if (looksLikeLinks(decoded)) decoded else null
        } catch (e: Exception) {
            null   // не base64 (например, тело уже плейн-текст со ссылками) → откат к body
        }
    }

    private fun looksLikeLinks(text: String): Boolean =
        KNOWN_SCHEMES.any { text.contains(it, ignoreCase = true) } || text.contains("://")
}
