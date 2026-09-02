package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.picosoft.xrayproxydroid.settings.SettingsStore

/**
 * Пр.129: АВТО-ПЕРЕКЛЮЧЕНИЕ режима экономии по ТИПУ СЕТИ — по СОБЫТИЮ смены сети (из NetworkCallback), НЕ опросом.
 *  • Wi-Fi/Ethernet → экономия ВЫКЛ (обычный режим);
 *  • мобильная (cellular) → экономия ВКЛ.
 * Работает, только если включена настройка [AppSettings.trafficSaveAutoByNetwork]. [trafficSaveMode] остаётся
 * ЕДИНЫМ источником правды (его читают все потребители) — автоматика просто пишет в него по типу сети.
 *
 * Пр.129.C: ручное переключение при активной автоматике держится ДО следующей смены ТИПА сети (флаг
 * [trafficSaveManualUntilNetChange]); при смене типа автоматика снимает флаг и берёт своё. Перезапуск сервиса
 * = свежая оценка по текущей сети (ожидающий ручной override при этом сбрасывается — чистый старт).
 */
object EconomyNet {
    // Последний известный тип сети (мобильная?) — чтобы реагировать ТОЛЬКО на смену Wi-Fi↔мобильная, а не на
    // каждое сетевое событие (сигнал/капы). null — ещё не оценивали (первая оценка после регистрации колбэка).
    @Volatile private var lastMobile: Boolean? = null

    private fun mobileFromCaps(caps: NetworkCapabilities?): Boolean {
        if (caps == null) return false
        // Wi-Fi/Ethernet — «немобильная» (даже если вдруг есть и cellular). Мобильная = только сотовая.
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return false
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }

    /** Событие смены сети по умолчанию (из NetworkCallback.onCapabilitiesChanged). Дедуп по типу. */
    fun onDefaultNetwork(app: Context, caps: NetworkCapabilities?) {
        val isMobile = mobileFromCaps(caps)
        val prev = lastMobile
        if (prev != null && prev == isMobile) return   // тип не менялся — не трогаем (ручной override живёт)
        lastMobile = isMobile
        applyForType(app.applicationContext, isMobile)
    }

    /** Применить по ТЕКУЩЕЙ сети немедленно (когда включили автоматику в настройках). Форсирует, минуя дедуп. */
    fun applyNow(app: Context) {
        val ctx = app.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val isMobile = mobileFromCaps(caps)
        lastMobile = isMobile
        applyForType(ctx, isMobile)
    }

    private fun applyForType(app: Context, isMobile: Boolean) {
        val s = SettingsStore.current()
        if (!s.trafficSaveAutoByNetwork) return                                   // автоматика выключена — тип сети не влияет
        if (s.trafficSaveMode == isMobile && !s.trafficSaveManualUntilNetChange) return   // уже как надо, override нет
        // Смена типа сети → автоматика берёт своё: снять ручной override и выставить режим по типу.
        SettingsStore.update(app, s.copy(trafficSaveMode = isMobile, trafficSaveManualUntilNetChange = false))
        MonitorLog.event(
            app, "net",
            if (isMobile) "Экономия включена — мобильная сеть" else "Экономия выключена — Wi-Fi",
            "авто по типу сети (Пр.129)",
        )
        MonitorCoordinator.wake()   // монитор перечитает режим на ближайшем цикле
    }
}
