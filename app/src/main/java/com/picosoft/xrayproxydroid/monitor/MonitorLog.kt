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
 * Одна запись журнала — ТОЛЬКО СОБЫТИЕ (смена состояния или происшествие), не рутина.
 * [kind]: "switch" (смена активного сервера любой причины), "monitor" (падение/восстановление/вердикт),
 * "net" (пропал/появился интернет), "error" (ошибка монитора). [text] — заголовок, [detail] — числа/причина.
 */
@Serializable
data class LogEvent(
    val ts: Long,
    val kind: String,
    val text: String,
    val detail: String = "",
)

/**
 * Кольцевой журнал последних [CAP] СОБЫТИЙ. Persist (monitor-log.json, атомарно) — переживает перезапуск.
 * Рутинные подтверждения нормы («ок»/«простой») сюда НЕ пишутся вообще (см. NetworkMonitor + признак
 * жизни MonitorStatus). Свежие в конце; UI показывает в обратном порядке.
 */
object MonitorLog {
    private const val TAG = "MonitorLog"
    private const val FILE_NAME = "monitor-log.json"
    private const val TMP_NAME = "monitor-log.json.tmp"
    private const val CAP = 200

    @Serializable
    private data class LogFile(val events: List<LogEvent> = emptyList())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow<List<LogEvent>>(emptyList())
    val state: StateFlow<List<LogEvent>> = _state.asStateFlow()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun init(context: Context) {
        val f = file(context)
        if (!f.exists()) return
        try {
            _state.value = json.decodeFromString<LogFile>(f.readText()).events.takeLast(CAP)
        } catch (e: Exception) {
            // Старый формат журнала (до Промпта 48) не парсится в LogEvent — просто начинаем пустым.
            Log.w(TAG, "load failed, keeping empty", e)
        }
    }

    @Synchronized
    fun add(context: Context, event: LogEvent) {
        val next = (_state.value + event).takeLast(CAP)
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

    /** Событие произвольного вида (время ставит сам). */
    fun event(context: Context, kind: String, text: String, detail: String = "") =
        add(context, LogEvent(System.currentTimeMillis(), kind, text, detail))

    /** Смена активного сервера — фиксируется НЕЗАВИСИМО от причины (старт/тест/ручной выбор/монитор). */
    fun switch(context: Context, from: String?, to: String, cause: String, detail: String = "") {
        val d = if (detail.isEmpty()) cause else "$cause · $detail"
        add(context, LogEvent(System.currentTimeMillis(), "switch", "${from ?: "—"} → $to", d))
    }

    @Synchronized
    fun clear(context: Context) {
        _state.value = emptyList()
        runCatching { file(context).delete() }
    }
}
