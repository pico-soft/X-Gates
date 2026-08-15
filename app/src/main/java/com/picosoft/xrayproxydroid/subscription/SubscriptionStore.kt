package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Единственная точка чтения/записи subscriptions.json в filesDir.
 * Формат — JSON-массив [Subscription] со вложенными серверами. Запись атомарная (temp→rename),
 * чтобы обрыв в процессе не оставил битый файл. Сети/парсинга здесь нет — только персистентность.
 */
object SubscriptionStore {

    private const val TAG = "SubscriptionStore"
    private const val FILE_NAME = "subscriptions.json"
    private const val TMP_NAME = "subscriptions.json.tmp"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true   // forward-compat: новые поля не ломают чтение старого файла
        encodeDefaults = true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Список подписок; пустой, если файла нет или он повреждён (не падаем). */
    @Synchronized
    fun load(context: Context): List<Subscription> {
        val f = file(context)
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Subscription>>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "load failed, returning empty", e)
            emptyList()
        }
    }

    /** Атомарная запись: сериализуем во временный файл и переименовываем поверх основного. */
    @Synchronized
    fun save(context: Context, subs: List<Subscription>) {
        val target = file(context)
        val tmp = File(context.filesDir, TMP_NAME)
        tmp.writeText(json.encodeToString(subs))

        if (tmp.renameTo(target)) return
        // На некоторых ФС rename поверх существующего не срабатывает — удаляем и пробуем снова.
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }
}
