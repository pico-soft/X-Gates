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
)

@Serializable
data class TrafficFile(val days: Map<String, DayBucket> = emptyMap())

/** Снимок для UI: сессия «с последнего запуска» + сегодня + сумма 30 дней + список по дням. */
data class TrafficSnapshot(
    val sessionActive: Boolean = false,
    val sessionStartElapsed: Long = 0,   // база SystemClock.elapsedRealtime для аптайма
    val sessionEndElapsed: Long = 0,     // фиксация аптайма после остановки
    val sessionRx: Long = 0,
    val sessionTx: Long = 0,
    val sessionTest: Long = 0,
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
    private var days = LinkedHashMap<String, DayBucket>()
    private var sessionActive = false
    private var sessionStartElapsed = 0L
    private var sessionEndElapsed = 0L
    private var sessionRx = 0L
    private var sessionTx = 0L
    private var sessionTest = 0L
    private var lastPersistElapsed = 0L
    private var appContext: Context? = null

    private val _state = MutableStateFlow(TrafficSnapshot())
    val state: StateFlow<TrafficSnapshot> = _state.asStateFlow()

    /** Загрузить корзины, отсечь старше 30 дней. Вызвать при старте приложения. */
    fun init(context: Context) = synchronized(lock) {
        appContext = context.applicationContext
        days = LinkedHashMap(TrafficStore.load(appContext!!).days)
        prune()
        publish()
    }

    /** Гарантировать контекст (если сервис стартовал раньше Activity). Не перетирает уже загруженное. */
    fun attachContext(context: Context) = synchronized(lock) {
        if (appContext == null) {
            appContext = context.applicationContext
            days = LinkedHashMap(TrafficStore.load(appContext!!).days)
            prune()
            publish()
        }
    }

    /** Сервис поднялся: новая сессия. */
    fun onServiceStart() = synchronized(lock) {
        sessionActive = true
        sessionStartElapsed = SystemClock.elapsedRealtime()
        sessionEndElapsed = 0
        sessionRx = 0; sessionTx = 0; sessionTest = 0
        lastPersistElapsed = SystemClock.elapsedRealtime()
        publish()
    }

    /** Сервис остановлен: зафиксировать аптайм, сохранить. Счётчики сессии остаются видны до нового старта. */
    fun onServiceStop() = synchronized(lock) {
        sessionActive = false
        sessionEndElapsed = SystemClock.elapsedRealtime()
        persist()
        publish()
    }

    /** Дельты туннеля из core queryStats (приём/отдача). Только положительные. */
    fun addTunnel(rx: Long, tx: Long) = synchronized(lock) {
        val r = rx.coerceAtLeast(0); val t = tx.coerceAtLeast(0)
        if (r > 0 || t > 0) {
            val k = today()
            val b = days[k] ?: DayBucket()
            days[k] = b.copy(tunnelRx = b.tunnelRx + r, tunnelTx = b.tunnelTx + t)
            if (sessionActive) { sessionRx += r; sessionTx += t }
            maybePersist()
            publish()
        }
    }

    /** Тестовый трафик (всего_байт пробника). Считается всегда; в сессию — только пока сервис активен. */
    fun addTest(bytes: Long) = synchronized(lock) {
        if (bytes > 0) {
            val k = today()
            val b = days[k] ?: DayBucket()
            days[k] = b.copy(test = b.test + bytes)
            if (sessionActive) sessionTest += bytes
            maybePersist()
            publish()
        }
    }

    /** Кнопка «Сбросить» на вкладке: обнулить счётчики сессии (дневные корзины НЕ трогаем). */
    fun resetSession() = synchronized(lock) {
        sessionRx = 0; sessionTx = 0; sessionTest = 0
        sessionStartElapsed = SystemClock.elapsedRealtime()
        sessionEndElapsed = if (sessionActive) 0 else sessionStartElapsed
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
        TrafficStore.save(ctx, TrafficFile(days = HashMap(days)))
    }

    private fun prune() {
        val cutoff = dateKeyDaysAgo(RETAIN_DAYS - 1)   // храним последние 30 дней включая сегодня
        days.keys.filter { it < cutoff }.forEach { days.remove(it) }
    }

    private fun publish() {
        val today = days[today()] ?: DayBucket()
        var rx = 0L; var tx = 0L; var test = 0L
        for (b in days.values) { rx += b.tunnelRx; tx += b.tunnelTx; test += b.test }
        val list = days.entries.sortedByDescending { it.key }.map { it.key to it.value }
        _state.value = TrafficSnapshot(
            sessionActive = sessionActive,
            sessionStartElapsed = sessionStartElapsed,
            sessionEndElapsed = sessionEndElapsed,
            sessionRx = sessionRx, sessionTx = sessionTx, sessionTest = sessionTest,
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
