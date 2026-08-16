package com.picosoft.xrayproxydroid.update

import android.content.Context
import android.os.Build
import android.util.Log
import com.picosoft.xrayproxydroid.BuildConfig
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import kotlinx.serialization.json.Json

/**
 * Проверка обновления (Промпт 70). БЛОКИРУЮЩАЯ — вызывать в фоновом потоке.
 *
 * Порядок: releases/latest (через каскад) → найти вложение update.json → скачать (через каскад) →
 * сравнить versionCode. Запасной путь без update.json — по метке v0.11 (менее надёжно). Всё
 * скачивание через [CascadeFetch] (GitHub из РФ нестабилен, туннель обычно поднят). Трафик проверки —
 * в поток «Тест» вкладки трафика.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    const val OWNER = "pico-soft"
    const val REPO = "XrayProxyDroid"
    private const val API_LATEST = "https://api.github.com/repos/pico-soft/XrayProxyDroid/releases/latest"
    const val UA = "XrayProxyDroid-Updater"
    private const val MANIFEST_NAME = "update.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun check(context: Context): UpdateCheckResult {
        val s = SettingsStore.current()
        val directT = s.subTimeoutSec * 1000
        val proxyT = s.subTimeoutSec * 1000 + 10_000
        val totalT = directT + proxyT + 5_000

        // 1 — метаданные последнего релиза (несколько КБ) через каскад.
        val rel = CascadeFetch.fetch(context, API_LATEST, UA, directT, proxyT, totalT,
            acceptBody = { it.ok && it.body.isNotBlank() })
        rel.result?.let { if (it.bodyBytes > 0) TrafficTracker.addTest(it.bodyBytes.toLong()) }

        if (!rel.ok) {
            // 404 = релизов ещё нет (это НЕ ошибка); иначе GitHub недоступен.
            val got404 = rel.attempts.any { it.result?.httpCode == 404 }
            if (got404) return UpdateCheckResult.NoReleases
            val detail = rel.attempts.filterNot { it.skipped }
                .joinToString("; ") { a -> a.stage.label + ": " + (a.result?.let { "HTTP ${it.httpCode}" } ?: "нет ответа") }
            return UpdateCheckResult.Error(UpdateErrorKind.API_UNAVAILABLE, detail)
        }

        val release = try {
            json.decodeFromString<GithubRelease>(rel.result!!.body)
        } catch (e: Exception) {
            Log.w(TAG, "release json parse failed", e)
            return UpdateCheckResult.Error(UpdateErrorKind.API_UNAVAILABLE, "не разобрать ответ GitHub")
        }

        // 2 — вложение update.json (ТОЧНЫЕ данные).
        val manifestAsset = release.assets.firstOrNull { it.name.equals(MANIFEST_NAME, ignoreCase = true) }
        if (manifestAsset == null || manifestAsset.browserDownloadUrl.isBlank()) {
            // Запасной путь — по метке релиза (менее надёжно).
            return fallbackByTag(release)
        }

        val mf = CascadeFetch.fetch(context, manifestAsset.browserDownloadUrl, UA, directT, proxyT, totalT,
            acceptBody = { it.ok && it.body.isNotBlank() })
        mf.result?.let { if (it.bodyBytes > 0) TrafficTracker.addTest(it.bodyBytes.toLong()) }
        if (!mf.ok) return UpdateCheckResult.Error(UpdateErrorKind.API_UNAVAILABLE, "update.json не скачался")

        val manifest = try {
            json.decodeFromString<UpdateManifest>(mf.result!!.body)
        } catch (e: Exception) {
            Log.w(TAG, "update.json parse failed", e)
            return UpdateCheckResult.Error(UpdateErrorKind.MANIFEST_PARSE)
        }

        return compareByManifest(manifest, release)
    }

    /** Сравнение по versionCode из update.json + выбор сборки под ABI устройства. */
    private fun compareByManifest(manifest: UpdateManifest, release: GithubRelease): UpdateCheckResult {
        val current = BuildConfig.VERSION_CODE
        val latestName = manifest.versionName.ifBlank { release.name.ifBlank { release.tagName } }
        if (manifest.versionCode <= current) return UpdateCheckResult.UpToDate(current, latestName)

        // Первая поддерживаемая архитектура устройства → её сборка; иначе универсальная.
        val exact = Build.SUPPORTED_ABIS?.firstNotNullOfOrNull { abi ->
            manifest.artifacts.firstOrNull { it.abi.equals(abi, ignoreCase = true) }
        }
        val universal = manifest.artifacts.firstOrNull { it.abi.equals("universal", ignoreCase = true) }
        val artifact = exact ?: universal
        if (artifact == null || artifact.fileName.isBlank())
            return UpdateCheckResult.Error(UpdateErrorKind.NO_ARTIFACT)

        // URL берём из вложений релиза по имени файла (API отдаёт browser_download_url).
        val asset = release.assets.firstOrNull { it.name == artifact.fileName }
        if (asset == null || asset.browserDownloadUrl.isBlank())
            return UpdateCheckResult.Error(UpdateErrorKind.ASSET_MISSING, artifact.fileName)

        return UpdateCheckResult.Available(
            versionCode = manifest.versionCode,
            versionName = latestName,
            notes = manifest.notes.ifBlank { release.body },
            artifact = artifact,
            downloadUrl = asset.browserDownloadUrl,
            sizeBytes = if (asset.size > 0) asset.size else -1,
            usingUniversal = exact == null,
        )
    }

    /**
     * Запасной путь: update.json нет — разбираем метку (v0.11) и сравниваем числа с нашей versionName.
     * Новее? Сообщаем как НЕНАДЁЖНОЕ (нет SHA-256/имени файла → скачивать и ставить нельзя). Не новее —
     * считаем, что стоит последняя (но помечать в UI, что update.json не найден).
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
            UpdateCheckResult.UpToDate(BuildConfig.VERSION_CODE, release.name.ifBlank { release.tagName })
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
