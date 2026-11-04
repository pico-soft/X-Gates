package com.picosoft.xrayproxydroid

import android.util.Base64
import com.picosoft.xrayproxydroid.xray.link.JsonConfigParser
import com.picosoft.xrayproxydroid.xray.link.VmessParser
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * org.json на Android рекурсивен: глубокая вложенность даёт StackOverflowError, а не JSONException, и catch (Exception) её
 * не ловит. Строка из подписки не должна ронять разбор. Настоящий org.json проверяется отдельным прогоном с jar из AOSP:
 * в зависимости тестов стоит другая реализация, она отвечает JSONException.
 */
class DeepNestingTest {

    private val deep = "[".repeat(200_000)

    @Test fun subscription_body_is_just_not_a_config() {
        assertTrue(JsonConfigParser.parse(deep).isEmpty())
        assertTrue(JsonConfigParser.parse("{\"outbounds\":$deep").isEmpty())
    }

    @Test fun vmess_link_is_just_dropped() {
        val body = Base64.encodeToString("{\"ps\":$deep".toByteArray(), Base64.NO_WRAP)
        assertNull(VmessParser.parse("vmess://$body"))
    }
}
