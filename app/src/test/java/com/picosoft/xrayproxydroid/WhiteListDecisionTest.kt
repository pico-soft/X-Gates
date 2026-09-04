package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.monitor.WhiteListDetector
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пр.140: чистая логика решения о режиме белых списков (без сети/настроек).
 *  effective: фича вкл + google недоступен + yandex доступен + юзер не держит обычный → белый режим.
 *  google вернулся → обычный (сбрасываем ручной override). оба мертвы → общий обрыв, не трогаем.
 */
class WhiteListDecisionTest {

    private fun d(auto: Boolean, manual: Boolean, active: Boolean, g: Boolean, y: Boolean) =
        WhiteListDetector.decide(auto, manual, active, googleOk = g, yandexOk = y)

    @Test fun feature_off_forces_normal() {
        val r = d(auto = false, manual = false, active = true, g = false, y = true)
        assertFalse(r.active); assertFalse(r.manualNormal)
    }

    @Test fun enters_white_when_google_blocked_yandex_ok() {
        val r = d(auto = true, manual = false, active = false, g = false, y = true)
        assertTrue("должен войти в белый режим", r.active)
    }

    @Test fun google_back_returns_to_normal_and_clears_manual() {
        val r = d(auto = true, manual = true, active = true, g = true, y = true)
        assertFalse("google открыт → обычный", r.active)
        assertFalse("ручной override снят", r.manualNormal)
    }

    @Test fun manual_normal_keeps_normal_even_if_google_blocked() {
        val r = d(auto = true, manual = true, active = false, g = false, y = true)
        assertFalse("юзер держит обычный", r.active)
        assertTrue("override держится, пока google не вернётся", r.manualNormal)
    }

    @Test fun both_dead_is_general_outage_no_change() {
        val wasActive = d(auto = true, manual = false, active = true, g = false, y = false)
        assertTrue("общий обрыв — режим не меняем (был белый)", wasActive.active)
        val wasNormal = d(auto = true, manual = false, active = false, g = false, y = false)
        assertFalse("общий обрыв — режим не меняем (был обычный)", wasNormal.active)
    }

    @Test fun normal_stays_normal_when_google_ok() {
        val r = d(auto = true, manual = false, active = false, g = true, y = true)
        assertFalse(r.active)
    }
}
