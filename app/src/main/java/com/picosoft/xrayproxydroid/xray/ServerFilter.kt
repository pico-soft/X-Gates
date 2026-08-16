package com.picosoft.xrayproxydroid.xray

import com.picosoft.xrayproxydroid.settings.AppSettings
import com.picosoft.xrayproxydroid.xray.link.ServerProfile

/**
 * ЕДИНАЯ точка всех отсевов сервера: живость (пинг), минимальная скорость, протокол
 * (и будущий стоп-лист — добавлять СЮДА). Через неё обязаны идти список «Живые», секция «Все»,
 * счётчики N, автоподключение и апгрейд — ни одного условия «по месту».
 *
 * ВАЖНО: фильтр НЕ применяется к ЗАМЕРУ (пинг/скорость меряют ВСЕХ) — только к отображению и выбору,
 * чтобы при обратном включении протокола числа были готовы без повторного прогона.
 */
object ServerFilter {

    /** Протокол разрешён настройками (базовый статический отсев; сюда же придёт стоп-лист). */
    fun protocolAllowed(p: ServerProfile, s: AppSettings): Boolean = p.protocol in s.allowedProtocols

    /** Виден в списке «Живые»: протокол разрешён + пинг жив + скорость пригодна ИЛИ ещё не мерена. */
    fun isVisible(p: ServerProfile, pingMs: Int?, speedMbps: Double?, s: AppSettings): Boolean =
        protocolAllowed(p, s) && pingMs != null && pingMs >= 0 &&
            (speedMbps == null || speedMbps >= s.minUsableMbps)

    /** Пригоден для автоподключения/апгрейда: протокол разрешён + измеренная скорость ≥ порога. */
    fun isSelectable(p: ServerProfile, speedMbps: Double?, s: AppSettings): Boolean =
        protocolAllowed(p, s) && speedMbps != null && speedMbps >= s.minUsableMbps
}
