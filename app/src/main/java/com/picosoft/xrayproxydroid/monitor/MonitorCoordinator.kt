package com.picosoft.xrayproxydroid.monitor

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Взаимное исключение монитора и РУЧНОГО полного теста (оба в одном процессе).
 *
 * Пока идёт ручной полный тест ([fullTestRunning] = true), цикл монитора обязан МОЛЧАТЬ и не лезть
 * со своими проверками: полный тест сам переключает активный сервер (early-connect/апгрейд), а монитор
 * в это же время мерил бы «переходный» туннель и делал ложные выводы. Общий ресурс — активный SOCKS
 * и активный сервер; их трогает только один за раз.
 *
 * Счётчики трафика туннеля монитор НЕ читает через queryTunnelDelta (её потребляет поллер трафика
 * сервиса — это был бы конфликт-на-общем-ресурсе); монитор берёт НАКОПЛЕННЫЕ байты из TrafficTracker.
 */
object MonitorCoordinator {
    private val _fullTest = AtomicBoolean(false)

    var fullTestRunning: Boolean
        get() = _fullTest.get()
        set(value) = _fullTest.set(value)
}
