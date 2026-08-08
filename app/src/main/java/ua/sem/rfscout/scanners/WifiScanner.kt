package ua.sem.rfscout.scanners

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import ua.sem.rfscout.Target

/**
 * Wi-Fi сканер з явною діагностикою.
 *
 * Найчастіша причина порожнього списку — вимкнена служба геолокації.
 * Android у цьому випадку не кидає помилку, а просто віддає порожній
 * масив, тому причину треба перевіряти самим і показувати оператору.
 */
class WifiScanner(private val ctx: Context) {

    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var receiver: BroadcastReceiver? = null
    private var onUpdate: ((List<Target>) -> Unit)? = null
    private var cache: List<Target> = emptyList()

    /** true, якщо система відхилила останній запит скану через тротлінг. */
    var throttled = false
        private set

    var lastResultAt = 0L
        private set

    fun start(onUpdate: (List<Target>) -> Unit) {
        this.onUpdate = onUpdate
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) = publish()
        }
        receiver = r
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(r, filter)
        }
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
        val ok = runCatching { wm.startScan() }.getOrDefault(false)
        throttled = !ok
    }

    @Suppress("DEPRECATION")
    fun connectedRssi(): Pair<String, Int>? = runCatching {
        val info = wm.connectionInfo ?: return null
        val bssid = info.bssid ?: return null
        if (bssid == "02:00:00:00:00:00") return null
        bssid to info.rssi
    }.getOrNull()

    fun bandsSupported(): String {
        val sb = StringBuilder("2.4")
        runCatching { if (wm.is5GHzBandSupported) sb.append("/5") }
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching { if (wm.is6GHzBandSupported) sb.append("/6") }
        }
        return "$sb ГГц"
    }

    /**
     * Причина, чому список порожній. null — все гаразд.
     */
    fun problem(): String? {
        if (!wm.isWifiEnabled) return "Увімкни Wi-Fi у налаштуваннях"

        val fine = ContextCompat.checkSelfPermission(
            ctx, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        if (!fine) return "Дай дозвіл на точну геолокацію"

        if (Build.VERSION.SDK_INT >= 33) {
            val nearby = ContextCompat.checkSelfPermission(
                ctx, Manifest.permission.NEARBY_WIFI_DEVICES
            ) == PackageManager.PERMISSION_GRANTED
            if (!nearby) return "Дай дозвіл «Пристрої поблизу»"
        }

        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val locOn = runCatching {
            if (Build.VERSION.SDK_INT >= 28) lm?.isLocationEnabled == true
            else lm?.isProviderEnabled(LocationManager.GPS_PROVIDER) == true ||
                    lm?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) == true
        }.getOrDefault(true)
        if (!locOn) return "Увімкни геолокацію — без неї Android ховає Wi-Fi мережі"

        if (cache.isEmpty()) return "Мереж не знайдено, чекаю скан…"
        return null
    }

    fun snapshot(): List<Target> = cache

    private fun publish() {
        val list = runCatching {
            wm.scanResults.map { r ->
                @Suppress("DEPRECATION")
                val name = if (r.SSID.isNullOrBlank()) "‹прихована›" else r.SSID
                Target(
                    id = r.BSSID ?: name,
                    label = name,
                    freqMhz = r.frequency,
                    rssi = r.level
                )
            }.sortedByDescending { it.rssi }
        }.getOrDefault(emptyList())
        if (list.isNotEmpty()) lastResultAt = System.currentTimeMillis()
        cache = list
        onUpdate?.invoke(list)
    }
}
