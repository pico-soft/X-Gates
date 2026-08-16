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
        dir.listFiles()?.forEach { it.delete() }   // не копим старые сборки
        val dest = File(dir, available.artifact.fileName)

        val s = SettingsStore.current()
        val directT = s.subTimeoutSec * 1000
        val proxyT = s.subTimeoutSec * 1000 + 10_000

        val res = CascadeFetch.download(
            context, available.downloadUrl, UpdateChecker.UA, dest,
            directTimeoutMs = directT, proxyTimeoutMs = proxyT, totalTimeoutMs = DOWNLOAD_TOTAL_TIMEOUT_MS,
            expectedSize = available.sizeBytes, isCancelled = isCancelled, onProgress = onProgress,
        )
        if (res.bytes > 0) TrafficTracker.addTest(res.bytes)   // трафик скачивания — в поток «Тест»

        if (res.cancelled) { dest.delete(); return DownloadOutcome.Fail(UpdateErrorKind.CANCELLED) }
        if (!res.ok || !dest.exists()) {
            dest.delete()
            val detail = res.attempts.filterNot { it.skipped }.joinToString("; ") { "${it.stage.label}: ${it.note}" }
            return DownloadOutcome.Fail(UpdateErrorKind.DOWNLOAD_FAILED, detail)
        }

        // 1 — контрольная сумма.
        val actual = sha256Hex(dest)
        if (!actual.equals(available.artifact.sha256, ignoreCase = true)) {
            dest.delete()
            return DownloadOutcome.Fail(
                UpdateErrorKind.CHECKSUM_MISMATCH,
                "ожидали ${available.artifact.sha256.take(16)}…, получили ${actual.take(16)}…",
            )
        }

        // 2 — сертификат подписи против установленного приложения.
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

    /** Открыть системный экран выдачи права установки для нашего пакета (а не падать). */
    fun openInstallPermissionSettings(context: Context) {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (e: Exception) {
            // На некоторых прошивках нет per-app экрана — открываем общий список источников.
            try {
                context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e2: Exception) {
                Log.w(TAG, "no install-sources settings screen", e2)
            }
        }
    }

    /** Запустить системный установщик для проверенного файла (через FileProvider). */
    fun launchInstaller(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    // ─────────────────────── проверки ───────────────────────

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
