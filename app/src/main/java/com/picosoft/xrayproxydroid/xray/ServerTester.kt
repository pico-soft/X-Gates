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
import java.util.concurrent.atomic.AtomicLong

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
        // submit() ВНУТРИ try: на насыщенном/исчерпанном пуле он бросает RejectedExecutionException (Exception)
        // или OutOfMemoryError (Error) — ловим ОБА через Throwable и считаем сервер мёртвым, а НЕ роняем задачу
        // пинга (иначе прогресс батча залипал — см. testAll).
        //
        // ⚠️ ГРАБЛЯ (полевой баг Fold SM-F936N, 0.43, «пинг 25/36» намертво): и XrayConfigBuilder.build(), и native
        // measureOutboundDelay идут ЦЕЛИКОМ внутри Callable → под мягким таймаутом future.get(timeoutMs). Раньше
        // build() стоял ДО future (вне таймаута): если на каком-то профиле билдер/натив держал поток дольше 5с,
        // воркер пула testAll не освобождался → awaitTermination(MAX_VALUE) ждал его вечно → батч виснул. Теперь
        // ЕДИНСТВЕННАЯ точка ожидания воркера — future.get(timeoutMs), гарантированно ≤ timeoutMs.
        return try {
            val future = jniPool.submit(Callable {
                val cfg = XrayConfigBuilder.build(profile)   // неподдерж. транспорт → бросит → ExecutionException → -1
                XrayController.measureOutboundDelay(context, cfg, GSTATIC_204)
            })
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
        val conc = concurrency.coerceAtLeast(1)
        val done = AtomicInteger(0)
        val cancelled = AtomicBoolean(false)
        // Терминальное состояние батча: onFinish РОВНО один раз (штатно ИЛИ по сторожу), после — воркеры немы.
        val finished = AtomicBoolean(false)
        val lastProgressMs = AtomicLong(System.currentTimeMillis())
        val pool = Executors.newFixedThreadPool(conc)

        for (p in servers) {
            pool.execute {
                if (cancelled.get() || finished.get()) return@execute
                var ms = -1L
                try {
                    ms = ping(appCtx, p)   // ping() уже не бросает (Throwable → -1), но подстрахуемся
                } catch (t: Throwable) {
                    android.util.Log.w("ServerTester", "задача пинга упала (проглочено): ${t.message}")
                } finally {
                    // ПРОГРЕСС ДВИГАЕМ ВСЕГДА (в finally): даже если пинг упал/пул отказал — счётчик не залипает,
                    // батч доходит до конца и onFinish срабатывает, «Стоп» действует. Полевой баг «пинг 33/140
                    // навсегда» был именно из-за пропуска onProgress на исключении. Гейт по finished — чтобы
                    // «опоздавший» воркер (после срабатывания сторожа) не двигал бар уже во время Этапа 2.
                    if (!cancelled.get() && !finished.get()) {
                        runCatching { onResult(p, ms) }
                        lastProgressMs.set(System.currentTimeMillis())
                        onProgress(done.incrementAndGet(), total)
                    }
                }
            }
        }
        pool.shutdown() // новых не принимаем; уже поданные выполняются

        fun finishOnce() {
            if (finished.compareAndSet(false, true) && !cancelled.get()) onFinish()
        }

        // ⏱️ ЖЁСТКИЙ СТОРОЖ ЗАВЕРШЕНИЯ (полевой баг Fold SM-F936N, 0.43, «пинг 25/36» намертво): у Этапа 1 своего
        // сторожа зависания НЕ было (в отличие от Этапа 2/экономии), а awaitTermination(MAX_VALUE) ждал зомби-поток
        // ВЕЧНО. Гарантия: батч ЗАВЕРШАЕТСЯ по застою (нет ни одного нового результата за stall) ИЛИ по общему
        // бюджету — недотестированные считаем мёртвыми, onFinish идёт с тем, что успели напинговать. Зомби-натив
        // сам гаснет по keepAlive jniPool. Теперь Этап 1 НЕ МОЖЕТ зависнуть ни на каком устройстве.
        val perServerMs = (SettingsStore.current().pingTimeoutMs + 2_000).toLong()
        val waves = ((total + conc - 1) / conc).coerceAtLeast(1)
        val hardBudgetMs = 15_000L + waves * perServerMs         // общий потолок на весь батч
        val stallLimitMs = (perServerMs * 3).coerceAtLeast(20_000L)  // нет НИ ОДНОГО нового результата столько → бросаем
        Thread {
            val startMs = System.currentTimeMillis()
            while (true) {
                if (pool.awaitTermination(2_000, TimeUnit.MILLISECONDS)) { finishOnce(); break }  // все воркеры отработали штатно
                if (cancelled.get() || finished.get()) break
                val nowMs = System.currentTimeMillis()
                val overBudget = nowMs - startMs > hardBudgetMs
                val stalled = nowMs - lastProgressMs.get() > stallLimitMs
                if (overBudget || stalled) {
                    android.util.Log.w("ServerTester",
                        "батч пинга завершён СТОРОЖЕМ (${if (overBudget) "бюджет" else "застой"}): ${done.get()}/$total")
                    pool.shutdownNow()   // снять недоданные; зомби-натив сам погаснет (jniPool keepAlive 30с)
                    finishOnce()
                    break
                }
            }
        }.start()

        return object : TestHandle {
            override fun cancel() {
                cancelled.set(true)
                finished.set(true)  // штатный onFinish уже не нужен — отмена
                pool.shutdownNow() // снять очередь; текущие JNI-замеры доработают до внутреннего таймаута
            }
        }
    }
}
