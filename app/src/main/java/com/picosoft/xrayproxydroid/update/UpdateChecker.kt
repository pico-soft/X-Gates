package com.picosoft.xrayproxydroid.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.picosoft.xrayproxydroid.BuildConfig
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.net.CascadeResult
import com.picosoft.xrayproxydroid.net.FetchStage
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionFetcher
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.xray.XrayConfig
import kotlinx.serialization.json.Json
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * Проверка обновления (Промпт 70, приборы+запасной путь — Промпт 76). БЛОКИРУЮЩАЯ — вызывать в фоне.
 *
 * Порядок: API releases/latest (через каскад) → вложение update.json (через каскад) → сравнить versionCode.
 * ЗАПАСНОЙ путь (76.E): если API недоступен ИЛИ вложение не скачалось — тянем update.json НАПРЯМУЮ по
 * предсказуемому адресу `releases/latest/download/update.json` (без API), APK-адреса строим как
 * `releases/latest/download/<файл>`. Порядок: сначала API (точнее), при неудаче — запасной.
 *
 * ПРИБОРЫ (76.B): каждый запрос логируется (тег UpdateChecker, гейт «Подробные логи») — итоговый host,
 * ступень каскада, HTTP-код, размер, класс исключения, цепочка редиректов; ошибка в UI типизирована
 * (API недоступен / вложение не скачалось / не разобрался ответ), а не «GitHub недоступен» в одну кучу.
 *
 * ВАЖНО (76.A): браузер ходит на github.com (HTML), а мы — на api.github.com и на CDN вложений
 * (objects.githubusercontent.com по редиректу). Это РАЗНЫЕ узлы, блокируются раздельно.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    const val OWNER = "pico-soft"
    const val UA = "X-Gates-Updater"
    private const val MANIFEST_NAME = "update.json"

    /** Один адрес репозитория и его URL-ы (API / запасной без API). Промпт 121.B. */
    data class Repo(val owner: String, val repo: String) {
        val slug: String get() = "$owner/$repo"
        val apiLatest: String get() = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val latestDownload: String get() = "https://github.com/$owner/$repo/releases/latest/download"
    }

    /**
     * СПИСОК АДРЕСОВ (Промпт 121.B): сначала НОВЫЙ (репозиторий переименован в X-Gates), при неудаче —
     * СТАРЫЙ. Старый через редирект GitHub тоже ведёт на новый; держим его ЗАПАСНЫМ, пока у всех не будет
     * версии со списком (уберём отдельной задачей). Неудача первого адреса — НЕ ошибка, просто переходим
     * ко второму; каждый идёт через полный каскад. Касается всех трёх путей: API, манифест, APK.
     */
    val REPOS = listOf(
        Repo("pico-soft", "X-Gates"),
        Repo("pico-soft", "XrayProxyDroid"),
    )

    /**
     * Сколько ждать подъёма СВОЕГО SOCKS перед АВТО-проверкой на холодном старте (Промпт 80.B). Автозапуск
     * коннектится не мгновенно; на сети с заблокированным напрямую GitHub проверка без туннеля обречена,
     * поэтому авто-проверка ждёт порт, а не уходит впустую напрямую. Единственное место с этой величиной —
     * ручной кнопке ожидание не нужно. Раньше было 60с (Промпт 77) — избыточно для необязательной проверки.
     */
    const val AUTO_CHECK_PROXY_WAIT_MS = 20_000

    // Пр.135: для РУЧНОЙ проверки разрешаем ступень 6 (temp-инстанс) на запрос СВЕДЕНИЙ (API/манифест) —
    // иначе при заблокированном напрямую api.github.com и невключённом туннеле обновиться нельзя вовсе.
    // Манифест весит килобайты, полный перебор не нужен: 2 кандидата, короткий таймаут на каждого.
    private const val UPDATE_TEMP_CANDIDATES = 2
    private const val UPDATE_TEMP_TIMEOUT_MS = 12_000

    // Три РАЗНЫХ узла GitHub, блокируются в РФ раздельно (Промпт 76/80.C). Доступность одного ничего не
    // говорит про остальные, поэтому в подробностях показываем результат по каждому отдельно.
    private const val NODE_API = "https://api.github.com/"
    private const val NODE_WEB = "https://github.com/robots.txt"
    private val NODE_CDN = "${REPOS.first().latestDownload}/$MANIFEST_NAME"   // github.com → редирект на release-assets.githubusercontent.com
    private const val NODE_PROBE_TIMEOUT_MS = 4000   // короткий: проба узла для диагностики, не тянем контент

    private val json = Json { ignoreUnknownKeys = true }

    private fun host(url: String): String = runCatching { java.net.URL(url).host }.getOrNull() ?: url

    /** Подробный разбор попыток каскада: ступень, HTTP/исключение, host, размер, редиректы. */
    private fun diag(cascade: CascadeResult): String = cascade.attempts.joinToString("\n") { a ->
        val r = a.result
        val body = when {
            a.skipped -> "пропущено (${a.note})"
            r == null -> "нет ответа"
            r.exceptionClass != null -> "${r.exceptionClass}: ${r.errorMessage} · host=${host(r.finalUrl)}"
            else -> "HTTP ${r.httpCode} · ${r.bodyBytes}б · host=${host(r.finalUrl)}" + if (a.accepted) " ✓" else ""
        }
        val redir = r?.redirectChain?.takeIf { it.size > 1 }
            ?.let { " · редиректы: ${it.joinToString(" → ") { u -> host(u) }}" } ?: ""
        "  • ${a.stage.label}: $body$redir"
    }

    /** Пр.135.C: сведения получены через temp-инстанс (обошли заблокированный напрямую GitHub) — показать это. */
    private fun tempNote(c: CascadeResult): String =
        if (c.stage == FetchStage.TEMP_RECENT) " · через запасной сервер (GitHub напрямую недоступен)" else ""

    /** КОНЕЧНЫЙ host, до которого не дошли (последняя реальная попытка), + причина — для сводки (77.C). */
    private fun lastHost(cascade: CascadeResult): String {
        val a = cascade.attempts.lastOrNull { !it.skipped && it.result != null } ?: return "нет попыток"
        val r = a.result!!
        val why = r.exceptionClass ?: "HTTP ${r.httpCode}"
        return "${host(r.finalUrl)} ($why)"
    }

    /** [manual] — РУЧНОЕ нажатие «Проверить обновление»/тап баннера: ходим по ВСЕМ ступеням, включая temp-инстанс
     *  (Пр.135). Авто-проверка на старте — [manual]=false: только дешёвые ступени, при неудаче молча ждём. */
    fun check(context: Context, manual: Boolean = false): CheckReport {
        val s = SettingsStore.current()
        val verbose = s.verboseLogs
        val directT = s.subTimeoutSec * 1000
        val proxyT = s.subTimeoutSec * 1000 + 10_000
        // При ручной проверке даём бюджет ещё и на temp-ступень (2 кандидата × короткий таймаут).
        val totalT = directT + proxyT + 5_000 + (if (manual) UPDATE_TEMP_CANDIDATES * UPDATE_TEMP_TIMEOUT_MS else 0)
        val det = StringBuilder()
        fun addTest(c: CascadeResult) { c.result?.let { if (it.bodyBytes > 0) TrafficTracker.addTest(it.bodyBytes.toLong()) } }
        // Один запрос обновления = один блок диагностики (лог + подробности UI). proxyFirst=true (77.B).
        fun request(label: String, url: String): CascadeResult {
            // Пр.135 (отменяет 81.E): для РУЧНОЙ проверки temp-инстанс РАЗРЕШЁН — при заблокированном напрямую
            // GitHub и невключённом туннеле иначе не обновиться вовсе. Манифест килобайтный → ограничиваем
            // 2 кандидатами по 12с (не превращаем в минутное ожидание). Свой SOCKS первым (77.B), при
            // поднятом туннеле порядок не меняем; терминальные коды (429/40x) обрывают каскад (81.A).
            val c = CascadeFetch.fetch(context, url, UA, directT, proxyT, totalT,
                acceptBody = { it.ok && it.body.isNotBlank() }, proxyFirst = true,
                allowTempInstance = manual, maxTempCandidates = UPDATE_TEMP_CANDIDATES, tempTimeoutMs = UPDATE_TEMP_TIMEOUT_MS)
            addTest(c)
            val d = diag(c)
            det.append(label).append("\n").append(d).append("\n\n")
            if (verbose) Log.i(TAG, "$label\n$d")
            return c
        }

        if (verbose) Log.i(TAG, "──────── проверка обновления ────────")

        // Проверка ОДНОГО адреса: API → вложение update.json → запасной адрес без API. Repo-aware.
        fun checkRepo(repo: Repo): UpdateCheckResult {
            // ── 1. API releases/latest ──
            val rel = request("API ${repo.apiLatest} (UA=$UA)", repo.apiLatest)
            if (rel.attempts.any { it.result?.httpCode == 404 }) return UpdateCheckResult.NoReleases

            if (rel.ok) {
                val release = runCatching { json.decodeFromString<GithubRelease>(rel.result!!.body) }.getOrNull()
                if (release != null) {
                    val manifestAsset = release.assets.firstOrNull { it.name.equals(MANIFEST_NAME, ignoreCase = true) }
                    if (manifestAsset == null || manifestAsset.browserDownloadUrl.isBlank()) {
                        det.append("В релизе нет вложения update.json → путь по метке ${release.tagName}\n\n")
                        return fallbackByTag(release)
                    }
                    val mf = request("update.json ЧЕРЕЗ API: ${manifestAsset.browserDownloadUrl}", manifestAsset.browserDownloadUrl)
                    if (mf.ok) {
                        val manifest = runCatching { json.decodeFromString<UpdateManifest>(mf.result!!.body) }.getOrNull()
                            ?: return UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_PARSE, "вложение скачано, но JSON не разобран")
                        return compareByManifest(manifest, release, repo, via = "через API · ${repo.slug}${tempNote(mf)}",
                            assetUrl = { fn -> release.assets.firstOrNull { it.name == fn }?.browserDownloadUrl })
                    }
                    // вложение не скачалось → пробуем запасной адрес ниже
                } else det.append("API: 200, но JSON не разобран\n\n")
            }

            // ── 2. ЗАПАСНОЙ адрес (76.E/77.C): update.json напрямую, БЕЗ API (тот же CDN — не панацея) ──
            val fbUrl = "${repo.latestDownload}/$MANIFEST_NAME"
            val fb = request("update.json ЗАПАСНОЙ (без API): $fbUrl", fbUrl)
            if (fb.attempts.any { it.result?.httpCode == 404 }) return UpdateCheckResult.NoReleases
            if (fb.ok) {
                val manifest = runCatching { json.decodeFromString<UpdateManifest>(fb.result!!.body) }.getOrNull()
                    ?: return UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_PARSE, "запасной скачан, но JSON не разобран")
                return compareByManifest(manifest, release = null, repo, via = "через запасной адрес · ${repo.slug}${tempNote(fb)}",
                    assetUrl = { fn -> "${repo.latestDownload}/$fn" })
            }

            // ── всё не вышло → типизированная ошибка (77.C / 80.C) ──
            return when {
                // Прямой путь к GitHub заблокирован, а свой SOCKS не слушает = НЕ «GitHub недоступен»
                // (узел жив, недоступен ПУТЬ). Нормально для приложения, которое туннель и даёт: подключить
                // и повторить. Условие проверяем по факту порта, не по флагу (77.A). В деталь берём узел,
                // который РЕАЛЬНО не пустил (загрузка вложения = CDN), а не API: тот мог ответить 200, но
                // вложение всё равно качается с заблокированного CDN — иначе деталь противоречит («не дошли,
                // но HTTP 200»). Полный разбор по трём узлам — ниже в probeNodes().
                !CascadeFetch.isOwnProxyUp() ->
                    UpdateCheckResult.Error(UpdateErrorKind.NO_TUNNEL, "напрямую не дошли до вложения: ${lastHost(fb)}")
                !rel.ok ->
                    UpdateCheckResult.Error(UpdateErrorKind.API_UNAVAILABLE, "не дошли: API=${lastHost(rel)}; запасной=${lastHost(fb)}")
                else ->
                    UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_DOWNLOAD_FAILED, "не дошли: вложение=${lastHost(fb)}")
            }
        }

        // ── СПИСОК АДРЕСОВ (121.B): по очереди новый → старый. Available/UpToDate/AvailableUnverified —
        // определённый НАШ ответ (стоп). NoReleases/Error (в т.ч. чужой релиз) — не ошибка, пробуем следующий.
        val result: UpdateCheckResult = run {
            var last: UpdateCheckResult = UpdateCheckResult.Error(UpdateErrorKind.API_UNAVAILABLE, "адреса не пробованы")
            for ((idx, repo) in REPOS.withIndex()) {
                if (REPOS.size > 1) det.append("═══ Адрес ${idx + 1}/${REPOS.size}: ${repo.slug} ═══\n")
                val r = checkRepo(repo)
                if (r is UpdateCheckResult.Available || r is UpdateCheckResult.UpToDate || r is UpdateCheckResult.AvailableUnverified)
                    return@run r
                last = r
                val why = (r as? UpdateCheckResult.Error)?.kind?.name ?: r::class.simpleName ?: "нет результата"
                if (idx < REPOS.lastIndex) det.append("→ адрес ${repo.slug}: $why — пробую следующий\n\n")
            }
            last
        }

        // Разбор по ТРЁМ узлам GitHub раздельно (80.C) — только на неуспехе (диагностика; happy-path не грузим).
        if (result is UpdateCheckResult.Error)
            det.append("\n").append(probeNodes()).append("\n")

        return CheckReport(result, det.toString().trim())
    }

    /**
     * Три РАЗНЫХ узла GitHub раздельно (80.C): по каждому — попытка напрямую и (если SOCKS слушает) через
     * свой прокси, короткий таймаут. Показывает то же, что Elyor руками через curl: прямой путь к узлу
     * заблокирован, а через туннель — открыт. Итоговый host из finalUrl (для CDN — release-assets после
     * редиректа с github.com). Только для «Подробностей» на неуспехе.
     */
    private fun probeNodes(): String {
        val socksUp = CascadeFetch.isOwnProxyUp()
        val nodes = listOf(
            "api.github.com" to NODE_API,
            "github.com" to NODE_WEB,
            "release-assets.githubusercontent.com" to NODE_CDN,
        )
        val sb = StringBuilder("Узлы GitHub (блокируются раздельно):\n")
        for ((label, url) in nodes) {
            val direct = probeOne(url, direct = true)
            val viaProxy = if (socksUp) probeOne(url, direct = false) else "прокси не запущен"
            sb.append("  • $label — напрямую: $direct · через прокси: $viaProxy\n")
        }
        return sb.toString().trimEnd()
    }

    private fun probeOne(url: String, direct: Boolean): String {
        val open: (java.net.URL) -> java.net.URLConnection =
            if (direct) { { it.openConnection() } }
            else { { it.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT))) } }
        val r = SubscriptionFetcher.fetch(url, UA, NODE_PROBE_TIMEOUT_MS, open)
        val finalHost = host(r.finalUrl)
        return if (r.exceptionClass != null) r.exceptionClass!!
        else "HTTP ${r.httpCode}" + (if (finalHost.isNotBlank() && !url.contains(finalHost)) " ($finalHost)" else "")
    }

    /**
     * Сравнение по versionCode из update.json + выбор сборки под ABI. [assetUrl] отдаёт адрес APK по имени
     * файла (через API — browser_download_url; запасной — releases/latest/download/<файл>). [release] может
     * быть null (запасной путь) — тогда размер вложения неизвестен, versionName/notes берём из манифеста.
     */
    private fun compareByManifest(
        manifest: UpdateManifest,
        release: GithubRelease?,
        repo: Repo,
        via: String,
        assetUrl: (String) -> String?,
    ): UpdateCheckResult {
        // Промпт 121.C: релиз НАШ? Имя пакета из манифеста сверяем ДО скачивания APK (при редиректах/смене
        // имени легко попасть не туда). Пусто = старый манифест без поля → проверку ПРОПУСКАЕМ (полагаемся
        // на подпись — последний рубеж), чтобы не сломать переход.
        if (manifest.packageName.isNotBlank() && manifest.packageName != BuildConfig.APPLICATION_ID)
            return UpdateCheckResult.Error(UpdateErrorKind.FOREIGN_RELEASE,
                "манифест указывает ${manifest.packageName}, наш ${BuildConfig.APPLICATION_ID}")

        val current = BuildConfig.VERSION_CODE
        val latestName = manifest.versionName.ifBlank { release?.let { it.name.ifBlank { it.tagName } } ?: "" }
        // Промпт 121.C: строго БОЛЬШЕ установленного (не «отличается») — иначе редирект на старый репозиторий
        // мог бы предложить ОТКАТ на прежнюю версию. versionCode == current и < current → «последняя».
        if (manifest.versionCode <= current) return UpdateCheckResult.UpToDate(current, latestName, via)

        val exact = Build.SUPPORTED_ABIS?.firstNotNullOfOrNull { abi ->
            manifest.artifacts.firstOrNull { it.abi.equals(abi, ignoreCase = true) }
        }
        val universal = manifest.artifacts.firstOrNull { it.abi.equals("universal", ignoreCase = true) }
        val artifact = exact ?: universal
        if (artifact == null || artifact.fileName.isBlank())
            return UpdateCheckResult.Error(UpdateErrorKind.NO_ARTIFACT)

        // Промпт 121.B: адреса скачивания APK по порядку — сначала с текущего адреса (API browser_download_url
        // или releases/latest/download этого repo), затем releases/latest/download каждого адреса из списка.
        val urls = buildList {
            assetUrl(artifact.fileName)?.takeIf { it.isNotBlank() }?.let { add(it) }
            for (r in REPOS) add("${r.latestDownload}/${artifact.fileName}")
        }.distinct()
        if (urls.isEmpty())
            return UpdateCheckResult.Error(UpdateErrorKind.ASSET_MISSING, artifact.fileName)
        val size = release?.assets?.firstOrNull { it.name == artifact.fileName }?.size?.takeIf { it > 0 } ?: -1

        return UpdateCheckResult.Available(
            versionCode = manifest.versionCode,
            versionName = latestName,
            notes = manifest.notes.ifBlank { release?.body ?: "" },
            artifact = artifact,
            downloadUrls = urls,
            sizeBytes = size,
            usingUniversal = exact == null,
            via = via,
        )
    }

    /**
     * Запасной путь: update.json нет — разбираем метку (v0.12) и сравниваем числа с нашей versionName.
     * Новее? Сообщаем как НЕНАДЁЖНОЕ (нет SHA-256/имени файла → скачивать и ставить нельзя). Не новее —
     * считаем, что стоит последняя.
     */
    private fun fallbackByTag(release: GithubRelease): UpdateCheckResult {
        val tagTuple = versionTuple(release.tagName)
        val curTuple = versionTuple(BuildConfig.VERSION_NAME)
        return if (compareTuples(tagTuple, curTuple) > 0)
            UpdateCheckResult.AvailableUnverified(
                tag = release.tagName,
                name = release.name.ifBlank { release.tagName },
                notes = release.body,
            )
        else
            UpdateCheckResult.UpToDate(BuildConfig.VERSION_CODE, release.name.ifBlank { release.tagName }, via = "по метке (без update.json)")
    }

    /** Все числа из строки версии по порядку: "v0.11 beta" → [0, 11], "1.2.3" → [1, 2, 3]. */
    private fun versionTuple(s: String): List<Int> =
        Regex("\\d+").findAll(s).mapNotNull { it.value.toIntOrNull() }.toList()

    private fun compareTuples(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }
}
