package com.picosoft.xrayproxydroid.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.picosoft.xrayproxydroid.BuildConfig
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.net.CascadeResult
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import kotlinx.serialization.json.Json

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
    const val REPO = "XrayProxyDroid"
    private const val API_LATEST = "https://api.github.com/repos/pico-soft/XrayProxyDroid/releases/latest"
    // Запасной адрес без API: всегда ведёт на последний релиз (github.com → редирект на CDN вложения).
    private const val LATEST_DOWNLOAD = "https://github.com/pico-soft/XrayProxyDroid/releases/latest/download"
    const val UA = "XrayProxyDroid-Updater"
    private const val MANIFEST_NAME = "update.json"

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

    /** КОНЕЧНЫЙ host, до которого не дошли (последняя реальная попытка), + причина — для сводки (77.C). */
    private fun lastHost(cascade: CascadeResult): String {
        val a = cascade.attempts.lastOrNull { !it.skipped && it.result != null } ?: return "нет попыток"
        val r = a.result!!
        val why = r.exceptionClass ?: "HTTP ${r.httpCode}"
        return "${host(r.finalUrl)} ($why)"
    }

    fun check(context: Context): CheckReport {
        val s = SettingsStore.current()
        val verbose = s.verboseLogs
        val directT = s.subTimeoutSec * 1000
        val proxyT = s.subTimeoutSec * 1000 + 10_000
        val totalT = directT + proxyT + 5_000
        val det = StringBuilder()
        fun addTest(c: CascadeResult) { c.result?.let { if (it.bodyBytes > 0) TrafficTracker.addTest(it.bodyBytes.toLong()) } }
        // Один запрос обновления = один блок диагностики (лог + подробности UI). proxyFirst=true (77.B).
        fun request(label: String, url: String): CascadeResult {
            val c = CascadeFetch.fetch(context, url, UA, directT, proxyT, totalT,
                acceptBody = { it.ok && it.body.isNotBlank() }, proxyFirst = true)
            addTest(c)
            val d = diag(c)
            det.append(label).append("\n").append(d).append("\n\n")
            if (verbose) Log.i(TAG, "$label\n$d")
            return c
        }

        if (verbose) Log.i(TAG, "──────── проверка обновления ────────")

        val result: UpdateCheckResult = run {
            // ── 1. API releases/latest ──
            val rel = request("API $API_LATEST (UA=$UA)", API_LATEST)
            if (rel.attempts.any { it.result?.httpCode == 404 }) return@run UpdateCheckResult.NoReleases

            if (rel.ok) {
                val release = runCatching { json.decodeFromString<GithubRelease>(rel.result!!.body) }.getOrNull()
                if (release != null) {
                    val manifestAsset = release.assets.firstOrNull { it.name.equals(MANIFEST_NAME, ignoreCase = true) }
                    if (manifestAsset == null || manifestAsset.browserDownloadUrl.isBlank()) {
                        det.append("В релизе нет вложения update.json → путь по метке ${release.tagName}\n\n")
                        return@run fallbackByTag(release)
                    }
                    val mf = request("update.json ЧЕРЕЗ API: ${manifestAsset.browserDownloadUrl}", manifestAsset.browserDownloadUrl)
                    if (mf.ok) {
                        val manifest = runCatching { json.decodeFromString<UpdateManifest>(mf.result!!.body) }.getOrNull()
                            ?: return@run UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_PARSE, "вложение скачано, но JSON не разобран")
                        return@run compareByManifest(manifest, release, via = "через API",
                            assetUrl = { fn -> release.assets.firstOrNull { it.name == fn }?.browserDownloadUrl })
                    }
                    // вложение не скачалось → пробуем запасной адрес ниже
                } else det.append("API: 200, но JSON не разобран\n\n")
            }

            // ── 2. ЗАПАСНОЙ адрес (76.E/77.C): update.json напрямую, БЕЗ API (тот же CDN — не панацея) ──
            val fbUrl = "$LATEST_DOWNLOAD/$MANIFEST_NAME"
            val fb = request("update.json ЗАПАСНОЙ (без API): $fbUrl", fbUrl)
            if (fb.attempts.any { it.result?.httpCode == 404 }) return@run UpdateCheckResult.NoReleases
            if (fb.ok) {
                val manifest = runCatching { json.decodeFromString<UpdateManifest>(fb.result!!.body) }.getOrNull()
                    ?: return@run UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_PARSE, "запасной скачан, но JSON не разобран")
                return@run compareByManifest(manifest, release = null, via = "через запасной адрес",
                    assetUrl = { fn -> "$LATEST_DOWNLOAD/$fn" })
            }

            // ── всё не вышло → типизированная ошибка с КОНЕЧНЫМ хостом (77.C) ──
            if (!rel.ok)
                UpdateCheckResult.Error(UpdateErrorKind.API_UNAVAILABLE, "не дошли: API=${lastHost(rel)}; запасной=${lastHost(fb)}")
            else
                UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_DOWNLOAD_FAILED, "не дошли: вложение=${lastHost(fb)}")
        }

        return CheckReport(result, det.toString().trim())
    }

    /**
     * Сравнение по versionCode из update.json + выбор сборки под ABI. [assetUrl] отдаёт адрес APK по имени
     * файла (через API — browser_download_url; запасной — releases/latest/download/<файл>). [release] может
     * быть null (запасной путь) — тогда размер вложения неизвестен, versionName/notes берём из манифеста.
     */
    private fun compareByManifest(
        manifest: UpdateManifest,
        release: GithubRelease?,
        via: String,
        assetUrl: (String) -> String?,
    ): UpdateCheckResult {
        val current = BuildConfig.VERSION_CODE
        val latestName = manifest.versionName.ifBlank { release?.let { it.name.ifBlank { it.tagName } } ?: "" }
        if (manifest.versionCode <= current) return UpdateCheckResult.UpToDate(current, latestName, via)

        val exact = Build.SUPPORTED_ABIS?.firstNotNullOfOrNull { abi ->
            manifest.artifacts.firstOrNull { it.abi.equals(abi, ignoreCase = true) }
        }
        val universal = manifest.artifacts.firstOrNull { it.abi.equals("universal", ignoreCase = true) }
        val artifact = exact ?: universal
        if (artifact == null || artifact.fileName.isBlank())
            return UpdateCheckResult.Error(UpdateErrorKind.NO_ARTIFACT)

        val url = assetUrl(artifact.fileName)
        if (url.isNullOrBlank())
            return UpdateCheckResult.Error(UpdateErrorKind.ASSET_MISSING, artifact.fileName)
        val size = release?.assets?.firstOrNull { it.name == artifact.fileName }?.size?.takeIf { it > 0 } ?: -1

        return UpdateCheckResult.Available(
            versionCode = manifest.versionCode,
            versionName = latestName,
            notes = manifest.notes.ifBlank { release?.body ?: "" },
            artifact = artifact,
            downloadUrl = url,
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
