package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.monitor.TrafficEstimate
import com.picosoft.xrayproxydroid.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Оценка расхода в Настройках (находка Windows-КК 535436a, решение Elyor 2026-11-03): прежняя формула считала только «держать лучший»
 * раз в 6 ч, ≈ 1,2 ГБ/мес при настройках по умолчанию, а в экономии обещала «≈ 0».
 */
class TrafficEstimateTest {
    private val s = AppSettings()
    private fun old(s: AppSettings) = 2_592_000.0 / s.optimizeSecNormal * (s.topBatchNormal + s.topMixUnmeasured) * (0.5 + s.sufficientMbps / 8.0 * 1.2)

    @Test fun total_counts_more_than_the_old_top_only_formula() {
        val total = TrafficEstimate.backgroundMbPerMonth(s, TrafficEstimate.FAST_MBPS)
        assertEquals(1224.0, old(s), 1.0)
        assertTrue("итог $total МБ должен быть много больше прежних ${old(s)}", total > 10 * old(s))
    }

    @Test fun slow_active_costs_more_on_charger_schedule() {
        val fast = TrafficEstimate.optimizeMbPerMonth(s, TrafficEstimate.FAST_MBPS)
        val slow = TrafficEstimate.optimizeMbPerMonth(s, TrafficEstimate.SLOW_MBPS)
        assertTrue("на зарядке медленный ищет каждую минуту: $slow > $fast", slow > 5 * fast)
    }

    @Test fun economy_is_not_zero_but_has_no_top_upkeep_or_plate_probe() {
        val eco = s.copy(trafficSaveMode = true)
        assertEquals(0.0, TrafficEstimate.optimizeMbPerMonth(eco, TrafficEstimate.SLOW_MBPS), 0.0)
        val total = TrafficEstimate.backgroundMbPerMonth(eco, TrafficEstimate.SLOW_MBPS)
        assertTrue("рейтинг и живость в экономии идут: $total", total > 0.0)
        assertEquals("проба плашки в экономии не идёт", total,
            TrafficEstimate.backgroundMbPerMonth(eco.copy(liveSpeedActiveProbeSec = 1_000_000), TrafficEstimate.SLOW_MBPS), 1e-9)
    }

    @Test fun plate_probe_and_rating_are_in_the_total() {
        val total = TrafficEstimate.backgroundMbPerMonth(s, TrafficEstimate.FAST_MBPS)
        assertTrue("проба плашки", total > TrafficEstimate.backgroundMbPerMonth(s.copy(liveSpeedActiveProbeSec = 1_000_000), TrafficEstimate.FAST_MBPS) + 1000.0)
        assertTrue("рейтинг и живость без перемера", TrafficEstimate.backgroundMbPerMonth(s.copy(optimizeSecNormal = 0), TrafficEstimate.FAST_MBPS) > 1000.0)
    }

    @Test fun checks_on_charger_every_10s_are_about_1mb_per_hour() {
        val perMonth = TrafficEstimate.checksMbPerMonth(10, 3000L)
        assertEquals(1.03, perMonth / 720.0, 0.05)   // 720 ч в месяце
    }
}
