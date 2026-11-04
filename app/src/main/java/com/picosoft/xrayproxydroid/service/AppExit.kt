package com.picosoft.xrayproxydroid.service

import android.app.Activity
import com.picosoft.xrayproxydroid.monitor.MonitorAlarm
import com.picosoft.xrayproxydroid.monitor.MonitorLog
import com.picosoft.xrayproxydroid.settings.SettingsStore

/**
 * Завершить приложение (решение Elyor 2026-11-03): прокси останавливается штатно, затем закрываем экран и процесс, чтобы не осталось
 * ни фоновой работы (подписки, замеры), ни будильника монитора.
 * Процесс завершаем, только когда сервис уже уничтожен: убитый на ходу сервис с START_STICKY система вернула бы сама, и прокси
 * поднялся бы снова. Не остановился за [STOP_WAIT_MS] — только закрываем экран, сервис договорит остановку сам.
 * [removeFromAutostart] — ещё и выключить «Запускать при включении телефона».
 */
object AppExit {
    private const val STOP_WAIT_MS = 5_000L

    fun quit(activity: Activity, removeFromAutostart: Boolean) {
        val app = activity.applicationContext
        if (removeFromAutostart) SettingsStore.update(app, SettingsStore.current().copy(startOnBoot = false))
        MonitorLog.event(app, "switch", "Приложение завершено", if (removeFromAutostart) "вручную, убрано из автозагрузки" else "вручную")
        XrayProxyService.stop(app)
        MonitorAlarm.cancel(app)
        Thread {
            val until = System.currentTimeMillis() + STOP_WAIT_MS
            while (XrayProxyService.alive && System.currentTimeMillis() < until) Thread.sleep(100)
            val stopped = !XrayProxyService.alive
            activity.runOnUiThread { activity.finishAndRemoveTask() }
            if (!stopped) return@Thread
            Thread.sleep(500)   // экран успевает закрыться
            android.os.Process.killProcess(android.os.Process.myPid())
        }.start()
    }
}
