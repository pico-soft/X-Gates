package com.picosoft.xrayproxydroid.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.net.HttpURLConnection
import java.net.URL

/**
 * Отношение НАШЕГО трафика к системному VPN (Промпт 60). Наличие VPN в системе НЕ означает, что через
 * него идём мы: при раздельном туннелировании наш пакет может быть исключён.
 *  - NONE     — VPN в системе нет;
 *  - INSIDE   — VPN есть, НАШ трафик идёт через него;
 *  - EXCLUDED — VPN есть, наш пакет исключён → мы уже мимо.
 */
enum class VpnRelation { NONE, INSIDE, EXCLUDED }

/**
 * Помощники по системным сетям (единая точка для каскада [CascadeFetch] и обхода чужого VPN в сервисе).
 * Про обход VPN: в libv2ray НЕТ API привязать исходящие ядра к сети (см. разбор AAR, Промпт 57) —
 * применяем процессную привязку `bindProcessToNetwork` к физической сети, когда мы ВНУТРИ чужого VPN.
 */
object NetworkUtils {

    private const val PROBE_204 = "https://www.gstatic.com/generate_204"

    fun connectivity(context: Context): ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    /** Активен ли КАКОЙ-ЛИБО системный VPN (транспорт VPN среди сетей). */
    fun vpnActive(cm: ConnectivityManager): Boolean =
        @Suppress("DEPRECATION") (cm.allNetworks).any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    /**
     * Отношение НАШЕГО трафика к VPN по ФАКТУ маршрута процесса ([getActiveNetwork] — per-UID, учитывает
     * список приложений чужого VPN). ВАЖНО: вызывать, СНЯВ нашу `bindProcessToNetwork` — иначе активной
     * окажется наша привязка и INSIDE замаскируется под EXCLUDED.
     */
    fun vpnRelation(cm: ConnectivityManager): VpnRelation {
        val active = cm.activeNetwork
        val activeIsVpn = active?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        if (activeIsVpn) return VpnRelation.INSIDE
        return if (vpnActive(cm)) VpnRelation.EXCLUDED else VpnRelation.NONE
    }

    /** VPN-сеть (для явной попытки «через VPN» в каскаде). */
    fun vpnNetwork(cm: ConnectivityManager): Network? =
        @Suppress("DEPRECATION") (cm.allNetworks).firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }

    /** Первая ФИЗИЧЕСКАЯ сеть WIFI/CELLULAR (не VPN) с доступом в интернет — для обхода чужого VPN. */
    fun physicalNetwork(cm: ConnectivityManager): Network? =
        @Suppress("DEPRECATION") (cm.allNetworks).firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

    /** Лёгкая проверка связности по ТЕКУЩЕМУ маршруту процесса (уважает нашу привязку). БЛОКИРУЮЩАЯ. */
    fun probeConnectivity(timeoutMs: Int): Boolean = try {
        val c = (URL(PROBE_204).openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs; readTimeout = timeoutMs
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "curl/8.0")
        }
        val code = c.responseCode
        c.disconnect()
        code in 200..399
    } catch (e: Exception) {
        false
    }
}
