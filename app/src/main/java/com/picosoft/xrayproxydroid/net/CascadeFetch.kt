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
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * ОБЩИЙ каскад загрузки внешнего ресурса (подписки, обновляемый конфиг, проверка версии и САМ APK
 * обновления — всё на GitHub, из РФ нестабильно, а туннель часто поднят). Ступени пробуются по
 * порядку; переход по ЛЮБОЙ неудаче (таймаут/код≠200/пусто/TLS/«скачано, но контент не подошёл» —
 * решает [acceptBody]). Неприменимая ступень ПРОПУСКАЕТСЯ (не неудача, не засоряет отчёт).
 *
 * Порядок (Промпт 51 + 54):
 *   1. напрямую;
 *   2. через свой активный SOCKS (если прокси поднят);
 *   3. напрямую МИМО системного VPN (bind к физической сети — только если МЫ ВНУТРИ VPN и ещё не мимо);
 *   4. ЧЕРЕЗ системный VPN (явно на VPN-сети — только если мы внутри; единственный путь при lockdown);
 *   5. через системный прокси (ConnectivityManager.getDefaultProxy — если задан);
 *   6. temp-инстанс на недавно рабочих серверах (до 5, только для ТЕКСТОВОГО [fetch], не во время теста).
 *
 * Отношение к VPN берём из [SystemVpnState] (его вычисляет сервис, сняв нашу привязку — иначе наша же
 * привязка путала бы детекцию). Ступени 3 и 4 осмысленны ТОЛЬКО в состоянии INSIDE.
 *
 * ТЕКСТОВЫЙ путь ([fetch]) и БИНАРНЫЙ путь ([download]) делят один планировщик ступеней [httpStagePlans]
 * — тонкая VPN-логика ступеней 1–5 живёт в ОДНОМ месте (Промпт 70: APK обновления тоже через каскад,
 * ровно ради чего каскад и делался общим). Отличие: text читает тело в строку и добавляет 6-ю ступень
 * (temp-инстанс), binary стримит в файл с прогрессом/отменой и 6-ю ступень не поддерживает.
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
    val note: String = "",     // причина пропуска / имя сервера для ступени 6 / краткий исход бинарной ступени
)

data class CascadeResult(
    val ok: Boolean,
    val stage: FetchStage?,
    val result: FetchResult?,
    val attempts: List<CascadeAttempt>,
)

/** Исход бинарной загрузки: какой ступенью удалось, сколько байт, отмена, поступенчатый отчёт. */
data class CascadeDownloadResult(
    val ok: Boolean,
    val stage: FetchStage?,
    val bytes: Long,
    val attempts: List<CascadeAttempt>,
    val cancelled: Boolean = false,
)

object CascadeFetch {

    private const val MAX_TEMP_CANDIDATES = 5
    private const val MAX_REDIRECTS = 5
    private const val DOWNLOAD_BUFFER = 64 * 1024

    /** План одной HTTP-ступени: либо [open] (пробуем), либо [skipNote]≠"" (пропускаем с причиной). */
    private class StagePlan(
        val stage: FetchStage,
        val timeoutMs: Int,
        val open: ((URL) -> java.net.URLConnection)?,
        val skipNote: String,
    )

    /**
     * Построить ступени 1–5 в порядке каскада. Каждая — либо открыватель соединения, либо пропуск с
     * причиной. overBudget/тело/стрим — забота вызывающего (text vs binary), сама VPN-логика тут ОДНА.
     */
    private fun httpStagePlans(
        cm: ConnectivityManager?,
        directTimeoutMs: Int,
        proxyTimeoutMs: Int,
    ): List<StagePlan> {
        val plans = ArrayList<StagePlan>(5)

        // 1 — напрямую (всегда пробуем)
        plans.add(StagePlan(FetchStage.DIRECT, directTimeoutMs, { it.openConnection() }, ""))

        // 2 — через свой активный SOCKS
        if (!ProxyState.state.value.running)
            plans.add(StagePlan(FetchStage.OWN_PROXY, proxyTimeoutMs, null, "прокси не запущен"))
        else
            plans.add(StagePlan(FetchStage.OWN_PROXY, proxyTimeoutMs, {
                it.openConnection(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", XrayConfig.SOCKS_PORT)))
            }, ""))

        // Отношение НАШЕГО трафика к VPN — из единого источника (сервис вычислил, сняв нашу привязку).
        val vpn = SystemVpnState.state.value

        // 3 — напрямую МИМО системного VPN. Осмысленно ТОЛЬКО когда мы ВНУТРИ VPN и ещё НЕ идём мимо
        //     (если bypassed — ступень 1 уже шла по физической сети через нашу привязку → дубликат).
        when {
            vpn.relation != VpnRelation.INSIDE ->
                plans.add(StagePlan(FetchStage.NON_VPN_DIRECT, proxyTimeoutMs, null,
                    if (vpn.relation == VpnRelation.EXCLUDED) "мы уже вне VPN" else "мы не внутри VPN"))
            vpn.bypassed ->
                plans.add(StagePlan(FetchStage.NON_VPN_DIRECT, proxyTimeoutMs, null, "уже идём мимо (привязка процесса)"))
            else -> {
                val net = if (cm != null) NetworkUtils.physicalNetwork(cm) else null
                if (net == null) plans.add(StagePlan(FetchStage.NON_VPN_DIRECT, proxyTimeoutMs, null, "нет физической сети WIFI/CELLULAR"))
                else plans.add(StagePlan(FetchStage.NON_VPN_DIRECT, proxyTimeoutMs, { net.openConnection(it) }, ""))
            }
        }

        // 4 — ЧЕРЕЗ системный VPN (явно на VPN-сети). Единственный путь при lockdown («блокировать без VPN»).
        //     Осмысленно ТОЛЬКО в INSIDE (в чужой туннель мы попадаем, лишь будучи внутри него).
        when {
            vpn.relation != VpnRelation.INSIDE ->
                plans.add(StagePlan(FetchStage.VIA_VPN, proxyTimeoutMs, null,
                    if (vpn.relation == VpnRelation.EXCLUDED) "мы вне VPN — в чужой туннель не попасть" else "VPN не активен"))
            else -> {
                val vpnNet = if (cm != null) NetworkUtils.vpnNetwork(cm) else null
                if (vpnNet == null) plans.add(StagePlan(FetchStage.VIA_VPN, proxyTimeoutMs, null, "нет VPN-сети"))
                else plans.add(StagePlan(FetchStage.VIA_VPN, proxyTimeoutMs, { vpnNet.openConnection(it) }, ""))
            }
        }

        // 5 — через системный прокси (Wi-Fi настройки / другое приложение)
        val sysProxy = if (cm != null) systemProxy(cm) else null
        if (sysProxy == null) plans.add(StagePlan(FetchStage.SYSTEM_PROXY, proxyTimeoutMs, null, "системный прокси не задан"))
        else plans.add(StagePlan(FetchStage.SYSTEM_PROXY, proxyTimeoutMs, { it.openConnection(sysProxy) }, ""))

        return plans
    }

    private fun skip(stage: FetchStage, note: String) =
        CascadeAttempt(stage, skipped = true, accepted = false, result = null, note = note)

    /** ТЕКСТОВАЯ загрузка (подписки/JSON): тело в строку, плюс 6-я ступень temp-инстанс. */
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

        // Ступени 1–5 из общего планировщика.
        for ((i, plan) in httpStagePlans(cm, directTimeoutMs, proxyTimeoutMs).withIndex()) {
            if (plan.open == null) { attempts.add(skip(plan.stage, plan.skipNote)); continue }
            if (i > 0 && overBudget()) { attempts.add(skip(plan.stage, "общий таймаут")); continue }
            val r = SubscriptionFetcher.fetch(url, userAgent, plan.timeoutMs, plan.open)
            val ok = acceptBody(r)
            attempts.add(CascadeAttempt(plan.stage, skipped = false, accepted = ok, result = r))
            if (ok) return CascadeResult(true, plan.stage, r, attempts)
        }

        // 6 — temp-инстанс на недавно рабочих серверах (только текстовый путь)
        if (MonitorCoordinator.fullTestRunning || MonitorCoordinator.monitorSearchRunning) {
            attempts.add(skip(FetchStage.TEMP_RECENT, "идёт полный тест / перебор монитора"))
        } else {
            val candidates = SubscriptionManager.recentWorkingServers(app).take(MAX_TEMP_CANDIDATES)
            if (candidates.isEmpty()) attempts.add(skip(FetchStage.TEMP_RECENT, "нет недавно рабочих серверов"))
            else for (c in candidates) {
                if (overBudget()) { attempts.add(skip(FetchStage.TEMP_RECENT, "общий таймаут")); break }
                val name = c.remarks.ifBlank { c.address }
                val r = ServerSpeedTester.fetchViaTempInstance(app, c, url, userAgent, proxyTimeoutMs)
                val ok = r != null && acceptBody(r)
                attempts.add(CascadeAttempt(FetchStage.TEMP_RECENT, skipped = false, accepted = ok, result = r, note = name))
                if (ok) return CascadeResult(true, FetchStage.TEMP_RECENT, r, attempts)
            }
        }

        return CascadeResult(false, null, null, attempts)
    }

    /**
     * БИНАРНАЯ загрузка (APK обновления): стрим в [dest] с прогрессом и отменой, те же ступени 1–5.
     * 6-ю ступень (temp-инстанс) для бинарного стрима не поддерживаем (нет API отдачи файла через
     * temp-инстанс) — для метаданных обновления (JSON, килобайты) их и так тянет [fetch].
     *
     * [onProgress] (скачано, всего): всего=-1, если сервер не отдал Content-Length и [expectedSize] нет.
     * [isCancelled] опрашивается между чтениями — отмена оставляет частичный файл вызывающему на удаление.
     */
    fun download(
        context: Context,
        url: String,
        userAgent: String,
        dest: File,
        directTimeoutMs: Int,
        proxyTimeoutMs: Int,
        totalTimeoutMs: Int,
        expectedSize: Long = -1,
        isCancelled: () -> Boolean = { false },
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> },
    ): CascadeDownloadResult {
        val app = context.applicationContext
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val attempts = ArrayList<CascadeAttempt>()
        val start = System.nanoTime()
        fun overBudget() = (System.nanoTime() - start) / 1_000_000 > totalTimeoutMs

        for ((i, plan) in httpStagePlans(cm, directTimeoutMs, proxyTimeoutMs).withIndex()) {
            if (plan.open == null) { attempts.add(skip(plan.stage, plan.skipNote)); continue }
            if (i > 0 && overBudget()) { attempts.add(skip(plan.stage, "общий таймаут")); continue }
            if (isCancelled()) return CascadeDownloadResult(false, null, 0, attempts, cancelled = true)

            val res = streamTo(url, userAgent, plan.timeoutMs, plan.open, dest, expectedSize, isCancelled, onProgress)
            when {
                res.cancelled -> {
                    attempts.add(CascadeAttempt(plan.stage, skipped = false, accepted = false, result = null, note = "отменено"))
                    return CascadeDownloadResult(false, null, res.bytes, attempts, cancelled = true)
                }
                res.ok -> {
                    attempts.add(CascadeAttempt(plan.stage, skipped = false, accepted = true, result = null, note = "OK, ${res.bytes} б"))
                    return CascadeDownloadResult(true, plan.stage, res.bytes, attempts)
                }
                else -> attempts.add(CascadeAttempt(plan.stage, skipped = false, accepted = false, result = null, note = res.note))
            }
        }

        attempts.add(skip(FetchStage.TEMP_RECENT, "бинарная загрузка через temp-инстанс не поддерживается"))
        return CascadeDownloadResult(false, null, 0, attempts)
    }

    private class StreamOutcome(val ok: Boolean, val bytes: Long, val note: String, val cancelled: Boolean = false)

    /** Одна попытка стрима в файл: проходим редиректы (в т.ч. GitHub asset → CDN), пишем чанками. */
    private fun streamTo(
        url: String, userAgent: String, timeoutMs: Int,
        open: (URL) -> java.net.URLConnection, dest: File, expectedSize: Long,
        isCancelled: () -> Boolean, onProgress: (Long, Long) -> Unit,
    ): StreamOutcome {
        var current = url
        var redirects = 0
        try {
            while (true) {
                if (isCancelled()) return StreamOutcome(false, 0, "отменено", cancelled = true)
                val conn = (open(URL(current)) as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = timeoutMs
                    readTimeout = timeoutMs
                    instanceFollowRedirects = false   // редиректы проходим сами (в т.ч. смена схемы)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept", "*/*")
                }
                val code = try {
                    conn.responseCode
                } catch (e: Exception) {
                    conn.disconnect()
                    return StreamOutcome(false, 0, "сеть: ${e.javaClass.simpleName}")
                }
                if (code in 300..399 && redirects < MAX_REDIRECTS) {
                    val loc = conn.getHeaderField("Location")
                    conn.disconnect()
                    if (loc.isNullOrBlank()) return StreamOutcome(false, 0, "редирект без Location")
                    current = URL(URL(current), loc).toString()
                    redirects++
                    continue
                }
                if (code !in 200..299) { conn.disconnect(); return StreamOutcome(false, 0, "HTTP $code") }

                val total = if (conn.contentLengthLong > 0) conn.contentLengthLong else expectedSize
                var downloaded = 0L
                try {
                    dest.outputStream().use { out ->
                        conn.inputStream.use { inp ->
                            val buf = ByteArray(DOWNLOAD_BUFFER)
                            while (true) {
                                if (isCancelled()) { conn.disconnect(); return StreamOutcome(false, downloaded, "отменено", cancelled = true) }
                                val n = inp.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                onProgress(downloaded, total)
                            }
                        }
                    }
                } catch (e: Exception) {
                    conn.disconnect()
                    return StreamOutcome(false, downloaded, "обрыв: ${e.javaClass.simpleName}")
                }
                conn.disconnect()
                return StreamOutcome(true, downloaded, "ok")
            }
        } catch (e: Exception) {
            return StreamOutcome(false, 0, "ошибка: ${e.javaClass.simpleName}")
        }
    }

    /** Системный HTTP-прокси (host:port). PAC-прокси не поддерживаем → null. */
    private fun systemProxy(cm: ConnectivityManager): Proxy? {
        val pi = cm.defaultProxy ?: return null
        val host = pi.host
        if (host.isNullOrEmpty() || pi.port <= 0) return null
        return Proxy(Proxy.Type.HTTP, InetSocketAddress(host, pi.port))
    }
}
