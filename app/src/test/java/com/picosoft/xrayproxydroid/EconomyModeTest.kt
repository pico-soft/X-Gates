package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.Blocklist
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пр.139: (1) эффективная экономия = мастер + подпункт «только моб.» + тип сети; (2) ЖИВОСТЬ («Живые») зависит
 * ТОЛЬКО от пинга, одинаково во всех режимах и при любой скорости (в т.ч. провал -1) — корень бага «в экономии
 * живых 0, выключил — появились».
 */
class EconomyModeTest {

    // ── (1) таблица истинности эффективного режима ──
    @Test fun master_off_never_economy() {
        val s = AppSettings(economyEnabled = false, trafficSaveAutoByNetwork = true)
        assertFalse(s.economyEffective(isMobile = true))
        assertFalse(s.economyEffective(isMobile = false))
    }

    @Test fun master_on_mobileOnly_economy_only_on_mobile() {
        val s = AppSettings(economyEnabled = true, trafficSaveAutoByNetwork = true)
        assertTrue("на мобильной — экономия", s.economyEffective(isMobile = true))
        assertFalse("на Wi-Fi — обычный", s.economyEffective(isMobile = false))
    }

    @Test fun master_on_alwaysEconomy() {
        val s = AppSettings(economyEnabled = true, trafficSaveAutoByNetwork = false)
        assertTrue(s.economyEffective(isMobile = true))
        assertTrue(s.economyEffective(isMobile = false))
    }

    @Test fun defaults_first_install_economy_off_but_suboption_on() {
        val d = AppSettings()
        assertFalse("первая установка — экономия ВЫКЛ", d.economyEnabled)
        assertTrue("подпункт «только моб.» по дефолту ВКЛ", d.trafficSaveAutoByNetwork)
    }

    // ── (2) видимость в «Живые» = только пинг, не зависит от скорости/режима ──
    private val bl = Blocklist()
    private fun srv() = ServerProfile(
        protocol = Protocol.VLESS, remarks = "n", address = "h.example.com", port = 443, credential = "u",
    )

    @Test fun alive_pinged_server_visible_regardless_of_speed() {
        val s = AppSettings()
        // пинг жив, скорость: не мерена / провал(-1) / низкая — ВСЕ должны быть видны (живость = пинг)
        assertTrue("не мерена", ServerFilter.isVisible(srv(), 120, null, s, bl))
        assertTrue("провал -1 не прячет", ServerFilter.isVisible(srv(), 120, -1.0, s, bl))
        assertTrue("низкая скорость не прячет", ServerFilter.isVisible(srv(), 120, 0.05, s, bl))
        assertTrue("нормальная", ServerFilter.isVisible(srv(), 120, 50.0, s, bl))
    }

    @Test fun dead_ping_not_visible() {
        val s = AppSettings()
        assertFalse("пинг мёртв (-1)", ServerFilter.isVisible(srv(), -1, 50.0, s, bl))
        assertFalse("пинг не мерян (null)", ServerFilter.isVisible(srv(), null, 50.0, s, bl))
    }

    @Test fun visibility_identical_no_matter_the_economy_settings() {
        val normal = AppSettings(economyEnabled = false)
        val economy = AppSettings(economyEnabled = true, trafficSaveMode = true)
        // Один и тот же ping-живой сервер с провальной скоростью виден в ОБОИХ режимах одинаково.
        assertEquals(
            ServerFilter.isVisible(srv(), 100, -1.0, normal, bl),
            ServerFilter.isVisible(srv(), 100, -1.0, economy, bl),
        )
        assertTrue(ServerFilter.isVisible(srv(), 100, -1.0, economy, bl))
    }
}
