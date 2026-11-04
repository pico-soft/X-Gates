package com.picosoft.xrayproxydroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Решение Elyor 2026-11-03: после обновления приложения прокси поднимается сам. Установка убивает процесс вместе с сервисом,
 * а START_STICKY после замены пакета система не выполняет, поэтому до открытия приложения прокси молчал.
 * Как и [BootReceiver], только поднимает сервис: тот берёт последний сервер из [LastServerStore] без полного теста.
 * Поднимаем, только если прокси работал: «Стоп» и «Завершить приложение» пишут флаг, и обновление его не отменяет.
 * Настройка «Запускать при включении телефона» здесь ни при чём: она про перезагрузку.
 */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        try {
            if (!LastServerStore.runningBeforeUpdate(context)) return   // прокси был остановлен — так и оставляем
            if (LastServerStore.load(context) == null) return           // ни разу не подключались
            Log.i("UpdateReceiver", "приложение обновлено → поднимаю сервис (последний сервер)")
            XrayProxyService.startAfterUpdate(context)
        } catch (e: Throwable) {
            Log.w("UpdateReceiver", "подъём после обновления пропущен: ${e.message}")
        }
    }
}
