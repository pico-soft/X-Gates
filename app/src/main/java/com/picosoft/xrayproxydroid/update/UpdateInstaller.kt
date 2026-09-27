package com.picosoft.xrayproxydroid.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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
            // OK — подписи совпали; UNVERIFIABLE — прочитать не смогли, доверяем системному установщику (см.
            // verifySignature). В обоих случаях идём ставить (файл ПОДЛИННЫЙ — SHA-256 уже сошёлся с манифестом).
            SignatureVerdict.OK, SignatureVerdict.UNVERIFIABLE -> {}
            // Полевой случай (авто-Android в машине, 0.45): установленное приложение подписано ДРУГИМ ключом
            // (ставилось из другого источника/сборки), поэтому Android не даёт обновить ПОВЕРХ. Файл при этом
            // ПОДЛИННЫЙ — SHA-256 уже сошёлся с манифестом (это НЕ подмена), значит незачем пугать «подменой» и
            // молча удалять. Сохраняем APK в «Загрузки» и даём ДЕЙСТВЕННУЮ инструкцию: удалить + поставить заново.
            SignatureVerdict.DEBUG_INSTALLED -> {
                val loc = exportToDownloads(context, dest)
                dest.delete()
                return DownloadOutcome.Fail(
                    UpdateErrorKind.SIGNATURE_DEBUG,
                    manualReinstallHint(loc, "на этом устройстве установлена отладочная сборка — релиз поверх неё не встаёт"),
                )
            }
            SignatureVerdict.MISMATCH -> {
                val loc = exportToDownloads(context, dest)
                dest.delete()
                return DownloadOutcome.Fail(
                    UpdateErrorKind.SIGNATURE_MISMATCH,
                    manualReinstallHint(loc, "приложение на этом устройстве подписано другим ключом (установлено из другого источника)"),
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

    /** Как именно удалось запустить установку (для честного сообщения пользователю). */
    enum class InstallStart { VIEW, SESSION, FAILED }

    /**
     * Запустить установку максимально надёжно (передний план). Сначала штатный ACTION_VIEW (как на телефонах —
     * поведение НЕ меняем). Если системе нечем открыть файловую установку (ActivityNotFoundException — полевой
     * случай автомагнитол/Android head unit) — падаем на системный PackageInstaller (session API), которому не
     * нужен сторонний обработчик и который спрашивает подтверждение/грант источника инлайн. Возврат сообщает, каким
     * путём пошло (или FAILED — не удалось начать НИ ОДНИМ способом → зовущий даёт ручной путь).
     */
    fun startInstall(context: Context, file: File): InstallStart {
        try {
            context.startActivity(buildInstallIntent(context, file))
            return InstallStart.VIEW
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "ACTION_VIEW install: нет обработчика — пробую PackageInstaller session", e)
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_VIEW install не удался — пробую PackageInstaller session", e)
        }
        return if (installViaSession(context, file)) InstallStart.SESSION else InstallStart.FAILED
    }

    /** Совместимость: true, если установка начата любым способом (не FAILED). */
    fun launchInstaller(context: Context, file: File): Boolean = startInstall(context, file) != InstallStart.FAILED

    /**
     * Установка через системный PackageInstaller (session API) — без стороннего активити-обработчика. Пишем APK в
     * сессию и коммитим; статус/подтверждение приходят в [UpdateInstallReceiver] (там же открывается системное окно
     * подтверждения и грант источника). true — сессия создана и закоммичена (окно подтверждения последует);
     * false — не удалось даже начать (например, нет службы установки — крайне редко).
     */
    fun installViaSession(context: Context, file: File): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        return try {
            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            runCatching { params.setAppPackageName(context.packageName) }
            val sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                val out = session.openWrite("base.apk", 0, file.length())
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
                out.close()
                val statusIntent = Intent(context, UpdateInstallReceiver::class.java)
                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pi = PendingIntent.getBroadcast(context, sessionId, statusIntent, piFlags)
                session.commit(pi.intentSender)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "PackageInstaller session install failed", e)
            false
        }
    }

    /** Подпись установленного ≠ подпись официального релиза (файл ПОДЛИННЫЙ — SHA-256 сошёлся с манифестом).
     *  Обновить поверх Android не даёт → честная инструкция вместо «возможна подмена»: удалить и поставить
     *  заново, APK уже сохранён в «Загрузках» (или скачать со страницы релиза, если сохранить не удалось). */
    private fun manualReinstallHint(savedLoc: String?, reason: String): String {
        val head = "Обновить поверх нельзя: $reason. Удалите текущее приложение и установите новую версию заново — данные подписок пропадут, сохраните ссылки."
        val tail = if (savedLoc != null) " APK уже сохранён: $savedLoc — откройте его в «Загрузках» и нажмите «Установить»."
                   else " Скачайте APK со страницы релиза на GitHub и установите вручную."
        return head + tail
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

    // ─────────────────────── уборка старых скачанных APK ───────────────────────

    /**
     * Удаляет старые скачанные APK авто-обновления из filesDir/updates, ОСТАВЛЯЯ только «последний скачанный»,
     * ещё не установленный. Каталог и так вытирается перед каждой закачкой, но ПОСЛЕ УСТАНОВКИ проверенный APK
     * (до 122 МБ у universal) лежит там до следующего обновления. Зовём на СТАРТЕ приложения (после успешного
     * самообновления процесс перезапускается — тут и чистим; сразу после запуска установщика удалять нельзя:
     * при ACTION_VIEW система читает файл во время установки).
     *
     * Правило: versionCode APK ≤ уже установленного (BuildConfig.VERSION_CODE) → своё отработал, удаляем. Строго
     * новее — кандидат «последний скачанный», оставляем ОДИН наибольший (прочие новые-дубли тоже удаляем). Чужой
     * пакет → удаляем. Версию не удалось прочитать → НЕ трогаем (осторожность). БЛОКИРУЮЩАЯ — звать в фоне.
     */
    fun pruneObsoleteApks(context: Context) {
        val dir = File(context.filesDir, UPDATES_DIR)
        val apks = dir.listFiles { f -> f.isFile && f.name.endsWith(".apk", ignoreCase = true) } ?: return
        if (apks.isEmpty()) return
        val installed = BuildConfig.VERSION_CODE.toLong()
        val codes: Map<File, Long?> = apks.associateWith { archiveVersionCode(context, it) }
        // «Последний скачанный» = наибольший versionCode среди строго новее установленного (ещё не поставлен).
        val keep = codes.entries.filter { (it.value ?: -1L) > installed }.maxByOrNull { it.value ?: -1L }?.key
        for ((f, code) in codes) {
            if (f == keep) continue
            if (code == null) continue   // версию не определили — оставляем (не удаляем вслепую)
            if (!f.delete()) Log.w(TAG, "prune: не удалил ${f.name}")
            else Log.i(TAG, "prune: удалён старый APK ${f.name} (code=$code, installed=$installed)")
        }
    }

    /** versionCode APK-архива (или null, если не прочитать); чужой пакет → 0 (будет удалён как устаревший). */
    private fun archiveVersionCode(context: Context, file: File): Long? = runCatching {
        val pi = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) ?: return null
        if (pi.packageName != context.packageName) return 0L
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pi.longVersionCode else pi.versionCode.toLong()
    }.getOrNull()

    private val EXPORT_NAME_RE = Regex("""XrayProxyDroid-([0-9]+(?:\.[0-9]+)*)-.*\.apk""", RegexOption.IGNORE_CASE)

    /**
     * Уборка НАКОПЛЕННЫХ экспортов в общих «Загрузках»: копии, что клал [exportToDownloads] при ручном сохранении/
     * фолбэке подписи (полевой случай магнитол — за 50+ обновлений могло скопиться много APK по 50-122 МБ).
     * Оставляем ТОЛЬКО самую свежую версию (актуальный «последний скачанный»), старые — удаляем. Удаляем ЛИШЬ СВОИ
     * записи MediaStore (созданные нами — мы их владельцы); чужие/скачанные браузером scoped storage трогать не даёт
     * (их только вручную через файловый менеджер). API 29+ (Downloads collection). БЛОКИРУЮЩАЯ — звать в фоне.
     */
    fun pruneExportedDownloads(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.DISPLAY_NAME)
        val sel = "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ?"
        data class E(val id: Long, val ver: Int)
        val entries = ArrayList<E>()
        runCatching {
            resolver.query(collection, proj, sel, arrayOf("XrayProxyDroid-%.apk"), null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val m = EXPORT_NAME_RE.matchEntire(name) ?: continue
                    entries.add(E(c.getLong(idCol), versionKey(m.groupValues[1])))
                }
            }
        }.onFailure { Log.w(TAG, "prune exports: запрос не удался", it) }
        if (entries.size <= 1) return
        val maxVer = entries.maxOf { it.ver }   // самая свежая = актуальный «последний скачанный»
        var removed = 0
        for (e in entries) {
            if (e.ver >= maxVer) continue        // актуальную версию оставляем (даже несколько ABI одной версии)
            val uri = android.content.ContentUris.withAppendedId(collection, e.id)
            // Наши записи удалятся; чужие кинут SecurityException → пропускаем (не наши, трогать нельзя).
            if (runCatching { resolver.delete(uri, null, null) }.getOrDefault(0) > 0) removed++
        }
        if (removed > 0) Log.i(TAG, "prune exports: удалено $removed старых APK из «Загрузок» (оставлена актуальная)")
    }

    /** «0.54» → сравнимое число (major*1000+minor), чтобы находить самую свежую версию по имени файла. */
    private fun versionKey(v: String): Int {
        val p = v.split('.')
        return (p.getOrNull(0)?.toIntOrNull() ?: 0) * 1000 + (p.getOrNull(1)?.toIntOrNull() ?: 0)
    }

    /** URIs НАШИХ экспортов в «Загрузках» СТАРЕЕ самой свежей версии (кандидаты на удаление). Пусто — нечего чистить. */
    fun oldExportUris(context: Context): List<Uri> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return emptyList()
        val resolver = context.contentResolver
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val proj = arrayOf(android.provider.MediaStore.Downloads._ID, android.provider.MediaStore.Downloads.DISPLAY_NAME)
        data class E(val uri: Uri, val ver: Int)
        val entries = ArrayList<E>()
        runCatching {
            resolver.query(collection, proj, "${android.provider.MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("XrayProxyDroid-%.apk"), null)?.use { c ->
                val idCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads._ID)
                val nameCol = c.getColumnIndexOrThrow(android.provider.MediaStore.Downloads.DISPLAY_NAME)
                while (c.moveToNext()) {
                    val name = c.getString(nameCol) ?: continue
                    val m = EXPORT_NAME_RE.matchEntire(name) ?: continue
                    entries.add(E(android.content.ContentUris.withAppendedId(collection, c.getLong(idCol)), versionKey(m.groupValues[1])))
                }
            }
        }.onFailure { Log.w(TAG, "oldExportUris query failed", it) }
        if (entries.size <= 1) return emptyList()
        val maxVer = entries.maxOf { it.ver }
        return entries.filter { it.ver < maxVer }.map { it.uri }
    }

    /**
     * Системный запрос на удаление НАКОПЛЕННЫХ старых экспортов (все XrayProxyDroid-*.apk в «Загрузках», кроме самой
     * свежей версии). Android 30+: одно окно-подтверждение, удаляет НЕЗАВИСИМО ОТ ВЛАДЕЛЬЦА (в т.ч. созданные до
     * переустановки — то, что pruneExportedDownloads не может). null — нечего удалять. Запускать через
     * StartIntentSenderForResult (см. MainActivity). На <30 API нет — там только свои чистятся авто + файл-менеджер.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    fun buildDeleteOldExportsRequest(context: Context): PendingIntent? {
        val uris = oldExportUris(context)
        if (uris.isEmpty()) return null
        return android.provider.MediaStore.createDeleteRequest(context.contentResolver, uris)
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
    private fun isVerifiedApk(context: Context, file: File, artifact: UpdateArtifact): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        if (!runCatching { sha256Hex(file).equals(artifact.sha256, ignoreCase = true) }.getOrDefault(false)) return false
        // OK и UNVERIFIABLE годятся (файл подлинный по SHA-256; при UNVERIFIABLE решает системный установщик).
        val v = verifySignature(context, file)
        return v != SignatureVerdict.MISMATCH && v != SignatureVerdict.DEBUG_INSTALLED
    }

    // UNVERIFIABLE — подпись хотя бы одной стороны прочитать НАДЁЖНО не удалось (не расхождение, а «не знаем»).
    private enum class SignatureVerdict { OK, MISMATCH, DEBUG_INSTALLED, UNVERIFIABLE }

    /**
     * Сверяем набор SHA-256 сертификатов подписи скачанного APK с установленным приложением. Совпал хоть один → OK.
     *
     * ⚠️ ГРАБЛЯ (полевой случай, авто-Android в машине, установка из GitHub): наши релизы подписаны ТОЛЬКО схемой
     * v2 (при minSdk 24 AGP отключает v1/JAR). На Android<28 [signaturesOfArchive] читает подпись АРХИВА через
     * GET_SIGNATURES — а он понимает лишь v1/JAR → набор ПУСТ → раньше это трактовалось как расхождение и обновление
     * ЛОЖНО блокировалось «подпись не совпадает». Теперь: если подпись хотя бы одной стороны прочитать НЕ удалось —
     * возвращаем UNVERIFIABLE и НЕ блокируем: системный установщик сверит подпись при установке САМ (авторитетно;
     * несовпадение он и так не пропустит). Блокируем ТОЛЬКО при УВЕРЕННОМ расхождении (обе стороны прочитаны и не
     * пересекаются). Сборка теперь v1+v2+v3 → на будущих релизах архив читается и на старых Android.
     */
    private fun verifySignature(context: Context, apk: File): SignatureVerdict {
        val pm = context.packageManager
        val installed = try { certHashes(signaturesOfPackage(pm, context.packageName)) } catch (e: Exception) {
            Log.w(TAG, "installed sig read failed", e); emptySet<String>()
        }
        val downloaded = try { certHashes(signaturesOfArchive(pm, apk.absolutePath)) } catch (e: Exception) {
            Log.w(TAG, "archive sig read failed", e); emptySet<String>()
        }
        if (installed.isEmpty() || downloaded.isEmpty()) {
            Log.w(TAG, "signature unverifiable (installed=${installed.size}, downloaded=${downloaded.size}) — доверяем системному установщику")
            return SignatureVerdict.UNVERIFIABLE
        }
        if (installed.intersect(downloaded).isNotEmpty()) return SignatureVerdict.OK
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
        if (si.hasMultipleSigners()) return si.apkContentsSigners ?: emptyArray()
        // Одиночный подписант: берём И текущих подписантов, И историю ротации — у архива и у установленного пакета
        // эти поля заполняются по-разному; объединение надёжнее выбора одного (certHashes дедупит).
        val current = si.apkContentsSigners ?: emptyArray()
        val history = si.signingCertificateHistory ?: emptyArray()
        return current + history
    }

    private fun certHashes(sigs: Array<Signature>): Set<String> {
        val md = MessageDigest.getInstance("SHA-256")
        return sigs.map { md.digest(it.toByteArray()).joinToString("") { b -> "%02x".format(b) } }.toSet()
    }
}
