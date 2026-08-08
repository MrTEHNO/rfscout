package ua.sem.rfscout.scanners

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import ua.sem.rfscout.Kind
import ua.sem.rfscout.Target

/**
 * Bluetooth Classic discovery — саме він знаходить периферію:
 * навушники, колонки, годинники, автомагнітоли, трекери, клавіатури.
 *
 * Обмеження: цикл inquiry триває ~12 секунд, RSSI приходить один раз
 * на пристрій за цикл. Для пеленгації це повільно, але для інвентаризації
 * ефіру — найкорисніше джерело.
 */
class BtClassicScanner(private val ctx: Context) {

    private val adapter: BluetoothAdapter? =
        (ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val seen = HashMap<String, Target>()
    private val lastSeenAt = HashMap<String, Long>()
    private var receiver: BroadcastReceiver? = null
    private var running = false

    fun isReady(): Boolean = adapter?.isEnabled == true

    fun start(): Boolean {
        val a = adapter ?: return false
        if (running) return true

        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev: BluetoothDevice? =
                            if (Build.VERSION.SDK_INT >= 33)
                                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            else
                                @Suppress("DEPRECATION")
                                i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val addr = dev?.address ?: return
                        if (rssi == Short.MIN_VALUE.toInt()) return
                        val name = runCatching { dev.name }.getOrNull()
                            ?: "BT ${addr.takeLast(5)}"
                        synchronized(seen) {
                            seen[addr] = Target(
                                id = addr, label = name, freqMhz = 2440,
                                rssi = rssi, kind = Kind.BTC
                            )
                            lastSeenAt[addr] = System.currentTimeMillis()
                        }
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                        // Перезапускаємо цикл, щоб RSSI оновлювався постійно.
                        if (running) runCatching { adapter.startDiscovery() }
                    }
                }
            }
        }
        receiver = r

        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(r, filter)
        }

        return runCatching {
            a.startDiscovery()
            running = true
            true
        }.getOrDefault(false)
    }

    fun stop() {
        running = false
        runCatching { adapter?.cancelDiscovery() }
        receiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        receiver = null
    }

    fun clear() = synchronized(seen) { seen.clear(); lastSeenAt.clear() }

    fun snapshot(): List<Target> = synchronized(seen) {
        val now = System.currentTimeMillis()
        val stale = lastSeenAt.filter { now - it.value > 60_000 }.keys
        stale.forEach { seen.remove(it); lastSeenAt.remove(it) }
        seen.values.sortedByDescending { it.rssi }
    }

    fun rssiOf(id: String): Int? = synchronized(seen) { seen[id]?.rssi }
}
