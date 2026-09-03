package com.picosoft.xrayproxydroid.update

import android.content.Context
import com.picosoft.xrayproxydroid.service.NotificationHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * ОДИН ТАП по баннеру «новая версия» на главной = проверить (если в этой сессии ещё не проверяли) → скачать →
 * СВЕРИТЬ контрольную сумму и подпись → системный установщик. БЕЗ лишних тапов в приложении. Неизбежные окна —
 * системные и вне нашего контроля: (1) окно установки Android (подтверждение установки); (2) РАЗОВО на первый
 * раз — «разрешить установку из этого источника» (после выдачи повторный тап по баннеру доустанавливает уже
 * скачанный файл, без повторной загрузки).
 *
 * Прогресс — в [phase]; баннер его показывает. Логика скачивания/сверки/установки переиспользует [UpdateInstaller].
 */
object UpdateFlowController {
    sealed interface Phase {
        object Idle : Phase
        object Checking : Phase
        data class Downloading(val done: Long, val total: Long) : Phase
        object NeedPermission : Phase          // открыт системный экран «разрешить установку» — тапнуть баннер ещё раз
        object ReadyToInstall : Phase          // Пункт 3: файл скачан+проверен, установка вынесена в уведомление
        data class Failed(val message: String) : Phase
    }

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    // Пункт 2: приложение на переднем плане? (обновляет MainActivity в onResume/onPause.) На переднем плане
    // запускаем установщик напрямую (один тап); в фоне прямой старт Activity блокируется системой (BAL) —
    // тогда установку выносим в уведомление.
    @Volatile var appInForeground = false

    @Volatile private var running = false
    @Volatile private var cancelFlag = false
    // Уже скачанный+проверенный файл (пережидаем выдачу разрешения на установку, чтобы не качать повторно).
    @Volatile private var readyFile: File? = null
    @Volatile private var readyForCode = 0

    private enum class InstallLaunch { LAUNCHED, NOTIFIED, NEED_PERMISSION }

    fun cancel() { cancelFlag = true }

    /** Тап по баннеру. Идемпотентно: пока идёт — повторный тап игнорируется (кроме «нужно разрешение» → доустановка). */
    fun oneTap(context: Context) {
        if (running) return
        running = true; cancelFlag = false
        val app = context.applicationContext
        Thread {
            try {
                var avail = UpdateStore.live.value as? UpdateCheckResult.Available
                // Уже скачан этот же код → просто доустановить (без повторной загрузки).
                readyFile?.let { f ->
                    if (f.exists() && (avail == null || readyForCode == avail.versionCode)) {
                        _phase.value = phaseFor(tryInstall(app, f))
                        return@Thread
                    }
                }
                // Available из последней проверки; если в этой сессии не проверяли — проверить сейчас.
                if (avail == null) {
                    _phase.value = Phase.Checking
                    runCatching { UpdateChecker.check(app) }.getOrNull()?.let {
                        UpdateStore.apply(app, it, System.currentTimeMillis())
                    }
                    avail = UpdateStore.live.value as? UpdateCheckResult.Available
                }
                if (avail == null) { _phase.value = Phase.Failed("Обновление недоступно — попробуйте позже"); return@Thread }

                _phase.value = Phase.Downloading(0L, avail.sizeBytes)
                var lastShown = 0L
                val outcome = UpdateInstaller.download(
                    app, avail,
                    isCancelled = { cancelFlag },
                    onProgress = { d, t ->
                        if (d == t || d - lastShown >= 512 * 1024) { lastShown = d; _phase.value = Phase.Downloading(d, t) }
                    },
                )
                when (outcome) {
                    is UpdateInstaller.DownloadOutcome.Ok -> {
                        readyFile = outcome.file; readyForCode = avail.versionCode
                        _phase.value = phaseFor(tryInstall(app, outcome.file))
                    }
                    is UpdateInstaller.DownloadOutcome.Fail ->
                        _phase.value = if (outcome.kind == UpdateErrorKind.CANCELLED) Phase.Idle
                                       else Phase.Failed(outcome.kind.text)
                }
            } catch (e: Throwable) {
                _phase.value = Phase.Failed("Ошибка обновления: ${e.message ?: e.javaClass.simpleName}")
            } finally { running = false }
        }.start()
    }

    private fun phaseFor(r: InstallLaunch): Phase = when (r) {
        InstallLaunch.LAUNCHED -> Phase.Idle                 // окно установки показано (передний план)
        InstallLaunch.NOTIFIED -> Phase.ReadyToInstall        // установка вынесена в уведомление — окно ещё не подтверждено
        InstallLaunch.NEED_PERMISSION -> Phase.NeedPermission
    }

    /**
     * Пункты 2+3: запустить установку максимально надёжно и НЕ врать про успех.
     *  • нет права установки → на переднем плане открыть системный экран, в фоне — уведомление «разрешите»;
     *  • на переднем плане → прямой запуск установщика (один тап); успех — снять полосу/уведомление;
     *  • в фоне (или прямой старт не удался) → установку в УВЕДОМЛЕНИЕ (обход BAL). Полосу/пометку «отклонено»
     *    НЕ трогаем — окно установки ещё не подтверждено пользователем (Пункт 3: не гасим баннер авансом).
     */
    private fun tryInstall(app: Context, file: File): InstallLaunch {
        if (!UpdateInstaller.canInstall(app)) {
            if (appInForeground) UpdateInstaller.openInstallPermissionSettings(app)
            else NotificationHelper.notifyInstallPermission(app, UpdateInstaller.permissionSettingsIntent(app))
            return InstallLaunch.NEED_PERMISSION
        }
        if (appInForeground) {
            val ok = runCatching { UpdateInstaller.launchInstaller(app, file) }.isSuccess
            if (ok) {
                NotificationHelper.cancelUpdate(app); NotificationHelper.cancelInstall(app)
                UpdateStore.markDismissed(app)
                return InstallLaunch.LAUNCHED
            }
        }
        NotificationHelper.notifyInstallReady(app, UpdateInstaller.buildInstallIntent(app, file))
        return InstallLaunch.NOTIFIED
    }
}
