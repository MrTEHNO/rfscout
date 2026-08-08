package ua.sem.rfscout

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ua.sem.rfscout.scanners.BleScanner
import ua.sem.rfscout.scanners.CellScanner
import ua.sem.rfscout.scanners.WifiScanner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var polar: PolarView
    private lateinit var spectrum: SpectrumView
    private lateinit var status: TextView
    private lateinit var bearingText: TextView
    private lateinit var list: ListView
    private lateinit var scanBtn: Button

    private lateinit var sensors: SensorManager
    private var rotationSensor: Sensor? = null

    private lateinit var wifi: WifiScanner
    private lateinit var ble: BleScanner
    private lateinit var cell: CellScanner

    private val acc = PolarAccumulator()
    private var mode = Mode.WIFI
    private var targets: List<Target> = emptyList()
    private var selectedId: String? = null
    private var running = true

    private var azimuth = 0f
    private var sinAvg = 0.0
    private var cosAvg = 1.0

    private val handler = Handler(Looper.getMainLooper())
    private var lastWifiScanRequest = 0L
    private var lastRssiSeen: Int? = null

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        polar = findViewById(R.id.polarView)
        spectrum = findViewById(R.id.spectrumView)
        status = findViewById(R.id.statusText)
        bearingText = findViewById(R.id.bearingText)
        list = findViewById(R.id.targetList)
        scanBtn = findViewById(R.id.scanBtn)
        polar.acc = acc

        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

        wifi = WifiScanner(this)
        ble = BleScanner(this)
        cell = CellScanner(this)

        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.setOnItemClickListener { _, _, pos, _ ->
            targets.getOrNull(pos)?.let {
                selectedId = it.id
                acc.reset()
                lastRssiSeen = null
            }
        }

        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            acc.reset()
            polar.invalidate()
        }

        scanBtn.setOnClickListener {
            running = !running
            scanBtn.text = if (running) "Стоп" else "Старт"
        }

        findViewById<RadioGroup>(R.id.modeGroup).setOnCheckedChangeListener { _, id ->
            mode = when (id) {
                R.id.modeBle -> Mode.BLE
                R.id.modeCell -> Mode.CELL
                else -> Mode.WIFI
            }
            selectedId = null
            acc.reset()
            restartScanners()
        }

        requestPerms()
    }

    private fun requestPerms() {
        val need = ArrayList<String>()
        need.add(Manifest.permission.ACCESS_FINE_LOCATION)
        need.add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 31) {
            need.add(Manifest.permission.BLUETOOTH_SCAN)
            need.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            need.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        val missing = need.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1)
        } else {
            restartScanners()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        restartScanners()
    }

    private fun restartScanners() {
        wifi.stop()
        ble.stop()
        when (mode) {
            Mode.WIFI -> wifi.start { updateTargets(it) }
            Mode.BLE -> {
                val ok = ble.start { updateTargets(it) }
                if (!ok) status.text = "BLE недоступний або вимкнений Bluetooth"
            }
            Mode.CELL -> {}
        }
    }

    private fun updateTargets(list0: List<Target>) {
        targets = list0
        val rows = list0.map { t ->
            val ch = if (t.channel() > 0) " · CH${t.channel()}" else ""
            val f = if (t.freqMhz > 0) " · ${t.freqMhz} МГц" else ""
            "${t.label}\n${t.rssi} dBm · ${t.band()}$f$ch  ${t.extra}"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, rows)
        val prev = selectedId
        list.adapter = adapter
        if (prev != null) {
            val idx = targets.indexOfFirst { it.id == prev }
            if (idx >= 0) list.setItemChecked(idx, true)
        }
        if (mode == Mode.WIFI) spectrum.update(list0)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (running) step()
            handler.postDelayed(this, 400)
        }
    }

    private fun step() {
        val now = System.currentTimeMillis()

        if (mode == Mode.WIFI && now - lastWifiScanRequest > 30_000) {
            lastWifiScanRequest = now
            wifi.requestScan()
        }
        if (mode == Mode.CELL) {
            updateTargets(cell.read())
        }

        val sel = selectedId
        var rssi: Int? = null
        var fresh = false

        if (sel != null) {
            when (mode) {
                Mode.WIFI -> {
                    val conn = wifi.connectedRssi()
                    if (conn != null && conn.first.equals(sel, true)) {
                        rssi = conn.second
                        fresh = true
                    } else {
                        rssi = targets.firstOrNull { it.id == sel }?.rssi
                        fresh = rssi != null && rssi != lastRssiSeen
                    }
                }
                Mode.BLE -> {
                    rssi = ble.rssiOf(sel)
                    fresh = true
                }
                Mode.CELL -> {
                    rssi = targets.firstOrNull { it.id == sel }?.rssi
                    fresh = rssi != null && rssi != lastRssiSeen
                }
            }
        }

        if (rssi != null && fresh) {
            acc.add(azimuth, rssi)
            lastRssiSeen = rssi
        }

        val b = acc.bearing()
        polar.heading = azimuth
        polar.bearing = b
        polar.invalidate()

        val selLabel = targets.firstOrNull { it.id == selectedId }?.label
        status.text = buildString {
            append("Курс ${azimuth.toInt()}° · охоплення ${acc.coveragePercent()}%")
            append(" · діапазони Wi-Fi: ${wifi.bandsSupported()}")
            if (selLabel != null) append("\nЦіль: $selLabel  ${rssi ?: "—"} dBm")
        }

        bearingText.text = when {
            selectedId == null -> "Обери ціль зі списку"
            b == null -> "Повертайся навколо себе на 360° — даних замало"
            else -> {
                val conf = (acc.confidence() * 100).toInt()
                val dist = targets.firstOrNull { it.id == selectedId }
                    ?.let { if (it.freqMhz > 0 && rssi != null) Rf.roughDistanceM(rssi, it.freqMhz) else -1.0 }
                    ?: -1.0
                val d = if (dist > 0) " · ~${String.format("%.0f", dist)} м" else ""
                "Пеленг ${b.toInt()}° · довіра $conf%$d"
            }
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        handler.post(tick)
        if (mode == Mode.WIFI || mode == Mode.BLE) restartScanners()
    }

    override fun onPause() {
        super.onPause()
        sensors.unregisterListener(this)
        handler.removeCallbacks(tick)
        wifi.stop()
        ble.stop()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        val e = event ?: return
        if (e.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            e.sensor.type != Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR
        ) return

        SensorManager.getRotationMatrixFromVector(rot, e.values)
        // Телефон тримають вертикально, екраном до себе: вісь пеленга — верхній торець.
        SensorManager.remapCoordinateSystem(
            rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
        )
        SensorManager.getOrientation(remapped, orient)
        val deg = Math.toDegrees(orient[0].toDouble())
        val rad = Math.toRadians(deg)

        // Низькочастотний фільтр по вектору, щоб не стрибав перехід 359->0.
        val a = 0.15
        sinAvg = sinAvg * (1 - a) + sin(rad) * a
        cosAvg = cosAvg * (1 - a) + cos(rad) * a
        var smooth = Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat()
        if (smooth < 0) smooth += 360f
        azimuth = smooth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
