package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch

/**
 * Промпт 101.A — ЧИСЛАМИ подтверждаем «один запрос внешнего адреса за раз».
 * 8 потоков зовут fetch() ОДНОВРЕМЕННО, «работа» специально медленная (имитация затянувшегося запроса
 * на мёртвом канале — именно тогда раньше шли наложения). Ожидаем: фактическая работа выполнилась РОВНО
 * один раз (остальные получили её результат, coalesce), параллельность fetchOnce НИКОГДА не превысила 1.
 */
class SingleFlightTest {

    @Test
    fun `concurrent callers collapse to one in-flight request`() {
        ExternalIpChecker.resetTestCounters()
        // Подменяем реальный сетевой запрос медленной «работой».
        ExternalIpChecker.onceOverrideForTest = {
            Thread.sleep(200)
            "1.2.3.4"
        }
        try {
            val n = 8
            val start = CountDownLatch(1)
            val done = CountDownLatch(n)
            val results = java.util.Collections.synchronizedList(mutableListOf<String?>())
            repeat(n) {
                Thread {
                    start.await()
                    results.add(ExternalIpChecker.fetch(socksPort = 10815))
                    done.countDown()
                }.start()
            }
            start.countDown()          // все стартуют одновременно
            done.await()

            // Один запрос за раз: параллельность реальной работы не превысила 1.
            assertEquals("максимум одновременных запросов", 1, ExternalIpChecker.maxConcurrentSeen)
            // Coalesce: фактическая работа выполнилась 1 раз, хотя вызвали 8.
            assertEquals("фактических запросов", 1, ExternalIpChecker.onceCount)
            // Никого не потеряли: все 8 получили результат.
            assertEquals(n, results.size)
            assertTrue("все получили результат идущего запроса", results.all { it == "1.2.3.4" })
        } finally {
            ExternalIpChecker.onceOverrideForTest = null
            ExternalIpChecker.resetTestCounters()
        }
    }
}
