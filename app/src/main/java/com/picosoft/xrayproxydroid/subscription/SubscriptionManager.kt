package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.net.CascadeResult
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.link.ParseResult
import com.picosoft.xrayproxydroid.xray.link.ServerLinkParser
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val outcome: SourceOutcome = if (ok) SourceOutcome.OK else SourceOutcome.ERROR,
    val rateLimited: Boolean = false,   // HTTP 429 — панель ограничивает частоту (Промпт 81.A)
    val retryAfterSec: Int? = null,     // из Retry-After (когда можно повторить)
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

    /** Дефолтная подписка «из коробки» (Промпт 72). Публичный список конфигов для обхода в РФ. */
    const val DEFAULT_SOURCE_URL = "https://raw.githack.com/igareck/vpn-configs-for-russia/main/BLACK_VLESS_RUS_mobile.txt"
    const val DEFAULT_SOURCE_NAME = "Обход ограничений в РФ по-умолчанию"

    /** Пр.140: источники «белого списка РФ» — сеются ВЫКЛ, авто-обновляются раз в полчаса (безакцептно), используются
     *  только в режиме белых списков (или если юзер включит их вручную). Дедуп по URL (не плодим дубли). Каждую
     *  пробуем каждые полчаса: на GitHub файлы меняются — какой-то может временно отдавать 404 (напр. `…-2.txt`
     *  сейчас 404), а `WHITE-CIDR-RU-all.txt` вопреки имени содержит рабочие vless-конфиги. В режиме белых списков
     *  прямой путь к githack закрыт — обновление идёт ЧЕРЕЗ ТУННЕЛЬ рабочего белого сервера (CascadeFetch, ступень SOCKS). */
    val WHITE_LIST_SOURCES = listOf(
        "Серверы для белых списков РФ" to "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile.txt",
        "Серверы для белых списков РФ (2)" to "https://raw.githack.com/igareck/vpn-configs-for-russia/main/Vless-Reality-White-Lists-Rus-Mobile-2.txt",
        "Белые списки РФ (CIDR)" to "https://raw.githack.com/igareck/vpn-configs-for-russia/main/WHITE-CIDR-RU-all.txt",
    )

    /** Пр.136: состояние посева дефолтной подписки для UI. [attempted]=пробовали хоть раз; [error] пуст = успех/не
     *  пробовали, непуст = последняя попытка не удалась (показываем причину + «Повторить», не пустой экран). */
    data class SeedStatus(val attempted: Boolean = false, val error: String = "")
    private val _seedStatus = MutableStateFlow(SeedStatus())
    val seedStatus: StateFlow<SeedStatus> = _seedStatus.asStateFlow()

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

    /**
     * Однократная инициализация (СИНХРОННО, без сети). Миграция старого `subscriptions.json`; затем: если у
     * юзера УЖЕ есть подписки — помечаем «посеяно», чтобы дефолт никогда не добавлялся авто. Сетевой посев
     * ПУСТОГО списка — отдельно и по ФАКТУ фетча ([trySeedDefaultSource], из автозапуска, в фоне).
     */
    fun init(context: Context) {
        val cur = SubscriptionStore.load(context)
        if (!cur.migratedLegacy) {
            val legacy = SubscriptionStore.readLegacy(context)
            val migrated = if (legacy.isNotEmpty()) convertLegacy(legacy) else freshDefault()
            SubscriptionStore.save(context, recount(migrated))
        }
        val f = SubscriptionStore.load(context)
        if (!f.seededDefaultRuBypass && f.sources.isNotEmpty()) {
            SubscriptionStore.save(context, f.copy(seededDefaultRuBypass = true))
        }
        // Пр.140: добавить источники белого списка (ВЫКЛ) существующим установкам, у кого их ещё нет. Только
        // метаданные (URL) — тела подтянет авто-рефреш раз в полчаса. На свежей установке их сеет trySeedDefaultSource.
        runCatching { ensureWhiteListSources(context) }
        // Промпт 85.E: самопроверка — неполные (осиротевшие) профили пересобрать из сырья + записать в журнал.
        runCatching { verifyAndHeal(context) }
        // Пр.136: восстановить статус посева для UI (переживает перезапуск) — чтобы карточка «не загрузилось»
        // и причина были видны сразу после старта, а не только в сессии, где случилась неудача.
        val ff = SubscriptionStore.load(context)
        _seedStatus.value = SeedStatus(attempted = ff.seedLastAttemptTs.isNotBlank() || ff.seededDefaultRuBypass, error = ff.seedLastError)
    }

    /**
     * Промпт 74: дефолтную подписку «Обход ограничений в РФ» добавляем ТОЛЬКО если её URL реально
     * ЗАФЕТЧИЛСЯ (первый запуск). Не зафетчился → список остаётся ПУСТЫМ (в UI «Добавьте вашу подписку»),
     * флаг НЕ ставим → повторим на следующем холодном старте. Сеть → вызывать в ФОНЕ.
     * Возвращает true, если только что добавила+импортировала (чтобы автозапуск не рефетчил повторно).
     * Идемпотентно: не сеем, если уже посеяно ИЛИ у юзера уже есть подписки (флаг [seededDefaultRuBypass]
     * не даёт дефолту вернуться после удаления пользователем).
     */
    fun trySeedDefaultSource(context: Context): Boolean {
        val file = SubscriptionStore.load(context)
        if (file.seededDefaultRuBypass) return false
        if (file.sources.isNotEmpty()) {                       // у юзера уже есть свои → дефолт не навязываем
            SubscriptionStore.save(context, file.copy(seededDefaultRuBypass = true))
            _seedStatus.value = SeedStatus(attempted = true, error = "")
            return false
        }
        val settings = SettingsStore.current()
        val url = normalizeUrl(DEFAULT_SOURCE_URL)
        val directT = settings.subTimeoutSec * 1000
        val proxyT = settings.subTimeoutSec * 1000 + 10_000
        val totalT = directT + proxyT + 5_000
        val cascade = CascadeFetch.fetch(context, url, settings.subUserAgent, directT, proxyT, totalT,
            acceptBody = { it.ok && hasSupportedLinks(it.body) })
        // Пр.136: НЕУДАЧА не помечается как выполненная (флаг seededDefaultRuBypass НЕ ставим → повторим позже),
        // но фиксируем ПРИЧИНУ в лог + persist + StateFlow — чтобы человек видел «список не загрузился, почему»,
        // а не пустой экран. На свежей установке работает только прямая ступень (туннеля и известных серверов ещё нет).
        if (!cascade.ok) {
            val reason = seedFailReason(cascade)
            Log.w(TAG, "посев дефолтной подписки не удался: $reason")
            SubscriptionStore.save(context, SubscriptionStore.load(context).copy(seedLastError = reason, seedLastAttemptTs = now()))
            _seedStatus.value = SeedStatus(attempted = true, error = reason)
            return false
        }
        val f2 = SubscriptionStore.load(context)               // перечитать (гонка) — вдруг юзер успел добавить
        if (f2.seededDefaultRuBypass || f2.sources.isNotEmpty()) { _seedStatus.value = SeedStatus(attempted = true, error = ""); return false }
        val id = newId()
        // Пр.140: вместе с чёрным списком (ВКЛ) сеем источники белого списка (ВЫКЛ, whiteList=true) — тела
        // подтянет авто-рефреш; в обычном режиме они не мешают (выключены), в белом — включатся эффективно.
        val whiteSources = WHITE_LIST_SOURCES.map { (nm, u) ->
            SubSource(id = newId(), name = nm, url = normalizeUrl(u), enabled = false, whiteList = true)
        }
        SubscriptionStore.save(context, f2.copy(
            seededDefaultRuBypass = true,
            seedLastError = "",                                // успех — чистим прежнюю ошибку
            sources = listOf(SubSource(id = id, name = DEFAULT_SOURCE_NAME, url = url, enabled = true)) + whiteSources,
        ))
        importInto(context, id, cascade.result!!.body, "Дефолтная подписка (первый успешный фетч)")
        _seedStatus.value = SeedStatus(attempted = true, error = "")
        return true
    }

    /** Пр.140: добавить недостающие источники белого списка (ВЫКЛ) — для установок, где чёрный список уже был.
     *  Идемпотентно: сверяем по URL, дубли не плодим. Только метаданные (без сети) — тела подтянет авто-рефреш. */
    fun ensureWhiteListSources(context: Context) {
        val file = SubscriptionStore.load(context)
        if (file.sources.isEmpty()) return                     // свежая установка — засеет trySeedDefaultSource
        val haveUrls = file.sources.map { it.url }.toSet()
        val missing = WHITE_LIST_SOURCES
            .filter { (_, u) -> normalizeUrl(u) !in haveUrls }
            .map { (nm, u) -> SubSource(id = newId(), name = nm, url = normalizeUrl(u), enabled = false, whiteList = true) }
        if (missing.isEmpty()) return
        SubscriptionStore.save(context, file.copy(sources = file.sources + missing))
        Log.i(TAG, "добавлены источники белого списка (ВЫКЛ): ${missing.size}")
    }

    /** Пр.140: id источников белого списка. */
    fun whiteListSourceIds(context: Context): Set<String> =
        SubscriptionStore.load(context).sources.filter { it.whiteList }.map { it.id }.toSet()

    /** Пр.140: авто-обновление источников белого списка (раз в полчаса, безакцептно). Возвращает сколько ОК. */
    fun refreshWhiteListSources(context: Context): Int {
        val ids = SubscriptionStore.load(context).sources.filter { it.whiteList && it.url.isNotBlank() }.map { it.id }
        var ok = 0
        for (id in ids) if (runCatching { refreshOne(context, id).ok }.getOrDefault(false)) ok++
        return ok
    }

    /** Пр.136: короткая причина неудачи посева (последняя реальная попытка каскада) — для показа человеку. */
    private fun seedFailReason(cascade: CascadeResult): String {
        val r = cascade.attempts.lastOrNull { !it.skipped && it.result != null }?.result
            ?: return "нет сети или прямой путь к адресу подписки заблокирован (туннеля ещё нет)"
        return when {
            r.exceptionClass != null -> "нет связи с адресом подписки (${r.exceptionClass})"
            else -> "адрес подписки ответил HTTP ${r.httpCode}"
        }
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

    /**
     * Свежая установка: ПУСТО (Промпт 74). Дефолтную подписку добавит [trySeedDefaultSource] из автозапуска —
     * но ТОЛЬКО если её URL реально зафетчится; иначе пусто + «Добавьте вашу подписку». seededDefaultRuBypass
     * оставляем false, чтобы посев повторился на следующем старте, пока не удастся (или пока юзер не добавит своё).
     * (Эволюция: П67 дефолт УБРАЛИ; П72 вернули безусловно; П74 — только по факту успешного фетча.)
     */
    private fun freshDefault(): SourcesFile = SourcesFile(
        migratedLegacy = true,
        seededDefaultRuBypass = false,
        sources = emptyList(),
        servers = emptyList(),
    )

    // ─────────────────────────── чтение ───────────────────────────

    /** Метаданные источников. */
    fun sources(context: Context): List<SubSource> = SubscriptionStore.load(context).sources

    /** Плоский ДЕДУПЛИЦИРОВАННЫЙ список серверов из ВКЛЮЧЁННЫХ источников (для списка/Авто).
     *  Пр.140: в режиме белых списков (whiteListModeActive) — ТОЛЬКО серверы белых источников, независимо от их
     *  enabled-флага (флаги пользователя НЕ трогаем — это эффективный override, а не разрушающее выключение). */
    fun allServers(context: Context): List<ServerProfile> {
        val file = SubscriptionStore.load(context)
        val useIds: Set<String> =
            if (SettingsStore.current().whiteListModeActive)
                file.sources.filter { it.whiteList }.map { it.id }.toSet()
            else
                file.sources.filter { it.enabled }.map { it.id }.toSet()
        return file.servers.filter { rec -> rec.sourceIds.any { it in useIds } }.map { it.profile }
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
     * Пр.137: ЛУЧШИЙ сервер, пригодный для АВТО-восстановления — НЕ в стоп-листе, НЕ на паузе, протокол разрешён.
     * Приоритет: по известной скорости (быстрые первыми), затем по пингу (живые первыми). Нужен, когда
     * «последний сервер» оказался в стоп-листе: авто-подъём (автозапуск / после убийства системой / после
     * перезагрузки) НЕ должен поднимать заблокированный сервер. null — годной замены нет.
     */
    fun bestSavedSelectable(context: Context): ServerProfile? {
        val s = SettingsStore.current()
        val bl = BlocklistStore.current()
        return allServers(context)
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
            .sortedWith(compareByDescending<ServerProfile> { it.speedMbps ?: 0.0 }.thenBy { it.pingMs ?: Int.MAX_VALUE })
            .firstOrNull()
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
            when {
                mbps == null -> rec
                // Провал замера (-1) НЕ затирает прежнюю ВАЛИДНУЮ скорость (Пр.88: не терять данные при массовом
                // провале temp-инстансов). Обновляем измерение только валидным результатом (≥0) или если раньше данных не было.
                mbps < 0 && (rec.profile.speedMbps ?: -1.0) >= 0.0 -> rec
                else -> rec.copy(profile = rec.profile.copy(speedMbps = mbps, speedTestedTs = ts))
            }
        }
        SubscriptionStore.save(context, file.copy(servers = servers))
    }

    // ─────────────────────────── управление источниками ───────────────────────────

    /**
     * Добавить источник по URL (нормализуем, дедуп по ТОЧНОМУ нормализованному url). Возвращает id или null.
     * NB (Промпт 81, уточнение): дубли РАЗНЫХ адресов, ведущих к одному списку (raw.githack.com и свой
     * домен) — НАМЕРЕННАЯ страховка (недоступен один путь — работает другой), их не блокируем и не помечаем.
     * Блокируем лишь ТОЧНОЕ повторное добавление одного и того же url.
     */
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

    /**
     * Удалить источник. Промпт 85: реестр ПЕРЕСОБИРАЕМ из оставшихся источников (по их сырым телам), а НЕ
     * вычитаем записи на месте. Вычитание оставляло осиротевшие профили у пары-страховки (общий сервер
     * числился за второй подпиской, но с данными от удалённой первой → список есть, подключиться нельзя).
     */
    fun remove(context: Context, id: String) {
        val file = SubscriptionStore.load(context)
        val trimmed = file.copy(
            sources = file.sources.filterNot { it.id == id },
            rawBodies = file.rawBodies - id,
        )
        SubscriptionStore.save(context, recount(rebuildRegistry(trimmed)))
    }

    /** Сколько серверов ИСЧЕЗНЕТ при удалении источника (принадлежат только ему). */
    fun serversLostOnRemove(context: Context, id: String): Int =
        SubscriptionStore.load(context).servers.count { it.sourceIds.singleOrNull() == id }

    /**
     * Вкл/выкл источник. Промпт 85: тоже ПЕРЕСОБИРАЕМ реестр (та же болезнь возможна и здесь). Данные
     * выключенного источника НЕ теряются — пересборка идёт из ВСЕХ источников с сырым телом (не только
     * включённых), а фильтр вкл/выкл применяется при ЧТЕНИИ ([allServers]) → включение обратно возвращает
     * полноценные записи без ручного переимпорта.
     */
    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val file = SubscriptionStore.load(context)
        val updated = file.copy(sources = file.sources.map { if (it.id == id) it.copy(enabled = enabled) else it })
        SubscriptionStore.save(context, recount(rebuildRegistry(updated)))
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
            // Терминальный код (Промпт 81.A): каскад ОБОРВАН, маршрут ни при чём. Классифицируем отдельно —
            // 429 это ограничение частоты (не поломка), 401/403/404 — адрес/токен/ресурс. НЕ «перебор ступеней».
            val term = cascade.result?.takeIf { CascadeFetch.isTerminalHttp(it.httpCode) }
            if (term != null) {
                val rateLimited = term.httpCode == 429
                val retry = term.retryAfterSec
                val msg = when (term.httpCode) {
                    429 -> "Панель ограничивает частоту запросов (HTTP 429) — это не блокировка и не поломка " +
                        "подписки. " + (if (retry != null) "Повторите через ~$retry с." else "Повторите через минуту.")
                    401, 403 -> "Доступ к подписке отклонён (HTTP ${term.httpCode}) — проверьте адрес/токен. " +
                        "Смена маршрута не поможет."
                    else -> "Подписка не найдена (HTTP ${term.httpCode}) — проверьте адрес. Смена маршрута не поможет."
                }
                val outcome = if (rateLimited) SourceOutcome.RATE_LIMITED else SourceOutcome.ERROR
                setStatus(context, id, ok = false, outcome = outcome, error = msg, detail = stagesDetail, retryAfter = retry)
                log("  → ТЕРМИНАЛ HTTP ${term.httpCode} (каскад оборван): $msg")
                return RefreshSummary(ok = false, error = msg, outcome = outcome, rateLimited = rateLimited, retryAfterSec = retry)
            }
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
        // Промпт 81.B (уточнён Elyor): НЕ ограничиваем по хосту — хосты у источников разные, но упираются в
        // ОДНУ панель. Просто разносим запросы во времени (пауза между источниками). 429 обрывает ступени
        // ТОЛЬКО для своего источника (81.A); остальные обновляем как обычно — «другой путь» пары-страховки
        // (тот же список серверов иным адресом) может пройти, в этом и смысл.
        for ((i, src) in targets.withIndex()) {
            if (cancelled()) break
            if (i > 0) runCatching { Thread.sleep(BETWEEN_SOURCES_PAUSE_MS) }
            if (cancelled()) break
            val summary = runCatching { refreshOne(context, src.id) }
                .getOrElse { RefreshSummary(ok = false, error = it.message ?: "ошибка") }
            result[src.id] = summary
            onEach(src, summary)
        }
        return result
    }

    private const val BETWEEN_SOURCES_PAUSE_MS = 1200L   // разнос запросов во времени при «Обновить все» (81.B)

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
            // Промпт 85: неудача НЕ вычитает серверы — только статус. Прежние записи целы (сырое тело не трогаем).
            setStatus(context, id, ok = false, error = err, detail = detail)
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
            // Промпт 85: разобрано 0 ссылок — статус-ошибка БЕЗ вычитания серверов и БЕЗ перезаписи сырого тела.
            setStatus(context, id, ok = false, error = err, detail = detail)
            log("  → $err")
            return RefreshSummary(ok = false, added = 0, unsupported = unsupported, invalid = invalid, error = err)
        }

        // Промпт 85: успех — СОХРАНИТЬ сырое тело источника и ПЕРЕСОБРАТЬ реестр из всех тел (а не дописывать
        // записи на месте). Профили всегда парсятся заново из сырья → полны и не зависят от порядка источников.
        storeRawAndRebuild(context, id, body, detail)
        return RefreshSummary(ok = true, added = profiles.size, unsupported = unsupported, invalid = invalid, duplicates = duplicates)
    }

    /** Сохранить сырое тело источника [id] и ПЕРЕСОБРАТЬ реестр (Промпт 85). Статус источника — успех. */
    private fun storeRawAndRebuild(context: Context, id: String, body: String, detail: String?) {
        val file = SubscriptionStore.load(context)
        val withRaw = file.copy(rawBodies = file.rawBodies + (id to body))
        val rebuilt = rebuildRegistry(withRaw)
        val sources = rebuilt.sources.map {
            if (it.id == id) applyStatus(it, true, SourceOutcome.OK, null, detail, null) else it
        }
        SubscriptionStore.save(context, recount(rebuilt.copy(sources = sources)))
    }

    /**
     * ПЕРЕСБОРКА реестра из сырых тел источников (Промпт 85) — единственная точка формирования [servers].
     * Для каждого источника С СЫРЫМ ТЕЛОМ парсим профили заново (полные, независимо от порядка) и склеиваем
     * по serverKey (union членства). Источники БЕЗ сырья (legacy до первого обновления) сохраняют прежние
     * записи. ИЗМЕРЕНИЯ (ping/speed/ts) переносятся по serverKey из прежнего реестра — пересборка их не теряет.
     */
    private fun rebuildRegistry(file: SourcesFile): SourcesFile {
        val meas = HashMap<String, ServerProfile>()
        for (rec in file.servers) meas[serverKey(rec.profile)] = rec.profile

        val byKey = LinkedHashMap<String, ServerRecord>()
        fun add(p: ServerProfile, sid: String) {
            val k = serverKey(p)
            val ex = byKey[k]
            if (ex == null) {
                val m = meas[k]
                val prof = if (m != null) p.copy(
                    pingMs = m.pingMs, lastTestedTs = m.lastTestedTs,
                    speedMbps = m.speedMbps, speedTestedTs = m.speedTestedTs,
                ) else p
                byKey[k] = ServerRecord(prof, listOf(sid))
            } else if (sid !in ex.sourceIds) {
                byKey[k] = ex.copy(sourceIds = ex.sourceIds + sid)
            }
        }

        val validIds = file.sources.map { it.id }.toHashSet()
        val withRaw = HashSet<String>()
        for (src in file.sources) {
            val raw = file.rawBodies[src.id] ?: continue
            withRaw.add(src.id)
            for (p in parseProfiles(raw)) add(p, src.id)
        }
        // Legacy-источники без сырья: сохранить прежние записи (до первого обновления, которое положит сырьё).
        for (rec in file.servers) for (sid in rec.sourceIds) {
            if (sid in withRaw || sid !in validIds) continue
            add(rec.profile, sid)
        }
        return file.copy(servers = byKey.values.toList())
    }

    /** Разбор тела в профили (decode → parse → дедуп внутри источника). Для пересборки реестра (Промпт 85). */
    private fun parseProfiles(body: String): List<ServerProfile> {
        val out = ArrayList<ServerProfile>()
        val seen = HashSet<String>()
        for (line in SubscriptionDecoder.decode(body)) {
            val r = ServerLinkParser.parse(line)
            if (r is ParseResult.Supported && seen.add(serverKey(r.profile))) out.add(r.profile)
        }
        return out
    }

    /** Профиль полон (можно подключиться): есть адрес, порт и учётные данные. Пустой credential = осиротевшая запись. */
    private fun isProfileComplete(p: ServerProfile): Boolean =
        p.address.isNotBlank() && p.port > 0 && p.credential.isNotBlank()

    /**
     * САМОПРОВЕРКА реестра (Промпт 85.E): если есть записи с НЕПОЛНЫМ профилем — ПЕРЕСОБРАТЬ реестр из сырья
     * и записать событие в журнал, а не оставлять пользователю неподключаемый список. Вызывать на старте.
     */
    fun verifyAndHeal(context: Context) {
        val file = SubscriptionStore.load(context)
        val bad = file.servers.count { !isProfileComplete(it.profile) }
        if (bad == 0) return
        val rebuilt = recount(rebuildRegistry(file))
        val after = rebuilt.servers.count { !isProfileComplete(it.profile) }
        SubscriptionStore.save(context, rebuilt)
        runCatching {
            com.picosoft.xrayproxydroid.monitor.MonitorLog.event(
                context, "monitor", "Реестр: неполные профили — пересборка",
                "было $bad, после пересборки $after" + if (after > 0) " (нужно обновить источники)" else "",
            )
        }
        Log.w(TAG, "verifyAndHeal: неполных было $bad, стало $after")
    }

    /**
     * Единая запись статуса источника (Промпт 81.C). Успех обновляет время УДАЧИ (lastOkTs) и ОЧИЩАЕТ
     * lastError; неудача сохраняет прежнее время удачи (ошибка не залипает поверх свежих данных). Время
     * ПОПЫТКИ (lastRefreshTs) обновляется всегда. retryAfterSec держим только для RATE_LIMITED.
     */
    private fun applyStatus(
        s: SubSource, ok: Boolean, outcome: SourceOutcome, error: String?, detail: String?, retryAfter: Int?,
    ): SubSource {
        val ts = now()
        return s.copy(
            lastRefreshTs = ts,
            lastOkTs = if (ok) ts else s.lastOkTs,
            lastOk = ok,
            lastOutcome = outcome,
            lastError = if (ok) null else error,
            lastDetail = detail,
            retryAfterSec = if (outcome == SourceOutcome.RATE_LIMITED) retryAfter else null,
        )
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
    private fun setStatus(
        context: Context, id: String, ok: Boolean, error: String?, detail: String?,
        outcome: SourceOutcome = if (ok) SourceOutcome.OK else SourceOutcome.ERROR, retryAfter: Int? = null,
    ) {
        val file = SubscriptionStore.load(context)
        SubscriptionStore.save(context, file.copy(sources = file.sources.map {
            if (it.id == id) applyStatus(it, ok, outcome, error, detail, retryAfter) else it
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
