package com.picosoft.xrayproxydroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.picosoft.xrayproxydroid.settings.SettingsStore

/**
 * Пр.134: автозапуск ПОСЛЕ ПЕРЕЗАГРУЗКИ телефона. Приёмник только ПОДНИМАЕТ сервис — сам работу не делает.
 * Сервис по ACTION_BOOT восстанавливает ПОСЛЕДНИЙ рабочий сервер из [LastServerStore] БЕЗ полного теста
 * (как восстановление после убийства, Пр.115); сеть после загрузки поднимается не сразу — ждём её ПО СОБЫТИЮ
 * (ядро поднимает ЛОКАЛЬНЫЙ SOCKS сразу, а внешнюю связь монитор досматривает по NetworkCallback — не паузой).
 *
 * Процесс при загрузке свежий → грузим настройку с диска (SettingsStore.init). До разблокировки
 * credential-storage может быть недоступно (LOCKED_BOOT) → всё в try/catch: не смогли прочитать/выключено —
 * тихо ждём BOOT_COMPLETED после разблокировки.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        try {
            SettingsStore.init(context)                       // свежий процесс — подгрузить настройку с диска
            if (!SettingsStore.current().startOnBoot) return  // выключено пользователем
            if (LastServerStore.load(context) == null) return // нечего поднимать — ни разу не подключались
            Log.i("BootReceiver", "boot ($action) → поднимаю сервис (последний сервер)")
            XrayProxyService.startFromBoot(context)
        } catch (e: Throwable) {
            // credential-storage недоступно до разблокировки / любая ошибка — пропускаем, BOOT_COMPLETED повторит.
            Log.w("BootReceiver", "boot skip: ${e.message}")
        }
    }
}
