package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.xray.ServerFilter
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

    private const val TAG = "SubscriptionManager"

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

    /** Сколько серверов ПОЯВИТСЯ, если включить выключенные источники (сейчас скрыты — нет активного источника). */
    fun serversFromDisabled(context: Context): Int {
        val file = SubscriptionStore.load(context)
        val enabled = file.sources.filter { it.enabled }.map { it.id }.toSet()
        val disabled = file.sources.filterNot { it.enabled }.map { it.id }.toSet()
        return file.servers.count { rec -> rec.sourceIds.none { it in enabled } && rec.sourceIds.any { it in disabled } }
    }

    /** Включить ВСЕ выключенные источники (пункт E, по согласию пользователя). Возвращает их id. */
    fun enableAllDisabled(context: Context): List<String> {
        val ids = SubscriptionStore.load(context).sources.filterNot { it.enabled }.map { it.id }
        ids.forEach { setEnabled(context, it, true) }
        return ids
    }

    /**
     * Серверы, ПОДТВЕРЖДЁННО работавшие за последние [withinHours] часов (speedMbps>0 и свежий
     * speedTestedTs), по убыванию известной скорости, через ЕДИНЫЙ предикат (протокол + стоп-лист).
     * Для ступени 5 каскада ([CascadeFetch]) — обновить подписку через temp-инстанс, когда прямой путь
     * заблокирован, а активный туннель упал.
     */
    fun recentWorkingServers(context: Context, withinHours: Int = 24): List<ServerProfile> {
        val settings = SettingsStore.current()
        val bl = BlocklistStore.current()
        val cutoff = System.currentTimeMillis() - withinHours * 3600_000L
        return allServers(context)
            .filter { (it.speedMbps ?: 0.0) > 0.0 }
            .filter { p -> parseTs(p.speedTestedTs)?.let { it >= cutoff } ?: false }
            .filter { ServerFilter.protocolAllowed(it, settings) && !ServerFilter.isBlocked(it, bl) }
            .sortedByDescending { it.speedMbps ?: 0.0 }
    }

    private fun parseTs(s: String?): Long? =
        if (s == null) null else runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(s)?.time }.getOrNull()

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

    /** Добавить источник по URL (нормализуем, дедуп по нормализованному url). Возвращает id или null. */
    fun addUrl(context: Context, url: String, name: String? = null): String? {
        val u = normalizeUrl(url)
        if (u.isEmpty()) return null
        val file = SubscriptionStore.load(context)
        if (file.sources.any { it.url == u }) return null
        val id = newId()
        val src = SubSource(id = id, url = u, name = name?.takeIf { it.isNotBlank() } ?: nameFromUrl(u))
        SubscriptionStore.save(context, file.copy(sources = file.sources + src))
        return id
    }

    /** Локальный источник из вставленного текста/файла (без URL). Тот же разбор, что refresh, но оффлайн. */
    fun addLocalFromBody(context: Context, body: String, name: String? = null): Pair<String, RefreshSummary> {
        val id = newId()
        val file = SubscriptionStore.load(context)
        val src = SubSource(id = id, url = "", name = name?.takeIf { it.isNotBlank() } ?: "Вставка ${now()}")
        SubscriptionStore.save(context, file.copy(sources = file.sources + src))
        val detail = "Локальная вставка: ${body.length} симв, похоже на ${sniff(body)}"
        return id to importInto(context, id, body, detail)
    }

    /** Переименовать источник. Имя persist и НЕ затирается обновлением (refresh не трогает name). */
    fun rename(context: Context, id: String, name: String) {
        val n = name.trim().ifBlank { "Без имени" }
        val file = SubscriptionStore.load(context)
        SubscriptionStore.save(context, file.copy(sources = file.sources.map {
            if (it.id == id) it.copy(name = n) else it
        }))
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
        val settings = SettingsStore.current()
        val verbose = settings.verboseLogs
        fun log(m: String) { if (verbose) Log.i(TAG, m) }

        val src = SubscriptionStore.load(context).sources.firstOrNull { it.id == id }
            ?: return RefreshSummary(ok = false, error = "источник не найден")
        if (src.url.isBlank()) return RefreshSummary(ok = false, error = "локальный источник — нечего скачивать")

        val url = normalizeUrl(src.url)
        // КАСКАД (Промпт 51): напрямую → через свой активный SOCKS (если поднят). Прямой путь часто
        // заблокирован — именно ради этих условий приложение и существует. Таймауты ступеней раздельные
        // (прокси-ступень медленнее, +10с), плюс общий предел. «Скачано, но ссылок нет» = неудача ступени
        // (панель могла отдать заглушку блокировщика) → пробуем следующую.
        val directTimeout = settings.subTimeoutSec * 1000
        val proxyTimeout = settings.subTimeoutSec * 1000 + 10_000
        val totalTimeout = directTimeout + proxyTimeout + 5_000
        log("── refresh «${src.name}» url=$url UA=${settings.subUserAgent} timeout=${settings.subTimeoutSec}s (каскад)")
        val cascade = CascadeFetch.fetch(
            context, url, settings.subUserAgent, directTimeout, proxyTimeout, totalTimeout,
            acceptBody = { it.ok && hasSupportedLinks(it.body) },
        )

        // Диагностика по КАЖДОЙ ступени (типизация ошибок из Промпта 25 сохранена). Пропущенные ступени
        // помечаем причиной (a.note), не считая неудачей.
        fun stageLabel(a: com.picosoft.xrayproxydroid.net.CascadeAttempt): String {
            val suffix = if (a.note.isNotEmpty()) " «${a.note}»" else ""
            return a.stage.label + suffix
        }
        val stagesDetail = cascade.attempts.joinToString("\n") { a ->
            val r = a.result
            "• ${stageLabel(a)}: " + when {
                a.skipped -> "пропущено (${a.note})"
                r == null -> "нет ответа"
                a.accepted -> "OK (HTTP ${r.httpCode}, ${r.bodyBytes} б)"
                !r.ok -> classifyFetchFailure(r)
                else -> "скачано ${r.bodyBytes} б, ссылок не найдено (похоже на ${sniff(r.body)})"
            }
        }

        if (!cascade.ok) {
            // Итог ПО КАЖДОЙ ступени (кроме пропущенных по неприменимости — они отчёт не засоряют).
            val err = "Не скачалось. " + cascade.attempts.filterNot { it.skipped }.joinToString("; ") { a ->
                val r = a.result
                a.stage.label + ": " + when {
                    r == null -> "нет ответа"
                    !r.ok -> classifyFetchFailure(r)
                    else -> "ссылок не найдено (${sniff(r.body)})"
                }
            }.ifBlank { "все применимые ступени пропущены (нет прокси/сети/кандидатов)" }
            setStatus(context, id, ok = false, error = err, detail = stagesDetail)
            log("  → ОТКАЗ (все ступени): $err")
            return RefreshSummary(ok = false, error = err)
        }

        val res = cascade.result!!
        val via = cascade.stage!!.label
        log("  OK ступенью «$via» http=${res.httpCode} bytes=${res.bodyBytes}")
        val detail = buildString {
            append("Загружено: $via\n")                 // КАКОЙ ступенью удалось (Промпт 51.C)
            append("URL: ${res.finalUrl}\n")
            append("HTTP: ${res.httpCode}")
            res.contentType?.let { append(" · $it") }
            append("\nБайт: ${res.bodyBytes}\n\nСтупени:\n$stagesDetail")
        }
        return importInto(context, id, res.body, detail)
    }

    /** Есть ли в теле хотя бы одна распознаваемая ссылка (для предиката каскада «ответ пригоден»). */
    private fun hasSupportedLinks(body: String): Boolean {
        if (body.isBlank()) return false
        for (line in SubscriptionDecoder.decode(body)) {
            if (ServerLinkParser.parse(line) is ParseResult.Supported) return true
        }
        return false
    }

    /** Классификация не-успешной загрузки в русский текст (сеть/таймаут/cleartext/HTTP-код/пусто). */
    private fun classifyFetchFailure(res: FetchResult): String = when {
        res.exceptionClass != null && (res.errorMessage?.contains("CLEARTEXT", true) == true ||
            res.errorMessage?.contains("Cleartext", true) == true) ->
            "cleartext http:// заблокирован политикой Android — используйте https://"
        res.exceptionClass == "UnknownHostException" -> "Хост не найден (нет сети/DNS): ${res.errorMessage}"
        res.exceptionClass == "SocketTimeoutException" -> "Таймаут загрузки (${res.errorMessage})"
        res.exceptionClass != null -> "Сеть недоступна: ${res.exceptionClass}: ${res.errorMessage}"
        res.httpCode !in 200..299 -> "HTTP ${res.httpCode} — сервер отклонил запрос"
        else -> "Неизвестная ошибка загрузки"
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
     * Классифицирует отказ разбора (пусто / 0 ссылок / всё unsupported) отдельным русским текстом —
     * это НЕ сетевая ошибка. [detail] — диагностика (URL/код/тело) для подробностей. Тестируемо без сети.
     */
    fun importInto(context: Context, id: String, body: String, detail: String? = null): RefreshSummary {
        val verbose = SettingsStore.current().verboseLogs
        fun log(m: String) { if (verbose) Log.i(TAG, m) }

        val bytes = body.toByteArray(Charsets.UTF_8).size
        if (body.isBlank()) {
            val err = "Тело пустое (0 байт)"
            persistMerge(context, id, emptyList(), ok = false, error = err, detail = detail)
            log("  → $err")
            return RefreshSummary(ok = false, error = err)
        }

        val lines = SubscriptionDecoder.decode(body)
        val profiles = ArrayList<ServerProfile>()
        val unsupportedKinds = LinkedHashSet<String>()
        val seen = HashSet<String>()   // дедуп ВНУТРИ источника
        var unsupported = 0; var invalid = 0; var duplicates = 0
        for (line in lines) {
            when (val r = ServerLinkParser.parse(line)) {
                is ParseResult.Supported ->
                    if (seen.add(serverKey(r.profile))) profiles.add(r.profile) else duplicates++
                is ParseResult.Unsupported -> { unsupported++; unsupportedKinds.add(r.scheme) }
                is ParseResult.Invalid -> invalid++
            }
        }
        log("  строк=${lines.size} распарсено=${profiles.size} unsupported=$unsupported ($unsupportedKinds) invalid=$invalid дубли=$duplicates")

        if (profiles.isEmpty()) {
            val err = when {
                lines.isNotEmpty() && unsupported > 0 && invalid == 0 ->
                    "Ссылки есть, но все протоколы не поддержаны: ${unsupportedKinds.joinToString(", ")}"
                else ->
                    "Скачано $bytes байт, ссылок не найдено (похоже на ${sniff(body)})"
            }
            persistMerge(context, id, emptyList(), ok = false, error = err, detail = detail)
            log("  → $err")
            return RefreshSummary(ok = false, added = 0, unsupported = unsupported, invalid = invalid, error = err)
        }

        persistMerge(context, id, profiles, ok = true, error = null, detail = detail)
        return RefreshSummary(ok = true, added = profiles.size, unsupported = unsupported, invalid = invalid, duplicates = duplicates)
    }

    /** Влить профили под источник [id] в реестр + выставить статус/детали источника + пересчёт + save. */
    private fun persistMerge(
        context: Context, id: String, profiles: List<ServerProfile>,
        ok: Boolean, error: String?, detail: String?,
    ) {
        val file = SubscriptionStore.load(context)
        val (registry, _) = mergeIntoRegistry(file.servers, id, profiles)
        val sources = file.sources.map {
            if (it.id == id) it.copy(lastOk = ok, lastError = error, lastDetail = detail, lastRefreshTs = now()) else it
        }
        SubscriptionStore.save(context, recount(file.copy(sources = sources, servers = registry)))
    }

    /** На что похоже тело (для сообщения «ссылок не найдено»). */
    private fun sniff(body: String): String {
        val t = body.trimStart()
        return when {
            t.startsWith("<") || t.contains("<!doctype", true) || t.contains("<html", true) -> "HTML (страница, не подписка)"
            t.startsWith("{") || t.startsWith("[") -> "JSON"
            t.contains("proxies:") || t.contains("proxy-groups:") || Regex("(?m)^[\\w-]+:\\s").containsMatchIn(t) -> "YAML/Clash (нужен base64-список, не Clash)"
            else -> "обычный текст без ссылок"
        }
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

    /** Выставить статус источника (сетевой/HTTP отказ — без изменения серверов). */
    private fun setStatus(context: Context, id: String, ok: Boolean, error: String?, detail: String?) {
        val file = SubscriptionStore.load(context)
        SubscriptionStore.save(context, file.copy(sources = file.sources.map {
            if (it.id == id) it.copy(lastOk = ok, lastError = error, lastDetail = detail, lastRefreshTs = now()) else it
        }))
    }

    private fun newId(): String = UUID.randomUUID().toString()

    /** Нормализация URL: обрезать пробелы/переводы строк с концов; без схемы — подставить https://. */
    fun normalizeUrl(raw: String): String {
        val u = raw.trim().trim('\n', '\r', ' ', '\t')
        if (u.isEmpty()) return ""
        return if (u.startsWith("http://", true) || u.startsWith("https://", true)) u else "https://$u"
    }

    /** Имя из URL — ТОЛЬКО host. Если хост извлечь не удалось — честно «Без имени» (не огрызок URL). */
    private fun nameFromUrl(url: String): String = try {
        URI(normalizeUrl(url)).host?.takeIf { it.isNotBlank() } ?: "Без имени"
    } catch (e: Exception) {
        "Без имени"
    }

    private fun now(): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
