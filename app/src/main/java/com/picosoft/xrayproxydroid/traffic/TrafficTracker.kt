package com.picosoft.xrayproxydroid.traffic

import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** Дневная корзина: раздельно ПРИЁМ/ОТДАЧА туннеля и ТЕСТОВЫЙ трафик (пробники фазы скорости). */
@Serializable
data class DayBucket(
    val tunnelRx: Long = 0,   // принято через активный прокси
    val tunnelTx: Long = 0,   // отдано через активный прокси
    val test: Long = 0,       // скачано пробниками замера скорости
) {
    fun total(): Long = tunnelRx + tunnelTx + test
    fun isEmpty(): Boolean = tunnelRx == 0L && tunnelTx == 0L && test == 0L
}

/**
 * @param days      история по дням: сумма = «за 30 дней» + список по дням. Сбрасывается ТОЛЬКО «За 30 дней».
 * @param todayDate дата НЕЗАВИСИМОГО суточного счётчика
 * @param today     «За сутки» — ОТДЕЛЬНЫЙ счётчик (не производный от days), сбрасывается ТОЛЬКО «За сутки».
 * Три счётчика независимы: сброс одного не трогает другие (требование Elyor).
 */
@Serializable
data class TrafficFile(
    val days: Map<String, DayBucket> = emptyMap(),
    val todayDate: String = "",
    val today: DayBucket = DayBucket(),
)

/** Снимок для UI: сессия «с последнего запуска» + сегодня + сумма 30 дней + список по дням. */
data class TrafficSnapshot(
    val sessionStartElapsed: Long = 0,   // точка отсчёта СЕССИИ (запуск приложения / последний сброс) — И байты, И таймер
    val sessionRx: Long = 0,
    val sessionTx: Long = 0,
    val sessionTest: Long = 0,
    // ОТДЕЛЬНО «Прокси активен» — время работы туннеля, не путать с временем сессии приложения.
    val proxyActive: Boolean = false,
    val proxyStartElapsed: Long = 0,
    val proxyEndElapsed: Long = 0,
    val today: DayBucket = DayBucket(),
    val total30: DayBucket = DayBucket(),
    val days: List<Pair<String, DayBucket>> = emptyList(),  // свежие сверху
)

/** Персистенция дневных корзин (traffic.json в filesDir). */
private object TrafficStore {
    private const val TAG = "TrafficStore"
    private const val FILE = "traffic.json"
    private const val TMP = "traffic.json.tmp"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun load(context: Context): TrafficFile {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return TrafficFile()
        return try { json.decodeFromString<TrafficFile>(f.readText()) }
        catch (e: Exception) { Log.w(TAG, "load failed", e); TrafficFile() }
    }

    fun save(context: Context, data: TrafficFile) {
        val target = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, TMP)
        try {
            tmp.writeText(json.encodeToString(data))
            if (tmp.renameTo(target)) return
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Exception) { Log.w(TAG, "save failed", e) }
    }
}

/**
 * Учёт трафика ДВУМЯ раздельными потоками: ТУННЕЛЬНЫЙ (через активный прокси, из core queryStats)
 * и ТЕСТОВЫЙ (пробники фазы скорости, из всего_байт). Никогда не смешиваются. Дневные корзины
 * (локальная полночь), хранение 30 дней. Сессия «с последнего запуска» живёт в памяти (переживает
 * сворачивание, не переживает перезапуск сервиса/процесса). На диск — раз в ~20с и при остановке.
 */
object TrafficTracker {

    private const val RETAIN_DAYS = 30
    private const val PERSIST_INTERVAL_MS = 20_000L

    private val lock = Any()
    private var days = LinkedHashMap<String, DayBucket>()   // история (30д + список), НЕЗАВИСИМА от суточного
    private var todayDate = ""                              // дата независимого суточного счётчика
    private var todayRx = 0L; private var todayTx = 0L; private var todayTest = 0L
    // Сессия «с последнего запуска»: отсчёт с запуска приложения / последнего сброса. Пополняется БЕЗУСЛОВНО.
    private var sessionStartElapsed = 0L
    private var sessionRx = 0L
    private var sessionTx = 0L
    private var sessionTest = 0L
    // «Прокси активен» — отдельная величина (время работы туннеля).
    private var proxyActive = false
    private var proxyStartElapsed = 0L
    private var proxyEndElapsed = 0L
    private var lastPersistElapsed = 0L
    private var appContext: Context? = null

    private val _state = MutableStateFlow(TrafficSnapshot())
    val state: StateFlow<TrafficSnapshot> = _state.asStateFlow()

    private fun loadFrom(file: TrafficFile) {
        days = LinkedHashMap(file.days)
        todayDate = file.todayDate
        todayRx = file.today.tunnelRx; todayTx = file.today.tunnelTx; todayTest = file.today.test
    }

    private fun ensureSessionStarted() {
        if (sessionStartElapsed == 0L) sessionStartElapsed = SystemClock.elapsedRealtime()
    }

    /** Загрузить историю + суточный счётчик, отсечь старше 30 дней. Вызвать при старте приложения. */
    fun init(context: Context) = synchronized(lock) {
        appContext = context.applicationContext
        loadFrom(TrafficStore.load(appContext!!))
        ensureSessionStarted()   // отсчёт сессии = запуск приложения
        prune()
        publish()
    }

    /** Гарантировать контекст (если сервис стартовал раньше Activity). Не перетирает уже загруженное. */
    fun attachContext(context: Context) = synchronized(lock) {
        if (appContext == null) {
            appContext = context.applicationContext
            loadFrom(TrafficStore.load(appContext!!))
            prune()
        }
        ensureSessionStarted()
        publish()
    }

    /** Прокси поднялся — только отметка «активен» (сессию НЕ трогаем: она считается безусловно). */
    fun onServiceStart() = synchronized(lock) {
        proxyActive = true
        proxyStartElapsed = SystemClock.elapsedRealtime()
        proxyEndElapsed = 0
        lastPersistElapsed = SystemClock.elapsedRealtime()
        publish()
    }

    /** Прокси остановлен — зафиксировать его аптайм, сохранить. Сессию НЕ трогаем. */
    fun onServiceStop() = synchronized(lock) {
        proxyActive = false
        proxyEndElapsed = SystemClock.elapsedRealtime()
        persist()
        publish()
    }

    /** Сброс суточного счётчика при смене локальной даты (иначе за сутки покажет вчерашнее). */
    private fun rollToday() {
        val k = today()
        if (todayDate != k) { todayDate = k; todayRx = 0; todayTx = 0; todayTest = 0 }
    }

    /** Дельты туннеля из core queryStats (приём/отдача). Обновляют ТРИ счётчика БЕЗУСЛОВНО из ОДНОЙ дельты. */
    fun addTunnel(rx: Long, tx: Long) = synchronized(lock) {
        val r = rx.coerceAtLeast(0); val t = tx.coerceAtLeast(0)
        if (r > 0 || t > 0) {
            ensureSessionStarted()
            val k = today()
            val b = days[k] ?: DayBucket()
            days[k] = b.copy(tunnelRx = b.tunnelRx + r, tunnelTx = b.tunnelTx + t)  // история/30д
            rollToday(); todayRx += r; todayTx += t                                 // «за сутки»
            sessionRx += r; sessionTx += t                                          // сессия — БЕЗУСЛОВНО
            maybePersist()
            publish()
        }
    }

    /** Тестовый трафик (всего_байт пробника). Обновляет ТРИ счётчика БЕЗУСЛОВНО (полный тест идёт без сервиса!). */
    fun addTest(bytes: Long) = synchronized(lock) {
        if (bytes > 0) {
            ensureSessionStarted()
            val k = today()
            val b = days[k] ?: DayBucket()
            days[k] = b.copy(test = b.test + bytes)      // история/30д
            rollToday(); todayTest += bytes              // «за сутки»
            sessionTest += bytes                          // сессия — БЕЗУСЛОВНО
            maybePersist()
            publish()
        }
    }

    // Три кнопки — три НЕЗАВИСИМЫХ счётчика: сброс одного не трогает другие.

    /** «С последнего запуска»: обнулить ТОЛЬКО счётчики сессии и её таймер (общая точка отсчёта). */
    fun resetSession() = synchronized(lock) {
        sessionRx = 0; sessionTx = 0; sessionTest = 0
        sessionStartElapsed = SystemClock.elapsedRealtime()   // байты И таймер — от одной точки
        publish()
    }

    /** «За сутки»: обнулить ТОЛЬКО суточный счётчик. Историю (30д/список) и сессию НЕ трогаем. Диск немедленно. */
    fun resetToday() = synchronized(lock) {
        todayDate = today(); todayRx = 0; todayTx = 0; todayTest = 0
        persist()
        publish()
    }

    /** «За 30 дней»: стереть ТОЛЬКО историю (все корзины + список). Суточный счётчик и сессию НЕ трогаем. Диск немедленно. */
    fun resetAll() = synchronized(lock) {
        days.clear()
        persist()
        publish()
    }

    // --- внутреннее ---

    private fun maybePersist() {
        if (SystemClock.elapsedRealtime() - lastPersistElapsed >= PERSIST_INTERVAL_MS) persist()
    }

    private fun persist() {
        val ctx = appContext ?: return
        lastPersistElapsed = SystemClock.elapsedRealtime()
        prune()
        TrafficStore.save(ctx, TrafficFile(
            days = HashMap(days),
            todayDate = todayDate,
            today = DayBucket(todayRx, todayTx, todayTest),
        ))
    }

    private fun prune() {
        val cutoff = dateKeyDaysAgo(RETAIN_DAYS - 1)   // храним последние 30 дней включая сегодня
        days.keys.filter { it < cutoff }.forEach { days.remove(it) }
    }

    private fun publish() {
        // «За сутки» = независимый суточный счётчик (0, если его дата — не сегодня, т.е. новый день без трафика).
        val today = if (todayDate == today()) DayBucket(todayRx, todayTx, todayTest) else DayBucket()
        // «За 30 дней» = сумма истории days.
        var rx = 0L; var tx = 0L; var test = 0L
        for (b in days.values) { rx += b.tunnelRx; tx += b.tunnelTx; test += b.test }
        val list = days.entries.sortedByDescending { it.key }.map { it.key to it.value }
        _state.value = TrafficSnapshot(
            sessionStartElapsed = sessionStartElapsed,
            sessionRx = sessionRx, sessionTx = sessionTx, sessionTest = sessionTest,
            proxyActive = proxyActive, proxyStartElapsed = proxyStartElapsed, proxyEndElapsed = proxyEndElapsed,
            today = today, total30 = DayBucket(rx, tx, test), days = list,
        )
    }

    private fun today(): String = fmt.format(Date())

    private fun dateKeyDaysAgo(n: Int): String {
        val c = Calendar.getInstance()
        c.add(Calendar.DAY_OF_YEAR, -n)
        return fmt.format(c.time)
    }

    private val fmt get() = SimpleDateFormat("yyyy-MM-dd", Locale.US)   // локальная таймзона
}
