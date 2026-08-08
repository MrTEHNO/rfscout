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
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ua.sem.rfscout.scanners.BleScanner
import ua.sem.rfscout.scanners.WifiScanner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var compass: CompassView
    private lateinit var hintText: TextView
    private lateinit var distText: TextView
    private lateinit var coverageText: TextView
    private lateinit var list: ListView

    private lateinit var sensors: SensorManager
    private var rotationSensor: Sensor? = null

    private lateinit var wifi: WifiScanner
    private lateinit var ble: BleScanner

    private val acc = PolarAccumulator()
    private val tracker = SignalTracker()
    private var mode = Mode.WIFI
    private var targets: List<Target> = emptyList()
    private var selectedId: String? = null

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

        compass = findViewById(R.id.compass)
        hintText = findViewById(R.id.hintText)
        distText = findViewById(R.id.distText)
        coverageText = findViewById(R.id.coverageText)
        list = findViewById(R.id.targetList)
        compass.acc = acc

        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sensors.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)

        wifi = WifiScanner(this)
        ble = BleScanner(this)
        tracker.setDefaultsFor(mode)

        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.setOnItemClickListener { _, _, pos, _ ->
            targets.getOrNull(pos)?.let {
                selectedId = it.id
                acc.reset()
                tracker.reset()
                lastRssiSeen = null
            }
        }

        findViewById<Button>(R.id.resetBtn).setOnClickListener {
            acc.reset()
            tracker.reset()
            compass.invalidate()
        }

        findViewById<Button>(R.id.calibBtn).setOnClickListener {
            val r = tracker.smoothedRssi()
            if (r == null) {
                Toast.makeText(this, "Спершу обери ціль", Toast.LENGTH_SHORT).show()
            } else {
                tracker.calibrateAt1m(r)
                Toast.makeText(
                    this,
                    "Опорна точка: $r dBm на 1 м. Оцінка дистанції уточнена.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

        findViewById<RadioGroup>(R.id.modeGroup).setOnCheckedChangeListener { _, id ->
            mode = if (id == R.id.modeBle) Mode.BLE else Mode.WIFI
            selectedId = null
            acc.reset()
            tracker.reset()
            tracker.setDefaultsFor(mode)
            restartScanners()
        }

        requestPerms()
    }

    private fun requestPerms() {
        val need = ArrayList<String>()
        need.add(Manifest.permission.ACCESS_FINE_LOCATION)
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
                if (!ok) {
                    Toast.makeText(this, "Увімкни Bluetooth", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateTargets(fresh: List<Target>) {
        targets = fresh
        val rows = fresh.map { t ->
            val ch = if (t.channel() > 0) " · CH${t.channel()}" else ""
            val f = if (t.freqMhz > 0) " · ${t.freqMhz} МГц" else ""
            "${t.label}\n${t.rssi} dBm · ${t.band()}$f$ch"
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, rows)
        list.adapter = adapter
        selectedId?.let { prev ->
            val idx = targets.indexOfFirst { it.id == prev }
            if (idx >= 0) list.setItemChecked(idx, true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 350)
        }
    }

    private fun step() {
        val now = System.currentTimeMillis()
        if (mode == Mode.WIFI && now - lastWifiScanRequest > 30_000) {
            lastWifiScanRequest = now
            wifi.requestScan()
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
                    fresh = rssi != null
                }
            }
        }

        if (rssi != null && fresh) {
            acc.add(azimuth, rssi)
            tracker.add(rssi)
            lastRssiSeen = rssi
        }

        compass.heading = azimuth
        compass.bearing = acc.bearing()
        compass.confidence = acc.confidence()
        compass.centerValue = tracker.smoothedRssi()?.let { "$it" } ?: "—"
        compass.centerCaption = if (selectedId == null) "dBm" else "dBm · ${tracker.distanceBracket()}"
        compass.invalidate()

        coverageText.text = "обхід ${acc.coveragePercent()}%"

        if (selectedId == null) {
            hintText.text = "Обери ціль зі списку"
            distText.text = "Wi-Fi: ${wifi.bandsSupported()} · BLE 2.4 ГГц"
        } else {
            hintText.text = acc.hint(azimuth)
            val conf = (acc.confidence() * 100).toInt()
            distText.text = "${tracker.trendText()} · дистанція ${tracker.distanceBracket()} · довіра $conf%"
        }
    }

    override fun onResume() {
        super.onResume()
        rotationSensor?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        handler.post(tick)
        restartScanners()
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
        SensorManager.remapCoordinateSystem(
            rot, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped
        )
        SensorManager.getOrientation(remapped, orient)
        val rad = orient[0].toDouble()

        val a = 0.15
        sinAvg = sinAvg * (1 - a) + sin(rad) * a
        cosAvg = cosAvg * (1 - a) + cos(rad) * a
        var smooth = Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat()
        if (smooth < 0) smooth += 360f
        azimuth = smooth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
