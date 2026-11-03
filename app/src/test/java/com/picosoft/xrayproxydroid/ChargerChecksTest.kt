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

    @Test fun active_ping_is_saved_every_cycle_on_battery_but_once_a_minute_on_charger() {
        // Реестр пишется целиком: на зарядке цикл раз в 10 с, а файл не должен переписываться шесть раз в минуту.
        assertTrue(NetworkMonitor.activePingSaveDue(fast = false, pingMs = 40, lastSavedPingMs = 40, sinceSaveMs = 10_000))
        assertFalse(NetworkMonitor.activePingSaveDue(fast = true, pingMs = 45, lastSavedPingMs = 40, sinceSaveMs = 10_000))
        assertTrue(NetworkMonitor.activePingSaveDue(fast = true, pingMs = 45, lastSavedPingMs = 40, sinceSaveMs = 60_000))
        // Сервер умер или ожил — список должен узнать сразу.
        assertTrue(NetworkMonitor.activePingSaveDue(fast = true, pingMs = -1, lastSavedPingMs = 40, sinceSaveMs = 10_000))
        assertTrue(NetworkMonitor.activePingSaveDue(fast = true, pingMs = 40, lastSavedPingMs = -1, sinceSaveMs = 10_000))
    }
}
