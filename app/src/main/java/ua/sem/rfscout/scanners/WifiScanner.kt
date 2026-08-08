package ua.sem.rfscout.scanners

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import ua.sem.rfscout.Target

/**
 * Важливо: з Android 9 система тротлить фонове сканування Wi-Fi
 * (4 скани за 2 хвилини). Тому повний список оновлюється рідко.
 * Для ротаційної пеленгації швидкий RSSI доступний лише по мережі,
 * до якої телефон підключений — вона опитується щосекунди.
 * Тротлінг вимикається в Developer options -> Wi-Fi scan throttling.
 */
class WifiScanner(private val ctx: Context) {

    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var receiver: BroadcastReceiver? = null
    private var onUpdate: ((List<Target>) -> Unit)? = null
    private var cache: List<Target> = emptyList()

    fun start(onUpdate: (List<Target>) -> Unit) {
        this.onUpdate = onUpdate
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = publish()
        }
        receiver = r
        ctx.registerReceiver(r, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        requestScan()
        publish()
    }

    fun stop() {
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
        onUpdate = null
    }

    @Suppress("DEPRECATION")
    fun requestScan() {
        runCatching { wm.startScan() }
    }

    /** Швидкий RSSI підключеної мережі — оновлюється без тротлінгу. */
    @Suppress("DEPRECATION")
    fun connectedRssi(): Pair<String, Int>? {
        return runCatching {
            val info = wm.connectionInfo ?: return null
            val bssid = info.bssid ?: return null
            if (bssid == "02:00:00:00:00:00") return null
            bssid to info.rssi
        }.getOrNull()
    }

    fun bandsSupported(): String {
        val sb = StringBuilder("2.4")
        runCatching { if (wm.is5GHzBandSupported) sb.append(" / 5") }
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            runCatching { if (wm.is6GHzBandSupported) sb.append(" / 6") }
        }
        return "$sb ГГц"
    }

    fun snapshot(): List<Target> = cache

    private fun publish() {
        val list = runCatching {
            wm.scanResults.map { r ->
                @Suppress("DEPRECATION")
                val name = if (r.SSID.isNullOrBlank()) "<прихована>" else r.SSID
                Target(
                    id = r.BSSID ?: name,
                    label = name,
                    freqMhz = r.frequency,
                    rssi = r.level,
                    extra = r.BSSID ?: ""
                )
            }.sortedByDescending { it.rssi }
        }.getOrDefault(emptyList())
        cache = list
        onUpdate?.invoke(list)
    }
}
