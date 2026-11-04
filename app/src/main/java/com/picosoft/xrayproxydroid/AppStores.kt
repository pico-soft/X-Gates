package com.picosoft.xrayproxydroid

import android.content.Context
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.update.UpdateStore

/**
 * Загрузка хранилищ с диска. Экран зовёт [load] в каждом onCreate, как и раньше. Сервис зовёт [ensureLoaded], потому что процесс
 * поднимается и без экрана: автозапуск после перезагрузки, возврат после убийства, подъём после обновления. Раньше в таком процессе
 * хранилища оставались пустыми: первая запись журнала перезаписывала файл, и история до перезапуска пропадала, а настройки и
 * стоп-лист до открытия экрана брались по умолчанию. Все хранилища пишут файл сразу, поэтому повторная загрузка ничего не теряет.
 */
object AppStores {
    @Volatile private var loaded = false

    @Synchronized
    fun load(context: Context) {
        val app = context.applicationContext
        SettingsStore.init(app)          // настройки — до первого замера
        BlocklistStore.init(app)         // стоп-лист (первый запуск → засев дефолтом RU/BY)
        MonitorLog.init(app)             // журнал автомониторинга (переживает перезапуск)
        SubscriptionManager.init(app)    // миграция старой подписки в мультиподписки (однократно)
        TrafficTracker.init(app)         // дневные корзины трафика
        UpdateStore.init(app)            // результат последней проверки обновления + время
        loaded = true
    }

    /** Загрузить, если в этом процессе ещё не загружали. */
    fun ensureLoaded(context: Context) {
        if (!loaded) load(context)
    }
}
