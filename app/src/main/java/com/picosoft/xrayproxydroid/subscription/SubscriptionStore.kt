package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import android.util.Log
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Единственная точка чтения/записи `sources.json` (мультиподписки) в filesDir.
 * Схема — [SourcesFile] (метаданные источников + общий реестр серверов). Запись атомарная.
 * Миграция старого `subscriptions.json` — в [SubscriptionManager.init] (бизнес-логика), здесь только
 * персистентность + сырое чтение легаси-файла.
 */
object SubscriptionStore {

    private const val TAG = "SubscriptionStore"
    private const val FILE_NAME = "sources.json"
    private const val TMP_NAME = "sources.json.tmp"
    private const val LEGACY_FILE_NAME = "subscriptions.json"

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    /** Текущий файл источников; пустой (migratedLegacy=false), если файла ещё нет. */
    @Synchronized
    fun load(context: Context): SourcesFile {
        val f = file(context)
        if (!f.exists()) return SourcesFile()
        return try {
            json.decodeFromString<SourcesFile>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "load failed, returning empty", e)
            SourcesFile()
        }
    }

    /** Атомарная запись (temp→rename). */
    @Synchronized
    fun save(context: Context, data: SourcesFile) {
        val target = file(context)
        val tmp = File(context.filesDir, TMP_NAME)
        tmp.writeText(json.encodeToString(data))
        if (tmp.renameTo(target)) return
        target.delete()
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    /** Сырое чтение СТАРОГО формата (`subscriptions.json` = список [Subscription] со вложенными
     *  серверами) — только для однократной миграции. Пусто, если файла нет или он не в старой схеме. */
    fun readLegacy(context: Context): List<Subscription> {
        val f = File(context.filesDir, LEGACY_FILE_NAME)
        if (!f.exists()) return emptyList()
        return try {
            json.decodeFromString<List<Subscription>>(f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "legacy read failed", e)
            emptyList()
        }
    }
}
