package com.picosoft.xrayproxydroid.update

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Хранит РЕЗУЛЬТАТ ПОСЛЕДНЕЙ проверки обновления и её время (для показа «результат последней проверки с
 * временем» на вкладке Настройки, переживает перезапуск) + держит в памяти живой [UpdateCheckResult]
 * для действий (кнопка скачивания появляется только для Available). Отдельно — троттлинг авто-проверки
 * при холодном старте (не чаще раза в сутки; это несколько КБ).
 */
object UpdateStore {

    private const val TAG = "UpdateStore"
    private const val FILE = "update_check.json"
    private const val TMP = "update_check.json.tmp"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    @Serializable
    data class Record(
        val checkedAtMs: Long = 0,     // время последней завершённой проверки (0 = не было)
        val summary: String = "",      // человекочитаемый итог
        val updateAvailable: Boolean = false,
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _record = MutableStateFlow(Record())
    val record: StateFlow<Record> = _record.asStateFlow()

    // Живой результат этой сессии — для кнопки скачивания (не персистим: Available с URL/размером теряется
    // при перезапуске, пользователь просто перепроверит; авто-проверка на старте его восстановит).
    private val _live = MutableStateFlow<UpdateCheckResult?>(null)
    val live: StateFlow<UpdateCheckResult?> = _live.asStateFlow()

    fun init(context: Context) {
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return
        try {
            _record.value = json.decodeFromString<Record>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }

    /** Сохранить итог проверки: живой результат (для кнопок) + персистентная запись (для показа с временем). */
    fun apply(context: Context, result: UpdateCheckResult, nowMs: Long) {
        _live.value = result
        val rec = Record(
            checkedAtMs = nowMs,
            summary = summarize(result),
            updateAvailable = result is UpdateCheckResult.Available || result is UpdateCheckResult.AvailableUnverified,
        )
        _record.value = rec
        val target = File(context.filesDir, FILE)
        val tmp = File(context.filesDir, TMP)
        try {
            tmp.writeText(json.encodeToString(rec))
            if (tmp.renameTo(target)) return
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    /** Пора ли авто-проверить при холодном старте (не чаще раза в сутки). */
    fun dueForAutoCheck(nowMs: Long): Boolean {
        val at = _record.value.checkedAtMs
        return at == 0L || nowMs - at >= DAY_MS
    }

    private fun summarize(result: UpdateCheckResult): String = when (result) {
        is UpdateCheckResult.NoReleases -> "Релизов пока нет"
        is UpdateCheckResult.UpToDate -> "У вас последняя версия (${result.latestName})"
        is UpdateCheckResult.Available ->
            "Доступно обновление: ${result.versionName}" + if (result.usingUniversal) " (универсальная сборка)" else ""
        is UpdateCheckResult.AvailableUnverified ->
            "Есть версия новее по метке ${result.tag}, но без update.json — проверить нельзя"
        is UpdateCheckResult.Error -> result.kind.text + (if (result.detail.isNotBlank()) " — ${result.detail}" else "")
    }

    /** Версия ядра xray (вкомпилирована). Может быть недоступна — тогда null. */
    fun coreVersion(): String? = try {
        libv2ray.Libv2ray.checkVersionX()?.takeIf { it.isNotBlank() }
    } catch (e: Throwable) {
        null
    }

    fun appVersion(): String = "${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})"
}
