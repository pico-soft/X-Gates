package com.picosoft.xrayproxydroid.xray

import android.content.Context
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
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
     *  ждём результат с Future.get(timeout); при просрочке — сервер считаем мёртвым.
     *
     *  ⚠️ ГРАБЛЯ (полевой случай, Huawei CTR-L21, мёртвый пул): future.cancel(true) НЕ останавливает уже идущий
     *  нативный measureOutboundDelay — он держит поток, пока не погаснет по СВОЕМУ внутреннему таймауту (дольше
     *  нашего мягкого). На ПОЛНОСТЬЮ мёртвом пуле КАЖДЫЙ пинг упирается в таймаут → безлимитный cachedThreadPool
     *  за ~20с порождал десятки залипших потоков → OutOfMemoryError при создании следующего потока → батч клинил
     *  («Этап 1: пинг 33/140» намертво; Стоп не спасал; фоновый refreshAllPings так же зависал). ФИКС: пул
     *  ОГРАНИЧЕН [MAX_CONCURRENT_MEASURES]. При насыщении submit() бросает RejectedExecutionException → пинг
     *  вернёт -1 (сервер мёртв), число потоков не взрывается. Залипшие потоки сами гаснут (нативный таймаут) и
     *  освобождаются — пул самовосстанавливается. corePoolSize=0 + keepAlive 30с: в простое потоков нет. */
    private const val MAX_CONCURRENT_MEASURES = 24
    private val jniPool: ExecutorService = ThreadPoolExecutor(
        0, MAX_CONCURRENT_MEASURES,
        30L, TimeUnit.SECONDS,
        SynchronousQueue(),
    )

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
        // submit() ВНУТРИ try: на насыщенном/исчерпанном пуле он бросает RejectedExecutionException (Exception)
        // или OutOfMemoryError (Error) — ловим ОБА через Throwable и считаем сервер мёртвым, а НЕ роняем задачу
        // пинга (иначе прогресс батча залипал — см. testAll).
        return try {
            val future = jniPool.submit(Callable { XrayController.measureOutboundDelay(context, cfg, GSTATIC_204) })
            try {
                future.get(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            } catch (e: TimeoutException) {
                future.cancel(true)   // прерываем ОЖИДАНИЕ; нативный вызов сам погаснет по своему таймауту
                -1L
            }
        } catch (t: Throwable) {
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
                var ms = -1L
                try {
                    ms = ping(appCtx, p)   // ping() уже не бросает (Throwable → -1), но подстрахуемся
                } catch (t: Throwable) {
                    android.util.Log.w("ServerTester", "задача пинга упала (проглочено): ${t.message}")
                } finally {
                    // ПРОГРЕСС ДВИГАЕМ ВСЕГДА (в finally): даже если пинг упал/пул отказал — счётчик не залипает,
                    // батч доходит до конца и onFinish срабатывает, «Стоп» действует. Полевой баг «пинг 33/140
                    // навсегда» был именно из-за пропуска onProgress на исключении.
                    if (!cancelled.get()) {
                        runCatching { onResult(p, ms) }
                        onProgress(done.incrementAndGet(), total)
                    }
                }
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
