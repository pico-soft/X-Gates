package com.picosoft.xrayproxydroid

import android.content.ContextWrapper
import com.picosoft.xrayproxydroid.monitor.NetworkMonitor
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/** Фоновые замеры идут внутри цикла монитора: молчит активный — бросаем их, иначе обрыв минутами никто не заметит. */
class BackgroundYieldTest {

    private val ctx = ContextWrapper(null)

    @After fun tearDown() { ProxyState.update(running = false, label = null, serverKey = null, message = "") ; NetworkMonitor.resetBackgroundYield() }

    private fun profile(port: Int) = ServerProfile(Protocol.VLESS, "n", "127.0.0.1", port, "u")

    @Test fun answering_active_server_does_not_stop_background_work() {
        ProxyState.update(running = true, label = "n", serverKey = "k", message = "")
        ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { s ->
            assertFalse(NetworkMonitor.backgroundMustYield(ctx, profile(s.localPort)))
        }
    }

    @Test fun silent_active_server_stops_background_work_and_flag_holds_until_cycle_reads_it() {
        ProxyState.update(running = true, label = "n", serverKey = "k", message = "")
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }   // порт уже закрыт
        assertTrue(NetworkMonitor.backgroundMustYield(ctx, profile(port)))
        // Следующий сервер в том же рейтинге: без новых пингов, флаг уже стоит.
        assertTrue(NetworkMonitor.backgroundMustYield(ctx, null))
    }

    @Test fun udp_active_server_is_judged_through_tunnel_not_by_tcp() {
        // Порт закрыт, и у живого UDP-сервера так всегда: TCP-пинг дал бы -1 и рабочий туннель считался бы оборванным.
        val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        val udp = profile(port).copy(network = "kcp")
        assertEquals(42, NetworkMonitor.activePing(ctx, udp, 1000) { 42 })
        assertEquals(-1, NetworkMonitor.activePing(ctx, udp.copy(network = "mkcp"), 1000) { -1 })
        assertEquals("tcp идёт обычным пингом", -1, NetworkMonitor.activePing(ctx, profile(port), 1000) { 42 })
    }

    @Test fun stopped_proxy_stops_background_work() {
        assertTrue(NetworkMonitor.backgroundMustYield(ctx, null))
    }
}
