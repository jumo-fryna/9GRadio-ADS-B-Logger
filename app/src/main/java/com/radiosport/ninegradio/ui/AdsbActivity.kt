package com.radiosport.ninegradio.ui

import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.*
import android.os.Bundle
import android.os.IBinder
import android.view.*
import android.widget.*
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.dsp.DemodMode
import com.radiosport.ninegradio.usb.RtlSdrService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.sample
import com.radiosport.ninegradio.RtlSdrApplication

/**
 * Full-screen ADS-B radar display.
 * Auto-tunes RTL-SDR to 1090 MHz and collects raw IQ magnitude from the
 * DspEngine's iqMagnitudeFlow — the correct input for the Mode-S preamble
 * detector (2 samples/µs at 2.048 MS/s).
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AdsbActivity : AppCompatActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var radarView: AdsbRadarView
    private lateinit var tvAircraftCount: TextView
    private lateinit var tvStatus: TextView

    private val logger get() = (application as RtlSdrApplication).adsbLogger
    private val liveTracks = MutableStateFlow<List<AdsbDecoder.AdsbFrame>>(emptyList())
    private val decoder = AdsbDecoder()
    private var loggerJob: Job? = null
    private var vmBound = false
    private lateinit var previousMode: DemodMode
    private val aircraft = HashMap<String, AdsbDecoder.AdsbFrame>()
    private val aircraftList = mutableListOf<String>()
    private lateinit var listAdapter: android.widget.ArrayAdapter<String>

    // ─── Service binding for IQ magnitude flow ────────────────────────────────

    private var sdrService: RtlSdrService? = null
    private var serviceBound = false
    private var iqFeedJob: Job? = null

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            sdrService = (binder as RtlSdrService.LocalBinder).getService()
            serviceBound = true
            startIqFeed()
        }
        override fun onServiceDisconnected(name: ComponentName) {
            iqFeedJob?.cancel()
            logger.stop()
            sdrService = null
        }
    }

    /** Feed raw IQ magnitude (not FFT) to the ADS-B preamble detector,
     *  and subscribe to the service's own connectionState so the status bar
     *  always reflects reality (avoids the Activity-scoped ViewModel issue). */
    private fun startIqFeed() {
        iqFeedJob?.cancel()
        val svc = sdrService ?: return
        iqFeedJob = lifecycleScope.launch {
            svc.connectionState.collectLatest { state ->
                tvStatus.text = when (state) {
                    is RtlSdrService.ConnectionState.Connected -> "LIVE — 1090 MHz"
                    is RtlSdrService.ConnectionState.Connecting -> "Connecting…"
                    else -> "No Device"
                }
                if (state is RtlSdrService.ConnectionState.Connected) {
                    val position = receiverPosition()
                    logger.start(position?.first, position?.second)
                    withContext(Dispatchers.Default) {
                        svc.dspEngine?.iqMagnitudeFlow?.collect { decoder.feed(it) }
                    }
                } else logger.stop()
            }
        }
    }

    private fun receiverPosition(): Pair<Double, Double>? {
        val prefs = getSharedPreferences("adsb_logger", MODE_PRIVATE)
        val lat = prefs.getString("receiverLat", null)?.toDoubleOrNull()
        val lon = prefs.getString("receiverLon", null)?.toDoubleOrNull()
        return if (com.radiosport.ninegradio.adsblog.ReceptionAggregator.validPosition(lat, lon)) Pair(lat!!, lon!!) else null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_adsb)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = "ADS-B Radar (1090 MHz)"


        radarView       = findViewById(R.id.radarView)
        tvAircraftCount = findViewById(R.id.tvAircraftCount)
        tvStatus        = findViewById(R.id.tvAdsbStatus)

        val listAircraft = findViewById<android.widget.ListView>(R.id.listAircraft)
        listAdapter = android.widget.ArrayAdapter(this, android.R.layout.simple_list_item_1, aircraftList)
        listAircraft.adapter = listAdapter
        listAircraft.setOnItemClickListener { _, _, pos, _ ->
            val frame = liveTracks.value.sortedByDescending { it.altitude ?: -1 }.getOrNull(pos) ?: return@setOnItemClickListener
            lifecycleScope.launch {
                logger.flush()
                startActivity(Intent(this@AdsbActivity, AdsbDetailActivity::class.java).putExtra("icao24", frame.icao24))
            }
        }

        // Auto-tune to 1090 MHz and switch to ADS-B mode.
        // setDemodMode() saves the previous mode's settings and restores any
        // previously saved ADS-B settings.  On first use (no snapshot yet) we
        // apply the protocol-required defaults so they become the ADS-B baseline.
        previousMode = savedInstanceState?.getString("previousMode")?.let { DemodMode.valueOf(it) } ?: viewModel.demodMode.value
        viewModel.setDemodMode(DemodMode.ADSB)
        viewModel.setFrequency(AdsbDecoder.ADSB_FREQ_HZ)
        if (!viewModel.hasModeSnapshot(DemodMode.ADSB)) {
            // First-ever ADS-B launch: seed protocol-required defaults.
            // 1.920 MS/s = 48 000 × 40 — perfect integer decimation to the 48 kHz
            // audio output rate; provides ample bandwidth for the 1.090 GHz Mode-S
            // channel and is within the RTL-SDR's main band.
            val adsbPrefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
            viewModel.setSampleRate(1_920_000)
            viewModel.setTunerAgc(adsbPrefs.getBoolean("pref_tuner_agc_default", false))
            viewModel.setHardwareAgc(adsbPrefs.getBoolean("pref_hardware_agc_default", false))
            if (!adsbPrefs.getBoolean("pref_tuner_agc_default", false))
                viewModel.setGain(adsbPrefs.getInt("pref_default_gain", 26))
        }

        // Merge partial frames in memory off the UI thread; checkpointing is independent.
        loggerJob = lifecycleScope.launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
            decoder.frames.collect { frame ->
                logger.accept(frame)
                val old = aircraft[frame.icao24]
                aircraft[frame.icao24] = if (old == null) frame else frame.copy(
                    callsign = frame.callsign ?: old.callsign, altitude = frame.altitude ?: old.altitude,
                    latitude = frame.latitude ?: old.latitude, longitude = frame.longitude ?: old.longitude,
                    velocity = frame.velocity ?: old.velocity, heading = frame.heading ?: old.heading,
                    verticalRate = frame.verticalRate ?: old.verticalRate)
                val cutoff = System.currentTimeMillis() - 120_000L
                aircraft.entries.removeAll { it.value.timestamp < cutoff }
                liveTracks.value = aircraft.values.toList()
            }
        }
        lifecycleScope.launch {
            liveTracks.sample(500).collect { frames ->
                radarView.updateAircraft(frames)
                tvAircraftCount.text = "Aircraft: ${frames.size}"
                aircraftList.clear()
                frames.sortedByDescending { it.altitude ?: -1 }.forEach { ac ->
                    val callsign = ac.callsign?.trim()?.ifBlank { ac.icao24 } ?: ac.icao24
                    val alt = ac.altitude?.let { "${it}ft" } ?: "??"
                    val pos = if (ac.latitude != null && ac.longitude != null)
                        "${"%.2f".format(ac.latitude)},${"%.2f".format(ac.longitude)}" else "no pos"
                    val spd = ac.velocity?.let { "${it}kt" } ?: ""
                    aircraftList.add("$callsign | $alt | $pos $spd")
                }
                listAdapter.notifyDataSetChanged()
            }
        }
        lifecycleScope.launch { logger.error.collect { error ->
            findViewById<TextView>(R.id.tvAdsbLogError).apply {
                text = error.orEmpty(); visibility = if (error == null) View.GONE else View.VISIBLE
            }
        } }
        receiverPosition()?.let { radarView.setOwnPosition(it.first, it.second) }
        findViewById<View>(R.id.btnAdsbBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnAdsbHistory).setOnClickListener {
            startActivity(Intent(this, AdsbLogActivity::class.java))
        }
        findViewById<View>(R.id.btnAdsbReceiver).setOnClickListener { configureReceiver() }
        // Bind the Activity's ViewModel too so its existing tuning commands reach the service.
        vmBound = bindService(Intent(this, RtlSdrService::class.java), viewModel.serviceConnection, BIND_AUTO_CREATE)
        serviceBound = bindService(Intent(this, RtlSdrService::class.java), serviceConnection, BIND_AUTO_CREATE)

        // The bound service owns reception state; the ViewModel supplies saved tuning settings.
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("previousMode", previousMode.name)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (isFinishing && ::previousMode.isInitialized) viewModel.setDemodMode(previousMode)
        super.onDestroy()
        iqFeedJob?.cancel()
        loggerJob?.cancel()
        if (!isChangingConfigurations) logger.stop()
        if (vmBound) { unbindService(viewModel.serviceConnection); vmBound = false }
        if (serviceBound) { unbindService(serviceConnection); serviceBound = false }
    }

    private fun configureReceiver() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 12, 24, 12) }
        val position = receiverPosition()
        val lat = EditText(this).apply { hint = "Receiver latitude (-90 … 90)"; setText(position?.first?.toString().orEmpty()) }
        val lon = EditText(this).apply { hint = "Receiver longitude (-180 … 180)"; setText(position?.second?.toString().orEmpty()) }
        form.addView(lat); form.addView(lon)
        val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Receiver position")
            .setMessage("Distance is unknown until a receiver position is set. Updating it starts a new listening session.")
            .setView(form).setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener { dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val a = lat.text.toString().toDoubleOrNull(); val b = lon.text.toString().toDoubleOrNull()
            if (!com.radiosport.ninegradio.adsblog.ReceptionAggregator.validPosition(a, b)) {
                lat.error = "Enter valid latitude and longitude"; return@setOnClickListener
            }
            getSharedPreferences("adsb_logger", MODE_PRIVATE).edit()
                .putString("receiverLat", a.toString()).putString("receiverLon", b.toString()).apply()
            radarView.setOwnPosition(a!!, b!!)
            logger.stop()
            if (sdrService?.connectionState?.value is RtlSdrService.ConnectionState.Connected) logger.start(a, b)
            dialog.dismiss()
        } }
        dialog.show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { finish(); return true }
        return super.onOptionsItemSelected(item)
    }
}

/**
 * Radar sweep display showing aircraft positions on a polar grid.
 */
class AdsbRadarView @JvmOverloads constructor(
    context: android.content.Context,
    attrs: android.util.AttributeSet? = null
) : View(context, attrs) {

    private var aircraftList = listOf<AdsbDecoder.AdsbFrame>()
    private var ownLat = 0.0
    private var ownLon = 0.0
    private var rangeNm = 250.0  // display range in nautical miles

    private val bgPaint    = Paint().apply { color = 0xFF050D12.toInt(); style = Paint.Style.FILL }
    private val gridPaint  = Paint().apply { color = 0x2200FF44.toInt(); style = Paint.Style.STROKE; strokeWidth = 0.7f; isAntiAlias = true }
    private val sweepPaint = Paint().apply { color = 0x4400FF44.toInt(); style = Paint.Style.STROKE; strokeWidth = 1.5f; isAntiAlias = true }
    private val dotPaint   = Paint().apply { color = 0xFF00FF88.toInt(); style = Paint.Style.FILL; isAntiAlias = true }
    private val textPaint  = Paint().apply { color = 0xCC00FF88.toInt(); textSize = 24f; isAntiAlias = true; typeface = Typeface.MONOSPACE }
    private val labelPaint = Paint().apply { color = 0x8800AAFF.toInt(); textSize = 18f; isAntiAlias = true }
    private val sweepGradPaint = Paint().apply { style = Paint.Style.FILL }

    private var sweepAngle = 0f
    private val sweepAnimator = android.animation.ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 4000
        repeatCount = android.animation.ValueAnimator.INFINITE
        interpolator = android.view.animation.LinearInterpolator()
        addUpdateListener { anim ->
            sweepAngle = anim.animatedValue as Float
            invalidate()
        }
        start()
    }

    fun updateAircraft(list: List<AdsbDecoder.AdsbFrame>) {
        aircraftList = list.filter { it.latitude != null && it.longitude != null }
        postInvalidate()
    }

    fun setOwnPosition(lat: Double, lon: Double) { ownLat = lat; ownLon = lon }
    fun setRange(nm: Double) { rangeNm = nm }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r  = minOf(cx, cy) * 0.94f

        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Range rings
        for (i in 1..4) {
            val ringR = r * i / 4f
            canvas.drawCircle(cx, cy, ringR, gridPaint)
            labelPaint.textAlign = Paint.Align.LEFT
            canvas.drawText("${(rangeNm * i / 4).toInt()} nm", cx + ringR + 4f, cy - 4f, labelPaint)
        }

        // Cardinal lines
        canvas.drawLine(cx, cy - r, cx, cy + r, gridPaint)
        canvas.drawLine(cx - r, cy, cx + r, cy, gridPaint)

        // Sweep gradient
        val sweepMatrix = Matrix().apply { postTranslate(-cx, -cy); postRotate(sweepAngle); postTranslate(cx, cy) }
        val sweepShader = SweepGradient(cx, cy,
            intArrayOf(0x0000FF44, 0x8800FF44.toInt(), 0x0000FF44), null)
        sweepShader.setLocalMatrix(sweepMatrix)
        sweepGradPaint.shader = sweepShader
        canvas.drawCircle(cx, cy, r, sweepGradPaint)

        // Sweep arm
        val sweepRad = Math.toRadians(sweepAngle.toDouble())
        canvas.drawLine(cx, cy,
            cx + (r * Math.sin(sweepRad)).toFloat(),
            cy - (r * Math.cos(sweepRad)).toFloat(),
            sweepPaint)

        // Aircraft blips
        for (ac in aircraftList) {
            val lat = ac.latitude ?: continue
            val lon = ac.longitude ?: continue

            val (px, py) = latLonToPixel(lat, lon, cx, cy, r)

            val ageSec = (System.currentTimeMillis() - ac.timestamp) / 1000L
            val alpha = (255 * (1.0 - ageSec / 120.0)).toInt().coerceIn(80, 255)
            dotPaint.alpha = alpha

            val dotR = when {
                (ac.altitude ?: 0) > 30_000 -> 7f
                (ac.altitude ?: 0) > 10_000 -> 5f
                else -> 4f
            }
            canvas.drawCircle(px, py, dotR, dotPaint)

            textPaint.alpha = (alpha * 0.8f).toInt()
            val label = ac.callsign?.trim()?.ifBlank { ac.icao24 } ?: ac.icao24
            canvas.drawText(label, px + 8f, py - 8f, textPaint)

            ac.altitude?.let {
                textPaint.alpha = (alpha * 0.5f).toInt()
                textPaint.textSize = 18f
                canvas.drawText("FL${it / 100}", px + 8f, py + 14f, textPaint)
                textPaint.textSize = 24f
            }
        }

        // Own position
        dotPaint.alpha = 255
        dotPaint.color = 0xFFFFFF00.toInt()
        canvas.drawCircle(cx, cy, 5f, dotPaint)
        dotPaint.color = 0xFF00FF88.toInt()

        // Compass labels
        textPaint.alpha = 180; textPaint.color = 0xFFCCFFCC.toInt()
        textPaint.textAlign = Paint.Align.CENTER
        canvas.drawText("N", cx, cy - r - 4f, textPaint)
        canvas.drawText("S", cx, cy + r + 20f, textPaint)
        canvas.drawText("W", cx - r - 4f, cy + 8f, textPaint)
        canvas.drawText("E", cx + r + 4f, cy + 8f, textPaint)
        textPaint.color = 0xFF00FF88.toInt()
        textPaint.textAlign = Paint.Align.LEFT
    }

    private fun latLonToPixel(lat: Double, lon: Double, cx: Float, cy: Float, r: Float): Pair<Float, Float> {
        val dLat = lat - ownLat
        val dLon = (lon - ownLon) * Math.cos(Math.toRadians(ownLat))
        val distNm = Math.sqrt(dLat * dLat + dLon * dLon) * 60.0
        val bearing = Math.atan2(dLon, dLat)
        val pixR = (distNm / rangeNm * r).toFloat().coerceAtMost(r)
        val px = cx + pixR * Math.sin(bearing).toFloat()
        val py = cy - pixR * Math.cos(bearing).toFloat()
        return Pair(px, py)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        sweepAnimator.cancel()
    }
}

