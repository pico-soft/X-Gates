package com.picosoft.xrayproxydroid.update

import android.content.Context
import com.picosoft.xrayproxydroid.service.NotificationHelper
import com.picosoft.xrayproxydroid.settings.SettingsStore

/**
 * Промпт 93.J: после самостоятельной проверки — показать системное уведомление о новой версии, если нужно
 * (настройка вкл, версия новее, не отклонена, ещё не показывали). ОДНО уведомление на версию.
 */
object UpdateNotifier {
    fun maybeNotify(context: Context) {
        val on = SettingsStore.current().notifyNewVersions
        if (!UpdateStore.shouldNotify(on)) return
        val rec = UpdateStore.record.value
        NotificationHelper.notifyUpdate(context, rec.availName, UpdateStore.availNotesFirstLine())
        UpdateStore.markNotified(context)
    }
}
