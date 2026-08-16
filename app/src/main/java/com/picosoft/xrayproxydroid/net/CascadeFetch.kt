package com.picosoft.xrayproxydroid.net

import android.content.Context
import android.net.ConnectivityManager
import com.picosoft.xrayproxydroid.monitor.MonitorCoordinator
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.SystemVpnState
import com.picosoft.xrayproxydroid.subscription.FetchResult
import com.picosoft.xrayproxydroid.subscription.SubscriptionFetcher
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.XrayConfig
import java.net.InetSocketAddress
import java.net.Proxy

/**
 * ОБЩИЙ каскад загрузки внешнего ресурса (подписки, обновляемый конфиг, проверка версии — все на
 * GitHub, из РФ нестабильно, а туннель часто поднят). Ступени пробуются по порядку; переход по ЛЮБОЙ
 * неудаче (таймаут/код≠200/пусто/TLS/«скачано, но контент не подошёл» — решает [acceptBody]).
 * Неприменимая ступень ПРОПУСКАЕТСЯ (не неудача, не засоряет отчёт).
 *
 * Порядок (Промпт 51 + 54):
 *   1. напрямую;
 *   2. через свой активный SOCKS (если прокси поднят);
 *   3. напрямую МИМО системного VPN (bind к физической сети — только если МЫ ВНУТРИ VPN и ещё не мимо);
 *   4. ЧЕРЕЗ системный VPN (явно на VPN-сети — только если мы внутри; единственный путь при lockdown);
 *   5. через системный прокси (ConnectivityManager.getDefaultProxy — если задан);
 *   6. temp-инстанс на недавно рабочих серверах (до 5, единый предикат, не во время теста/перебора).
 *
 * Отношение к VPN берём из [SystemVpnState] (его вычисляет сервис, сняв нашу привязку — иначе наша же
 * привязка путала бы детекцию). Ступени 3 и 4 осмысленны ТОЛЬКО в состоянии INSIDE.
 */
enum class FetchStage(val label: String) {
    DIRECT("напрямую"),
    OWN_PROXY("через свой прокси"),
    NON_VPN_DIRECT("напрямую мимо системного VPN"),
    VIA_VPN("через системный VPN"),
    SYSTEM_PROXY("через системный прокси"),
    TEMP_RECENT("через недавно рабочий сервер"),
}

data class CascadeAttempt(
    val stage: FetchStage,
    val skipped: Boolean,
    val accepted: Boolean,
    val result: FetchResult?,
    val note: String = "",     // причина пропуска / имя сервера для ступени 5
)

data class CascadeResult(
    val ok: Boolean,
    val stage: FetchStage?,
    val result: FetchResult?,
    val attempts: List<CascadeAttempt>,
)

object CascadeFetch {

    private const val MAX_TEMP_CANDIDATES = 5

    fun fetch(
        context: Context,
        url: String,
        userAgent: String,
        directTimeoutMs: Int,
        proxyTimeoutMs: Int,
        totalTimeoutMs: Int,
        acceptBody: (FetchResult) -> Boolean = { it.ok && it.body.isNotBlank() },
    ): CascadeResult {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val attempts = ArrayList<CascadeAttempt>()
        val start = System.nanoTime()
        fun overBudget() = (System.nanoTime() - start) / 1_000_000 > totalTimeoutMs

        fun tryStage(stage: FetchStage, timeout: Int, open: (java.net.URL) -> java.net.URLConnection): CascadeResult? {
            val r = SubscriptionFetcher.fetch(url, userAgent, timeout, open)
            val ok = acceptBody(r)
            attempts.add(CascadeAttempt(stage, skipped = false, accepted = ok, result = r))
            return if (ok) CascadeResult(true, stage, r, attempts) else null
        }
        fun skip(stage: FetchStage, note: String) { attempts.add(CascadeAttempt(stage, skipped = true, accepted = false, result = null, note = note)) }

        // 1 — напрямую
        tryStage(FetchStage.DIRECT, directTimeoutMs) { it.openConnection() }?.let { return it }

        // 2 — через свой активный SOCKS
        if (!ProxyState.state.value.running) skip(FetchStage.OWN_PROXY, "прокси не запущен")
        else if (overBudget()) skip(FetchStage.OWN_PROXY, "общий таймаут")
        else tryStage(FetchStage.OWN_PROXY, proxyTimeoutMs) {
            it.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT)))
        }?.let { return it }

        // Отношение НАШЕГО трафика к VPN — из единого источника (сервис вычислил, сняв нашу привязку).
        val vpn = SystemVpnState.state.value

        // 3 — напрямую МИМО системного VPN. Осмысленно ТОЛЬКО когда мы ВНУТРИ VPN и ещё НЕ идём мимо
        //     (если bypassed — ступень 1 уже шла по физической сети через нашу привязку → дубликат).
        when {
            vpn.relation != VpnRelation.INSIDE ->
                skip(FetchStage.NON_VPN_DIRECT, if (vpn.relation == VpnRelation.EXCLUDED) "мы уже вне VPN" else "мы не внутри VPN")
            vpn.bypassed -> skip(FetchStage.NON_VPN_DIRECT, "уже идём мимо (привязка процесса)")
            overBudget() -> skip(FetchStage.NON_VPN_DIRECT, "общий таймаут")
            else -> {
                val net = if (cm != null) NetworkUtils.physicalNetwork(cm) else null
                if (net == null) skip(FetchStage.NON_VPN_DIRECT, "нет физической сети WIFI/CELLULAR")
                else tryStage(FetchStage.NON_VPN_DIRECT, proxyTimeoutMs) { net.openConnection(it) }?.let { return it }
            }
        }

        // 4 — ЧЕРЕЗ системный VPN (явно на VPN-сети). Единственный путь при lockdown («блокировать без VPN»).
        //     Осмысленно ТОЛЬКО в INSIDE (в чужой туннель мы попадаем, лишь будучи внутри него).
        when {
            vpn.relation != VpnRelation.INSIDE ->
                skip(FetchStage.VIA_VPN, if (vpn.relation == VpnRelation.EXCLUDED) "мы вне VPN — в чужой туннель не попасть" else "VPN не активен")
            overBudget() -> skip(FetchStage.VIA_VPN, "общий таймаут")
            else -> {
                val vpnNet = if (cm != null) NetworkUtils.vpnNetwork(cm) else null
                if (vpnNet == null) skip(FetchStage.VIA_VPN, "нет VPN-сети")
                else tryStage(FetchStage.VIA_VPN, proxyTimeoutMs) { vpnNet.openConnection(it) }?.let { return it }
            }
        }

        // 4 — через системный прокси (Wi-Fi настройки / другое приложение)
        val sysProxy = if (cm != null) systemProxy(cm) else null
        if (sysProxy == null) skip(FetchStage.SYSTEM_PROXY, "системный прокси не задан")
        else if (overBudget()) skip(FetchStage.SYSTEM_PROXY, "общий таймаут")
        else tryStage(FetchStage.SYSTEM_PROXY, proxyTimeoutMs) { it.openConnection(sysProxy) }?.let { return it }

        // 5 — temp-инстанс на недавно рабочих серверах
        if (MonitorCoordinator.fullTestRunning || MonitorCoordinator.monitorSearchRunning) {
            skip(FetchStage.TEMP_RECENT, "идёт полный тест / перебор монитора")
        } else {
            val candidates = SubscriptionManager.recentWorkingServers(app).take(MAX_TEMP_CANDIDATES)
            if (candidates.isEmpty()) skip(FetchStage.TEMP_RECENT, "нет недавно рабочих серверов")
            else for (c in candidates) {
                if (overBudget()) { skip(FetchStage.TEMP_RECENT, "общий таймаут"); break }
                val name = c.remarks.ifBlank { c.address }
                val r = ServerSpeedTester.fetchViaTempInstance(app, c, url, userAgent, proxyTimeoutMs)
                val ok = r != null && acceptBody(r)
                attempts.add(CascadeAttempt(FetchStage.TEMP_RECENT, skipped = false, accepted = ok, result = r, note = name))
                if (ok) return CascadeResult(true, FetchStage.TEMP_RECENT, r, attempts)
            }
        }

        return CascadeResult(false, null, null, attempts)
    }

    /** Системный HTTP-прокси (host:port). PAC-прокси не поддерживаем → null. */
    private fun systemProxy(cm: ConnectivityManager): Proxy? {
        val pi = cm.defaultProxy ?: return null
        val host = pi.host
        if (host.isNullOrEmpty() || pi.port <= 0) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, pi.port))
    }
}
