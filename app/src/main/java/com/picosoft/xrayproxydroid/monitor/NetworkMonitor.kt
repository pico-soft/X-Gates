package com.picosoft.xrayproxydroid.monitor

import android.content.Context
import android.net.ConnectivityManager
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.settings.BlocklistStore
import com.picosoft.xrayproxydroid.settings.SettingsStore
import com.picosoft.xrayproxydroid.service.NotificationHelper
import com.picosoft.xrayproxydroid.service.ProxyState
import com.picosoft.xrayproxydroid.service.XrayProxyService
import com.picosoft.xrayproxydroid.subscription.SubscriptionManager
import com.picosoft.xrayproxydroid.traffic.TrafficTracker
import com.picosoft.xrayproxydroid.xray.ExternalIpChecker
import com.picosoft.xrayproxydroid.xray.ServerFilter
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester
import com.picosoft.xrayproxydroid.xray.ServerTester
import com.picosoft.xrayproxydroid.xray.XrayConfig
import com.picosoft.xrayproxydroid.xray.XrayConfigBuilder
import com.picosoft.xrayproxydroid.xray.XrayController
import kotlinx.coroutines.delay
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.math.roundToInt

/**
 * Цикл АВТОМОНИТОРИНГА. Корутина в foreground-сервисе; запускается ТОЛЬКО когда монитор включён И прокси
 * активен (иначе не работает вообще — сервис не создаёт эту корутину, ради батареи). Включён — следит И
 * ПЕРЕКЛЮЧАЕТ (режима «только наблюдать» больше нет).
 *
 * Порядок цикла:
 *   0. Сигнал A (нет интернета) — В САМОМ НАЧАЛЕ, ДО перебора. Нет сети → пауза с удвоением (10м→4ч),
 *      сброс по событию ConnectivityManager / действию пользователя ([MonitorCoordinator.awaitWake]).
 *   1. Сигнал B (внешний IP через активный SOCKS) — туннель жив? да → рутина, ничего не пишем.
 *   2. Тяжёлый замер (прямой русскими CDN + туннель зарубежным) только при провале B.
 *   3. Падение (N неудач подряд) → перебор кандидатов и переключение.
 *
 * Перебор: ВЕСЬ список сверху вниз (от быстрых к медленным по известной скорости); дешёвая проверка
 * (temp-инстанс, реальный запрос через ServerTester.ping) → подключаемся к ПЕРВОМУ живому (связь важнее
 * точного числа), скорость меряем ПОСЛЕ подключения. Не подошёл никто → обновить подписки → ещё проход.
 * Опять никто → предложить включить выключенные источники (не включаем сами) → пауза с удвоением.
 */
object NetworkMonitor {
    private const val TAG = "NetworkMonitor"

    private val directDnsProbes = listOf("77.88.8.8" to 53, "8.8.8.8" to 53, "1.1.1.1" to 53)

    // Пр.140: режим белых списков — интервалы. Источники обновляем раз в полчаса; оценку сети — раз в полчаса
    // в норме, но раз в минуту при проблемах (нет серверов/восстановление/нет интернета), чтобы быстро распознать.
    private const val WHITE_REFRESH_MS = 30 * 60_000L
    private const val WHITE_EVAL_NORMAL_MS = 30 * 60_000L
    private const val WHITE_EVAL_PROBLEM_MS = 60_000L

    private const val SWITCH_THROTTLE_MS = 60_000L        // в фоне переключаться не чаще раза в 60с
    // Пауза при «НЕТ РАБОЧИХ СЕРВЕРОВ» (интернет есть, но ни один сервер не поднялся): удвоение 10 мин → 4 ч.
    private const val BACKOFF_START_MS = 10 * 60_000L     // первая пауза «нет серверов» — 10 минут
    // Верхний предел паузы — 4 часа: дальше удваивать бессмысленно (это уже «редкая фоновая проверка»),
    // а ConnectivityManager всё равно разбудит мгновенно при появлении сети; экономим батарею при долгом офлайне.
    private const val BACKOFF_MAX_MS = 4 * 3600_000L

    // НЕТ ИНТЕРНЕТА (прямой канал мёртв): туннель чинить бессмысленно — НЕ трогаем ядро. Честно «нет связи» и
    // ПЕРЕСПРАШИВАЕМ прямой канал по нарастающей: 1→2→4→8→16→…→30 мин, пока интернет не появится. Событие сети
    // (ConnectivityManager) поднимает мгновенно; экспонента — лишь верхняя граница проактивной проверки.
    private const val NO_NET_BACKOFF_START_MS = 60_000L        // первый повтор — через 1 мин
    private const val NO_NET_BACKOFF_MAX_MS = 30 * 60_000L     // потолок повтора — 30 мин

    // Живая скорость плашки: не чаще раза в ~50с (совпадает с циклом при connectionCheckIntervalSec=60);
    // окно пассивного замера — 6с (счётчик трафика тикает раз в POLL_MS=2.5с → окно должно перекрыть ≥2 тика,
    // иначе дельта случайно 0 при живом трафике); порог «трафик идёт» — 32 КБ за окно (keepalive < этого).
    private const val SPEED_SAMPLE_MIN_GAP_MS = 50_000L
    private const val SPEED_PASSIVE_WINDOW_MS = 6_000L
    private const val SPEED_PASSIVE_MIN_BYTES = 32L * 1024L

    // Пр.143: анти-дребезг обрыва БЕЗ 60-сек ожидания. Раньше первая осечка внешнего IP → continue → верх цикла
    // ждал monitorProblemIntervalSec (60с) до второй проверки, и «восстановление» тянулось ~2 мин. Теперь обрыв
    // подтверждаем БЫСТРО, тут же в цикле: короткие перепроверки IP с этой паузой; удачная → это была миганка.
    private const val ANTIFLAP_RETRY_MS = 2_500L

    // Пр.143: поддержание списка «Живые». Пока активный ОК — периодически пингуем живых (свежий пинг ≥0) и
    // отсеиваем не отозвавшихся (pingMs=−1 → уходит из «Живых»), чтобы список был честным, а быстрый подбор при
    // обрыве брал реально живого. Пинг стоит килобайты (принцип Пр.127), поэтому дёшев; замеры скорости тут НЕ трогаем.
    private const val LIVE_PRUNE_INTERVAL_MS = 10 * 60_000L

    private enum class SwitchResult { SWITCHED, ABORTED, NO_CANDIDATES }

    // Промпт 102 (ГЛАВНЫЙ ФИКС): ключ сервера, ядро которого УЖЕ перезапускали в текущем эпизоде обрыва.
    // ПОЧЕМУ: перезапуск ядра (шаг 1 лестницы) через XrayProxyService.start ставит running=false → сервис
    // пересоздаёт корутину монитора и УБИВАЕТ идущую лестницу ещё ДО перебора серверов; новый монитор снова
    // делает шаг 1 → ВЕЧНЫЙ ЦИКЛ на мёртвом сервере (подтверждено логами P102). Поле object'а переживает рестарт
    // монитора: один и тот же сервер повторно НЕ перезапускаем — сразу к перебору живых. Сбрасывается при OK.
    @Volatile private var restartedKeySinceOk: String? = null
    // Пр.127.C: когда последний раз делали ШАГ по деградации (throttle между шагами). Object-поле — переживает
    // пересоздание корутины монитора (как restartedKeySinceOk), чтобы throttle не сбрасывался при рестарте.
    @Volatile private var lastDegradationMs = 0L

    /**
     * Промпт 95 — ФАКТ-ПЕРВЫЙ цикл непрерывности связи. Работает ВСЕГДА, пока прокси активен (НЕ гейтится
     * monitorEnabled — это ОСНОВНОЙ принцип, а не опция; monitorEnabled гейтит лишь вторичную оптимизацию
     * «держать лучший»). ЖИВОСТЬ = ФАКТ прохождения запроса (внешний IP через SOCKS). Провал = мёртвый
     * туннель → немедленная лестница восстановления. Проверка идёт и по расписанию, и по событиям (wake()).
     */
    suspend fun loop(context: Context) {
        val app = context.applicationContext
        var failures = 0
        var backoffMs = 0L
        var noNetBackoffMs = 0L         // экспонента повтора прямого канала при «нет интернета» (1→2→4→…→30 мин)
        var cycles = 0
        var lastSwitchMs = 0L
        var lastOptimizeMs = now()
        // Пр.126.A: «через туннель давно нет трафика» — предохранитель перемера. Считаем ФАКТ трафика по
        // приросту туннельных байт между циклами (temp-инстансы кандидатов сюда НЕ попадают; активная проба
        // плашки попадает, но она под screenInteractive — при выключенном экране ночью пула нет → детектор чист).
        var lastUserTrafficMs = now()
        var lastTunnelBytes = 0L
        var lastSpeedSampleMs = 0L      // живая скорость плашки: когда последний раз сэмплировали
        var lastActiveProbeMs = 0L      // когда последний раз делали АКТИВНУЮ пробу (в простое)
        var lastExactAlarm = false      // Промпт 118: последний тик поставлен ТОЧНЫМ будильником? (для лога/диагностики)
        var lastWhiteEvalMs = 0L        // Пр.140: когда последний раз оценивали режим белых списков
        var lastWhiteRefreshMs = 0L     // Пр.140: когда последний раз обновляли источники белого списка
        var lastEconomyRecheckMs = now()   // Пр.141: когда последний раз проверяли активный сервер в экономии
        var lastLivePruneMs = now()        // Пр.143: когда последний раз пинговали живых для прунинга списка
        val cycleLock = MonitorAlarm.newWakeLock(app)   // держит CPU на время ОДНОГО цикла (иначе уснёт посреди пробы)
        Log.i(TAG, "monitor loop started")

        try {
        while (true) {
            if (!XrayController.isRunning || !ProxyState.state.value.running) { TunnelHealth.reset(); TunnelSpeed.clear(); return }

            // Интервал: при восстановлении/нет-серверов — чаще (быстрее вернуть связь); в норме — обычный.
            // Прерываемый wake() — событийные триггеры (сеть/экран/передний план) поднимают ДОСРОЧНО.
            val s = SettingsStore.current()
            val ph = TunnelHealth.snapshot().phase
            val waitMs = when (ph) {
                // Нет интернета — переспрашиваем прямой канал по экспоненте (1→2→…→30 мин), не чаще.
                TunnelHealth.Phase.NO_INTERNET -> noNetBackoffMs.coerceAtLeast(NO_NET_BACKOFF_START_MS)
                // Восстановление туннеля / нет серверов — чаще (быстрее вернуть связь).
                TunnelHealth.Phase.RECOVERING, TunnelHealth.Phase.NO_SERVERS -> s.monitorProblemIntervalSec.coerceAtLeast(15) * 1000L
                else -> s.connectionCheckIntervalSec.coerceAtLeast(15) * 1000L
            }
            // ── СОН (Промпт 118): отпустить CPU, поставить ТОЧНЫЙ будильник (срабатывает и в Doze), ждать его ИЛИ
            // событие (экран/сеть). Корутинный awaitWake здесь — лишь ЗАПАСНОЙ предел: в Doze он растягивается
            // вместе с CPU (корень бага 111), реальный тик приносит будильник. Без точных будильников (система
            // отказала) awaitWake и работает как раньше — деградация, а не молчание.
            releaseCycleLock(cycleLock)
            lastExactAlarm = MonitorAlarm.scheduleNext(app, waitMs)
            MonitorCoordinator.drainWakeups()
            MonitorCoordinator.awaitWake(waitMs)
            MonitorAlarm.cancel(app)
            acquireCycleLock(cycleLock)

            val cur = SettingsStore.current()
            if (!XrayController.isRunning || !ProxyState.state.value.running) { TunnelHealth.reset(); TunnelSpeed.clear(); return }
            if (MonitorCoordinator.fullTestRunning) {
                // Тест сам управляет переключением — лестницу НЕ запускаем. Но статус держим ЧЕСТНЫМ:
                // нет интернета → сказать; иначе не трогаем (факт подтвердит UI-проверка/следующий цикл).
                if (!directAlive()) TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
                continue
            }
            cycles++
            Log.i(TAG, "cycle $cycles (exactAlarm=$lastExactAlarm, wait=${waitMs / 1000}s, phase=$ph)")

            // Пр.140: режим белых списков — авто-обновление источников (раз в полчаса) + оценка сети. Оценка чаще
            // при проблемах (быстрее распознать «только белый список»), иначе раз в полчаса. Пинг/живость это НЕ
            // трогает — детектор лишь сужает эффективный список серверов (allServers) через whiteListModeActive.
            if (cur.whiteListAutoEnabled) {
                if (now() - lastWhiteRefreshMs >= WHITE_REFRESH_MS) {
                    lastWhiteRefreshMs = now()
                    runCatching { SubscriptionManager.refreshWhiteListSources(app) }
                        .onFailure { MonitorLog.event(app, "error", "Ошибка обновления белых списков", it.message ?: "") }
                }
                val problem = ph == TunnelHealth.Phase.NO_SERVERS || ph == TunnelHealth.Phase.RECOVERING || ph == TunnelHealth.Phase.NO_INTERNET
                val evalDue = now() - lastWhiteEvalMs >= (if (problem) WHITE_EVAL_PROBLEM_MS else WHITE_EVAL_NORMAL_MS)
                if (evalDue) {
                    lastWhiteEvalMs = now()
                    runCatching { WhiteListDetector.evaluate(app, tunnelForeignOk = false) }
                        .onFailure { MonitorLog.event(app, "error", "Ошибка оценки белых списков", it.message ?: "") }
                }
            } else if (cur.whiteListModeActive) {
                // Фичу выключили в настройках, а режим ещё активен → сбросить в обычный.
                runCatching { WhiteListDetector.evaluate(app, tunnelForeignOk = false) }
            }
            // Пр.126.A: отметить факт трафика через туннель (прирост байт с прошлого цикла > порога).
            val bytesNow = TrafficTracker.state.value.let { it.sessionRx + it.sessionTx }
            if (lastTunnelBytes in 1..bytesNow && bytesNow - lastTunnelBytes > OPTIMIZE_IDLE_TRAFFIC_MIN_BYTES) lastUserTrafficMs = now()
            lastTunnelBytes = bytesNow

            // ⭐ ПРИНЦИП (Пр.127.A): проверка живости туннеля (СИГНАЛ A directAlive + СИГНАЛ B внешний IP через SOCKS)
            // идёт КАЖДЫЙ цикл с интервалом [connectionCheckIntervalSec] — ОДИНАКОВО в обоих режимах. Режим экономии
            // (trafficSaveMode) её НЕ касается и НЕ разрежает: проверка стоит килобайты, ради неё приложение и живёт.
            // Экономия трогает ТОЛЬКО замеры СКОРОСТИ (мегабайты). Не вздумать «экономить» на проверке живости.
            // ── СИГНАЛ A: есть ли интернет ВООБЩЕ (прямой канал) — отличаем «нет интернета» от «нет серверов» ──
            // Нет интернета → туннель чинить БЕССМЫСЛЕННО (ядро НЕ трогаем): честно «нет связи» + переспрос
            // прямого канала по экспоненте (пауза берётся вверху по фазе NO_INTERNET). Интернет вернётся → ниже
            // проверим туннель и активируем его по нашим правилам (лестница).
            if (!directAlive()) {
                failures = 0
                noNetBackoffMs = enterNoInternet(app, noNetBackoffMs, cycles)
                continue
            }
            noNetBackoffMs = 0L   // интернет есть — сбросить экспоненту повтора

            // ── СИГНАЛ B (ФАКТ): реальный запрос через SOCKS вернул внешний IP = связь РАБОТАЕТ ──
            val ip = ExternalIpChecker.fetch()
            if (ip != null) {
                onRecovered(app)
                TunnelHealth.ok(ip, now(), ProxyState.state.value.serverKey ?: "")   // Пр.123.B: подтверждение принадлежит активному серверу
                failures = 0; backoffMs = 0; restartedKeySinceOk = null   // Промпт 102: связь ок — сбросить «уже перезапускали»
                MonitorStatus.update(true, "ок", now(), cycles)
                // ЖИВАЯ скорость плашки (гибрид): раз в цикл сэмплируем — реальный трафик даёт скорость
                // бесплатно, иначе активная проба в простое. Держим ↓/↑ и время замера свежими.
                if (cur.liveSpeedEnabled && now() - lastSpeedSampleMs >= SPEED_SAMPLE_MIN_GAP_MS) {
                    lastSpeedSampleMs = now()
                    lastActiveProbeMs = runCatching { sampleTunnelSpeed(app, cur, lastActiveProbeMs) }
                        .onFailure { MonitorLog.event(app, "error", "Ошибка замера скорости", it.message ?: "") }
                        .getOrDefault(lastActiveProbeMs)
                }
                // Пр.143: активный ОК → «продолжаем пинг живых, убирая тех, кто не отозвался». Периодически (раз в
                // LIVE_PRUNE_INTERVAL_MS) и не во время активной загрузки — чтобы список «Живые» был честным и быстрый
                // подбор при обрыве брал реально живого. Только ПИНГ (килобайты); скорость тут не мерим.
                if (now() - lastLivePruneMs >= LIVE_PRUNE_INTERVAL_MS && !userTrafficActive()) {
                    lastLivePruneMs = now()
                    runCatching { pruneLiveByPing(app, cur) }
                        .onFailure { MonitorLog.event(app, "error", "Ошибка пинга живых", it.message ?: "") }
                }
                // Вторичное: «держать лучший». Пр.126.B: ДЕФОЛТ ВЫКЛ (monitorOptimizeSec=0) — замеры активного и
                // кандидата несопоставимы, решения шли бы по шуму. Пр.125.E: в экономии тоже выкл. Когда пользователь
                // включил — предохранители Пр.126.A (Wi-Fi / давно-нет-трафика / текущий-уже-быстрый) + не во время
                // активной загрузки (чтобы не делить канал и не портить замер).
                if (!cur.trafficSaveMode && cur.monitorEnabled && cur.activeOptimizeSec > 0 && now() - lastOptimizeMs >= cur.activeOptimizeSec * 1000L) {
                    lastOptimizeMs = now()
                    val skip = optimizeSkipReason(app, cur, lastUserTrafficMs)
                    when {
                        skip != null -> MonitorLog.event(app, "monitor", "Перемер пропущен (Пр.126)", skip)
                        userTrafficActive() -> { /* идёт загрузка — не мешаем и не портим замер */ }
                        else -> runCatching { optimizeToFastest(app, cur) }.onFailure { MonitorLog.event(app, "error", "Ошибка оптимизации", it.message ?: "") }
                    }
                }
                // Пр.141: в ЭКОНОМИИ автоперемер выше выключен, поэтому полу-живой сервер (IP-чек проходит, но
                // реально плохо) сам не чинится. Раз в economyRecheckSec — ОДИН дешёвый замер достаточности активного;
                // если ниже порога деградации, шагаем на лучшего кандидата (переиспользуем maybeStepFromDegraded).
                val recheckKey = ProxyState.state.value.serverKey
                if (cur.trafficSaveMode && cur.economyRecheckSec > 0 && !userTrafficActive() && recheckKey != null &&
                    now() - lastEconomyRecheckMs >= cur.economyRecheckSec * 1000L) {
                    lastEconomyRecheckMs = now()
                    val curSrv = SubscriptionManager.allServers(app).firstOrNull { SubscriptionManager.serverKey(it) == recheckKey }
                    if (curSrv != null) runCatching {
                        val mbps = ServerSpeedTester.measureSufficiency(app, curSrv, cur.sufficientMbps.coerceAtLeast(0.1))
                        MonitorLog.event(app, "monitor", "Экономия: ре-чек активного", "${fmt(mbps)} Мбит/с")
                        if (mbps in 0.0..cur.degradationMinMbps) maybeStepFromDegraded(app, cur, recheckKey, mbps)
                    }.onFailure { MonitorLog.event(app, "error", "Ошибка ре-чека экономии", it.message ?: "") }
                }
                continue
            }

            // Живой трафик пользователя — тоже ФАКТ работы туннеля (сигнал B мог мигнуть).
            if (userTrafficActive()) {
                onRecovered(app)
                TunnelHealth.ok(TunnelHealth.snapshot().ip, now(), ProxyState.state.value.serverKey ?: "")
                failures = 0; backoffMs = 0; restartedKeySinceOk = null   // Промпт 102
                MonitorStatus.update(true, "ок (активный трафик)", now(), cycles)
                continue
            }

            // Туннель не подтвердился. ПЕРЕПРОВЕРИТЬ прямой канал: интернет мог пропасть между сигналами A и B
            // (или мигал). Нет интернета → это НЕ поломка туннеля — ядро НЕ перезапускаем, честно «нет связи».
            if (!directAlive()) {
                failures = 0
                noNetBackoffMs = enterNoInternet(app, noNetBackoffMs, cycles)
                continue
            }

            // ── ИНТЕРНЕТ ЕСТЬ, но туннель не пропускает ──
            // Пр.143: подтвердить обрыв БЫСТРО, не отпуская цикл на 60с. Короткие перепроверки внешнего IP прямо
            // здесь (monitorFailuresToVerdict−1 раз с паузой ANTIFLAP_RETRY_MS): любая удачная → это была миганка,
            // остаёмся; интернет пропал по ходу → это не поломка туннеля. Так «восстановление» стартует за секунды.
            TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "восстановление…")
            MonitorStatus.update(true, "проверяю связь…", now(), cycles)
            var flapRecovered = false
            var flapLostInternet = false
            for (n in 1 until cur.monitorFailuresToVerdict.coerceAtLeast(1)) {
                delay(ANTIFLAP_RETRY_MS)
                if (!directAlive()) { flapLostInternet = true; break }
                if (ExternalIpChecker.fetch() != null) { flapRecovered = true; break }
            }
            if (flapRecovered) {
                onRecovered(app)
                TunnelHealth.ok(TunnelHealth.snapshot().ip, now(), ProxyState.state.value.serverKey ?: "")
                failures = 0; backoffMs = 0; restartedKeySinceOk = null
                MonitorStatus.update(true, "ок", now(), cycles)
                continue
            }
            if (flapLostInternet || !directAlive()) {   // интернет ушёл во время перепроверок — не вина туннеля
                failures = 0
                noNetBackoffMs = enterNoInternet(app, noNetBackoffMs, cycles)
                continue
            }

            // Обрыв ПОДТВЕРЖДЁН → восстановление: сперва БЫСТРЫЙ ping-подбор живых, при неудаче — тщательно.
            failures = 0
            MonitorLog.event(app, "monitor", "Связь прервалась — ищу живой сервер", "внешний IP не получен")
            when (runRecoveryLadder(app, cur)) {
                SwitchResult.SWITCHED -> {
                    lastSwitchMs = now(); failures = 0; backoffMs = 0
                    val ip2 = ExternalIpChecker.fetch()   // подтвердить ФАКТОМ сразу
                    if (ip2 != null) { TunnelHealth.ok(ip2, now(), ProxyState.state.value.serverKey ?: ""); onRecovered(app) }
                    else TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "проверяю после переключения…")
                }
                SwitchResult.ABORTED -> { /* пользователь вмешался — оставляем как есть */ }
                SwitchResult.NO_CANDIDATES -> {
                    // РАБОЧИХ СЕРВЕРОВ НЕТ НИ ОДНИМ СПОСОБОМ — СКАЗАТЬ ПРЯМО (Промпт 95.E) + уведомление в шторке.
                    TunnelHealth.setPhase(TunnelHealth.Phase.NO_SERVERS, now(), "рабочих серверов нет")
                    NotificationHelper.notifyNoServers(app)
                    backoffMs = if (backoffMs == 0L) BACKOFF_START_MS else minOf(backoffMs * 2, BACKOFF_MAX_MS)
                    MonitorLog.event(app, "monitor", "Рабочих серверов нет — пауза ${humanDur(backoffMs)}", "выход по любому событию")
                    MonitorStatus.update(true, "нет рабочих серверов, пауза ${humanDur(backoffMs)}", now(), cycles)
                    // Длинная пауза «нет серверов» — тоже на будильнике: не держим CPU и просыпаемся в Doze (Промпт 118).
                    releaseCycleLock(cycleLock)
                    MonitorAlarm.scheduleNext(app, backoffMs)
                    MonitorCoordinator.drainWakeups(); MonitorCoordinator.awaitWake(backoffMs)
                    MonitorAlarm.cancel(app)
                    acquireCycleLock(cycleLock)
                }
            }
        }
        } finally {
            releaseCycleLock(cycleLock)
            MonitorAlarm.cancel(app)   // корутина монитора гаснет — снять запланированный тик
        }
    }

    private const val CYCLE_WAKELOCK_TIMEOUT_MS = 10 * 60_000L   // страховка от утечки; нормальный цикл — секунды
    // Пр.126.A: прирост туннельных байт за цикл выше этого = «шёл трафик» (не keepalive). 1 МБ — заведомо реальный.
    private const val OPTIMIZE_IDLE_TRAFFIC_MIN_BYTES = 1_048_576L
    // Пр.127.C: минимум трафика в окне пассивного замера, чтобы наблюдаемая скорость была ОСМЫСЛЕННОЙ признаком
    // капасити (а не «мало качали»). 512 КБ за окно 6с ≈ ≥0.7 Мбит/с реально прошло — только тогда судим о деградации.
    private const val DEGRADATION_MIN_TRAFFIC_BYTES = 512L * 1024L
    private fun acquireCycleLock(wl: PowerManager.WakeLock?) {
        runCatching { if (wl != null && !wl.isHeld) wl.acquire(CYCLE_WAKELOCK_TIMEOUT_MS) }
    }
    private fun releaseCycleLock(wl: PowerManager.WakeLock?) {
        runCatching { if (wl?.isHeld == true) wl.release() }
    }

    /**
     * НЕТ ИНТЕРНЕТА (прямой канал мёртв): честно сказать «нет связи», ядро НЕ трогаем. Возвращает СЛЕДУЮЩУЮ паузу
     * экспоненты (1→2→4→…→30 мин) — само ожидание делает верх цикла по фазе NO_INTERNET (прерывается событием сети).
     */
    private fun enterNoInternet(app: Context, prevBackoffMs: Long, cycles: Int): Long {
        TunnelHealth.setPhase(TunnelHealth.Phase.NO_INTERNET, now(), "нет интернета")
        NotificationHelper.cancelNoServers(app)   // это НЕ «нет серверов» — туннель не виноват
        val b = if (prevBackoffMs == 0L) NO_NET_BACKOFF_START_MS else minOf(prevBackoffMs * 2, NO_NET_BACKOFF_MAX_MS)
        MonitorStatus.update(true, "нет интернета · повтор через ${humanDur(b)}", now(), cycles)
        return b
    }

    /** Связь подтверждена — снять предложения/уведомления «нет серверов»/«включить источники». */
    private fun onRecovered(context: Context) {
        if (MonitorPrompt.pending) { MonitorPrompt.clear(); NotificationHelper.cancelEnableSources(context) }
        MonitorPrompt.resetDeclined()
        NotificationHelper.cancelNoServers(context)
    }

    /**
     * ЛЕСТНИЦА ВОССТАНОВЛЕНИЯ (Промпт 95.C / Пр.143), сама, по порядку:
     *  0) Пр.143 БЫСТРЫЙ ПУТЬ — сразу переключаемся на САМЫЙ БЫСТРЫЙ из живых: гейт ПИНГ (дёшев ~1.5с),
     *     подтверждение внешним IP на новом сервере. Не тратим ~2 мин на реанимацию мёртвого активного.
     *  1) ПЕРЕЗАПУСК ЯДРА текущего сервера — ТОЛЬКО как ЗАПАСНОЙ (когда живые не отозвались): при смене сети
     *     соединения привязаны к ушедшей сети и сами не оживают, а перезапуск ядра их чинит (Промпт 102).
     *  2–5) перебор всех (probeAlive, без пинг-гейта) → обновить подписки → белые списки → предложить источники.
     * После каждой ступени — подтверждение ФАКТОМ (внешний IP). Отменяется, если вмешался пользователь.
     */
    private suspend fun runRecoveryLadder(app: Context, s: AppSettings): SwitchResult {
        MonitorCoordinator.monitorSearchRunning = true
        try {
            val startKey = ProxyState.state.value.serverKey
            // Ступень 0 (Пр.143): быстрый ping-подбор живых по убыванию скорости. Успех → сразу наверх.
            val fast = fastPingSwitch(app, s, startKey)
            if (fast != SwitchResult.NO_CANDIDATES) return fast

            // Живые на пинге не ответили (или пинговались, но туннель не прошёл) → ТЩАТЕЛЬНО, аналог «Самый быстрый».
            // fastPingSwitch мог оставить нас на неудачном кандидате — берём АКТУАЛЬНЫЙ ключ для шага 1.
            val curKey = ProxyState.state.value.serverKey
            // Ступень 1: перезапуск ядра на ТЕКУЩЕМ сервере — ТОЛЬКО ОДИН РАЗ за эпизод обрыва (Промпт 102).
            // Если этот сервер уже перезапускали и связь не вернулась — перезапуск бесполезен (сервер мёртв) И
            // вдобавок убивает лестницу (см. restartedKeySinceOk). Пропускаем шаг 1 → сразу перебор всех серверов.
            val alreadyRestarted = curKey != null && curKey == restartedKeySinceOk
            if (curKey != null && !aborted(curKey) && !alreadyRestarted) {
                val curSrv = SubscriptionManager.allServers(app).firstOrNull { SubscriptionManager.serverKey(it) == curKey }
                val cfg = curSrv?.let { runCatching { XrayConfigBuilder.build(it) }.getOrNull() }
                if (curSrv != null && cfg != null) {
                    // Отметить ДО start: XrayProxyService.start ставит running=false → монитор (и эта корутина)
                    // пересоздаётся, код НИЖЕ может не выполниться; флаг object'а переживёт рестарт.
                    restartedKeySinceOk = curKey
                    MonitorLog.event(app, "monitor", "Восстановление: перезапуск ядра (запасной)", ServerLabels.display(curSrv))
                    TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "пробую переподключиться к серверу")
                    XrayProxyService.start(app, cfg, ServerLabels.full(curSrv), curKey)
                    var waited = 0
                    while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != curKey)) { delay(500); waited += 500 }
                    delay(1500)
                    if (aborted(curKey)) return SwitchResult.ABORTED
                    if (ExternalIpChecker.fetch() != null) { MonitorLog.event(app, "monitor", "Восстановление: перезапуск ядра помог", ""); return SwitchResult.SWITCHED }
                }
            }
            // Ступени 2–5.
            return runSwitchSearchInner(app, s)
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
        }
    }

    /**
     * Пр.143: БЫСТРОЕ восстановление. Живые (свежий пинг ≥0 ИЛИ известная скорость >0) по УБЫВАНИЮ скорости
     * (самый быстрый первым). Для каждого: короткий ПИНГ → не отозвался → помечаем мёртвым (уйдёт из «Живых»)
     * и дальше; ПИНГ ОК → переключаемся и подтверждаем ЗАМЕРОМ АКТИВНОГО (внешний IP). Есть IP → остаёмся
     * (скорость доберём следом). Нет IP (пинг был, но туннель не пропускает) → следующий по скорости. Никого —
     * NO_CANDIDATES (наверх, к тщательной процедуре). Свежие пинги персистятся (прунит список «Живых»).
     */
    private suspend fun fastPingSwitch(app: Context, s: AppSettings, startKey: String?): SwitchResult {
        val bl = BlocklistStore.current()
        val candidates = orderFastCandidates(
            SubscriptionManager.allServers(app)
                .filter { SubscriptionManager.serverKey(it) != startKey }
                .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
        )
        if (candidates.isEmpty()) return SwitchResult.NO_CANDIDATES
        val pingTimeout = s.pingTimeoutMs.coerceAtMost(1500)
        val pingUpdates = HashMap<String, Int>()   // свежие пинги → persist (в т.ч. −1 для прунинга «Живых»)
        // Наш ключ активного меняется по ходу (мы сами переключаемся). Вмешательство пользователя ловим сравнением
        // ФАКТИЧЕСКОГО serverKey с тем, что ПОСТАВИЛИ мы (ownedKey), а не со стартовым (тот уже не активен).
        var ownedKey = startKey
        try {
            for ((i, c) in candidates.withIndex()) {
                if (MonitorCoordinator.fullTestRunning || ProxyState.state.value.serverKey != ownedKey) return SwitchResult.ABORTED
                val key = SubscriptionManager.serverKey(c)
                TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "быстрый подбор: ${i + 1}/${candidates.size}")
                MonitorStatus.update(true, "быстрый подбор ${i + 1}/${candidates.size} · ${ServerLabels.display(c)}", now(), 0)
                val p = ServerTester.ping(app, c, pingTimeout).toInt()
                pingUpdates[key] = p
                if (p < 0) continue   // не отозвался — мимо (из «Живых» уйдёт при persist)
                val cfg = runCatching { XrayConfigBuilder.build(c) }.getOrNull()
                if (cfg == null) { MonitorLog.event(app, "error", "Кандидат ${ServerLabels.display(c)}: ошибка конфига", ""); continue }
                val from = ServerLabels.displayForKey(app, ownedKey)
                XrayProxyService.start(app, cfg, ServerLabels.full(c), key)
                ownedKey = key
                MonitorLog.switch(app, from, ServerLabels.display(c), "монитор", "быстрый: пинг ОК (${p} мс)")
                var waited = 0
                while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != key)) { delay(500); waited += 500 }
                delay(800)   // дать туннелю осесть
                if (ProxyState.state.value.serverKey != key) return SwitchResult.ABORTED   // сменили не мы
                // ЗАМЕР АКТИВНОГО = внешний IP через новый сервер. Есть → остаёмся; скорость доберём.
                if (ExternalIpChecker.fetch() != null) {
                    measureAfterConnect(app, c)
                    return SwitchResult.SWITCHED
                }
                MonitorLog.event(app, "monitor", "Пинг ОК, но туннель не прошёл — дальше", ServerLabels.display(c))
            }
            return SwitchResult.NO_CANDIDATES
        } finally {
            SubscriptionManager.applyPingResults(app, pingUpdates)   // обновить живость (в т.ч. прунинг не-ответивших)
        }
    }

    // ---- Перебор кандидатов / переключение ----

    private suspend fun runSwitchSearch(app: Context, s: AppSettings): SwitchResult {
        MonitorCoordinator.monitorSearchRunning = true
        try {
            return runSwitchSearchInner(app, s)
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
        }
    }

    private suspend fun runSwitchSearchInner(app: Context, s: AppSettings): SwitchResult {
        val startKey = ProxyState.state.value.serverKey   // если сменится не нами — значит вмешался пользователь

        val r1 = probeAndConnect(app, s, startKey, "1")
        if (r1 != SwitchResult.NO_CANDIDATES) return r1

        // Никто не подошёл → обновить ВСЕ включённые подписки → пройти список заново.
        if (aborted(startKey)) return SwitchResult.ABORTED
        MonitorLog.event(app, "monitor", "Никто не подошёл — обновляю подписки", "")
        MonitorStatus.update(true, "обновляю подписки", now(), 0)
        TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "обновляю список серверов")
        runCatching { SubscriptionManager.refreshAllEnabled(app, cancelled = { aborted(startKey) }, onEach = { _, _ -> }) }
            .onFailure { MonitorLog.event(app, "error", "Ошибка обновления подписок", it.message ?: "") }
        if (aborted(startKey)) return SwitchResult.ABORTED

        val r2 = probeAndConnect(app, s, startKey, "2")
        if (r2 != SwitchResult.NO_CANDIDATES) return r2

        // Пр.141: ПОСЛЕДНИЙ ШАНС перед «нет серверов» — авто-включить дефолтные Белые списки (сеются ВЫКЛ),
        // уведомить пользователя, обновить их и пройти ещё раз. Связь любой ценой (Пр.95).
        if (aborted(startKey)) return SwitchResult.ABORTED
        val wlEnabled = runCatching { SubscriptionManager.enableWhiteListForFallback(app) }.getOrDefault(0)
        if (wlEnabled > 0) {
            MonitorLog.event(app, "monitor", "Рабочих серверов нет — включаю «Белые списки»", "источников: $wlEnabled")
            NotificationHelper.notifyWhiteListEnabled(app)
            TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "включил Белые списки — ищу сервер")
            runCatching { SubscriptionManager.refreshWhiteListSources(app) }
            if (aborted(startKey)) return SwitchResult.ABORTED
            val r3 = probeAndConnect(app, s, startKey, "3 (белые списки)")
            if (r3 != SwitchResult.NO_CANDIDATES) return r3
        }

        handleNoAlive(app)   // пункт E: предложить включить прочие выкл-источники / добавить (если выкл нет)
        return SwitchResult.NO_CANDIDATES
    }

    /** true, если перебор надо прервать: идёт ручной тест ИЛИ активный сервер сменил кто-то другой (пользователь). */
    private fun aborted(startKey: String?): Boolean =
        MonitorCoordinator.fullTestRunning || ProxyState.state.value.serverKey != startKey

    /**
     * Один проход по ВСЕМУ списку (от быстрых к медленным). Кандидаты — единый предикат (протокол +
     * стоп-лист), МИНУЯ пинг-фильтр (мёртвый пинг ≠ мёртвый сервер, v2rayN). Дешёвая проверка на temp-
     * инстансе; подключаемся к ПЕРВОМУ живому; скорость меряем ПОСЛЕ.
     */
    private suspend fun probeAndConnect(app: Context, s: AppSettings, startKey: String?, round: String): SwitchResult {
        val bl = BlocklistStore.current()
        // Пр.141: СНАЧАЛА вероятно-живые (свежий пинг ≥0 ИЛИ известная скорость >0) — быстро пробуем их, а не
        // грызём весь пул мёртвых по 68 штук (жалоба «17 мин перебирает 13/68 при 2 живых»). Внутри — по скорости,
        // затем по пингу. Остальные (никогда не мерянные/мёртвые) идут ХВОСТОМ — как запас, если живые не подошли.
        val candidates = SubscriptionManager.allServers(app)
            .filter { SubscriptionManager.serverKey(it) != startKey }
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
            .sortedWith(
                compareByDescending<com.picosoft.xrayproxydroid.xray.link.ServerProfile> { (it.pingMs ?: -1) >= 0 || (it.speedMbps ?: 0.0) > 0.0 }
                    .thenByDescending { it.speedMbps ?: 0.0 }
                    .thenBy { it.pingMs ?: Int.MAX_VALUE }
            )
        // Подключиться к кандидату (общий хвост: конфиг → старт → замер). Возврат SWITCHED/null-для-continue.
        suspend fun tryConnect(c: com.picosoft.xrayproxydroid.xray.link.ServerProfile): SwitchResult? {
            val cfg = runCatching { XrayConfigBuilder.build(c) }.getOrNull()
            if (cfg == null) { MonitorLog.event(app, "error", "Кандидат ${ServerLabels.display(c)}: ошибка конфига", ""); return null }
            val from = ServerLabels.displayForKey(app, startKey)
            XrayProxyService.start(app, cfg, ServerLabels.full(c), SubscriptionManager.serverKey(c))
            MonitorLog.switch(app, from, ServerLabels.display(c), "монитор", "первый живой (проход $round)")
            measureAfterConnect(app, c)
            return SwitchResult.SWITCHED
        }

        // Пр.142: ПРОХОД 1 — сперва быстрый ПИНГ недавно-рабочих (жалоба: не перебирать вслепую замером). Пингуем
        // короче обычного; отвечающих сразу проверяем temp-инстансом и подключаемся. Непингующихся откладываем.
        val likely = candidates.filter { (it.pingMs ?: -1) >= 0 || (it.speedMbps ?: 0.0) > 0.0 }
        val pingTimeout = SettingsStore.current().pingTimeoutMs.coerceAtMost(1500)
        val pingDead = ArrayList<com.picosoft.xrayproxydroid.xray.link.ServerProfile>()
        for ((i, c) in likely.withIndex()) {
            if (aborted(startKey)) return SwitchResult.ABORTED
            TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "пингую недавно рабочие: ${i + 1}/${likely.size}")
            MonitorStatus.update(true, "пинг $round: ${i + 1}/${likely.size} · ${ServerLabels.display(c)}", now(), 0)
            if (ServerTester.ping(app, c, pingTimeout) < 0) { pingDead.add(c); continue }   // не отозвался — отложить
            if (!ServerSpeedTester.probeAlive(app, c)) continue
            tryConnect(c)?.let { return it }
        }
        // Пр.142: ПРОХОД 2 — запас: непингующиеся недавно-рабочие (мёртвый пинг ≠ мёртвый под DPI) + никогда-не-мерянные,
        // проверяем temp-инстансом БЕЗ пинг-гейта (как раньше). Так DPI-серверы не теряются, но идут ПОСЛЕ быстрых.
        val rest = pingDead + candidates.filterNot { it in likely }
        for ((i, c) in rest.withIndex()) {
            if (aborted(startKey)) return SwitchResult.ABORTED
            TunnelHealth.setPhase(TunnelHealth.Phase.RECOVERING, now(), "проверяю остальные: ${i + 1}/${rest.size}")
            MonitorStatus.update(true, "перебор $round: ${i + 1}/${rest.size} · ${ServerLabels.display(c)}", now(), 0)
            if (!ServerSpeedTester.probeAlive(app, c)) continue
            tryConnect(c)?.let { return it }
        }
        return SwitchResult.NO_CANDIDATES
    }

    /**
     * Пр.143: ПОДДЕРЖАНИЕ «Живых» — пингуем текущих живых (свежий пинг ≥0), НЕ активного (его живость — по IP-чеку),
     * и записываем результат (в т.ч. −1 → сервер уходит из «Живых»). Пул ServerTester.testAll (concurrency из
     * настроек); ждём завершения без блокировки потока. Только пинг (килобайты) — скорость не трогаем.
     */
    private suspend fun pruneLiveByPing(app: Context, s: AppSettings) {
        val bl = BlocklistStore.current()
        val curKey = ProxyState.state.value.serverKey
        val live = SubscriptionManager.allServers(app)
            .filter { SubscriptionManager.serverKey(it) != curKey }
            .filter { (it.pingMs ?: -1) >= 0 }
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
        if (live.isEmpty()) return
        val results = java.util.concurrent.ConcurrentHashMap<String, Int>()
        kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
            val handle = ServerTester.testAll(
                app, live,
                onResult = { p, ms -> results[SubscriptionManager.serverKey(p)] = ms.toInt() },
                onProgress = { _, _ -> },
                onFinish = { if (cont.isActive) cont.resumeWith(Result.success(Unit)) },
            )
            cont.invokeOnCancellation { runCatching { handle.cancel() } }
        }
        if (results.isNotEmpty()) {
            SubscriptionManager.applyPingResults(app, results)
            val dead = results.count { it.value < 0 }
            MonitorLog.event(app, "monitor", "Пинг живых: ${results.size}, отсеяно $dead", "поддержание списка «Живые»")
        }
    }

    /**
     * Пр.143: «вероятно живой» кандидат для быстрого подбора — свежий пинг ≥0 ИЛИ известная скорость >0.
     * Пинг вслепую по серверам без данных смысла нет: гейтом служит реальный ПИНГ при переборе (fastPingSwitch).
     */
    internal fun isLikelyAlive(p: com.picosoft.xrayproxydroid.xray.link.ServerProfile): Boolean =
        (p.pingMs ?: -1) >= 0 || (p.speedMbps ?: 0.0) > 0.0

    /**
     * Пр.143: порядок быстрого подбора — САМЫЙ БЫСТРЫЙ ПЕРВЫМ (по известной скорости убыв.), при равной скорости
     * меньший пинг раньше; берём только «вероятно живых» (isLikelyAlive). Чистая функция — покрыта тестом.
     */
    internal fun orderFastCandidates(
        list: List<com.picosoft.xrayproxydroid.xray.link.ServerProfile>
    ): List<com.picosoft.xrayproxydroid.xray.link.ServerProfile> =
        list.filter { isLikelyAlive(it) }
            .sortedWith(
                compareByDescending<com.picosoft.xrayproxydroid.xray.link.ServerProfile> { it.speedMbps ?: 0.0 }
                    .thenBy { it.pingMs ?: Int.MAX_VALUE }
            )

    /** Скорость измеряем ПОСЛЕ подключения (пользователю нужна связь, не число) и записываем серверу. */
    private suspend fun measureAfterConnect(app: Context, c: com.picosoft.xrayproxydroid.xray.link.ServerProfile) {
        val key = SubscriptionManager.serverKey(c)
        var waited = 0
        while (waited < 8000 && (!ProxyState.state.value.running || ProxyState.state.value.serverKey != key)) {
            delay(500); waited += 500
        }
        delay(1000)   // дать туннелю осесть
        val mbps = ServerSpeedTester.measureActiveDownloadMbps(app)   // сопоставимо с кандидатами (Промпт 90.A)
        if (mbps > 0) {
            SubscriptionManager.applySpeedResults(app, mapOf(key to mbps))
            TunnelSpeed.setProbe(null, mbps, null, now(), key)   // начальная туннельная ↓ (прямую/↑ доберёт проба в простое)
            MonitorLog.event(app, "monitor", "Скорость нового сервера", "${fmt(mbps)}")
        }
    }

    /** Пункт E: живых нет после двух проходов. Есть выключенные источники → предложить (не включать сами). */
    private fun handleNoAlive(app: Context) {
        val disabled = SubscriptionManager.sources(app).filter { !it.enabled }
        if (disabled.isNotEmpty() && !MonitorPrompt.declined) {
            val srv = SubscriptionManager.serversFromDisabled(app)
            MonitorPrompt.request(disabled.size, srv)
            NotificationHelper.notifyEnableSources(app, disabled.size, srv)
            MonitorLog.event(app, "monitor", "Нет живых серверов", "есть ${disabled.size} выключенных источников (~$srv серв.) — предложил включить")
        } else if (disabled.isEmpty()) {
            // Пр.141: включать нечего (все источники уже включены/их нет) → предложить ДОБАВИТЬ подписку.
            NotificationHelper.notifyAddSubscription(app)
            MonitorLog.event(app, "monitor", "Нет живых серверов", "включать нечего — предложил добавить подписку")
        } else {
            MonitorLog.event(app, "monitor", "Нет живых серверов", "пользователь отказался включать")
        }
    }

    /** Активная сеть — платная (мобильная/лимитный хотспот)? Гейт «только Wi-Fi» для дорогого перемера (Пр.126.A). */
    private fun isMeteredNetwork(app: Context): Boolean =
        (app.getSystemService(ConnectivityManager::class.java))?.isActiveNetworkMetered ?: false

    /**
     * Пр.126.A: причина ПРОПУСТИТЬ периодический перемер «держать лучший» (null — можно мерить). Предохранители
     * против дорогого трафика в фоне: платная сеть, давно нет трафика через туннель, текущий сервер уже быстрый.
     */
    private fun optimizeSkipReason(app: Context, s: AppSettings, lastUserTrafficMs: Long): String? {
        if (s.monitorOptimizeWifiOnly && isMeteredNetwork(app)) return "мобильная/платная сеть — только по Wi-Fi"
        if (s.monitorOptimizeIdleSkipSec > 0 && now() - lastUserTrafficMs > s.monitorOptimizeIdleSkipSec * 1000L)
            return "через туннель давно нет трафика (${humanDur(now() - lastUserTrafficMs)}) — нечего оптимизировать"
        if (s.monitorOptimizeSkipAboveMbps > 0) {
            val curKey = ProxyState.state.value.serverKey
            val curSpeed = curKey?.let { k ->
                SubscriptionManager.allServers(app).firstOrNull { SubscriptionManager.serverKey(it) == k }?.speedMbps
            } ?: 0.0
            if (curSpeed >= s.monitorOptimizeSkipAboveMbps)
                return "текущий уже быстрый (${fmt(curSpeed)} ≥ ${fmt(s.monitorOptimizeSkipAboveMbps)}) — улучшать нечего"
        }
        return null
    }

    /**
     * ПОДДЕРЖАНИЕ ТОПА (Пр.132): не мерить всех подряд, а держать актуальным небольшой топ быстрых серверов.
     * Меряем ТОЛЬКО кандидатов в топ и ТОЛЬКО по поводам (Пр.132.C): ни разу не измерен / замер устарел (Пр.132.D:
     * старее topFreshSec — скорость НЕ действующая) / прошлый замер провалился. Плюс подмешиваем несколько ещё не
     * измеренных (Пр.132.B: пинг плохо предсказывает скорость под DPI — иначе быстрый сервер с плохим пингом не
     * найдётся). Топ свежий → НЕ мерим. Пинг — только для первого приближения/сортировки неизмеренных. Замеры
     * сопоставимы (Пр.130: и активный, и кандидатов — temp-инстансом). Массового замера всех живых в фоне НЕТ.
     */
    private suspend fun optimizeToFastest(app: Context, s: AppSettings) {
        val curKey = ProxyState.state.value.serverKey ?: return
        val bl = BlocklistStore.current()
        fun key(p: com.picosoft.xrayproxydroid.xray.link.ServerProfile) = SubscriptionManager.serverKey(p)
        val topN = s.activeTopBatch.coerceAtLeast(1)
        val all = SubscriptionManager.allServers(app)
            .filter { ServerFilter.protocolAllowed(it, s) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
        if (all.size <= 1) return
        // СВЕЖИЙ топ: измерены (speed>0) и НЕ старее предела давности (Пр.132.D). Их перемерять не нужно.
        val freshHours = (s.topFreshSec.coerceAtLeast(600) / 3600).coerceAtLeast(1)
        val freshTop = SubscriptionManager.recentWorkingServers(app, freshHours).take(topN)
        val freshKeys = freshTop.map { key(it) }.toSet()
        // КОГО МЕРИТЬ (только поводы Пр.132.C): добираем топ до N из НЕсвежих. Сначала известные-но-устаревшие по
        // скорости (были в топе, пора обновить), затем неизмеренные/провальные по пингу (первое приближение).
        val toMeasure = LinkedHashMap<String, com.picosoft.xrayproxydroid.xray.link.ServerProfile>()
        val byPing = all.sortedBy { it.pingMs ?: Int.MAX_VALUE }
        val staleByKnown = all.filter { (it.speedMbps ?: 0.0) > 0.0 && key(it) !in freshKeys }
            .sortedByDescending { it.speedMbps ?: 0.0 }
        for (p in staleByKnown) { if (toMeasure.size + freshKeys.size >= topN) break; toMeasure[key(p)] = p }
        for (p in byPing) { if (toMeasure.size + freshKeys.size >= topN) break; val k = key(p); if (k !in freshKeys && k !in toMeasure) toMeasure[k] = p }
        // Пр.132.B: подмешать неизмеренных живых (иначе быстрый-с-плохим-пингом не найдём).
        var mixed = 0
        for (p in byPing) { if (mixed >= s.topMixUnmeasured) break; val k = key(p); if (p.speedMbps == null && k !in freshKeys && k !in toMeasure) { toMeasure[k] = p; mixed++ } }
        if (toMeasure.isEmpty()) {                                          // топ свежий — повода мерить нет
            MonitorLog.event(app, "monitor", "Топ свежий — перемер не нужен", "в топе ${freshKeys.size}/$topN, все ≤ ${freshHours}ч")
            return
        }
        MonitorLog.event(app, "monitor", "Поддержание топа: мерю ${toMeasure.size} (топ $topN)", "свежих ${freshKeys.size}, подмешано $mixed · правило ×${s.keepBestMultiplier}")
        MonitorStatus.update(true, "поддержание топа: ${toMeasure.size} замер(ов)", now(), 0)
        MonitorCoordinator.monitorSearchRunning = true
        try {
            // Пр.130: активный меряем ТЕМ ЖЕ способом, что кандидатов — temp-инстансом (measureSpeed), а НЕ живым
            // SOCKS (measureActiveDownloadMbps систематически занижает, Пр.108/109). Живой замер — только для плашки.
            // Пр.133.B: мерим на ДОСТАТОЧНОСТЬ (стоп при подтверждении порога) — не точное число. Достаточные
            // читаются как ~порог, поэтому правило «держать лучший ×N» между ними НЕ дёргает (нет churn/расхода);
            // переключимся, лишь когда текущий перестал быть достаточным (порог/низкий → ×N сработает).
            val enough = s.sufficientMbps.coerceAtLeast(0.1)
            val curProfile = all.firstOrNull { key(it) == curKey }
            val curMbps = if (curProfile != null) ServerSpeedTester.measureSufficiency(app, curProfile, enough) else -1.0
            val results = HashMap<String, Double>()
            if (curMbps > 0) results[curKey] = curMbps
            var bestKey: String? = null; var bestMbps = 0.0
            for (c in toMeasure.values) {
                if (MonitorCoordinator.fullTestRunning) return               // ручной тест — уступаем
                if (ProxyState.state.value.serverKey != curKey) return       // пользователь сменил — уступаем
                val k = key(c)
                if (k == curKey) continue
                val mbps = ServerSpeedTester.measureSufficiency(app, c, enough)   // temp-инстанс, стоп на пороге
                results[k] = mbps
                if (mbps > bestMbps) { bestMbps = mbps; bestKey = k }
            }
            // лучший из СВЕЖЕГО топа тоже участвует в решении (его не перемеряли — он свежий)
            for (p in freshTop) { val sp = p.speedMbps ?: 0.0; if (sp > bestMbps) { bestMbps = sp; bestKey = key(p) } }
            SubscriptionManager.applySpeedResults(app, results)
            // ПРАВИЛО «ДЕРЖАТЬ ЛУЧШИЙ» (Промпт 90.A): переключаемся, если лучший быстрее текущего КРАТНО
            // (в keepBestMultiplier раз), даже когда текущий выше порога. Кратно — чтобы шум не дёргал.
            if (bestKey != null && bestKey != curKey && curMbps > 0 && bestMbps >= curMbps * s.keepBestMultiplier) {
                val target = all.firstOrNull { key(it) == bestKey } ?: return
                val cfg = runCatching { XrayConfigBuilder.build(target) }.getOrNull() ?: return
                val from = ServerLabels.displayForKey(app, curKey)
                XrayProxyService.start(app, cfg, ServerLabels.full(target), bestKey!!)
                MonitorLog.switch(app, from, ServerLabels.display(target), "монитор: держать лучший",
                    "было ${fmt(curMbps)} → стало ${fmt(bestMbps)} (×${s.keepBestMultiplier})")
            } else {
                MonitorLog.event(app, "monitor", "Топ обновлён, смены нет",
                    "текущий ${fmt(curMbps)}, лучший ${fmt(bestMbps)} (нужно ×${s.keepBestMultiplier})")
            }
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
        }
    }

    // ---- Замеры/утилиты ----

    private fun now(): Long = System.currentTimeMillis()

    /**
     * ЖИВАЯ скорость активного туннеля для плашки (гибрид). Возвращает обновлённое время последней АКТИВНОЙ пробы.
     *  1) ПАССИВНО (бесплатно): дельта туннельных байт (TrafficTracker) за окно ~2с. Идёт реальный трафик →
     *     это и есть актуальная скорость ↓/↑ → source=LIVE. Ничего лишнего не качаем.
     *  2) ПРОСТОЙ (трафика нет): раз в [liveSpeedActiveProbeSec] и ТОЛЬКО при включённом экране — активная проба
     *     download+upload через туннель → source=PROBE. Экономия: не тратим трафик на замер для невидимой плашки.
     * Download активной пробы попутно пишем серверу (обновляет список «Живые» — решение «то, что монитор меряет»).
     */
    private suspend fun sampleTunnelSpeed(app: Context, cur: AppSettings, lastActiveProbeMs: Long): Long {
        val curKey = ProxyState.state.value.serverKey ?: return lastActiveProbeMs

        // (1) Пассивно: реальный трафик пользователя даёт скорость бесплатно.
        val t0 = SystemClock.elapsedRealtime()
        val rx0 = TrafficTracker.state.value.sessionRx; val tx0 = TrafficTracker.state.value.sessionTx
        delay(SPEED_PASSIVE_WINDOW_MS)
        val dtSec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val dRx = (TrafficTracker.state.value.sessionRx - rx0).coerceAtLeast(0)
        val dTx = (TrafficTracker.state.value.sessionTx - tx0).coerceAtLeast(0)
        if (dtSec > 0 && dRx + dTx > SPEED_PASSIVE_MIN_BYTES) {
            val down = dRx * 8.0 / dtSec / 1_000_000.0
            val up = dTx * 8.0 / dtSec / 1_000_000.0
            TunnelSpeed.setLive(down, up, now(), curKey)   // прямую скорость сохраняем от прошлой пробы
            // Пр.127.C: деградация по УЖЕ идущему трафику (бесплатно). Только при СУЩЕСТВЕННОМ трафике — иначе
            // низкая скорость = «мало качали», а не медленный туннель. Тишину (иначе — ниже порога байт) НЕ трактуем.
            if (dRx + dTx >= DEGRADATION_MIN_TRAFFIC_BYTES) maybeStepFromDegraded(app, cur, curKey, down)
            return lastActiveProbeMs
        }

        // (2) Простой: активная проба — только если пришло время И экран включён (иначе не тратим трафик).
        // Пр.125.E: в режиме экономии ПЛАНОВАЯ проба плашки в простое ОТКЛЮЧЕНА — замер активного туннеля только
        // по подозрению (лестница восстановления), не по расписанию. Пассивный сэмпл (шаг 1) бесплатен — он остаётся.
        if (cur.trafficSaveMode) return lastActiveProbeMs
        if (now() - lastActiveProbeMs < cur.liveSpeedActiveProbeSec * 1000L) return lastActiveProbeMs
        if (!screenInteractive(app)) return lastActiveProbeMs
        if (MonitorCoordinator.fullTestRunning || ProxyState.state.value.serverKey != curKey) return lastActiveProbeMs
        probeActiveSpeedNow(app)
        return now()
    }

    /**
     * Пр.127.C: «живой, но медленный». Наблюдаемая по ПАССИВНОМУ трафику скорость [passiveDown] ниже порога
     * полезности → сделать ОДИН шаг: замерить ЛУЧШЕГО по СОХРАНЁННОЙ скорости кандидата (не текущего) и перейти,
     * если он приемлемо быстрее того, что человек реально получает сейчас. Один кандидат, один замер, не перебор.
     * Не чаще [degradationCheckSec]. Работает в ОБОИХ режимах (в экономии — «приемлемый», не «лучший»). Вызывается
     * ТОЛЬКО когда трафик был существенным (см. вызов), поэтому passiveDown — осмысленный признак капасити.
     */
    private suspend fun maybeStepFromDegraded(app: Context, cur: AppSettings, curKey: String, passiveDown: Double) {
        if (cur.degradationMinMbps <= 0.0) return                        // фича выключена
        if (passiveDown >= cur.degradationMinMbps) return                // не деградация — туннель тянет
        if (now() - lastDegradationMs < cur.degradationCheckSec * 1000L) return   // не чаще интервала
        if (MonitorCoordinator.fullTestRunning || MonitorCoordinator.monitorSearchRunning) return
        lastDegradationMs = now()
        val bl = BlocklistStore.current()
        // ОДИН кандидат: лучший по СОХРАНЁННОЙ скорости, не текущий, годный (протокол/стоп-лист/пауза).
        val cand = SubscriptionManager.allServers(app)
            .filter { SubscriptionManager.serverKey(it) != curKey }
            .filter { ServerFilter.protocolAllowed(it, cur) && !ServerFilter.isBlocked(it, bl) && !ServerFilter.isPaused(it, bl) }
            .maxByOrNull { it.speedMbps ?: 0.0 } ?: return
        if ((cand.speedMbps ?: 0.0) <= 0.0) return                       // о кандидатах нет данных — судить не по чему
        MonitorLog.event(app, "monitor", "Туннель медленный (${fmt(passiveDown)}) — проверяю кандидата", ServerLabels.display(cand))
        MonitorCoordinator.monitorSearchRunning = true
        try {
            val candMbps = ServerSpeedTester.measureSpeed(app, cand)     // ОДИН замер (temp-инстанс)
            SubscriptionManager.applySpeedResults(app, mapOf(SubscriptionManager.serverKey(cand) to candMbps))
            if (ProxyState.state.value.serverKey != curKey) return       // пользователь/лестница сменили — не вмешиваемся
            if (candMbps >= cur.degradationMinMbps && candMbps > passiveDown) {
                val cfg = runCatching { XrayConfigBuilder.build(cand) }.getOrNull() ?: return
                val from = ServerLabels.displayForKey(app, curKey)
                XrayProxyService.start(app, cfg, ServerLabels.full(cand), SubscriptionManager.serverKey(cand))
                MonitorLog.switch(app, from, ServerLabels.display(cand), "деградация: кандидат приемлемо быстрее",
                    "${fmt(passiveDown)} → ${fmt(candMbps)}")
                measureAfterConnect(app, cand)
            } else {
                MonitorLog.event(app, "monitor", "Кандидат не лучше (${fmt(candMbps)}) — остаюсь",
                    "порог ${fmt(cur.degradationMinMbps)}, сейчас ${fmt(passiveDown)}")
            }
        } finally {
            MonitorCoordinator.monitorSearchRunning = false
        }
    }

    /**
     * ПРИОРИТЕТНЫЙ активный замер СЕЙЧАС: прямой канал + туннель ↓/↑ активного сервера → в [TunnelSpeed] (плашка).
     * Блокирующий (зовётся из потока полного теста сразу после подключения И из монитора). Обратная связь после
     * 0.23: чтобы плашка показывала РЕАЛЬНЫЕ числа активного сервера ASAP (а не «замеряю…» до цикла монитора),
     * и чтобы они были ЕДИНЫМ источником правды по скорости (без расхождения с «Готово: …»).
     */
    // Промпт 104.B: [includeUpload]=false — пропустить замер ОТДАЧИ (он display-only и гарантированный сток
    // времени, см. ServerSpeedTester.uploadMbps). Полный тест зовёт с false (важна скорость перебора), монитор —
    // с true (для ↑ на плашке; там замер в фоне и с жёстким потолком времени).
    // Промпт 108: ВОЗВРАЩАЕТ измеренную скорость активного туннеля ↓ (Мбит/с, -1 если не запущен/провал/сервер
    // сменился) — чтобы полный тест мог СРАВНИТЬ активный сервер с кандидатами, а не подключаться к первому годному
    // безусловно. Прочие вызыватели (монитор/onDone) возврат игнорируют.
    fun probeActiveSpeedNow(app: Context, includeUpload: Boolean = true): Double {
        val curKey = ProxyState.state.value.serverKey ?: return -1.0
        val direct = ServerSpeedTester.measureDirectDownloadMbps(app).takeIf { it >= 0.0 }   // НАПРЯМУЮ ↓ (без туннеля)
        val down = ServerSpeedTester.measureActiveDownloadMbps(app)                          // В ТУННЕЛЕ ↓
        if (ProxyState.state.value.serverKey != curKey) return -1.0   // сервер сменили во время замера — не приписываем чужое
        val up = if (includeUpload) ServerSpeedTester.measureActiveUploadMbps(app) else -1.0 // В ТУННЕЛЕ ↑
        TunnelSpeed.setProbe(direct, down.takeIf { it >= 0.0 }, up.takeIf { it >= 0.0 }, now(), curKey)
        if (down > 0) SubscriptionManager.applySpeedResults(app, mapOf(curKey to down))      // обновить «Живые»
        return down
    }

    /** Экран включён (пользователь потенциально смотрит на плашку)? Гейт для платной активной пробы. */
    private fun screenInteractive(app: Context): Boolean =
        (app.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true

    private fun fmt(mbps: Double) = "${(mbps * 10).roundToInt() / 10.0} Мбит/с"

    private fun humanDur(ms: Long): String {
        val sec = (ms / 1000).coerceAtLeast(0)
        return when {
            sec < 60 -> "${sec}с"
            sec < 3600 -> "${sec / 60}м"
            else -> "${sec / 3600}ч ${(sec % 3600) / 60}м"
        }
    }

    // internal (не private): переиспользуется кнопкой «Проверить» на главной как интернет-гейт.
    internal fun directAlive(): Boolean {
        for ((host, port) in directDnsProbes) {
            try {
                Socket().use { it.connect(InetSocketAddress(host, port), 3000); return true }
            } catch (e: Exception) { /* следующий */ }
        }
        return false
    }

    /**
     * Идёт ли ЖИВОЙ трафик пользователя через туннель (Промпт 90.D): дельта принятых+отданных байт за ~1.2с.
     * Порог 128 КБ отсекает служебные keepalive, но ловит реальную загрузку. Если идёт — тяжёлый замер
     * пропускаем (испортили бы и замер, и его загрузку). Байты — накопленные из TrafficTracker (поллер
     * сервиса их обновляет; queryTunnelDelta НЕ трогаем — её потребляет поллер).
     */
    internal suspend fun userTrafficActive(): Boolean {
        val before = TrafficTracker.state.value.let { it.sessionRx + it.sessionTx }
        delay(1200)
        val after = TrafficTracker.state.value.let { it.sessionRx + it.sessionTx }
        return after - before > 128 * 1024
    }
}
