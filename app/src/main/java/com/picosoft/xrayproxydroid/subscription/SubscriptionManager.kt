package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import com.picosoft.xrayproxydroid.xray.link.ParseResult
import com.picosoft.xrayproxydroid.xray.link.ServerLinkParser
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/** Итог обновления одного источника. */
data class RefreshSummary(
    val ok: Boolean,
    val added: Int = 0,
    val unsupported: Int = 0,   // hysteria2/tuic… — распознаны, но не наше ядро
    val invalid: Int = 0,       // битые/неизвестные строки
    val duplicates: Int = 0,    // повторы в самом источнике — отброшены
    val error: String? = null,
)

/** Итог склейки при импорте (факт-проверка дедупа между источниками). */
data class MergeStats(
    val incoming: Int,          // распознано в этом импорте
    val newInRegistry: Int,     // новых записей добавлено в общий реестр
    val mergedExisting: Int,    // совпало с уже существующими serverKey (склейка, не дубль)
    val registryTotal: Int,     // всего записей в реестре после
)

/**
 * Оркестратор МУЛЬТИподписок поверх [SubscriptionStore] ([SourcesFile]).
 * Серверы склеиваются по [serverKey] в общий реестр; членство — список источников.
 * Сетевые методы БЛОКИРУЮЩИЕ — вызывать в фоне.
 */
object SubscriptionManager {

    /** URL старой единственной подписки — ТОЛЬКО как значение при первой миграции (из кода убран). */
    private const val LEGACY_DEFAULT_URL = "https://maxim-zodchy.ru/sub-black.php"

    /**
     * Ключ ПОЛНОЙ идентичности соединения — дедуп внутри источника, склейка МЕЖДУ источниками,
     * привязка pingMs/speed, определение активного сервера. НЕ только addr+port+cred (иначе
     * склеятся транспортно/fp-разные варианты — это разные конфиги). Исключены динамика/метки.
     */
    fun serverKey(p: ServerProfile): String = listOf(
        p.protocol.name, p.address, p.port.toString(), p.credential,
        p.method.orEmpty(), p.flow.orEmpty(),
        p.security, p.sni.orEmpty(), p.fingerprint.orEmpty(), p.alpn.orEmpty(),
        p.network, p.path.orEmpty(), p.hostHeader.orEmpty(), p.serviceName.orEmpty(),
        p.publicKey.orEmpty(), p.shortId.orEmpty(), p.spiderX.orEmpty(),
    ).joinToString("|")

    // ─────────────────────────── миграция (однократно) ───────────────────────────

    /** Однократная инициализация: миграция старого `subscriptions.json` или создание дефолтного источника. */
    fun init(context: Context) {
        val cur = SubscriptionStore.load(context)
        if (cur.migratedLegacy) return
        val legacy = SubscriptionStore.readLegacy(context)
        val migrated = if (legacy.isNotEmpty()) convertLegacy(legacy) else freshDefault()
        SubscriptionStore.save(context, recount(migrated))
    }

    /** Старые вложенные подписки → источники + реестр (измерения серверов сохраняются). */
    private fun convertLegacy(legacy: List<Subscription>): SourcesFile {
        val sources = ArrayList<SubSource>()
        var registry = emptyList<ServerRecord>()
        for (s in legacy) {
            val id = newId()
            sources.add(
                SubSource(
                    id = id, name = s.name, url = s.url, enabled = true,
                    lastRefreshTs = s.lastUpdateTs, lastOk = s.lastUpdateOk,
                )
            )
            registry = mergeIntoRegistry(registry, id, s.servers).first  // s.servers несут измерения
        }
        return SourcesFile(migratedLegacy = true, sources = sources, servers = registry)
    }

    /** Свежая установка: один источник — старый URL, ещё не обновлён (серверов нет). */
    private fun freshDefault(): SourcesFile = SourcesFile(
        migratedLegacy = true,
        sources = listOf(SubSource(id = newId(), name = nameFromUrl(LEGACY_DEFAULT_URL), url = LEGACY_DEFAULT_URL)),
        servers = emptyList(),
    )

    // ─────────────────────────── чтение ───────────────────────────

    /** Метаданные источников. */
    fun sources(context: Context): List<SubSource> = SubscriptionStore.load(context).sources

    /** Плоский ДЕДУПЛИЦИРОВАННЫЙ список серверов из ВКЛЮЧЁННЫХ источников (для списка/Авто). */
    fun allServers(context: Context): List<ServerProfile> {
        val file = SubscriptionStore.load(context)
        val enabled = file.sources.filter { it.enabled }.map { it.id }.toSet()
        return file.servers.filter { rec -> rec.sourceIds.any { it in enabled } }.map { it.profile }
    }

    // ─────────────────────────── применение замеров ───────────────────────────

    /** Обновить pingMs (ключ→мс) в реестре и сохранить ОДИН раз. */
    fun applyPingResults(context: Context, pingByKey: Map<String, Int>) {
        if (pingByKey.isEmpty()) return
        val ts = now()
        val file = SubscriptionStore.load(context)
        val servers = file.servers.map { rec ->
            val ms = pingByKey[serverKey(rec.profile)]
            if (ms != null) rec.copy(profile = rec.profile.copy(pingMs = ms, lastTestedTs = ts)) else rec
        }
        SubscriptionStore.save(context, file.copy(servers = servers))
    }

    /** Обновить speedMbps (ключ→Mbps) в реестре и сохранить ОДИН раз. */
    fun applySpeedResults(context: Context, speedByKey: Map<String, Double>) {
        if (speedByKey.isEmpty()) return
        val ts = now()
        val file = SubscriptionStore.load(context)
        val servers = file.servers.map { rec ->
            val mbps = speedByKey[serverKey(rec.profile)]
            if (mbps != null) rec.copy(profile = rec.profile.copy(speedMbps = mbps, speedTestedTs = ts)) else rec
        }
        SubscriptionStore.save(context, file.copy(servers = servers))
    }

    // ─────────────────────────── управление источниками ───────────────────────────

    /** Добавить источник по URL (дедуп по url). Имя пустое → из хоста. Возвращает id или null (дубль/пусто). */
    fun addUrl(context: Context, url: String, name: String? = null): String? {
        val u = url.trim()
        if (u.isEmpty()) return null
        val file = SubscriptionStore.load(context)
        if (file.sources.any { it.url == u }) return null
        val id = newId()
        val src = SubSource(id = id, url = u, name = name?.takeIf { it.isNotBlank() } ?: nameFromUrl(u))
        SubscriptionStore.save(context, file.copy(sources = file.sources + src))
        return id
    }

    /** Локальный источник из вставленного текста/файла (без URL). Тот же путь, что refresh, но оффлайн. */
    fun addLocalFromBody(context: Context, body: String, name: String? = null): Pair<String, RefreshSummary> {
        val id = newId()
        val file = SubscriptionStore.load(context)
        val src = SubSource(id = id, url = "", name = name?.takeIf { it.isNotBlank() } ?: "Вставка ${now()}")
        SubscriptionStore.save(context, file.copy(sources = file.sources + src))
        return id to importInto(context, id, body)
    }

    /** Удалить источник; серверы убираем ТОЛЬКО там, где не осталось ни одного источника. */
    fun remove(context: Context, id: String) {
        val file = SubscriptionStore.load(context)
        val sources = file.sources.filterNot { it.id == id }
        val servers = file.servers.mapNotNull { rec ->
            val ids = rec.sourceIds - id
            if (ids.isEmpty()) null else rec.copy(sourceIds = ids)
        }
        SubscriptionStore.save(context, recount(file.copy(sources = sources, servers = servers)))
    }

    /** Сколько серверов ИСЧЕЗНЕТ при удалении источника (принадлежат только ему). */
    fun serversLostOnRemove(context: Context, id: String): Int =
        SubscriptionStore.load(context).servers.count { it.sourceIds.singleOrNull() == id }

    /** Вкл/выкл источник (серверы не удаляем — фильтруются при чтении по enabled). */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val file = SubscriptionStore.load(context)
        SubscriptionStore.save(context, file.copy(sources = file.sources.map {
            if (it.id == id) it.copy(enabled = enabled) else it
        }))
    }

    // ─────────────────────────── обновление ───────────────────────────

    /** Скачать + импортировать ОДИН источник по его url. БЛОКИРУЮЩИЙ. Локальные (url пустой) пропускаем. */
    fun refreshOne(context: Context, id: String): RefreshSummary {
        val src = SubscriptionStore.load(context).sources.firstOrNull { it.id == id }
            ?: return RefreshSummary(ok = false, error = "источник не найден")
        if (src.url.isBlank()) return RefreshSummary(ok = false, error = "локальный источник — нечего скачивать")
        val body = SubscriptionFetcher.fetch(src.url).getOrElse { e ->
            markStatus(context, id, ok = false, error = e.message ?: "fetch failed")
            return RefreshSummary(ok = false, error = e.message ?: "fetch failed")
        }
        return importInto(context, id, body)
    }

    /**
     * Обновить ВСЕ включённые URL-источники. Одна упавшая не роняет прочие. [cancelled] — флаг между
     * источниками. [onEach] — колбэк прогресса (имя, summary). Возвращает карту id→summary.
     */
    fun refreshAllEnabled(
        context: Context,
        cancelled: () -> Boolean = { false },
        onEach: (SubSource, RefreshSummary) -> Unit = { _, _ -> },
    ): Map<String, RefreshSummary> {
        val result = LinkedHashMap<String, RefreshSummary>()
        val targets = SubscriptionStore.load(context).sources.filter { it.enabled && it.url.isNotBlank() }
        for (src in targets) {
            if (cancelled()) break
            val summary = runCatching { refreshOne(context, src.id) }
                .getOrElse { RefreshSummary(ok = false, error = it.message ?: "ошибка") }
            result[src.id] = summary
            onEach(src, summary)
        }
        return result
    }

    /**
     * Оффлайн-ядро: тело → decode → parse → склейка в реестр под источник [id] → пересчёт → save.
     * Обновляет статус источника. Тестируемо без сети.
     */
    fun importInto(context: Context, id: String, body: String): RefreshSummary {
        val lines = SubscriptionDecoder.decode(body)
        val profiles = ArrayList<ServerProfile>()
        val seen = HashSet<String>()   // дедуп ВНУТРИ источника
        var unsupported = 0; var invalid = 0; var duplicates = 0
        for (line in lines) {
            when (val r = ServerLinkParser.parse(line)) {
                is ParseResult.Supported ->
                    if (seen.add(serverKey(r.profile))) profiles.add(r.profile) else duplicates++
                is ParseResult.Unsupported -> unsupported++
                is ParseResult.Invalid -> invalid++
            }
        }

        val file = SubscriptionStore.load(context)
        val (registry, _) = mergeIntoRegistry(file.servers, id, profiles)
        val sources = file.sources.map {
            if (it.id == id) it.copy(lastOk = true, lastError = null, lastRefreshTs = now()) else it
        }
        SubscriptionStore.save(context, recount(file.copy(sources = sources, servers = registry)))
        return RefreshSummary(ok = true, added = profiles.size, unsupported = unsupported, invalid = invalid, duplicates = duplicates)
    }

    /** Факт-проверка дедупа: склеить набор профилей в реестр и вернуть статистику пересечений. */
    fun mergeStats(existing: List<ServerRecord>, sourceId: String, profiles: List<ServerProfile>): MergeStats {
        val existingKeys = existing.map { serverKey(it.profile) }.toHashSet()
        val incomingKeys = profiles.map { serverKey(it) }.toHashSet()
        val merged = incomingKeys.count { it in existingKeys }
        val (reg, _) = mergeIntoRegistry(existing, sourceId, profiles)
        return MergeStats(
            incoming = profiles.size,
            newInRegistry = incomingKeys.size - merged,
            mergedExisting = merged,
            registryTotal = reg.size,
        )
    }

    // ─────────────────────────── склейка реестра ───────────────────────────

    /**
     * Влить [profiles] под [sourceId] в реестр: совпавшие по serverKey — склеить (СОХРАНИВ измерения
     * существующей записи, добавив sourceId), отсутствующие в импорте — убрать sourceId (и запись,
     * если источников не осталось), новые — добавить. Возвращает (новый реестр, число склеек).
     */
    private fun mergeIntoRegistry(
        registry: List<ServerRecord>,
        sourceId: String,
        profiles: List<ServerProfile>,
    ): Pair<List<ServerRecord>, Int> {
        val incoming = LinkedHashMap<String, ServerProfile>()
        for (p in profiles) incoming.putIfAbsent(serverKey(p), p)

        val result = ArrayList<ServerRecord>()
        val handled = HashSet<String>()
        var merged = 0
        for (rec in registry) {
            val k = serverKey(rec.profile)
            val fresh = incoming[k]
            if (fresh != null) {
                // Совпадение: сохраняем измерения существующей записи, статические поля берём из fresh.
                val mergedProfile = fresh.copy(
                    pingMs = rec.profile.pingMs, lastTestedTs = rec.profile.lastTestedTs,
                    speedMbps = rec.profile.speedMbps, speedTestedTs = rec.profile.speedTestedTs,
                )
                result.add(ServerRecord(mergedProfile, (rec.sourceIds + sourceId).distinct()))
                if (sourceId !in rec.sourceIds) merged++
                handled.add(k)
            } else {
                // Не в этом импорте — убираем данный источник; запись остаётся, если есть другие.
                val ids = rec.sourceIds - sourceId
                if (ids.isNotEmpty()) result.add(rec.copy(sourceIds = ids))
            }
        }
        for ((k, fresh) in incoming) if (k !in handled) result.add(ServerRecord(fresh, listOf(sourceId)))
        return result to merged
    }

    /** Пересчитать serverCount каждого источника из реестра (денормализация без дрейфа). */
    private fun recount(file: SourcesFile): SourcesFile {
        val counts = HashMap<String, Int>()
        for (rec in file.servers) for (id in rec.sourceIds) counts[id] = (counts[id] ?: 0) + 1
        return file.copy(sources = file.sources.map { it.copy(serverCount = counts[it.id] ?: 0) })
    }

    // ─────────────────────────── helpers ───────────────────────────

    private fun markStatus(context: Context, id: String, ok: Boolean, error: String?) {
        val file = SubscriptionStore.load(context)
        SubscriptionStore.save(context, file.copy(sources = file.sources.map {
            if (it.id == id) it.copy(lastOk = ok, lastError = error, lastRefreshTs = now()) else it
        }))
    }

    private fun newId(): String = UUID.randomUUID().toString()

    /** Имя из URL: host + последний сегмент пути (12 симв.). */
    private fun nameFromUrl(url: String): String = try {
        val u = URI(url)
        val host = u.host ?: return url.take(30)
        val seg = u.path.orEmpty().trim('/').substringAfterLast('/', "")
        if (seg.isNotEmpty()) "$host/${seg.take(12)}" else host
    } catch (e: Exception) {
        url.take(30)
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
