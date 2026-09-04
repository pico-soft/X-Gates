package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.picosoft.xrayproxydroid.settings.SettingsStore

/**
 * Пр.139 (переустройство Пр.129): вычисляет ЭФФЕКТИВНОЕ состояние экономии [trafficSaveMode] (его читают все
 * потребители) из МАСТЕРА [economyEnabled] + подпункта «только моб.» [trafficSaveAutoByNetwork] + типа сети:
 *   effective = economyEnabled && ( !onlyMobile || isMobile ).
 * Единственный писатель trafficSaveMode — [recompute] (идемпотентен). Зовётся по событию сети (onDefaultNetwork)
 * и по [applyNow] (старт сервиса / возврат приложения / смена мастера-подпункта) — последнее чинит случай, когда
 * Wi-Fi уже подключён ДО регистрации колбэка и события смены не приходит.
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

    /** Событие сети по умолчанию (из NetworkCallback). Пересчитываем ЭФФЕКТИВНОЕ состояние (идемпотентно). */
    fun onDefaultNetwork(app: Context, caps: NetworkCapabilities?) {
        val isMobile = mobileFromCaps(caps)
        lastMobile = isMobile
        recompute(app.applicationContext, isMobile)
    }

    /**
     * Применить по ТЕКУЩЕЙ активной сети немедленно. Зовётся при старте сервиса, возврате приложения на
     * передний план и переключении мастера/подпункта — ЧИНИТ «игнор уже-подключённого Wi-Fi» (событие смены
     * не приходит, если сеть подключена ДО регистрации колбэка).
     */
    fun applyNow(app: Context) {
        val ctx = app.applicationContext
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm?.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val isMobile = mobileFromCaps(caps)
        lastMobile = isMobile
        recompute(ctx, isMobile)
    }

    /**
     * Пр.139: ЕДИНСТВЕННЫЙ писатель эффективного [trafficSaveMode]. Чистая функция входов:
     *   effective = economyEnabled && ( !onlyMobile || isMobile )
     *  • мастер выкл → экономии нет никогда;
     *  • мастер вкл + «только моб.» вкл → экономия лишь на мобильной (на Wi-Fi обычный);
     *  • мастер вкл + «только моб.» выкл → экономия всегда.
     * Пишет только при РЕАЛЬНОЙ смене (идемпотентно) — можно звать сколько угодно.
     */
    private fun recompute(app: Context, isMobile: Boolean) {
        val s = SettingsStore.current()
        val want = s.economyEffective(isMobile)
        if (s.trafficSaveMode == want) return
        SettingsStore.update(app, s.copy(trafficSaveMode = want))
        val why = when {
            !s.economyEnabled -> "режим экономии выключен"
            !s.trafficSaveAutoByNetwork -> "экономия всегда (подпункт «только моб.» выкл)"
            isMobile -> "мобильная сеть"
            else -> "Wi-Fi (экономия только на мобильной)"
        }
        MonitorLog.event(app, "net", if (want) "Экономия включена" else "Экономия выключена", why)
        MonitorCoordinator.wake()   // монитор перечитает режим на ближайшем цикле
    }
}
