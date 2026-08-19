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
        val details: String = "",      // полная постадийная диагностика (77.E) — под «Подробности» в UI
        // Промпт 93.J/K/L: доступная версия ПЕРСИСТИТСЯ (полоса переживает перезапуск/возврат из «недавних»),
        // + состояние «уже показали»/«отклонено» (одно уведомление на версию, снятие в двух местах).
        val availCode: Int = 0,        // versionCode доступного обновления (0 = нет)
        val availName: String = "",
        val availNotes: String = "",
        val dismissedCode: Int = 0,    // версия, которую пользователь отклонил (полоса+уведомление сняты)
        val notifiedCode: Int = 0,     // версия, о которой уже показали уведомление (одно на версию)
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

    /** Сохранить итог проверки: живой результат (для кнопок) + персистентная запись (сводка+время+подробности). */
    fun apply(context: Context, report: CheckReport, nowMs: Long) {
        val result = report.result
        _live.value = result
        val prev = _record.value
        val avail = result as? UpdateCheckResult.Available
        val rec = prev.copy(
            checkedAtMs = nowMs,
            summary = summarize(result),
            updateAvailable = result is UpdateCheckResult.Available || result is UpdateCheckResult.AvailableUnverified,
            details = report.details,
            // Доступную версию сохраняем только для проверяемого Available (с update.json); иначе полоса не нужна.
            availCode = avail?.versionCode ?: 0,
            availName = avail?.versionName ?: "",
            availNotes = avail?.notes ?: "",
            // dismissedCode/notifiedCode НЕ сбрасываем — переживают проверки (одно уведомление на версию).
        )
        save(context, rec)
    }

    private fun save(context: Context, rec: Record) {
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

    // ── Промпт 93.J/K/L: полоса и уведомление о новой версии ──
    private fun currentCode() = BuildConfig.VERSION_CODE

    /** Инфо для ПОЛОСЫ: доступная версия НОВЕЕ текущей, не отклонена, настройка вкл. Иначе null. Переживает
     *  перезапуск (из персистентной записи) — полоса видна и при возврате из «недавних» (L). */
    fun bannerVersion(notifyOn: Boolean): Pair<Int, String>? {
        val r = _record.value
        return if (notifyOn && r.availCode > currentCode() && r.availCode != r.dismissedCode) r.availCode to r.availName else null
    }

    fun availNotesFirstLine(): String = _record.value.availNotes.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    /** Показать ли УВЕДОМЛЕНИЕ: новее + не отклонено + ещё НЕ показывали (одно на версию) + настройка вкл. */
    fun shouldNotify(notifyOn: Boolean): Boolean {
        val r = _record.value
        return notifyOn && r.availCode > currentCode() && r.availCode != r.dismissedCode && r.availCode != r.notifiedCode
    }

    /** Уведомление показано — больше про эту версию не напоминаем (даже после перезапусков). */
    fun markNotified(context: Context) { val r = _record.value; if (r.availCode != 0) save(context, r.copy(notifiedCode = r.availCode)) }

    /** Пользователь отклонил (крестик полосы / установка) — снять полосу И уведомление для ЭТОЙ версии. */
    fun markDismissed(context: Context) { val r = _record.value; if (r.availCode != 0) save(context, r.copy(dismissedCode = r.availCode)) }

    /** Пора ли авто-проверить при холодном старте (не чаще раза в сутки). */
    fun dueForAutoCheck(nowMs: Long): Boolean {
        val at = _record.value.checkedAtMs
        return at == 0L || nowMs - at >= DAY_MS
    }

    private fun summarize(result: UpdateCheckResult): String = when (result) {
        is UpdateCheckResult.NoReleases -> "Релизов пока нет"
        is UpdateCheckResult.UpToDate ->
            "У вас последняя версия (${result.latestName})" + if (result.via.isNotBlank()) " · ${result.via}" else ""
        is UpdateCheckResult.Available ->
            "Доступно обновление: ${result.versionName}" + (if (result.usingUniversal) " (универсальная сборка)" else "") +
                if (result.via.isNotBlank()) " · ${result.via}" else ""
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
