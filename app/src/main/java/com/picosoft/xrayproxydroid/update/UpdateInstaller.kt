package com.picosoft.xrayproxydroid.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import com.picosoft.xrayproxydroid.BuildConfig
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import java.io.File
import java.security.MessageDigest

/**
 * Скачивание и установка APK обновления (Промпт 70). Это УСТАНОВКА ИСПОЛНЯЕМОГО КОДА ИЗ СЕТИ, поэтому
 * ДВЕ обязательные проверки перед установкой, всегда, даже с нашего адреса:
 *   1) SHA-256 скачанного файла против update.json — не совпало → удалить, сказать;
 *   2) СЕРТИФИКАТ ПОДПИСИ скачанного APK против установленного приложения — не совпал → НЕ ставить,
 *      удалить, предупредить (последний рубеж против подмены файла/зеркала).
 *
 * Файл держим в приватном каталоге (filesDir/updates), не в общих загрузках. Установка не тихая:
 * запускаем системный установщик, пользователь подтверждает сам.
 */
object UpdateInstaller {

    private const val TAG = "UpdateInstaller"
    private const val UPDATES_DIR = "updates"
    // 50+ МБ бинарь через нестабильный GitHub/туннель — щедрый общий бюджет.
    private const val DOWNLOAD_TOTAL_TIMEOUT_MS = 10 * 60 * 1000

    sealed interface DownloadOutcome {
        data class Ok(val file: File) : DownloadOutcome
        data class Fail(val kind: UpdateErrorKind, val detail: String = "") : DownloadOutcome
    }

    private fun updatesDir(context: Context): File =
        File(context.filesDir, UPDATES_DIR).apply { mkdirs() }

    /**
     * Скачать через каскад → сверить SHA-256 → сверить подпись. БЛОКИРУЮЩАЯ (фоновый поток).
     * Любая непройденная проверка удаляет файл. Успех оставляет проверенный APK на диске.
     */
    fun download(
        context: Context,
        available: UpdateCheckResult.Available,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): DownloadOutcome {
        val dir = updatesDir(context)
        val dest = File(dir, available.artifact.fileName)
        // Пункт 1 (переиспользование): проверенный APK ИМЕННО этой сборки уже лежит на диске — НЕ качаем
        // повторно. Раньше готовый файл помнился только в памяти процесса (readyFile), а download() первой
        // строкой стирал каталог → после перезапуска/возврата приложение качало заново («скачалось дважды»).
        // Сумму и подпись сверяем заново — доверяем только полностью проверенному файлу.
        if (isVerifiedApk(context, dest, available.artifact)) return DownloadOutcome.Ok(dest)
        dir.listFiles()?.forEach { it.delete() }   // чистим устаревшее/битое/чужие сборки

        val s = SettingsStore.current()
        val directT = s.subTimeoutSec * 1000
        val proxyT = s.subTimeoutSec * 1000 + 10_000

        // Промпт 121.B: адреса APK по порядку (текущий → запасные), каждый через полный каскад. Провал
        // скачивания или несовпадение суммы — пробуем следующий адрес; успех с верной суммой — выходим.
        val urls = available.downloadUrls.ifEmpty { listOf(available.downloadUrl) }
        var lastFail: DownloadOutcome.Fail? = null
        var downloaded = false
        for (url in urls) {
            if (isCancelled()) { dest.delete(); return DownloadOutcome.Fail(UpdateErrorKind.CANCELLED) }
            val res = CascadeFetch.download(
                context, url, UpdateChecker.UA, dest,
                directTimeoutMs = directT, proxyTimeoutMs = proxyT, totalTimeoutMs = DOWNLOAD_TOTAL_TIMEOUT_MS,
                expectedSize = available.sizeBytes, isCancelled = isCancelled, onProgress = onProgress,
            )
            if (res.bytes > 0) TrafficTracker.addTest(res.bytes)   // трафик скачивания — в поток «Тест»
            if (res.cancelled) { dest.delete(); return DownloadOutcome.Fail(UpdateErrorKind.CANCELLED) }
            if (!res.ok || !dest.exists()) {
                dest.delete()
                val detail = res.attempts.filterNot { it.skipped }.joinToString("; ") { "${it.stage.label}: ${it.note}" }
                lastFail = DownloadOutcome.Fail(UpdateErrorKind.DOWNLOAD_FAILED, "${hostOf(url)}: $detail")
                continue
            }
            // 1 — контрольная сумма (тот же приём, что для чужого зеркала: не совпало — следующий адрес).
            val actual = sha256Hex(dest)
            if (!actual.equals(available.artifact.sha256, ignoreCase = true)) {
                dest.delete()
                lastFail = DownloadOutcome.Fail(
                    UpdateErrorKind.CHECKSUM_MISMATCH,
                    "${hostOf(url)}: ожидали ${available.artifact.sha256.take(16)}…, получили ${actual.take(16)}…",
                )
                continue
            }
            downloaded = true
            break
        }
        if (!downloaded) {
            // Пр.135: сам APK (~50 МБ) через одноразовый temp-инстанс НЕ тянем (дорого, и стриминг через него
            // не реализован). Если прямой путь заблокирован, а туннеля нет — честно направляем поднять прокси
            // (сведения об обновлении при этом уже могли прийти через temp-инстанс, а файл идёт стадией 2/SOCKS).
            if (!CascadeFetch.isOwnProxyUp())
                return DownloadOutcome.Fail(UpdateErrorKind.DOWNLOAD_FAILED,
                    "Прямой доступ к GitHub заблокирован, а прокси не запущен. Запустите прокси на главном экране (▶) и повторите — файл скачается через туннель.")
            return lastFail ?: DownloadOutcome.Fail(UpdateErrorKind.DOWNLOAD_FAILED)
        }

        // 2 — сертификат подписи против установленного приложения. ТЕРМИНАЛЬНО (адреса не перебираем):
        // SHA-256 уже совпал с манифестом, значит файл тот; расхождение подписи = реальная подмена (Пр.121.C —
        // подпись остаётся последним рубежом).
        when (verifySignature(context, dest)) {
            SignatureVerdict.OK -> {}
            SignatureVerdict.DEBUG_INSTALLED -> {
                dest.delete()
                return DownloadOutcome.Fail(
                    UpdateErrorKind.SIGNATURE_DEBUG,
                    "Удалите текущую сборку и установите релиз заново (данные подписок при этом пропадут).",
                )
            }
            SignatureVerdict.MISMATCH -> {
                dest.delete()
                return DownloadOutcome.Fail(
                    UpdateErrorKind.SIGNATURE_MISMATCH,
                    "Файл подписан другим ключом — возможна подмена. Установка отменена.",
                )
            }
        }

        return DownloadOutcome.Ok(dest)
    }

    // ─────────────────────── разрешение на установку ───────────────────────

    /** Есть ли право ставить пакеты (Android 8+ спрашивает per-app «неизвестные источники»). */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    /** Интент системного экрана выдачи права установки для нашего пакета (для startActivity ИЛИ PendingIntent). */
    fun permissionSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** Открыть системный экран выдачи права установки для нашего пакета (а не падать). */
    fun openInstallPermissionSettings(context: Context) {
        try {
            context.startActivity(permissionSettingsIntent(context))
        } catch (e: Exception) {
            // На некоторых прошивках нет per-app экрана — открываем общий список источников.
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                Log.w(TAG, "no install-sources settings screen", e2)
            }
        }
    }

    /** Интент запуска системного установщика для проверенного файла (через FileProvider). Годится и для
     *  startActivity (передний план), и для PendingIntent уведомления (обход запрета фонового старта). */
    fun buildInstallIntent(context: Context, file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /** Запустить системный установщик для проверенного файла (прямой startActivity — только с переднего плана). */
    fun launchInstaller(context: Context, file: File) {
        context.startActivity(buildInstallIntent(context, file))
    }

    /** Пункт 4: копия проверенного APK в общие «Загрузки», чтобы поставить вручную из файлового менеджера.
     *  Возвращает человекочитаемое расположение или null. API 29+ — через MediaStore (без разрешений);
     *  на старых версиях без WRITE_EXTERNAL_STORAGE не пишем (там — ручная загрузка со страницы релиза). */
    fun exportToDownloads(context: Context, file: File): String? {
        if (!file.exists() || file.length() <= 0) return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return try {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME, file.name)
                put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
                put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri).use { out -> file.inputStream().use { it.copyTo(out!!) } }
            values.clear(); values.put(android.provider.MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Загрузки/${file.name}"
        } catch (e: Exception) {
            Log.w(TAG, "export to downloads failed", e); null
        }
    }

    // ─────────────────────── проверки ───────────────────────

    private fun hostOf(url: String): String = runCatching { java.net.URL(url).host }.getOrNull() ?: url

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { inp ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = inp.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** Пункт 1: файл на диске — это ПРОВЕРЕННЫЙ APK именно этой сборки (сумма манифеста + подпись приложения). */
    private fun isVerifiedApk(context: Context, file: File, artifact: UpdateArtifact): Boolean =
        file.exists() && file.length() > 0 &&
            runCatching { sha256Hex(file).equals(artifact.sha256, ignoreCase = true) }.getOrDefault(false) &&
            verifySignature(context, file) == SignatureVerdict.OK

    private enum class SignatureVerdict { OK, MISMATCH, DEBUG_INSTALLED }

    /**
     * Сверяем набор SHA-256 сертификатов подписи скачанного APK с установленным приложением.
     * Совпал хоть один → OK. Иначе: если установлена ОТЛАДОЧНАЯ сборка (BuildConfig.DEBUG) — это
     * ожидаемо (debug≠release, поверх не встанет), возвращаем DEBUG_INSTALLED; если релизная — это
     * настоящее расхождение подписи (возможна подмена), MISMATCH.
     */
    private fun verifySignature(context: Context, apk: File): SignatureVerdict {
        val pm = context.packageManager
        val installed = try { certHashes(signaturesOfPackage(pm, context.packageName)) } catch (e: Exception) {
            Log.w(TAG, "installed sig read failed", e); emptySet<String>()
        }
        val downloaded = try { certHashes(signaturesOfArchive(pm, apk.absolutePath)) } catch (e: Exception) {
            Log.w(TAG, "archive sig read failed", e); emptySet<String>()
        }
        // Не смогли прочитать подпись скачанного файла — считаем расхождением (не рискуем).
        if (downloaded.isEmpty()) return if (BuildConfig.DEBUG) SignatureVerdict.DEBUG_INSTALLED else SignatureVerdict.MISMATCH
        if (installed.isNotEmpty() && installed.intersect(downloaded).isNotEmpty()) return SignatureVerdict.OK
        return if (BuildConfig.DEBUG) SignatureVerdict.DEBUG_INSTALLED else SignatureVerdict.MISMATCH
    }

    @Suppress("DEPRECATION")
    private fun signaturesOfPackage(pm: PackageManager, pkg: String): Array<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            signaturesFromInfo(info.signingInfo)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES).signatures ?: emptyArray()
        }
    }

    @Suppress("DEPRECATION")
    private fun signaturesOfArchive(pm: PackageManager, path: String): Array<Signature> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES) ?: return emptyArray()
            signaturesFromInfo(info.signingInfo)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)?.signatures ?: emptyArray()
        }
    }

    private fun signaturesFromInfo(si: android.content.pm.SigningInfo?): Array<Signature> {
        if (si == null) return emptyArray()
        return if (si.hasMultipleSigners()) si.apkContentsSigners ?: emptyArray()
        else si.signingCertificateHistory ?: si.apkContentsSigners ?: emptyArray()
    }

    private fun certHashes(sigs: Array<Signature>): Set<String> {
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
}
