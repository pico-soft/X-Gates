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

    /**
     * Активен ли КАКОЙ-ЛИБО системный VPN. Нужен ПЕРЕБОР сетей (allNetworks): чужой VPN, из которого наш
     * пакет ИСКЛЮЧЁН (EXCLUDED), не является нашей activeNetwork — getActiveNetwork его не покажет, а
     * различать EXCLUDED и NONE обязательно. allNetworks помечен deprecated (API 31), но функционален с
     * ACCESS_NETWORK_STATE; на случай ограничения прошивкой — защищено (ошибка → false = обход не делаем).
     */
    fun vpnActive(cm: ConnectivityManager): Boolean = runCatching {
        @Suppress("DEPRECATION") (cm.allNetworks).any {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrDefault(false)

    /**
     * Отношение НАШЕГО трафика к VPN по ФАКТУ маршрута процесса ([getActiveNetwork] — per-UID, учитывает
     * список приложений чужого VPN). ВАЖНО: вызывать, СНЯВ нашу `bindProcessToNetwork` — иначе активной
     * окажется наша привязка и INSIDE замаскируется под EXCLUDED. Ошибка → NONE (обход не делаем).
     */
    fun vpnRelation(cm: ConnectivityManager): VpnRelation = runCatching {
        val active = cm.activeNetwork
        val activeIsVpn = active?.let { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) } == true
        if (activeIsVpn) VpnRelation.INSIDE
        else if (vpnActive(cm)) VpnRelation.EXCLUDED else VpnRelation.NONE
    }.getOrDefault(VpnRelation.NONE)

    /** VPN-сеть (для явной попытки «через VPN» в каскаде). Перебор нужен: ищем сеть КОНКРЕТНОГО транспорта. */
    fun vpnNetwork(cm: ConnectivityManager): Network? = runCatching {
        @Suppress("DEPRECATION") (cm.allNetworks).firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        }
    }.getOrNull()

    /**
     * Первая ФИЗИЧЕСКАЯ сеть WIFI/CELLULAR (не VPN) с доступом в интернет — для обхода чужого VPN. Перебор
     * нужен: getActiveNetwork при активном VPN вернёт VPN, а нам нужна ПОДЛЕЖАЩАЯ физическая. Ошибка → null.
     */
    fun physicalNetwork(cm: ConnectivityManager): Network? = runCatching {
        @Suppress("DEPRECATION") (cm.allNetworks).firstOrNull { n ->
            val c = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) &&
                c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }.getOrNull()

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
