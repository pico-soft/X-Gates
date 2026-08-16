package com.picosoft.xrayproxydroid.xray

import android.content.Context
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Real-ping тесты серверов из списка. Каждый замер — независимый временный инстанс ядра
 * (measureOutboundDelay), не трогает активный прокси. Всё в фоне.
 */
object ServerTester {

    const val GSTATIC_204 = "https://www.gstatic.com/generate_204"

    /** Пул для МЯГКОГО таймаута: libv2ray.measureOutboundDelay сам таймаут не принимает, поэтому
     *  ждём результат с Future.get(timeout); при просрочке — сервер считаем мёртвым. */
    private val jniPool = Executors.newCachedThreadPool()

    /**
     * Один замер: profile → config → real ping. Возвращает мс (≥0) или -1 (мёртвый/таймаут/ошибка).
     * [timeoutMs] — мягкий верхний предел (из настроек); брошенный JNI-замер сам погасит temp-инстанс.
     */
    fun ping(context: Context, profile: ServerProfile, timeoutMs: Int = SettingsStore.current().pingTimeoutMs): Long {
        val cfg = try {
            XrayConfigBuilder.build(profile)
        } catch (e: Exception) {
            return -1L   // неподдерживаемый транспорт и т.п.
        }
        val future = jniPool.submit(Callable { XrayController.measureOutboundDelay(context, cfg, GSTATIC_204) })
        return try {
            future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            future.cancel(true)
            -1L
        } catch (e: Exception) {
            -1L
        }
    }

    /** Управление запущенным батчем. */
    interface TestHandle {
        fun cancel()
    }

    /**
     * Батч: ограниченный пул [concurrency] одновременно; на каждый готовый результат СРАЗУ
     * [onResult] (прогрессивно, не ждём всех) + [onProgress]. По завершении всех — [onFinish].
     *
     * Колбэки вызываются на фоновых потоках пула — маршалинг в UI на вызывающем.
     * Отмена ([TestHandle.cancel]) прерывает очередь; недотестированные остаются как есть,
     * onFinish при отмене НЕ вызывается.
     */
    fun testAll(
        context: Context,
        servers: List<ServerProfile>,
        concurrency: Int = SettingsStore.current().pingPool,
        onResult: (ServerProfile, Long) -> Unit,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinish: () -> Unit = {},
    ): TestHandle {
        val appCtx = context.applicationContext
        val total = servers.size
        val done = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        val pool = Executors.newFixedThreadPool(concurrency.coerceAtLeast(1))

        for (p in servers) {
            pool.execute {
                if (cancelled.get()) return@execute
                val ms = ping(appCtx, p)
                if (cancelled.get()) return@execute
                onResult(p, ms)
                onProgress(done.incrementAndGet(), total)
            }
        }
        pool.shutdown() // новых не принимаем; уже поданные выполняются

        // Ждём завершения в отдельном потоке, чтобы не блокировать вызывающего.
        Thread {
            pool.awaitTermination(Long.MAX_VALUE, TimeUnit.MILLISECONDS)
            if (!cancelled.get()) onFinish()
        }.start()

        return object : TestHandle {
            override fun cancel() {
                cancelled.set(true)
                pool.shutdownNow() // снять очередь; текущие JNI-замеры доработают до внутреннего таймаута
            }
        }
    }
}
