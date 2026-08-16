package com.picosoft.xrayproxydroid

import com.picosoft.xrayproxydroid.subscription.ServerRecord
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Факт-проверка склейки серверов МЕЖДУ источниками по serverKey (JVM, без Android).
 * Источник A = {s1,s2,s3}, источник B = {s3,s4,s5} → пересечение по s3.
 * Ожидаем: incoming=3, mergedExisting=1, newInRegistry=2, registryTotal=5 (s3 НЕ задваивается).
 */
class MergeDedupTest {

    private fun p(id: Int) = ServerProfile(
        protocol = Protocol.VLESS,
        remarks = "srv$id",
        address = "10.0.0.$id",
        port = 443,
        credential = "uuid-$id",
    )

    @Test
    fun crossSource_mergesByServerKey_noDuplicates() {
        val s1 = p(1); val s2 = p(2); val s3 = p(3); val s4 = p(4); val s5 = p(5)

        // Реестр после источника A (s1,s2,s3) — по одному источнику "A".
        val regA = listOf(s1, s2, s3).map { ServerRecord(it, listOf("A")) }

        // Вливаем источник B (s3,s4,s5). s3 пересекается.
        val stats = SubscriptionManager.mergeStats(regA, "B", listOf(s3, s4, s5))

        println("MERGE-STATS: до=${regA.size} входящих=${stats.incoming} склейка=${stats.mergedExisting} новых=${stats.newInRegistry} после=${stats.registryTotal}")

        assertEquals("входящих", 3, stats.incoming)
        assertEquals("склейка (пересечение по s3)", 1, stats.mergedExisting)
        assertEquals("новых (s4,s5)", 2, stats.newInRegistry)
        assertEquals("итог реестра (s3 не задвоен)", 5, stats.registryTotal)
    }
}
