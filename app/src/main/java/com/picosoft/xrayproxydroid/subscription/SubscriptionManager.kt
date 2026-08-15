package com.picosoft.xrayproxydroid.subscription

import android.content.Context
import com.picosoft.xrayproxydroid.xray.link.ParseResult
import com.picosoft.xrayproxydroid.xray.link.ServerLinkParser
import com.picosoft.xrayproxydroid.xray.link.ServerProfile
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Итог обновления подписки: сколько серверов добавлено и сколько строк пропущено. */
data class RefreshSummary(
    val ok: Boolean,
    val added: Int = 0,
    val unsupported: Int = 0,   // hysteria2/tuic… — распознаны, но не наше ядро
    val invalid: Int = 0,       // битые/неизвестные строки
    val error: String? = null,
)

/**
 * Оркестратор подписок: fetch → decode → parse → save, поверх [SubscriptionStore].
 * Сетевые методы БЛОКИРУЮЩИЕ — вызывать в фоновом потоке (обеспечит UI на шаге 4).
 */
object SubscriptionManager {

    /** Все подписки. */
    fun list(context: Context): List<Subscription> = SubscriptionStore.load(context)

    /** Плоский список всех серверов из всех подписок (для выбора в UI). */
    fun allServers(context: Context): List<ServerProfile> =
        SubscriptionStore.load(context).flatMap { it.servers }

    /** Добавить подписку (dedup по url). Имя по умолчанию — из URL. true если добавили. */
    fun add(context: Context, url: String, name: String? = null): Boolean {
        val u = url.trim()
        if (u.isEmpty()) return false
        val subs = SubscriptionStore.load(context).toMutableList()
        if (subs.any { it.url == u }) return false
        subs.add(Subscription(url = u, name = name?.takeIf { it.isNotBlank() } ?: nameFromUrl(u)))
        SubscriptionStore.save(context, subs)
        return true
    }

    /** Удалить подписку вместе с её серверами. true если что-то удалили. */
    fun remove(context: Context, url: String): Boolean {
        val subs = SubscriptionStore.load(context)
        val kept = subs.filterNot { it.url == url }
        if (kept.size == subs.size) return false
        SubscriptionStore.save(context, kept)
        return true
    }

    /** Скачать + импортировать подписку. БЛОКИРУЮЩИЙ (сеть). */
    fun refresh(context: Context, url: String): RefreshSummary {
        val body = SubscriptionFetcher.fetch(url).getOrElse { e ->
            markFailed(context, url)
            return RefreshSummary(ok = false, error = e.message ?: "fetch failed")
        }
        return importFromBody(context, url, body)
    }

    /**
     * Оффлайн-ядро: тело → decode → parse каждую строку → заменить servers подписки → save.
     * Без сети — тестируемо отдельно. Если подписки с таким url ещё нет, создаётся.
     */
    fun importFromBody(context: Context, url: String, body: String): RefreshSummary {
        val lines = SubscriptionDecoder.decode(body)

        val profiles = ArrayList<ServerProfile>()
        var unsupported = 0
        var invalid = 0
        for (line in lines) {
            when (val r = ServerLinkParser.parse(line)) {
                is ParseResult.Supported -> profiles.add(r.profile)
                is ParseResult.Unsupported -> unsupported++
                is ParseResult.Invalid -> invalid++
            }
        }

        val subs = SubscriptionStore.load(context).toMutableList()
        val idx = subs.indexOfFirst { it.url == url }
        val name = if (idx >= 0) subs[idx].name else nameFromUrl(url)
        val updated = Subscription(
            url = url,
            name = name,
            lastUpdateOk = true,
            lastUpdateTs = now(),
            servers = profiles,          // ре-импорт заменяет серверы целиком
        )
        if (idx >= 0) subs[idx] = updated else subs.add(updated)
        SubscriptionStore.save(context, subs)

        return RefreshSummary(ok = true, added = profiles.size, unsupported = unsupported, invalid = invalid)
    }

    // --- helpers ---

    /** Пометить существующую подписку как неуспешно обновлённую (для индикатора в UI). */
    private fun markFailed(context: Context, url: String) {
        val subs = SubscriptionStore.load(context)
        if (subs.none { it.url == url }) return
        SubscriptionStore.save(context, subs.map {
            if (it.url == url) it.copy(lastUpdateOk = false, lastUpdateTs = now()) else it
        })
    }

    /** Имя из URL — как Python _name_from_url: host + последний сегмент пути (12 симв.). */
    private fun nameFromUrl(url: String): String = try {
        val u = URI(url)
        val host = u.host ?: return url.take(30)
        val seg = u.path.orEmpty().trim('/').substringAfterLast('/', "")
        if (seg.isNotEmpty()) "$host/${seg.take(12)}" else host
    } catch (e: Exception) {
        url.take(30)
    }

    private fun now(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
}
