package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.crash.Sanitizer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Промпт 97.D — повторная проверка очистки отчёта перед отправкой постороннему: ни адресов подписок,
 * ни учётных данных, ни UUID, ни паролей, ни внешних IP не должно остаться в очищённом тексте.
 */
class SanitizerTest {

    @Test
    fun `strips subscription urls credentials uuid ip and tokens`() {
        val raw = """
            === XrayProxyDroid — отчёт о сбое ===
            Устройство: samsung SM-S908E · Android 14 (SDK 34)
            Подписка: https://sub.example.com/link/abcDEF123456?token=SECRETVALUE
            Сервер: vless://3f2504e0-4f89-41d3-9a0c-0305e82c3301@203.0.113.77:443?type=tcp#Node1
            Trojan: trojan://myS3cretPass@198.51.100.12:8443#T
            Shadowsocks: ss://YWVzLTI1Ni1nY206c2VjcmV0cGFzc3dvcmQ@192.0.2.9:8388#SS
            Логин: admin:hunter2password@server.host
            UUID: 550e8400-e29b-41d4-a716-446655440000
            Внешний IP: 45.132.98.210
        """.trimIndent()

        val clean = Sanitizer.clean(raw)

        // Ничего чувствительного не осталось.
        assertFalse("URL подписки", clean.contains("sub.example.com"))
        assertFalse("схема vless://", clean.contains("vless://"))
        assertFalse("схема trojan://", clean.contains("trojan://"))
        assertFalse("схема ss://", clean.contains("ss://"))
        assertFalse("UUID", clean.contains("550e8400-e29b-41d4-a716-446655440000"))
        assertFalse("UUID в vless", clean.contains("3f2504e0-4f89-41d3-9a0c-0305e82c3301"))
        assertFalse("внешний IP", clean.contains("45.132.98.210"))
        assertFalse("IP сервера", clean.contains("203.0.113.77"))
        assertFalse("пароль trojan", clean.contains("myS3cretPass"))
        assertFalse("логин:пароль", clean.contains("hunter2password"))
        assertFalse("base64-секрет ss", clean.contains("YWVzLTI1Ni1nY206c2VjcmV0cGFzc3dvcmQ"))
        assertFalse("token в URL", clean.contains("SECRETVALUE"))

        // Полезное (модель/версия Android) сохраняется — маска не съедает диагностику.
        assertTrue("модель устройства", clean.contains("SM-S908E"))
        assertTrue("версия Android", clean.contains("Android 14"))
    }
}
