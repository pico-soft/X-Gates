package com.picosoft.xrayproxydroid.settings

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
 * Одна персональная блокировка сервера — точечно по serverKey (НАШЕ расширение поверх эталона).
 * [name] храним рядом с ключом, чтобы список заблокированных читался, даже когда сервер пропал из
 * подписки (иначе снять точечную блокировку было бы нечем — ключ без имени нечитаем).
 */
@Serializable
data class BlockedServer(
    val serverKey: String,
    val name: String,
    val blockedAt: Long = 0L,
)

/**
 * Стоп-лист = ДВА типа правил:
 *  - [words] — текстовые правила, ищутся в ИМЕНИ сервера (как эталон Termux check_blacklisted:
 *    подстрока, регистронезависимо). Хранятся уже приведёнными к lower-case.
 *  - [servers] — точечные блокировки по serverKey (наше расширение): режет ИМЕННО этот вариант,
 *    не всех одноимённых.
 *
 * Хранится ОТДЕЛЬНЫМ файлом (blocklist.json в filesDir), НЕ внутри профилей и НЕ в кэше подписки:
 * refresh подписки перезаписывает список серверов целиком и стёр бы правила ([overrides-and-blocklist-spec]).
 */
/**
 * Пользовательское переименование сервера (оверрайд по serverKey). [originalName] — имя провайдера на
 * момент переименования: нужно и для диалога («Имя от сервера: …»), и чтобы список читался, когда
 * сервер пропал из подписки, и для «применить ко всем с таким же ИСХОДНЫМ именем».
 */
@Serializable
data class ServerRename(
    val serverKey: String,
    val customName: String,
    val originalName: String,
    val renamedAt: Long = 0L,
)

/**
 * Отложенный сервер («на паузе», Промпт 91) — оверрайд по serverKey, ОТДЕЛЬНЫЙ от стоп-листа:
 * стоп-лист прячет отовсюду и снимается через настройки; отложенный скрыт из «Живых» и автовыбора,
 * но ВИДЕН в «Все серверы» с кнопкой возврата на месте. [name] — как у [BlockedServer], для читаемости
 * записи, когда сервер пропал из подписки.
 */
@Serializable
data class PausedServer(
    val serverKey: String,
    val name: String,
    val pausedAt: Long = 0L,
)

@Serializable
data class Blocklist(
    val words: List<String> = emptyList(),
    val servers: List<BlockedServer> = emptyList(),
    val renames: List<ServerRename> = emptyList(),   // добавлено в Промпт 44 (старый JSON читается — default)
    val paused: List<PausedServer> = emptyList(),     // Промпт 91: «на паузе» (старый JSON читается — default)
) {
    private val blockedKeys: Set<String> get() = servers.mapTo(HashSet()) { it.serverKey }
    private val nameByKey: Map<String, String> get() = renames.associate { it.serverKey to it.customName }
    private val pausedKeys: Set<String> get() = paused.mapTo(HashSet()) { it.serverKey }

    fun isServerBlocked(serverKey: String): Boolean = serverKey in blockedKeys

    /** Отложен ли сервер («на паузе»). Отдельная причина от стоп-листа — не смешивать. */
    fun isPaused(serverKey: String): Boolean = serverKey in pausedKeys

    /** Пользовательское имя для [serverKey] или null, если не переименован. */
    fun customName(serverKey: String): String? = nameByKey[serverKey]

    /**
     * Правило-слово блокирует, если оно подстрока ЛЮБОГО из переданных имён (исходного И пользовательского),
     * регистронезависимо (подстрока — 1:1 с эталоном). Проверять ОБА имени обязательно (см. пункт D2 спеки):
     * иначе переименование молча снимало бы блокировку, а заблокировать по своему имени было бы нельзя.
     */
    fun matchesWord(vararg names: String?): Boolean {
        if (words.isEmpty()) return false
        val ns = names.filterNotNull().map { it.lowercase() }
        return words.any { w -> w.isNotBlank() && ns.any { it.contains(w) } }
    }

    /** ЕДИНЫЙ предикат блокировки: точечно по ключу ИЛИ по слову в исходном/пользовательском имени. */
    fun isBlocked(originalName: String, customName: String?, serverKey: String): Boolean =
        isServerBlocked(serverKey) || matchesWord(originalName, customName)

    /**
     * Сколько серверов блокирует слово (для счётчика у чипа; 0 → вероятно опечатка). [names] — пары
     * (исходное имя, пользовательское или null): учитываем ОБА, чтобы счётчик не разошёлся с фактом (D3).
     */
    fun countForWord(word: String, names: List<Pair<String, String?>>): Int {
        val w = word.lowercase()
        if (w.isBlank()) return 0
        return names.count { (o, c) -> o.lowercase().contains(w) || (c != null && c.lowercase().contains(w)) }
    }
}

/**
 * Хранилище стоп-листа. Тот же паттерн, что [SettingsStore] (StateFlow + атомарная запись temp→rename
 * в filesDir). Отдельно от настроек — это оверрайды, а не пороги; сброс настроек к дефолтам стоп-лист
 * не трогает.
 */
object BlocklistStore {
    private const val TAG = "BlocklistStore"
    private const val FILE_NAME = "blocklist.json"
    private const val TMP_NAME = "blocklist.json.tmp"

    /**
     * Дефолтный RU/BY стоп-лист — 1:1 с эталоном (DEFAULT_BLACKLIST в xproxy_lib.py). Ставится ТОЛЬКО
     * при первом запуске (файла ещё нет). Если пользователь удалил все слова — файл существует пустым,
     * повторно НЕ засеваем (иначе удаление было бы невозможным).
     */
    val DEFAULT_WORDS = listOf(
        "москва", "россия", "санкт-петербург", "беларусь", "минск", "крым",
        "moscow", "russia", "st. petersburg", "belarus", "minsk", "crimea",
    )

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(Blocklist())
    val state: StateFlow<Blocklist> = _state.asStateFlow()
    fun current(): Blocklist = _state.value

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Загрузить при старте (MainActivity.onCreate). Нет файла → первый запуск → засеять дефолтом. */
    @Synchronized
    fun init(context: Context) {
        val f = file(context)
        if (!f.exists()) {
            save(context, Blocklist(words = DEFAULT_WORDS))
            return
        }
        try {
            _state.value = json.decodeFromString<Blocklist>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "load failed, keeping empty", e)
        }
    }

    @Synchronized
    private fun save(context: Context, new: Blocklist) {
        _state.value = new
        val target = file(context)
        val tmp = File(context.filesDir, TMP_NAME)
        try {
            tmp.writeText(json.encodeToString(new))
            if (tmp.renameTo(target)) return
            target.delete()
            if (!tmp.renameTo(target)) { tmp.copyTo(target, overwrite = true); tmp.delete() }
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
        }
    }

    /** Добавить слово-правило (нормализуем trim+lower, без дублей). false — пусто/дубль. */
    fun addWord(context: Context, word: String): Boolean {
        val w = word.trim().lowercase()
        if (w.isBlank() || w in current().words) return false
        save(context, current().let { it.copy(words = it.words + w) })
        return true
    }

    fun removeWord(context: Context, word: String) {
        save(context, current().let { it.copy(words = it.words - word) })
    }

    /** Точечно заблокировать сервер. [nowMs] передаёт вызывающий (стор без обращения к часам — тестируемость). */
    fun blockServer(context: Context, serverKey: String, name: String, nowMs: Long) {
        if (current().isServerBlocked(serverKey)) return
        save(context, current().let {
            it.copy(servers = it.servers + BlockedServer(serverKey, name, nowMs))
        })
    }

    fun unblockServer(context: Context, serverKey: String) {
        save(context, current().let {
            it.copy(servers = it.servers.filterNot { s -> s.serverKey == serverKey })
        })
    }

    /**
     * Переименовать. [keysWithOriginal] — пары (serverKey, исходное имя): один элемент = один сервер,
     * несколько = «применить ко всем с таким же исходным именем». Пустое [customName] = СБРОС (удаляем
     * записи для этих ключей). Одна запись в файл на всю операцию (batch), без множественных сохранений.
     */
    fun rename(context: Context, keysWithOriginal: List<Pair<String, String>>, customName: String, nowMs: Long) {
        val trimmed = customName.trim()
        val keys = keysWithOriginal.mapTo(HashSet()) { it.first }
        val cur = current()
        val without = cur.renames.filterNot { it.serverKey in keys }
        val next = if (trimmed.isEmpty()) without
                   else without + keysWithOriginal.map { (k, orig) -> ServerRename(k, trimmed, orig, nowMs) }
        save(context, cur.copy(renames = next))
    }

    /** Сброс пользовательского имени одного сервера («Вернуть имя от сервера»). */
    fun clearName(context: Context, serverKey: String) {
        save(context, current().let { it.copy(renames = it.renames.filterNot { r -> r.serverKey == serverKey }) })
    }

    /** Отложить сервер («На паузе»). Идемпотентно. [nowMs] передаёт вызывающий (стор без часов — тестируемость). */
    fun pauseServer(context: Context, serverKey: String, name: String, nowMs: Long) {
        if (current().isPaused(serverKey)) return
        save(context, current().let { it.copy(paused = it.paused + PausedServer(serverKey, name, nowMs)) })
    }

    /** Вернуть сервер из паузы. */
    fun resumeServer(context: Context, serverKey: String) {
        save(context, current().let { it.copy(paused = it.paused.filterNot { p -> p.serverKey == serverKey }) })
    }
}
