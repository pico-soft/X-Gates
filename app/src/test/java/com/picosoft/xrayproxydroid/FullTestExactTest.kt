package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.xray.FullTestRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Этап 3 полного теста: подключённый сервер сравнивается по точному замеру, а не по быстрой оценке. */
class FullTestExactTest {

    @Test fun overestimated_first_server_loses_to_really_faster_one() {
        var connectedSpeed = 50.0                                                       // быстрая оценка первого рабочего
        connectedSpeed = FullTestRunner.exactConnectedSpeed("a", "a", 3.0, connectedSpeed)   // его точный замер
        connectedSpeed = FullTestRunner.exactConnectedSpeed("a", "b", 30.0, connectedSpeed)  // чужой замер число не меняет
        assertEquals(3.0, connectedSpeed, 0.0)
        assertTrue(30.0 > connectedSpeed * 1.1)
    }

    @Test fun failed_exact_measure_of_connected_server_counts_as_zero() {
        assertEquals(0.0, FullTestRunner.exactConnectedSpeed("a", "a", -1.0, 50.0), 0.0)
    }

    @Test fun nothing_connected_yet_changes_nothing() {
        assertEquals(0.0, FullTestRunner.exactConnectedSpeed(null, "a", 30.0, 0.0), 0.0)
    }
}
