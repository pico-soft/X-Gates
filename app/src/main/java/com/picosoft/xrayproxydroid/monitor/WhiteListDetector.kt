package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import com.picosoft.xrayproxydroid.net.CascadeFetch
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Пр.140: РЕЖИМ БЕЛЫХ СПИСКОВ РФ. Часть сетей пускает только «белый список» (домены из реестра) — тогда
 * yandex.ru открыт, а google.com нет, и обычные (зарубежные) серверы не поднимаются. Детектор это распознаёт
 * и переводит приложение на источники белого списка; раз в полчаса проверяет возврат google → обычный режим.
 *
 * Живость/пинг это НЕ трогает (Пр.139). Эффективный список серверов сужается в [SubscriptionManager.allServers]
 * по флагу [whiteListModeActive] — enabled-флаги пользователя НЕ меняем.
 */
object WhiteListDetector {

    /** Итог оценки — ЧИСТАЯ функция входов (для юнит-тестов; без сети/настроек). */
    data class Decision(val active: Boolean, val manualNormal: Boolean, val event: String?)

    /**
     * @param auto фича включена (whiteListAutoEnabled)
     * @param manualNormal пользователь выбрал «Обычный» на главной (держим обычный, пока google не вернётся)
     * @param active сейчас активен режим белых списков
     * @param googleOk google доступен (через туннель ИЛИ напрямую) — зарубеж работает
     * @param yandexOk yandex доступен напрямую — домашний сегмент жив
     */
    fun decide(auto: Boolean, manualNormal: Boolean, active: Boolean, googleOk: Boolean, yandexOk: Boolean): Decision = when {
        !auto -> Decision(active = false, manualNormal = false, event = if (active) "Режим белых списков выключен в настройках — обычный режим" else null)
        googleOk -> Decision(active = false, manualNormal = false,
            event = if (active) "Google снова доступен — возврат в обычный режим" else null)
        yandexOk && !manualNormal -> Decision(active = true, manualNormal = false,
            event = if (!active) "Google недоступен, Yandex открыт — переход в режим белых списков" else null)
        yandexOk && manualNormal -> Decision(active = false, manualNormal = true, event = null)   // юзер держит обычный
        else -> Decision(active = active, manualNormal = manualNormal, event = null)              // и то и то мертво = общий обрыв, не трогаем
    }

    private const val PROBE_TIMEOUT_MS = 4000
    private val GOOGLE_HOSTS = listOf("www.google.com" to 443, "google.com" to 443)
    private val YANDEX_HOSTS = listOf("ya.ru" to 443, "yandex.ru" to 443, "77.88.8.8" to 53)

    /** Прямой TCP-коннект (приложение работает через ЛОКАЛЬНЫЙ SOCKS, не VpnService → default network = физическая). */
    private fun tcpOk(host: String, port: Int): Boolean =
        try { Socket().use { it.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS); true } } catch (e: Exception) { false }

    private fun anyOk(hosts: List<Pair<String, Int>>): Boolean = hosts.any { (h, p) -> tcpOk(h, p) }

    /**
     * Оценить сеть и применить решение. [tunnelForeignOk] — зарубеж уже подтверждён через туннель в этом цикле
     * (ExternalIpChecker вернул IP); тогда google считаем доступным без отдельной пробы. БЛОКИРУЮЩАЯ (фон).
     * Возвращает true, если режим ПЕРЕКЛЮЧИЛСЯ (вызывающему стоит пересобрать выбор сервера).
     */
    fun evaluate(context: Context, tunnelForeignOk: Boolean): Boolean {
        val app = context.applicationContext
        val s = SettingsStore.current()
        if (!s.whiteListAutoEnabled) {
            if (s.whiteListModeActive || s.whiteListManualNormal)
                SettingsStore.update(app, s.copy(whiteListModeActive = false, whiteListManualNormal = false))
            return false
        }
        val googleOk = tunnelForeignOk || anyOk(GOOGLE_HOSTS) ||
            (CascadeFetch.isOwnProxyUp() && ExternalIpChecker.fetch() != null)
        val yandexOk = anyOk(YANDEX_HOSTS)
        val d = decide(s.whiteListAutoEnabled, s.whiteListManualNormal, s.whiteListModeActive, googleOk, yandexOk)
        val changed = d.active != s.whiteListModeActive
        if (d.active != s.whiteListModeActive || d.manualNormal != s.whiteListManualNormal) {
            SettingsStore.update(app, s.copy(whiteListModeActive = d.active, whiteListManualNormal = d.manualNormal))
        }
        d.event?.let { MonitorLog.event(app, "net", it, "google=${if (googleOk) "ок" else "нет"}, yandex=${if (yandexOk) "ок" else "нет"}") }
        if (changed) MonitorCoordinator.wake()
        return changed
    }

    /** Пользователь на главной нажал «Обычный режим» при активном белом. Держим обычный до возврата google/смены сети. */
    fun userForceNormal(context: Context) {
        val app = context.applicationContext
        val s = SettingsStore.current()
        SettingsStore.update(app, s.copy(whiteListModeActive = false, whiteListManualNormal = true))
        MonitorLog.event(app, "net", "Пользователь выбрал обычный режим (из белого)", "")
        MonitorCoordinator.wake()
    }

    /** Смена сети → свежая оценка: снять ручной «держать обычный», чтобы новая сеть переоценилась с нуля. */
    fun onNetworkChange(context: Context) {
        val s = SettingsStore.current()
        if (s.whiteListManualNormal) SettingsStore.update(context.applicationContext, s.copy(whiteListManualNormal = false))
    }
}
