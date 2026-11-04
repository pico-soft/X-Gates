package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.monitor.TunnelSpeed
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** Решение Elyor 2026-11-02: что качает браузер — не скорость туннеля. Поток показываем отдельно, замер он не заменяет. */
class TunnelSpeedFlowTest {

    @Before @After fun reset() = TunnelSpeed.clear()

    @Test fun browser_traffic_does_not_replace_measured_speed() {
        TunnelSpeed.setProbe(30.0, 15.0, 5.0, 1000L, "a")
        TunnelSpeed.setFlow(0.7, 0.1, 2000L, "a")
        val s = TunnelSpeed.snapshot()
        assertEquals(15.0, s.tunnelDownMbps!!, 0.0)
        assertEquals(5.0, s.tunnelUpMbps!!, 0.0)
        assertEquals(1000L, s.measuredAtMs)   // возраст замера тоже прежний: подпись «N мин назад» честная
        assertEquals(0.7, s.flowDownMbps!!, 0.0)
        assertEquals(30.0, s.directDownMbps!!, 0.0)
    }

    @Test fun flow_above_measured_speed_raises_it_because_tunnel_proved_it() {
        TunnelSpeed.setProbe(null, 15.0, 5.0, 1000L, "a")
        TunnelSpeed.setFlow(40.0, 1.0, 2000L, "a")
        val s = TunnelSpeed.snapshot()
        assertEquals(40.0, s.tunnelDownMbps!!, 0.0)
        assertEquals(5.0, s.tunnelUpMbps!!, 0.0)
        assertEquals(2000L, s.measuredAtMs)
    }

    @Test fun without_measurement_flow_is_not_passed_off_as_speed() {
        TunnelSpeed.setFlow(0.7, 0.1, 2000L, "a")
        val s = TunnelSpeed.snapshot()
        assertNull(s.tunnelDownMbps)
        assertEquals(0L, s.measuredAtMs)
        assertEquals(0.7, s.flowDownMbps!!, 0.0)
    }

    @Test fun first_measure_is_still_due_while_only_flow_is_known() {
        TunnelSpeed.setFlow(0.7, 0.1, 2000L, "a")
        assertEquals(false, TunnelSpeed.measured("a"))   // при постоянном трафике проба всё равно нужна, иначе «Туннель» пустой
        TunnelSpeed.setProbe(null, 15.0, 5.0, 3000L, "a")
        assertEquals(true, TunnelSpeed.measured("a"))
        assertEquals(false, TunnelSpeed.measured("b"))
    }

    @Test fun numbers_of_previous_server_do_not_leak_to_new_one() {
        TunnelSpeed.setProbe(30.0, 15.0, 5.0, 1000L, "a")
        TunnelSpeed.setFlow(40.0, 1.0, 2000L, "b")
        val s = TunnelSpeed.snapshot()
        assertNull(s.tunnelDownMbps)
        assertEquals("b", s.serverKey)
        assertEquals(40.0, s.flowDownMbps!!, 0.0)
    }

    @Test fun silence_clears_flow_but_keeps_measurement_and_probe_keeps_flow() {
        TunnelSpeed.setProbe(null, 15.0, 5.0, 1000L, "a")
        TunnelSpeed.setFlow(0.7, 0.1, 2000L, "a")
        TunnelSpeed.setProbe(null, 16.0, 5.0, 3000L, "a")
        assertEquals(0.7, TunnelSpeed.snapshot().flowDownMbps!!, 0.0)
        TunnelSpeed.clearFlow("a")
        assertNull(TunnelSpeed.snapshot().flowDownMbps)
        assertEquals(0L, TunnelSpeed.snapshot().flowAtMs)
        assertEquals(16.0, TunnelSpeed.snapshot().tunnelDownMbps!!, 0.0)
    }
}
