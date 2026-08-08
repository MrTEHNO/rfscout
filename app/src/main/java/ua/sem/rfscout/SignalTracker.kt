package ua.sem.rfscout

import kotlin.math.pow

/**
 * Трекер сигналу однієї цілі.
 *
 * Дистанція в метрах з RSSI — недостовірна: без калібрування потужності
 * конкретного передавача і в умовах перевідбиттів похибка 3-5 разів.
 * Тому замість числа видаємо діапазон («2-10 м») і, головне, тренд:
 * тренд рахується по зміні RSSI, а не по абсолютному значенню,
 * тому він працює навіть без калібрування.
 */
class SignalTracker {

    private var fast = Double.NaN
    private var slow = Double.NaN
    private var samples = 0

    /** RSSI на відстані 1 м. Типові значення; уточнюється калібруванням. */
    var rssiAt1m = -40
        private set

    /** Показник затухання: 2.0 відкритий простір, 2.7 забудова, 3.5 приміщення. */
    var pathLossExp = 2.7

    fun setDefaultsFor(mode: Mode) {
        rssiAt1m = if (mode == Mode.BLE || mode == Mode.BTC) -59 else -40
    }

    fun setDefaultsForKind(kind: Kind) {
        rssiAt1m = if (kind == Kind.WIFI) -40 else -59
    }

    fun calibrateAt1m(rssi: Int) {
        rssiAt1m = rssi
    }

    fun reset() {
        fast = Double.NaN
        slow = Double.NaN
        samples = 0
    }

    fun add(rssi: Int) {
        val v = rssi.toDouble()
        if (fast.isNaN()) {
            fast = v; slow = v
        } else {
            fast = fast * 0.70 + v * 0.30
            slow = slow * 0.92 + v * 0.08
        }
        samples++
    }

    fun smoothedRssi(): Int? = if (fast.isNaN()) null else fast.toInt()

    /** -1 віддаляємось, 0 стабільно, +1 наближаємось. */
    fun trend(): Int {
        if (samples < 8 || fast.isNaN()) return 0
        val d = fast - slow
        return when {
            d > 1.5 -> 1
            d < -1.5 -> -1
            else -> 0
        }
    }

    fun trendText(): String = when (trend()) {
        1 -> "▲ наближаєшся"
        -1 -> "▼ віддаляєшся"
        else -> "— відстань стабільна"
    }

    /** Груба вилка дистанції. Свідомо без точних чисел. */
    fun distanceBracket(): String {
        val r = smoothedRssi() ?: return "—"
        val d = 10.0.pow((rssiAt1m - r) / (10.0 * pathLossExp))
        return when {
            d < 2 -> "менше 2 м"
            d < 10 -> "2–10 м"
            d < 30 -> "10–30 м"
            d < 100 -> "30–100 м"
            else -> "понад 100 м"
        }
    }
}
