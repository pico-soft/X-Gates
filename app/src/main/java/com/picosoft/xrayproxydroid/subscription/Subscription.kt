package com.picosoft.xrayproxydroid.subscription

import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import kotlinx.serialization.Serializable

/**
 * Подписка со вложенными серверами — зеркало Python (subscriptions.json + SOURCE).
 * Хранится в subscriptions.json (filesDir) через [SubscriptionStore].
 *
 * @param url          адрес подписки (уникальный ключ; дубли не добавляем)
 * @param name         человекочитаемое имя (по умолчанию — из URL, см. Manager)
 * @param lastUpdateOk результат последнего фетча: true/false/null (ещё не обновлялась)
 * @param lastUpdateTs метка времени последнего фетча ("yyyy-MM-dd HH:mm")
 * @param servers      распарсенные серверы этой подписки; при ре-импорте заменяются целиком
 */
@Serializable
data class Subscription(
    val url: String,
    val name: String,
    val lastUpdateOk: Boolean? = null,
    val lastUpdateTs: String? = null,
    val servers: List<ServerProfile> = emptyList(),
)
