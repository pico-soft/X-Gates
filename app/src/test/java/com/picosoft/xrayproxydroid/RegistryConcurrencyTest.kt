package com.picosoft.xrayproxydroid

import android.content.ContextWrapper
import com.picosoft.xrayproxydroid.subscription.ServerRecord
import com.picosoft.xrayproxydroid.subscription.SourcesFile
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.subscription.SubscriptionStore
import com.picosoft.xrayproxydroid.xray.link.Protocol
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/** Замеры пишутся из нескольких потоков: между чтением и записью реестра чужой результат терялся. Перенос 556d96b из X-Gates-Win. */
class RegistryConcurrencyTest {

    private val dir: File = Files.createTempDirectory("xgates-registry").toFile()
    private val ctx = object : ContextWrapper(null) { override fun getFilesDir(): File = dir }
    private val servers = (1..60).map { ServerProfile(Protocol.VLESS, "n$it", "h$it.example.com", 443, "u") }
    private val keys = servers.map(SubscriptionManager::serverKey)

    @After fun tearDown() { dir.deleteRecursively() }

    private fun inParallel(write: (String) -> Unit) {
        SubscriptionStore.save(ctx, SourcesFile(servers = servers.map { ServerRecord(it, listOf("s")) }))
        val go = CountDownLatch(1)
        keys.map { k -> thread { go.await(); write(k) } }.also { go.countDown() }.forEach { it.join() }
    }

    private fun stored() = SubscriptionStore.load(ctx).servers.map { it.profile }

    @Test fun parallel_pings_all_survive() {
        inParallel { SubscriptionManager.applyPingResults(ctx, mapOf(it to 40)) }
        assertEquals(60, stored().count { it.pingMs == 40 })
    }

    @Test fun parallel_speeds_all_survive() {
        inParallel { SubscriptionManager.applySpeedResults(ctx, mapOf(it to 5.0)) }
        assertEquals(60, stored().count { it.speedMbps == 5.0 })
    }

    @Test fun parallel_tagged_speeds_all_survive() {
        inParallel { SubscriptionManager.applySpeedResultsTagged(ctx, mapOf(it to (5.0 to true))) }
        assertEquals(60, stored().count { it.speedMbps == 5.0 && it.speedFull == true })
    }
}
