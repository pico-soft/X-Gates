package com.picosoft.xrayproxydroid.xray

import android.content.Context
import android.util.Log
import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.Blocklist
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

/**
 * ПРИБОРЫ по стоп-листу (Промпт 73.C): подробный дамп КАЖДОГО пересчёта фильтра под тегом "Blocklist",
 * гейт — «Подробные логи» (settings.verboseLogs). Цель — поймать ИНВЕРСИЮ (скрываются серверы, у которых
 * слова НЕТ), которую чтение кода не объясняет. Приборы важнее объяснений (Промпт 73.B): пока причина
 * не найдена — вопрос ОТКРЫТ ([[overrides-and-blocklist-spec]]).
 *
 * Логируем ровно то, что просил Elyor (73.C):
 *  - всего серверов в реестре;
 *  - ОТДЕЛЬНО выборку самого фильтра (список из BootScreen) и выборку чипа «слово (N)»
 *    (SubscriptionManager.allServers) — если это РАЗНЫЕ выборки, они и расходятся (73.D1);
 *  - по каждому серверу: точную remarks, точную displayName, вердикт и ПРИЧИНУ (какое слово совпало /
 *    точечное правило / протокол / пинг / скорость);
 *  - итоги: сколько скрыто каждой причиной, сколько видно в «Живых» и в «Все».
 *
 * Предикаты ВЫЧИСЛЯЕМ ровно как [ServerFilter]/[Blocklist] — не своя копия логики блокировки, а те же
 * функции (matchesWord/isServerBlocked/protocolAllowed); отличие только в том, что здесь ещё и печатаем,
 * КАКОЕ слово совпало (в проде это не нужно).
 */
object BlocklistLog {
    private const val TAG = "Blocklist"

    fun dump(
        context: Context,
        filterServers: List<ServerProfile>,   // ровно тот список, что фильтрует BootScreen (`servers`)
        settings: AppSettings,
        blocklist: Blocklist,
        effPing: (ServerProfile) -> Int?,
        effSpeed: (ServerProfile) -> Double?,
        cause: String,
    ) {
        if (!settings.verboseLogs) return
        val chipServers = SubscriptionManager.allServers(context)   // источник чипа «слово (N)»

        Log.i(TAG, "──────── пересчёт фильтра ($cause) ────────")
        val same = filterServers.size == chipServers.size
        Log.i(TAG, "выборка ФИЛЬТРА (BootScreen.servers)=${filterServers.size} · выборка ЧИПА (allServers)=${chipServers.size}" +
            if (same) " (размеры совпали)" else "  ⚠ РАЗНЫЕ РАЗМЕРЫ")
        val fKeys = filterServers.mapTo(HashSet()) { SubscriptionManager.serverKey(it) }
        val cKeys = chipServers.mapTo(HashSet()) { SubscriptionManager.serverKey(it) }
        val onlyFilter = fKeys - cKeys; val onlyChip = cKeys - fKeys
        if (onlyFilter.isNotEmpty() || onlyChip.isNotEmpty())
            Log.i(TAG, "  ⚠ РАСХОЖДЕНИЕ выборок: только в фильтре=${onlyFilter.size}, только в чипе=${onlyChip.size}")
        Log.i(TAG, "слова=${blocklist.words} · точечных=${blocklist.servers.size} · протоколы=${settings.allowedProtocols} · minMbps=${settings.minUsableMbps}")

        var byWord = 0; var byPoint = 0; var byProto = 0; var noPing = 0; var bySpeed = 0; var visibleAlive = 0
        val wordHits = LinkedHashMap<String, Int>()

        for (p in filterServers) {
            val key = SubscriptionManager.serverKey(p)
            val nameForMatch = p.remarks.ifBlank { p.address }     // ровно то, что матчит ServerFilter.isBlocked
            val custom = blocklist.customName(key)
            val display = custom ?: nameForMatch                    // ровно displayName()
            val point = blocklist.isServerBlocked(key)
            val word = blocklist.words.firstOrNull { w ->
                w.isNotBlank() && (nameForMatch.lowercase().contains(w) || (custom != null && custom.lowercase().contains(w)))
            }
            val protoOk = ServerFilter.protocolAllowed(p, settings)
            val ping = effPing(p); val speed = effSpeed(p)

            val (verdict, reason) = when {
                point -> { byPoint++; "СКРЫТ" to "точечно (serverKey)" }
                word != null -> { byWord++; wordHits[word] = (wordHits[word] ?: 0) + 1; "СКРЫТ" to "слово «$word»" }
                !protoOk -> { byProto++; "СКРЫТ" to "протокол ${p.protocol}" }
                ping == null || ping < 0 -> { noPing++; "НЕ В ЖИВЫХ" to "нет пинга (в «Все» присутствует)" }
                speed != null && speed < settings.minUsableMbps -> { bySpeed++; "СКРЫТ-живые" to "скорость $speed < ${settings.minUsableMbps}" }
                else -> { visibleAlive++; "ВИДЕН" to "—" }
            }
            Log.i(TAG, "  [$verdict] remarks=\"${p.remarks}\" display=\"$display\" ключ=…${key.takeLast(8)} → $reason")
        }

        // «Все» = не скрыт словом/точечно/протоколом (пинг НЕ требуется); «Живые» = ещё пинг есть и скорость ок.
        val shownAll = filterServers.count {
            val k = SubscriptionManager.serverKey(it)
            !blocklist.isBlocked(it.remarks.ifBlank { it.address }, blocklist.customName(k), k) && ServerFilter.protocolAllowed(it, settings)
        }
        Log.i(TAG, "ИТОГ: скрыто словом=$byWord ${if (wordHits.isNotEmpty()) wordHits else "{}"} · точечно=$byPoint · протокол=$byProto · без-пинга(не в Живых)=$noPing · скорость=$bySpeed || ВИДНЫ: Живые=$visibleAlive, «Все»=$shownAll")
    }
}
