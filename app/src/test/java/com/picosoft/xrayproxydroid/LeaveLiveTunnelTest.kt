package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.settings.AppSettings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Правило Elyor: с живого туннеля уходим, только если уверены, что другой намного быстрее (порог 2 Мбит/с, правило ×3). */
class LeaveLiveTunnelTest {

    private val s = AppSettings(degradationMinMbps = 2.0, keepBestMultiplier = 3.0)

    @Test fun clearly_faster_candidate_is_worth_leaving_for() {
        assertTrue(NetworkMonitor.worthLeaving(candMbps = 3.0, curMbps = 1.0, s))
        assertTrue(NetworkMonitor.worthLeaving(candMbps = 40.0, curMbps = 1.5, s))
    }

    @Test fun slightly_faster_candidate_is_not_a_reason_to_break_live_tunnel() {
        assertFalse(NetworkMonitor.worthLeaving(candMbps = 2.2, curMbps = 1.5, s))
    }

    @Test fun candidate_below_useful_speed_is_not_worth_leaving_for() {
        assertFalse(NetworkMonitor.worthLeaving(candMbps = 1.5, curMbps = 0.1, s))
    }

    @Test fun browser_consumption_alone_passes_but_real_tunnel_speed_keeps_us() {
        // Страница в браузере: за 6 с скачано 0,7 Мбит/с, а туннель тянет 15. Кандидат на 3 проходит только против потребления.
        assertTrue(NetworkMonitor.worthLeaving(candMbps = 3.0, curMbps = 0.7, s))
        assertFalse(NetworkMonitor.worthLeaving(candMbps = 3.0, curMbps = maxOf(15.0, 0.7), s))
    }

    @Test fun stored_speed_is_a_reason_to_check_only_while_it_could_beat_the_tunnel() {
        val s3 = s.copy(keepBestMultiplier = 3.0)
        assertTrue(NetworkMonitor.storedWorthChecking(storedMbps = 30.0, bestMbps = 0.0, curMbps = 1.0, s3))
        assertFalse("медленнее ×3", NetworkMonitor.storedWorthChecking(storedMbps = 2.0, bestMbps = 0.0, curMbps = 1.0, s3))
        assertFalse("не лучше уже найденного", NetworkMonitor.storedWorthChecking(storedMbps = 30.0, bestMbps = 40.0, curMbps = 1.0, s3))
        assertFalse("активный не измерен", NetworkMonitor.storedWorthChecking(storedMbps = 30.0, bestMbps = 0.0, curMbps = -1.0, s3))
    }
}
