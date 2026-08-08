package ua.sem.rfscout.scanners

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import ua.sem.rfscout.Target

/**
 * BLE не тротлиться системою: RSSI приходить кілька разів на секунду.
 * Це найкращий режим для ротаційної пеленгації.
 */
class BleScanner(private val ctx: Context) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val seen = LinkedHashMap<String, Target>()
    private var onUpdate: ((List<Target>) -> Unit)? = null
    private var scanning = false

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val addr = r.device?.address ?: return
            val name = runCatching { r.device?.name }.getOrNull()
                ?: r.scanRecord?.deviceName
                ?: "BLE"
            seen[addr] = Target(
                id = addr,
                label = name,
                freqMhz = 2440,
                rssi = r.rssi,
                extra = addr
            )
            onUpdate?.invoke(seen.values.sortedByDescending { it.rssi })
        }
    }

    fun start(onUpdate: (List<Target>) -> Unit): Boolean {
        this.onUpdate = onUpdate
        val scanner = adapter?.bluetoothLeScanner ?: return false
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setReportDelay(0)
            .build()
        return runCatching {
            scanner.startScan(null, settings, cb)
            scanning = true
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!scanning) return
        runCatching { adapter?.bluetoothLeScanner?.stopScan(cb) }
        scanning = false
        onUpdate = null
    }

    fun rssiOf(id: String): Int? = seen[id]?.rssi
}
