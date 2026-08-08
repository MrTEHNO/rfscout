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
import android.widget.ViewFlipper
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import ua.sem.rfscout.scanners.BleScanner
import ua.sem.rfscout.scanners.BtClassicScanner
import ua.sem.rfscout.scanners.WifiScanner
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var compass: CompassView
    private lateinit var spectrum: SpectrumView
    private lateinit var flipper: ViewFlipper
    private lateinit var hintText: TextView
    private lateinit var distText: TextView
    private lateinit var coverageText: TextView
    private lateinit var list: ListView

    private lateinit var sensors: SensorManager
    private var rotationSensor: Sensor? = null

    private lateinit var wifi: WifiScanner
    private lateinit var ble: BleScanner
    private lateinit var btc: BtClassicScanner

    private val acc = PolarAccumulator()
    private val tracker = SignalTracker()
    private var mode = Mode.ALL
    private var targets: List<Target> = emptyList()
    private var selectedId: String? = null

    private var azimuth = 0f
    private var sinAvg = 0.0
    private var cosAvg = 1.0

    private val handler = Handler(Looper.getMainLooper())
    private var lastWifiScanRequest = 0L
    private var lastListRefresh = 0L
    private var lastSpectrumPush = 0L
    private var lastRssiSeen: Int? = null

    private val rows = ArrayList<String>()
    private var adapter: ArrayAdapter<String>? = null

    private val rot = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orient = FloatArray(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        compass = findViewById(R.id.compass)
        spectrum = findViewById(R.id.spectrum)
        flipper = findViewById(R.id.flipper)
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
        btc = BtClassicScanner(this)
        tracker.setDefaultsFor(mode)
        spectrum.setBands(wifi.has5GHz(), wifi.has6GHz())

        list.choiceMode = ListView.CHOICE_MODE_SINGLE
        list.setOnItemClickListener { _, _, pos, _ ->
            targets.getOrNull(pos)?.let {
                selectedId = it.id
                acc.reset()
                tracker.reset()
                tracker.setDefaultsForKind(it.kind)
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
                Toast.makeText(this, "Опорна точка: $r dBm на 1 м", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<RadioGroup>(R.id.tabGroup).setOnCheckedChangeListener { _, id ->
            flipper.displayedChild = if (id == R.id.tabSpectrum) 1 else 0
        }

        findViewById<RadioGroup>(R.id.modeGroup).setOnCheckedChangeListener { _, id ->
            mode = when (id) {
                R.id.modeWifi -> Mode.WIFI
                R.id.modeBle -> Mode.BLE
                R.id.modeBtc -> Mode.BTC
                else -> Mode.ALL
            }
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
        wifi.stop(); ble.stop(); btc.stop()

        val wantWifi = mode == Mode.WIFI || mode == Mode.ALL
        val wantBle = mode == Mode.BLE || mode == Mode.ALL
        val wantBtc = mode == Mode.BTC || mode == Mode.ALL

        if (wantWifi) wifi.start { }
        if (wantBle) { ble.clear(); ble.start() }
        if (wantBtc) { btc.clear(); btc.start() }

        if ((wantBle || wantBtc) && !ble.isReady()) {
            Toast.makeText(this, "Увімкни Bluetooth", Toast.LENGTH_SHORT).show()
        }
        lastListRefresh = 0L
    }

    private fun collect(): List<Target> {
        val out = ArrayList<Target>()
        when (mode) {
            Mode.WIFI -> out.addAll(wifi.snapshot())
            Mode.BLE -> out.addAll(ble.snapshot())
            Mode.BTC -> out.addAll(btc.snapshot())
            Mode.ALL -> {
                out.addAll(wifi.snapshot())
                out.addAll(ble.snapshot())
                out.addAll(btc.snapshot())
            }
        }
        return out.sortedByDescending { it.rssi }
    }

    private fun refreshList(fresh: List<Target>) {
        targets = fresh
        rows.clear()
        fresh.mapTo(rows) { t ->
            val ch = if (t.channel() > 0) " · CH${t.channel()}" else ""
            val f = if (t.freqMhz > 0) " · ${t.freqMhz} МГц" else ""
            "[${t.tag()}] ${t.label}\n${t.rssi} dBm · ${t.band()}$f$ch"
        }
        if (adapter == null) {
            adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, rows)
            list.adapter = adapter
        } else {
            adapter?.notifyDataSetChanged()
        }
        selectedId?.let { prev ->
            val idx = targets.indexOfFirst { it.id == prev }
            if (idx >= 0) list.setItemChecked(idx, true)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, 100)
        }
    }

    private fun step() {
        val now = System.currentTimeMillis()

        if ((mode == Mode.WIFI || mode == Mode.ALL) && now - lastWifiScanRequest > 4_000) {
            lastWifiScanRequest = now
            wifi.requestScan()
        }

        if (now - lastListRefresh > 1_000) {
            lastListRefresh = now
            refreshList(collect())
        }

        if (now - lastSpectrumPush > 1_000) {
            lastSpectrumPush = now
            spectrum.push(targets)
        }

        val sel = selectedId
        var rssi: Int? = null
        var fresh = false

        if (sel != null) {
            val kind = targets.firstOrNull { it.id == sel }?.kind
            when (kind) {
                Kind.BLE -> { rssi = ble.rssiOf(sel); fresh = rssi != null }
                Kind.BTC -> {
                    rssi = btc.rssiOf(sel)
                    fresh = rssi != null && rssi != lastRssiSeen
                }
                else -> {
                    val conn = wifi.connectedRssi()
                    if (conn != null && conn.first.equals(sel, true)) {
                        rssi = conn.second
                        fresh = true
                    } else {
                        rssi = targets.firstOrNull { it.id == sel }?.rssi
                        fresh = rssi != null && rssi != lastRssiSeen
                    }
                }
            }
        }

        if (rssi != null && fresh) {
            acc.add(azimuth, rssi)
            tracker.add(rssi)
            lastRssiSeen = rssi
        }

        compass.setHeading(azimuth)
        compass.setBearing(acc.bearing())
        compass.confidence = acc.confidence()
        compass.centerValue = tracker.smoothedRssi()?.toString() ?: "—"
        compass.centerCaption = if (selectedId == null) "dBm" else "dBm · ${tracker.distanceBracket()}"

        coverageText.text = "обхід ${acc.coveragePercent()}%"

        val problem = when {
            (mode == Mode.WIFI) -> wifi.problem()
            (mode == Mode.BLE || mode == Mode.BTC) && !ble.isReady() -> "Увімкни Bluetooth"
            else -> null
        }

        if (selectedId == null) {
            hintText.text = problem ?: "Обери ціль зі списку"
            distText.text = when {
                (mode == Mode.WIFI || mode == Mode.ALL) && wifi.throttled ->
                    "Тротлінг скану. Developer options → Wi-Fi scan throttling → вимкнути"
                else -> "Знайдено ${targets.size} · Wi-Fi ${wifi.bandsSupported()} · BT 2.4 ГГц"
            }
        } else {
            hintText.text = acc.hint(azimuth)
            val conf = (acc.confidence() * 100).toInt()
            distText.text = "${tracker.trendText()} · ${tracker.distanceBracket()} · довіра $conf%"
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
        wifi.stop(); ble.stop(); btc.stop()
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

        val a = 0.22
        sinAvg = sinAvg * (1 - a) + sin(rad) * a
        cosAvg = cosAvg * (1 - a) + cos(rad) * a
        var smooth = Math.toDegrees(atan2(sinAvg, cosAvg)).toFloat()
        if (smooth < 0) smooth += 360f
        azimuth = smooth
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
