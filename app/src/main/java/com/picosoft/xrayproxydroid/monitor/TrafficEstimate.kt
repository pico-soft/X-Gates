package com.picosoft.xrayproxydroid.monitor

import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.xray.ServerSpeedTester

/**
 * Оценка расхода трафика на фоновые замеры монитора, МБ/мес, для блока «Периодический перемер» в Настройках.
 * Раньше считалось одно занятие из четырёх («держать лучший» раз в 6 ч): без рейтинга топа, живости, пробы плашки и расписания
 * на зарядке, а в экономии строка обещала «≈ 0». Находка Windows-КК (X-Gates-Win 535436a), решение Elyor 2026-11-03: «Да!».
 * Интервалы и бюджеты берутся из констант монитора и замера: оценка разошлась с кодом именно из-за копии чисел.
 *
 * Это потолок: телефон круглые сутки на зарядке по Wi-Fi, экран включён, канал простаивает. При трафике пользователя рейтинг и проба
 * не идут, на батарее с выключенным экраном замеров нет совсем ([NetworkMonitor] heavyMeasureAllowed).
 */
object TrafficEstimate {
    private const val MONTH_SEC = 2_592_000.0
    private const val MIB = 1_048_576.0
    const val FAST_MBPS = 10.0   // от этой скорости более быстрый ищем раз в час
    const val SLOW_MBPS = 0.5    // пример очень медленного: ищем каждую минуту

    /** Один замер на достаточность, МБ: бюджет самого замера. Медленный сервер упирается во время, а не в объём. */
    internal fun enoughMb(thresholdMbps: Double, serverMbps: Double? = null): Double {
        val budget = (ServerSpeedTester.SUFFICIENCY_WARMUP_BYTES + ServerSpeedTester.sufficiencyBudgetBytes(thresholdMbps)) / MIB
        val sec = (ServerSpeedTester.SUFFICIENCY_WARMUP_MS + ServerSpeedTester.SUFFICIENCY_WINDOW_MS) / 1000.0
        return if (serverMbps == null) budget else minOf(budget, serverMbps / 8.0 * 1_000_000.0 * sec / MIB)
    }

    /**
     * «Держать лучший», МБ/мес. На зарядке по Wi-Fi проход идёт сразу после рейтинга и по [NetworkMonitor.upgradeCheckMs], на батарее и
     * мобильной раз в настройку. Проход мерит активный и подмешанных неизмеренных, весь топ — только когда устарел. В экономии выключен.
     */
    fun optimizeMbPerMonth(s: AppSettings, activeMbps: Double?): Double {
        if (s.trafficSaveMode || s.activeOptimizeSec <= 0) return 0.0
        val everySec = minOf(NetworkMonitor.upgradeIntervalMs(true, activeMbps, s.activeOptimizeSec), NetworkMonitor.TOP_RATE_CHARGING_WIFI_MS) / 1000.0
        val perMeasure = enoughMb(s.sufficientMbps)
        return MONTH_SEC / everySec * (enoughMb(s.sufficientMbps, activeMbps) + s.topMixUnmeasured * perMeasure) +
            MONTH_SEC / maxOf(everySec, s.topFreshSec.toDouble()) * s.activeTopBatch * perMeasure
    }

    /**
     * Все замеры монитора, МБ/мес. Кроме «держать лучший»: рейтинг топа (минимальный замер живых без свежей скорости, полный — лучшим),
     * проверка активного на живость, проба плашки в простое при включённом экране (напрямую, туннель вниз и вверх), в экономии — редкий перечек.
     */
    fun backgroundMbPerMonth(s: AppSettings, activeMbps: Double?): Double {
        val full = (s.speedWarmupBudgetBytes + s.activeMeasureBudgetBytes) / MIB
        val minimal = (ServerSpeedTester.MINIMAL_WARMUP_BYTES + ServerSpeedTester.MINIMAL_BUDGET_BYTES) / MIB
        val rateSec = NetworkMonitor.TOP_RATE_CHARGING_WIFI_MS / 1000.0
        val rating = MONTH_SEC / rateSec * NetworkMonitor.rateMaxScan(s.trafficSaveMode) * minimal +
            MONTH_SEC / maxOf(rateSec, s.liveFreshSec.toDouble()) * NetworkMonitor.rateTopTarget(s.trafficSaveMode) * full
        val liveness = MONTH_SEC / (NetworkMonitor.LIVENESS_MEASURE_STABLE_MS / 1000.0) * enoughMb(s.degradationMinMbps.coerceAtLeast(0.1))
        if (s.trafficSaveMode)
            return rating + liveness + (if (s.economyRecheckSec > 0) MONTH_SEC / s.economyRecheckSec * enoughMb(s.sufficientMbps) else 0.0)
        val plate = MONTH_SEC / s.liveSpeedActiveProbeSec.coerceAtLeast(10) * (2 * full + s.uploadMeasureBudgetBytes / MIB)
        return rating + liveness + plate + optimizeMbPerMonth(s, activeMbps)
    }

    /** Проверки связи, МБ/мес: на батарее по настройке, на зарядке по Wi-Fi раз в [NetworkMonitor.AC_CHECK_MS]. */
    fun checksMbPerMonth(intervalSec: Int, bytesPerCheck: Long): Double = MONTH_SEC / intervalSec.coerceAtLeast(1) * bytesPerCheck / MIB
}
