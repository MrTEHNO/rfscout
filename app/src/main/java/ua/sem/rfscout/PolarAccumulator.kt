package ua.sem.rfscout

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Накопичує максимальний RSSI по азимутальних секторах.
 *
 * Метод: телефон не вміє вимірювати кут приходу хвилі (одна антена, немає
 * фазового інтерферометра). Тому напрямок отримуємо ротаційним скануванням —
 * оператор повертається на 360 градусів, тримаючи телефон перед собою.
 * Тіло оператора дає 6-15 дБ затінення, тому максимум RSSI припадає на
 * напрямок джерела. Реальна похибка 20-40 градусів.
 */
class PolarAccumulator {

    val best = IntArray(Rf.BINS) { EMPTY }
    private var samples = 0

    fun add(azimuthDeg: Float, rssi: Int) {
        var a = azimuthDeg % 360f
        if (a < 0) a += 360f
        val bin = ((a / Rf.BIN_DEG).toInt()) % Rf.BINS
        if (rssi > best[bin]) best[bin] = rssi
        samples++
    }

    fun reset() {
        for (i in best.indices) best[i] = EMPTY
        samples = 0
    }

    fun filledBins(): Int = best.count { it != EMPTY }

    fun coveragePercent(): Int = filledBins() * 100 / Rf.BINS

    fun minRssi(): Int = best.filter { it != EMPTY }.minOrNull() ?: EMPTY
    fun maxRssi(): Int = best.filter { it != EMPTY }.maxOrNull() ?: EMPTY

    /**
     * Оцінка пеленга. Векторна сума по секторах з вагою (rssi - min),
     * що прибирає постійну складову і залишає лише діаграму затінення.
     * null, якщо даних замало або діаграма пласка (джерело всенаправлене
     * або надто близько/далеко).
     */
    fun bearing(): Float? {
        if (filledBins() < 8) return null
        val mn = minRssi()
        val mx = maxRssi()
        if (mx - mn < 4) return null

        var x = 0.0
        var y = 0.0
        for (i in best.indices) {
            if (best[i] == EMPTY) continue
            val w = (best[i] - mn).toDouble()
            val rad = Math.toRadians((i * Rf.BIN_DEG + Rf.BIN_DEG / 2.0))
            x += w * sin(rad)
            y += w * cos(rad)
        }
        if (x == 0.0 && y == 0.0) return null
        var deg = Math.toDegrees(atan2(x, y)).toFloat()
        if (deg < 0) deg += 360f
        return deg
    }

    /** Наскільки виражений максимум: 0..1. Нижче ~0.25 пеленгу вірити не варто. */
    fun confidence(): Float {
        if (filledBins() < 8) return 0f
        val mn = minRssi()
        val mx = maxRssi()
        val span = (mx - mn).coerceAtLeast(0)
        val cov = filledBins().toFloat() / Rf.BINS
        return ((span / 20f).coerceAtMost(1f)) * cov
    }


    /** Наскільки повернути оператора, щоб дивитись на джерело. Текстова підказка. */
    fun hint(heading: Float): String {
        val b = bearing() ?: return "Повертайся навколо себе на 360°"
        var d = b - heading
        while (d > 180) d -= 360
        while (d < -180) d += 360
        val a = Math.abs(d).toInt()
        return when {
            a <= 12 -> "Джерело прямо перед тобою"
            d > 0 -> "Повертай праворуч на $a°"
            else -> "Повертай ліворуч на $a°"
        }
    }

    companion object {
        const val EMPTY = -999
    }
}
