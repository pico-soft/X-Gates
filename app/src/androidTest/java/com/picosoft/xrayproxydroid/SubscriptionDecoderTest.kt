package com.picosoft.xrayproxydroid

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.picosoft.xrayproxydroid.subscription.SubscriptionDecoder
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Декодер подписки. Инструментальный (не JVM-unit), т.к. android.util.Base64 доступен только
 * на устройстве/Robolectric. Проверяем: base64→ссылки, плейн-текст, и чинёную эвристику (vmess/ss без vless).
 */
@RunWith(AndroidJUnit4::class)
class SubscriptionDecoderTest {

    private fun b64(s: String) = Base64.encodeToString(s.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

    private val vless = "vless://13a0205c-107a-4c7a-954e-2b5fcb235449@example.com:443?type=ws&security=tls#a"
    private val vmess = "vmess://eyJhZGQiOiJoay5leGFtcGxlIiwicG9ydCI6IjQ0MyJ9"
    private val ss = "ss://Y2hhY2hhMjAtaWV0Zi1wb2x5MTMwNTpwdw@1.2.3.4:8388#c"

    @Test
    fun base64Body_decodesToLinks() {
        val body = b64(listOf(vless, vmess, ss).joinToString("\n"))
        assertEquals(listOf(vless, vmess, ss), SubscriptionDecoder.decode(body))
    }

    @Test
    fun plaintextBody_returnedAsLines() {
        // Плейн-текст (не base64): содержит "://" → base64-декод не пройдёт → откат к телу.
        val body = listOf(vless, ss).joinToString("\n")
        assertEquals(listOf(vless, ss), SubscriptionDecoder.decode(body))
    }

    @Test
    fun vmessAndSsOnly_stillDecoded_fixedHeuristic() {
        // Ни vless, ни trojan — Python-эвристика бы провалилась; наша (любая схема / "://") — нет.
        val body = b64(listOf(vmess, ss).joinToString("\n"))
        assertEquals(listOf(vmess, ss), SubscriptionDecoder.decode(body))
    }

    @Test
    fun emptyAndBlankLines_skipped() {
        val body = b64("\n$vless\n\n   \n$ss\n")
        assertEquals(listOf(vless, ss), SubscriptionDecoder.decode(body))
    }
}
