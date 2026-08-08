package ua.sem.rfscout

enum class Mode { WIFI, BLE, CELL }

/**
 * Одне джерело радіосигналу, яке телефон реально бачить.
 * freqMhz = 0, якщо частота невідома (BLE, частина стільникових).
 */
data class Target(
    val id: String,
    val label: String,
    val freqMhz: Int,
    val rssi: Int,
    val extra: String = ""
) {
    fun band(): String = when {
        freqMhz == 0 -> "—"
        freqMhz < 1000 -> "sub-GHz"
        freqMhz in 2400..2500 -> "2.4 ГГц"
        freqMhz in 5150..5925 -> "5 ГГц"
        freqMhz in 5926..7125 -> "6 ГГц"
        else -> "${freqMhz} МГц"
    }

    fun channel(): Int = Rf.freqToChannel(freqMhz)
}

object Rf {

    fun freqToChannel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2412..2472 -> (freqMhz - 2407) / 5
        freqMhz in 5160..5885 -> (freqMhz - 5000) / 5
        freqMhz in 5955..7115 -> (freqMhz - 5950) / 5
        else -> 0
    }

    /**
     * Груба оцінка дистанції по log-distance path loss.
     * Не метрологія: без калібрування txPower похибка легко 2-3x.
     */
    fun roughDistanceM(rssi: Int, freqMhz: Int, txPowerDbm: Int = 20): Double {
        if (freqMhz <= 0) return -1.0
        val fspl = txPowerDbm - rssi
        val exp = (fspl - 20.0 * Math.log10(freqMhz.toDouble()) + 27.55) / 20.0
        return Math.pow(10.0, exp)
    }

    /** Ширина сектора одного біна полярної діаграми, градусів. */
    const val BIN_DEG = 5
    const val BINS = 360 / BIN_DEG
}
