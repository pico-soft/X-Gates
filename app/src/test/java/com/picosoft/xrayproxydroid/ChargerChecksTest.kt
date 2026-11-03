package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение Elyor 2026-10-31 (как в Windows-версии): редкая проверка связи — только на батарее. На зарядке по Wi-Fi без
 * экономии трафика — проверка раз в 10 с, обрыв после 30 с тишины (перепроверка 10 с) → переключение ≈ 35 с вместо
 * 2–2,5 мин. Экономия трафика и мобильная сеть — как на батарее.
 */
class ChargerChecksTest {

    private val s = AppSettings(connectionCheckIntervalSec = 60)

    @Test fun battery_keeps_slider_and_long_grace() {
        val fast = NetworkMonitor.fastChecks(charging = false, s = s, mobile = false)
        assertFalse(fast)
        assertEquals(60_000L, NetworkMonitor.healthyCheckMs(s, fast))
        assertEquals(90_000L, NetworkMonitor.graceMs(fast))
        assertEquals(25_000L, NetworkMonitor.graceRecheckMs(fast))
    }

    @Test fun charger_on_wifi_checks_every_10s_and_declares_outage_after_30s() {
        val fast = NetworkMonitor.fastChecks(charging = true, s = s, mobile = false)
        assertTrue(fast)
        assertEquals(10_000L, NetworkMonitor.healthyCheckMs(s, fast))
        assertEquals(30_000L, NetworkMonitor.graceMs(fast))
        assertEquals(10_000L, NetworkMonitor.graceRecheckMs(fast))
        // Ползунок на зарядке не замедляет: даже 15 мин → 10 с.
        assertEquals(10_000L, NetworkMonitor.healthyCheckMs(AppSettings(connectionCheckIntervalSec = 900), fast))
    }

    @Test fun charger_with_traffic_save_or_mobile_stays_as_on_battery() {
        assertFalse("экономия трафика", NetworkMonitor.fastChecks(charging = true, s = s.copy(trafficSaveMode = true), mobile = false))
        assertFalse("мобильная сеть", NetworkMonitor.fastChecks(charging = true, s = s, mobile = true))
    }
}
