package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

/**
 * Отображаемое имя сервера для ФОНОВЫХ модулей (журнал, монитор, полный тест) — читает оверрайд из
 * [BlocklistStore.current]. UI (MainActivity.displayName) использует собранный StateFlow ради
 * рекомпозиции; здесь снимок на момент вызова. Единая семантика: пользовательское имя или провайдера.
 */
object ServerLabels {
    fun display(p: ServerProfile): String =
        BlocklistStore.current().customName(SubscriptionManager.serverKey(p)) ?: p.remarks.ifBlank { p.address }

    /** Имя по serverKey (ищем профиль в реестре). null — сервера уже нет / ключ null. */
    fun displayForKey(context: Context, key: String?): String? {
        if (key == null) return null
        val p = SubscriptionManager.allServers(context)
            .firstOrNull { SubscriptionManager.serverKey(it) == key } ?: return null
        return display(p)
    }

    /** Verbose-метка для нотификации сервиса: «proto · имя · addr:port». */
    fun full(p: ServerProfile): String =
        "${p.protocol}  ·  ${display(p)}  ·  ${p.address}:${p.port}"
}
