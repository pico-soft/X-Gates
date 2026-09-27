package com.picosoft.xrayproxydroid.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Статус установки через системный PackageInstaller (session API — запасной путь, когда ACTION_VIEW не с чем
 * открыть; полевой случай: автомагнитолы/Android head unit, где нет активити-обработчика файловой установки).
 *
 * Зачем: раньше на таких устройствах «скачать и установить» просто МОЛЧАЛО — startActivity(ACTION_VIEW) кидал
 * ActivityNotFoundException, и пользователь не понимал, что делать. Session API работает без стороннего окна:
 * система сама возвращает подтверждающее окно (STATUS_PENDING_USER_ACTION) и грант «неизвестных источников» —
 * инлайн, без отдельного экрана настроек. Любой исход делаем ВИДИМЫМ (Toast), чтобы не было «тишины».
 */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Система просит подтверждение (и, если нужно, грант источника) — открываем её окно.
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        Log.w(TAG, "confirm intent launch failed", e)
                        toast(context, "Не удалось открыть окно установки на этом устройстве. Сохраните APK в «Загрузки» и поставьте через файловый менеджер.")
                    }
                } else {
                    toast(context, "Система не вернула окно установки. Сохраните APK в «Загрузки» и поставьте через файловый менеджер.")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                // Приложение будет заменено (процесс перезапустится) — отдельного сообщения не требуется.
                Log.i(TAG, "install success")
            }
            else -> {
                Log.w(TAG, "install failed: status=$status msg=$msg")
                toast(context, "Установка не удалась (${msg ?: "код $status"}). Сохраните APK в «Загрузки» и поставьте через файловый менеджер.")
            }
        }
    }

    private fun toast(context: Context, text: String) {
        runCatching { Toast.makeText(context.applicationContext, text, Toast.LENGTH_LONG).show() }
    }

    companion object {
        private const val TAG = "UpdateInstallReceiver"
        const val ACTION = "com.picosoft.xrayproxydroid.action.UPDATE_INSTALL_STATUS"
    }
}
