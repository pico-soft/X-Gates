package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Одно событие журнала монитора. САМОЕ ВАЖНОЕ поле этого этапа — [wouldDo]: что монитор СДЕЛАЛ БЫ,
 * будь переключение включено («переключился бы на X, потому что…»). Именно по нему мы проверяем,
 * правильно ли он думает, НИЧЕГО не ломая (наблюдение без переключений — этап 1).
 */
@Serializable
data class MonitorEvent(
    val ts: Long,            // epoch millis
    val direct: String,      // состояние прямого канала (сигнал A): «жив»/«нет»/«1.8 Мбит/с»
    val tunnel: String,      // состояние туннеля (сигнал B): «OK»/«нет ответа»/«0.3 Мбит/с»/«—»
    val verdict: String,     // вывод: «всё в порядке»/«нет интернета»/«простой»/«падение туннеля»…
    val wouldDo: String = "", // гипотетическое действие (пусто = делать было бы нечего)
)

/**
 * Кольцевой журнал последних [CAP] событий монитора. Persist на диск (monitor-log.json в filesDir,
 * атомарно temp→rename, как остальные хранилища) — переживает перезапуск. Свежие в конце списка;
 * UI показывает в обратном порядке (свежие сверху).
 */
object MonitorLog {
    private const val TAG = "MonitorLog"
    private const val FILE_NAME = "monitor-log.json"
    private const val TMP_NAME = "monitor-log.json.tmp"
    private const val CAP = 200

    @Serializable
    private data class LogFile(val events: List<MonitorEvent> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow<List<MonitorEvent>>(emptyList())
    val state: StateFlow<List<MonitorEvent>> = _state.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun init(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        try {
            _state.value = json.decodeFromString<LogFile>(f.readText()).events.takeLast(CAP)
        } catch (e: Exception) {
            Log.w(TAG, "load failed, keeping empty", e)
        }
    }

    @Synchronized
    fun add(context: Context, event: MonitorEvent) {
        val next = (_state.value + event).takeLast(CAP)   // кольцо: держим последние CAP
        _state.value = next
        val target = file(context)
        val tmp = File(context.filesDir, TMP_NAME)
        try {
            tmp.writeText(json.encodeToString(LogFile(next)))
            if (tmp.renameTo(target)) return
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        _state.value = emptyList()
        runCatching { file(context).delete() }
    }
}
