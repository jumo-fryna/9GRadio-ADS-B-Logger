package com.radiosport.ninegradio.skylog

import android.app.*
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.content.pm.ServiceInfo
import android.os.*
import androidx.core.app.NotificationCompat
import com.radiosport.ninegradio.R
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.adsblog.*
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.dsp.NativeDsp
import com.radiosport.ninegradio.source.RtlSdrDeviceSource
import com.radiosport.ninegradio.usb.UsbDeviceManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Only ADS-B: fixed tuner, application-owned decoder and writer, no audio/FFT pipeline. */
class SkyLogService : Service() {
    companion object { const val STOP = "skylog.STOP"; const val APPLY = "skylog.APPLY"; const val RATE = 2_000_000 }
    inner class LocalBinder : Binder() { val service get() = this@SkyLogService }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private lateinit var usb: UsbDeviceManager
    private var source: RtlSdrDeviceSource? = null
    private var reader: Job? = null
    private var health: Job? = null
    private val wake by lazy { (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"SkyLog1090:USB").apply{setReferenceCounted(false)} }
    private val logger get() = (application as RtlSdrApplication).adsbLogger
    val state = MutableStateFlow("NO RTL-SDR • waiting for USB")
    val live = MutableStateFlow<List<AdsbDecoder.AdsbFrame>>(emptyList())
    val routes=MutableStateFlow<Map<String,List<TrackPoint>>>(emptyMap())
    private val trails=HashMap<String,MutableList<TrackPoint>>()
    private var utcDay=java.time.LocalDate.now(java.time.ZoneOffset.UTC)
    private val aircraft = HashMap<String, AdsbDecoder.AdsbFrame>()
    private val prefs get() = getSharedPreferences("adsb_logger", MODE_PRIVATE)
    override fun onBind(intent: Intent): IBinder = LocalBinder()
    override fun onCreate() {
        super.onCreate()
        usb = UsbDeviceManager(this)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            usb.deviceEvents.collect { event -> when (event) {
                is UsbDeviceManager.DeviceEvent.Attached -> { state.value = "USB detected • permission requested"; usb.requestPermission(event.device) }
                is UsbDeviceManager.DeviceEvent.PermissionGranted -> connect(event.device)
                is UsbDeviceManager.DeviceEvent.PermissionDenied -> { state.value = "USB permission denied • reconnect to retry" }
                is UsbDeviceManager.DeviceEvent.Detached -> if (source?.usbDevice?.deviceId == event.device.deviceId) mutex.withLock { disconnect(); state.value = "RTL-SDR disconnected" }
                else -> Unit
            } }
        }
        usb.startListening()
        scope.launch { usb.autoConnect() }
        scope.launch { while (isActive) { delay(1000); synchronized(aircraft) {
            aircraft.entries.removeAll { System.currentTimeMillis() - it.value.timestamp > 120_000 }
            trails.keys.retainAll(aircraft.keys)
            live.value = aircraft.values.toList(); routes.value=trails.mapValues{it.value.toList()}
        } } }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, RtlSdrApplication.CHANNEL_SDR_SERVICE)
            .setContentTitle("SkyLog 1090").setContentText("1090.000 MHz • ADS-B reception")
            .setSmallIcon(R.drawable.ic_radio).setOngoing(true)
            .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, SkyLogActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(R.drawable.ic_stop, "Stop", PendingIntent.getService(this, 0, Intent(this, SkyLogService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(1090, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) else startForeground(1090, notification)
        if (intent?.action == STOP) { scope.launch { mutex.withLock { disconnect(); state.value = "STOPPED" }; stopForeground(STOP_FOREGROUND_REMOVE);stopSelf() } }
        if (intent?.action == APPLY) scope.launch { mutex.withLock { source?.let { s->try {withContext(Dispatchers.IO){applySettings(s)};logger.stop();position().let { p -> logger.start(p?.first,p?.second) }} catch(e:CancellationException){throw e} catch(e:Exception){state.value="Settings error: ${e.message}"} } } }
        if(intent?.action!=STOP&&intent?.action!=APPLY&&source==null)scope.launch{usb.autoConnect()}
        return START_NOT_STICKY
    }
    fun position(): Pair<Double, Double>? {
        val a = prefs.getString("receiverLat", null)?.toDoubleOrNull(); val b = prefs.getString("receiverLon", null)?.toDoubleOrNull()
        return if (ReceptionAggregator.validPosition(a,b)) Pair(a!!, b!!) else null
    }
    private fun applySettings(s: RtlSdrDeviceSource) {
        val agc = prefs.getBoolean("tunerAgc", false)
        s.setDirectSampling(0); s.setGainMode(if (agc) 0 else 1)
        s.setTunerAgcEnabled(agc); s.setHardwareAgcEnabled(prefs.getBoolean("hardwareAgc", false))
        if (!agc) s.setGain(prefs.getInt("gain", 26).coerceIn(0, (s.getGainCount()-1).coerceAtLeast(0)))
        s.setPpmCorrection(prefs.getInt("ppm", 0)); s.setBiasTee(false)
        check(s.setSampleRate(RATE)) { "Cannot set decoder sample rate (2 MS/s)" }
        check(s.setCenterFrequency(AdsbDecoder.ADSB_FREQ_HZ)) { "Cannot tune to 1090 MHz" }
    }
    private suspend fun connect(device: UsbDevice) = mutex.withLock {
        if (source?.usbDevice?.deviceId == device.deviceId) return@withLock
        disconnect(); state.value = "CONNECTING • 1090.000 MHz • ADS-B"
        val s = RtlSdrDeviceSource(getSystemService(USB_SERVICE) as UsbManager, device)
        try {
            withContext(Dispatchers.IO) { check(s.open()) { "Cannot open RTL-SDR" }; applySettings(s) }
            source = s
            val decoder = AdsbDecoder()
            position().let { p -> logger.start(p?.first, p?.second) }
            reader = scope.launch {
                coroutineScope {
                    launch(start = CoroutineStart.UNDISPATCHED) { decoder.frames.collect { f ->
                        val day=java.time.Instant.ofEpochMilli(f.timestamp).atOffset(java.time.ZoneOffset.UTC).toLocalDate()
                        if(day!=utcDay){utcDay=day;logger.stop();position().let{p->logger.start(p?.first,p?.second)}}
                        logger.accept(f)
                        synchronized(aircraft) {
                            if(ReceptionAggregator.validPosition(f.latitude,f.longitude)) {
                                val points=trails.getOrPut(f.icao24){mutableListOf()};val last=points.lastOrNull()
                                if(last==null||f.timestamp-last.timestamp>=10_000) { points.add(TrackPoint(f.icao24,0,f.timestamp,f.latitude!!,f.longitude!!,f.altitude));if(points.size>128){val kept=points.filterIndexed{i,_->i==0||i%2==0||i==points.lastIndex};points.clear();points.addAll(kept)} }
                            }
                            val old = aircraft[f.icao24]
                            aircraft[f.icao24] = if (old == null) f else f.copy(callsign=f.callsign?:old.callsign, altitude=f.altitude?:old.altitude,
                                latitude=f.latitude?:old.latitude, longitude=f.longitude?:old.longitude, velocity=f.velocity?:old.velocity,
                                heading=f.heading?:old.heading, verticalRate=f.verticalRate?:old.verticalRate)
                        }
                    } }
                    val iq = SkyLogIq()
                    s.iqFlow.collect { bytes -> decoder.feed(iq.magnitude(bytes)) }
                }
            }
            withContext(Dispatchers.IO) { s.startStreaming() }
            wake.acquire()
            state.value = "${if(s.device.isV4) "V4L" else "RTL-SDR"} CONNECTED • 1090.000 MHz • ADS-B • LOGGING"
            health = scope.launch {
                var restarts = 0; var lastFailure = 0L
                s.statusFlow.collect { status -> if (status.streamRestartRequired) {
                    val now = System.currentTimeMillis(); if (now-lastFailure > 10_000) restarts=0
                    lastFailure=now; restarts++
                    if (restarts <= 5 && s.device.restartStreaming()) state.value = "RTL-SDR CONNECTED • 1090.000 MHz • ADS-B • LOGGING"
                    else { state.value = "IQ stream failed • reconnect receiver"; logger.stop();reader?.cancel();if(wake.isHeld)wake.release() }
                } }
            }
        } catch (e: CancellationException) { s.close(); throw e
        } catch (e: Exception) { s.close(); source=null; logger.stop(); state.value="Receiver error: ${e.message}" }
    }
    private suspend fun disconnect() {
        if(wake.isHeld)wake.release()
        reader?.cancelAndJoin(); reader=null; health?.cancel(); health=null
        withContext(Dispatchers.IO) { source?.close(); source=null }
        logger.stop()
        synchronized(aircraft) { aircraft.clear(); trails.clear();routes.value=emptyMap();live.value=emptyList() }
    }
    override fun onDestroy() {
        usb.stopListening();if(wake.isHeld)wake.release();reader?.cancel();health?.cancel();val closing=source;source=null;logger.stop();scope.cancel();(application as RtlSdrApplication).cleanupScope.launch{closing?.close()};super.onDestroy()
    }
}

/** Exact uint8 normalization/DC/magnitude stages extracted from upstream DspEngine. */
internal class SkyLogIq {
    private var floats = FloatArray(0)
    private var mag = FloatArray(0)
    private val dc = FloatArray(4)
    private val tail=FloatArray(240)
    private var tailSize=0
    private var output=FloatArray(0)
    fun magnitude(bytes: ByteArray): FloatArray {
        if(floats.size!=bytes.size) floats=FloatArray(bytes.size)
        if(mag.size!=bytes.size/2) mag=FloatArray(bytes.size/2)
        NativeDsp.convertUint8ToFloatInto(bytes, floats, bytes.size)
        NativeDsp.removeDc(floats, dc, 0.9999f)
        for(i in mag.indices) { val a=floats[2*i]; val b=floats[2*i+1]; mag[i]=kotlin.math.sqrt(a*a+b*b) }
        // Decoder deliberately skips starts in the last 240 samples. Carry them forward
        // to decode USB-boundary frames without changing decoder logic or double counting.
        val length=tailSize+mag.size
        if(output.size!=length)output=FloatArray(length)
        tail.copyInto(output,0,0,tailSize);mag.copyInto(output,tailSize)
        tailSize=minOf(240,length);output.copyInto(tail,0,length-tailSize,length)
        return output // synchronously consumed, never queued or shared with UI
    }
}
