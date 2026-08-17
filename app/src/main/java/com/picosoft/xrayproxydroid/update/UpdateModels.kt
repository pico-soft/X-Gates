package com.picosoft.xrayproxydroid.update

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модели проверки обновления (Промпт 70).
 *
 * ГЛАВНОЕ РЕШЕНИЕ: сравниваем версии ТОЛЬКО по [UpdateManifest.versionCode] из update.json, вложенного
 * в релиз. Публичный API GitHub versionCode не отдаёт (только строку tag_name/name), а строку версии
 * сравнивать ненадёжно. update.json — источник ТОЧНЫХ данных (versionCode + имя файла + SHA-256 по
 * каждой архитектуре). Имена вложений (…-arm64-v8a.apk и т.п.) — КОНТРАКТ с обновлялкой, менять нельзя.
 */

// ─────────────────────── ответ GitHub API (releases/latest) ───────────────────────
// Публичный API. Черновые релизы (draft) API не отдаёт — учитывать не нужно. Пререлизы latest тоже
// не отдаёт (поэтому первый релиз 0.11 надо публиковать полным, не prerelease).

@Serializable
data class GithubAsset(
    val name: String = "",
    @SerialName("browser_download_url") val browserDownloadUrl: String = "",
    val size: Long = 0,
)

@Serializable
data class GithubRelease(
    @SerialName("tag_name") val tagName: String = "",
    val name: String = "",
    val body: String = "",           // описание релиза (человекочитаемое, запасное)
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GithubAsset> = emptyList(),
)

// ─────────────────────── update.json (вложение релиза, ТОЧНЫЕ данные) ───────────────────────

@Serializable
data class UpdateArtifact(
    val abi: String = "",        // "arm64-v8a" | "armeabi-v7a" | "x86_64" | "universal"
    val fileName: String = "",   // имя вложения релиза — контракт
    val sha256: String = "",     // hex, нижний/верхний регистр — сверяем без учёта регистра
)

@Serializable
data class UpdateManifest(
    val versionCode: Int = 0,
    val versionName: String = "",
    val notes: String = "",                       // краткое описание изменений
    val artifacts: List<UpdateArtifact> = emptyList(),
)

// ─────────────────────── типизированные исходы (как у подписок) ───────────────────────

/** Типы ошибок — «никаких общих ‘ошибка’». Текст — готовое сообщение пользователю. */
enum class UpdateErrorKind(val text: String) {
    API_UNAVAILABLE("GitHub API недоступен — не удалось получить сведения о релизах"),
    MANIFEST_DOWNLOAD_FAILED("сведения получены, но файл update.json не скачался (вложение недоступно)"),
    MANIFEST_MISSING("в релизе нет файла update.json"),
    MANIFEST_PARSE("файл update.json повреждён или в неизвестном формате"),
    NO_ARTIFACT("в update.json нет сборки под вашу архитектуру и нет универсальной"),
    ASSET_MISSING("сборка указана в update.json, но отсутствует среди вложений релиза"),
    DOWNLOAD_FAILED("не удалось скачать файл обновления"),
    CHECKSUM_MISMATCH("контрольная сумма не совпала — файл повреждён или подменён"),
    SIGNATURE_MISMATCH("подпись файла не совпадает с установленным приложением"),
    SIGNATURE_DEBUG("у вас отладочная сборка — релизный APK поверх неё не встанет"),
    NO_INSTALL_PERMISSION("нет разрешения на установку приложений"),
    CANCELLED("отменено"),
}

sealed interface UpdateCheckResult {
    /** Релизов ещё нет (API вернул 404) — это НЕ ошибка. */
    object NoReleases : UpdateCheckResult

    /** Установлена последняя версия. [via] — каким путём получены сведения (API / запасной). */
    data class UpToDate(val currentCode: Int, val latestName: String, val via: String = "") : UpdateCheckResult

    /** Есть обновление с подтверждённым update.json — можно скачать и проверить. */
    data class Available(
        val versionCode: Int,
        val versionName: String,
        val notes: String,
        val artifact: UpdateArtifact,
        val downloadUrl: String,
        val sizeBytes: Long,
        val usingUniversal: Boolean,   // точной сборки под ABI нет → универсальная
        val via: String = "",          // каким путём получены сведения (API / запасной)
    ) : UpdateCheckResult

    /**
     * По метке релиза (v0.11) версия НОВЕЕ, но update.json нет → скачать/проверить надёжно нельзя
     * (нет SHA-256 и имени файла). Только сообщаем (менее надёжный путь, запасной).
     */
    data class AvailableUnverified(val tag: String, val name: String, val notes: String) : UpdateCheckResult

    data class Error(val kind: UpdateErrorKind, val detail: String = "") : UpdateCheckResult
}

/**
 * Итог проверки: результат + ПОЛНАЯ постадийная диагностика (по каждому адресу — какие ступени каскада
 * выполнялись/пропущены и почему, код, размер, исключение, КОНЕЧНЫЙ host, цепочка редиректов). Показывается
 * в UI под «Подробности» (Промпт 77.E), чтобы разбирательство занимало одно нажатие.
 */
data class CheckReport(val result: UpdateCheckResult, val details: String)
