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
    private const val CHANNEL_ID = "xray_proxy"
    private const val CHANNEL_NAME = "Xray proxy"

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
