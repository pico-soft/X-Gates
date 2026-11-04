package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.xray.ServerTester
import org.junit.Assert.assertEquals
import org.junit.Test

/** Сторож батча пинга: UDP-серверы идут очередью из трёх временных ядер, их время входит в потолок. */
class PingBatchBudgetTest {

    @Test fun tcp_only_budget_is_unchanged() {
        assertEquals(15_000L + 46 * 7_000L, ServerTester.batchBudgetMs(total = 361, udp = 0, concurrency = 8, perServerMs = 7_000L))
    }

    @Test fun udp_queue_adds_its_own_waves() {
        assertEquals(15_000L + (2 + 4) * 7_000L, ServerTester.batchBudgetMs(total = 16, udp = 10, concurrency = 8, perServerMs = 7_000L))
    }
}
