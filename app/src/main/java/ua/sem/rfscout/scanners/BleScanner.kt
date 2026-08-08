package ua.sem.rfscout.scanners

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import ua.sem.rfscout.Kind
import ua.sem.rfscout.Target

/**
 * BLE не тротлиться системою: RSSI приходить кілька разів на секунду.
 * Це найкращий режим для ротаційної пеленгації.
 *
 * Колбек лише оновлює мапу; перемальовуванням списку керує UI,
 * інакше список смикається на кожен пакет.
 */
class BleScanner(private val ctx: Context) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val seen = HashMap<String, Target>()
    private val lastSeenAt = HashMap<String, Long>()
    private var scanning = false

    private val cb = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            val r = result ?: return
            val addr = r.device?.address ?: return
            val name = runCatching { r.device?.name }.getOrNull()
                ?: r.scanRecord?.deviceName
                ?: "BLE ${addr.takeLast(5)}"
            synchronized(seen) {
                seen[addr] = Target(id = addr, label = name, freqMhz = 2440, rssi = r.rssi, kind = Kind.BLE)
                lastSeenAt[addr] = System.currentTimeMillis()
            }
        }
    }

    fun isReady(): Boolean = adapter?.isEnabled == true

    fun start(): Boolean {
        if (scanning) return true
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
    }

    fun clear() = synchronized(seen) { seen.clear(); lastSeenAt.clear() }

    /** Пристрої, які озвались за останні 20 секунд. */
    fun snapshot(): List<Target> = synchronized(seen) {
        val now = System.currentTimeMillis()
        val stale = lastSeenAt.filter { now - it.value > 20_000 }.keys
        stale.forEach { seen.remove(it); lastSeenAt.remove(it) }
        seen.values.sortedByDescending { it.rssi }
    }

    fun rssiOf(id: String): Int? = synchronized(seen) { seen[id]?.rssi }
}
