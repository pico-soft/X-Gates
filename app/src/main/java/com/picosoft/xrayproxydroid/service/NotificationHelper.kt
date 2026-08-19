package com.picosoft.xrayproxydroid.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.picosoft.xrayproxydroid.MainActivity
import com.picosoft.xrayproxydroid.R

/** Постоянная нотификация foreground-сервиса с кнопкой Stop (без broadcast — PendingIntent.getService). */
object NotificationHelper {

    const val NOTIFICATION_ID = 1
    const val PROMPT_ID = 2
    const val UPDATE_ID = 3
    const val NO_SERVERS_ID = 5   // Промпт 95.E: «нет рабочих серверов»
    private const val CHANNEL_ID = "xray_proxy"
    private const val CHANNEL_NAME = "Xray proxy"
    // Промпт 93.J: ОТДЕЛЬНЫЙ канал «Обновления» — можно выключить, не трогая нотификацию сервиса.
    private const val UPDATE_CHANNEL_ID = "xray_updates"
    private const val UPDATE_CHANNEL_NAME = "Обновления"

    /** Уведомление о новой версии (Промпт 93.J): версия + первая строка описания, тап → «О приложении».
     *  Одно на версию (гейтит вызывающий через UpdateStore). Без размера файла. Ничего не скачивает. */
    fun notifyUpdate(context: Context, versionName: String, firstLine: String) {
        ensureUpdateChannel(context)
        val intent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_UPDATE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pi = PendingIntent.getActivity(context, 3, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Доступна новая версия $versionName")
            .setContentText(firstLine.ifBlank { "Откройте приложение, чтобы обновить" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(firstLine.ifBlank { "Откройте приложение, чтобы обновить" }))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(UPDATE_ID, n) }
    }

    fun cancelUpdate(context: Context) =
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(UPDATE_ID) }.let { }

    private fun ensureUpdateChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(UPDATE_CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    UPDATE_CHANNEL_ID, UPDATE_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT
                ).apply { description = "Уведомления о новых версиях приложения" })
            }
        }
    }

    /** Уведомление (пункт E): нет живых серверов, предложить включить выключенные источники. Тап → приложение. */
    fun notifyEnableSources(context: Context, sources: Int, servers: Int) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, 2, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Нет живых серверов")
            .setContentText("Включить $sources выключенных источников (~$servers серверов)? Откройте приложение.")
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        androidx.core.app.NotificationManagerCompat.from(context).notify(PROMPT_ID, n)
    }

    fun cancelEnableSources(context: Context) =
        androidx.core.app.NotificationManagerCompat.from(context).cancel(PROMPT_ID)

    /** Промпт 95.E: связь через туннель недоступна НИ ОДНИМ способом — сказать ПРЯМО в шторке (не открывая
     *  приложение). Отдельно от «нет интернета». Тап → приложение. Снимается при восстановлении. */
    fun notifyNoServers(context: Context) {
        ensureChannel(context)
        val pi = PendingIntent.getActivity(
            context, 4, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Нет рабочих серверов")
            .setContentText("Связь через туннель сейчас недоступна — приложение перебрало все варианты. Откройте, чтобы проверить.")
            .setOngoing(true)   // держится, пока связь не восстановлена (снимается cancelNoServers)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pi)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).notify(NO_SERVERS_ID, n) }
    }

    fun cancelNoServers(context: Context) =
        runCatching { androidx.core.app.NotificationManagerCompat.from(context).cancel(NO_SERVERS_ID) }.let { }

    fun buildNotification(service: Service, label: String?): Notification {
        ensureChannel(service)

        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

        // Тап по нотификации → открыть приложение.
        val contentIntent = PendingIntent.getActivity(
            service, 0, Intent(service, MainActivity::class.java), flags
        )
        // Кнопка Stop → прямо в сервис (ACTION_STOP), без BroadcastReceiver.
        val stopIntent = PendingIntent.getService(
            service, 1,
            Intent(service, XrayProxyService::class.java).setAction(XrayProxyService.ACTION_STOP),
            flags
        )

        return NotificationCompat.Builder(service, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_proxy)
            .setContentTitle("Прокси активен")
            .setContentText(label ?: "xray работает")
            .setOngoing(true)
            .setShowWhen(false)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Минуем Android 12+ FGS deferral — показываем нотификацию сразу.
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
            .addAction(0, "Стоп", stopIntent)
            .build()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    // LOW — тихая, но постоянная (обязательна для foreground-сервиса).
                    NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }
}
