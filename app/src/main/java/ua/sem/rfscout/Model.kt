package ua.sem.rfscout

enum class Mode { WIFI, BLE }

/**
 * Одне джерело радіосигналу, яке телефон реально бачить.
 * Ширші діапазони (3.3 ГГц, 5.8 аналог, sub-GHz) вбудованим залізом
 * недосяжні — для них потрібен зовнішній SDR по OTG.
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
        freqMhz in 2400..2500 -> "2.4 ГГц"
        freqMhz in 5150..5925 -> "5 ГГц"
        freqMhz in 5926..7125 -> "6 ГГц"
        else -> "$freqMhz МГц"
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

    const val BIN_DEG = 5
    const val BINS = 360 / BIN_DEG
}
