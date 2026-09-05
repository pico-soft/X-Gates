package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пр.143: быстрое восстановление при обрыве активного сервера. Ядро выбора — «самый быстрый из ЖИВЫХ первым»,
 * гейт при переборе — реальный пинг (не покрыт здесь: требует ядра/сети). Здесь проверяем ЧИСТУЮ часть:
 *  (1) «вероятно живой» = свежий пинг ≥0 ИЛИ известная скорость >0 (иначе перебирать вслепую бессмысленно);
 *  (2) порядок = по убыванию скорости, при равной — меньший пинг раньше; не-живые ОТСЕЯНЫ.
 */
class FastRecoveryTest {

    private fun srv(name: String, ping: Int? = null, speed: Double? = null) = ServerProfile(
        protocol = Protocol.VLESS, remarks = name, address = "$name.example.com", port = 443, credential = "u",
        pingMs = ping, speedMbps = speed,
    )

    // ── (1) isLikelyAlive ──
    @Test fun likely_alive_by_ping_or_speed() {
        assertTrue("свежий пинг → живой", NetworkMonitor.isLikelyAlive(srv("a", ping = 120)))
        assertTrue("известная скорость → живой", NetworkMonitor.isLikelyAlive(srv("b", speed = 12.0)))
        assertTrue("пинг+скорость → живой", NetworkMonitor.isLikelyAlive(srv("c", ping = 80, speed = 30.0)))
    }

    @Test fun not_alive_without_ping_or_speed() {
        assertFalse("нет данных → не живой", NetworkMonitor.isLikelyAlive(srv("x")))
        assertFalse("мёртвый пинг −1, скорости нет → не живой", NetworkMonitor.isLikelyAlive(srv("y", ping = -1)))
        assertFalse("скорость 0, пинга нет → не живой", NetworkMonitor.isLikelyAlive(srv("z", speed = 0.0)))
    }

    // ── (2) порядок подбора: самый быстрый первым ──
    @Test fun order_fastest_first_then_lower_ping() {
        val list = listOf(
            srv("slow", ping = 50, speed = 5.0),
            srv("fast", ping = 200, speed = 90.0),
            srv("mid", ping = 30, speed = 40.0),
        )
        val ordered = NetworkMonitor.orderFastCandidates(list).map { it.remarks }
        assertEquals(listOf("fast", "mid", "slow"), ordered)   // по скорости убыв., пинг вторичен
    }

    @Test fun order_equal_speed_prefers_lower_ping() {
        val list = listOf(
            srv("hi-ping", ping = 300, speed = 20.0),
            srv("lo-ping", ping = 40, speed = 20.0),
        )
        val ordered = NetworkMonitor.orderFastCandidates(list).map { it.remarks }
        assertEquals(listOf("lo-ping", "hi-ping"), ordered)   // равная скорость → меньший пинг раньше
    }

    @Test fun order_drops_non_alive_and_keeps_ping_only() {
        val list = listOf(
            srv("dead", ping = -1),                 // отсеять
            srv("unmeasured"),                       // отсеять (нет данных)
            srv("ping-only", ping = 90),             // оставить (живой пинг, скорость не мерена)
            srv("fast", ping = 120, speed = 55.0),   // оставить, первым
        )
        val ordered = NetworkMonitor.orderFastCandidates(list).map { it.remarks }
        assertEquals(listOf("fast", "ping-only"), ordered)   // «fast» (скорость>0) раньше «ping-only» (скорость 0)
    }
}
