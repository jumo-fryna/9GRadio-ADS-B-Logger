package com.radiosport.ninegradio.skylog

import android.content.*
import android.os.*
import android.graphics.*
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.adsblog.*
import com.radiosport.ninegradio.dsp.AdsbDecoder
import com.radiosport.ninegradio.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import java.time.LocalDate
import java.time.ZoneOffset

class SkyLogActivity : AppCompatActivity() {
    private var service: SkyLogService?=null
    private var bound=false
    private lateinit var status: TextView
    private lateinit var metrics: TextView
    private lateinit var content: LinearLayout
    private var radar=false
    private val app get()=application as RtlSdrApplication
    private val connection=object:ServiceConnection {
        override fun onServiceConnected(name:ComponentName,binder:IBinder) {
            service=(binder as SkyLogService.LocalBinder).service
            lifecycleScope.launch { service!!.state.collect { status.text=it } }
        }
        override fun onServiceDisconnected(name:ComponentName) { service=null;status.text="Receiver service disconnected" }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState);supportActionBar?.hide()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setBackgroundColor(Color.rgb(5,18,28)) }
        setContentView(root)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v,i -> val s=i.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars());v.setPadding(s.left,s.top,s.right,s.bottom);i }
        root.addView(logText(this,"SKYLOG 1090  /  LIVE",23f))
        status=logText(this,"Detecting RTL-SDR • 1090.000 MHz • ADS-B",12f);root.addView(status)
        metrics=logText(this,"Aircraft now 0 / Aircraft today 0\nFrames 0 / Max range —",15f);root.addView(metrics)
        val nav=LinearLayout(this)
        listOf("LIVE","RADAR","HISTORIA","RAPORT","USTAW.").forEachIndexed { i,label ->
            nav.addView(Button(this).apply { text=label;textSize=10f;setPadding(0,8,0,8);setOnClickListener {
                when(i) { 0 -> {radar=false;render()};1 -> {radar=true;render()};2 -> startActivity(Intent(this@SkyLogActivity,AdsbLogActivity::class.java));3 -> startActivity(Intent(this@SkyLogActivity,AdsbLogActivity::class.java).putExtra("mobileReport",true));4 -> startActivity(Intent(this@SkyLogActivity,SkyLogSettingsActivity::class.java)) }
            } },LinearLayout.LayoutParams(0,-2,1f))
        }
        root.addView(nav)
        content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL };root.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        androidx.core.content.ContextCompat.startForegroundService(this,Intent(this,SkyLogService::class.java))
        bound=bindService(Intent(this,SkyLogService::class.java),connection,BIND_AUTO_CREATE)
        lifecycleScope.launch {
            while(isActive) {
                render()
                val from=LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                val cards=withContext(Dispatchers.IO) { app.database.adsbLogDao().filteredOnce(LogFilter(from=from)) }
                val stats=ReceptionStats.from(cards.map{it.reception},emptyList())
                metrics.text="Aircraft now ${service?.live?.value?.size?:0}  /  Aircraft today ${stats.aircraft}\nFrames ${stats.frames}  /  Max range ${ReportFormat.distance(stats.farthestNm)}"
                app.adsbLogger.error.value?.let { status.text="LOGGING ERROR • $it" }
                delay(2000)
            }
        }
    }
    private fun render() {
        if(!::content.isInitialized)return
        val frames=service?.live?.value.orEmpty().sortedBy { it.callsign?:it.icao24 }
        content.removeAllViews()
        if(radar) {
            content.addView(SkyRadar(this).apply { update(frames,service?.position(),service?.routes?.value.orEmpty()) },LinearLayout.LayoutParams(-1,0,1f))
            content.addView(logText(this,"Offline radar • NM • tracks when positions are available",12f));return
        }
        val scroll=ScrollView(this);val list=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};scroll.addView(list);content.addView(scroll)
        if(frames.isEmpty())list.addView(logText(this,"Waiting for aircraft on 1090 MHz…\nConnect RTL-SDR and accept USB access. Reception continues while viewing reports.",16f))
        lifecycleScope.launch {
            val identities=withContext(Dispatchers.IO) {frames.associate {it.icao24 to app.database.adsbLogDao().identity(it.icao24)}}
            if(radar||!isActive)return@launch
            frames.forEach { f ->
                val id=identities[f.icao24]; val p=service?.position()
                val distance=if(p!=null&&ReceptionAggregator.validPosition(f.latitude,f.longitude))ReceptionAggregator.distanceNm(p.first,p.second,f.latitude!!,f.longitude!!)else null
                val card=logText(this,"${f.callsign?:f.icao24}   ${id?.registration?:"—"}\n${f.icao24} • ${id?.aircraftType?:id?.model?:"Unknown type"}\n▰ ${id?.operator?:"Unidentified operator"}\n${f.altitude?:"—"} ft • ${f.velocity?:"—"} kt • ${f.heading?:"—"}°\n${ReportFormat.distance(distance)} • age ${(System.currentTimeMillis()-f.timestamp)/1000}s",15f)
                card.setBackgroundColor(Color.rgb(12,36,49));list.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(12,5,12,5)})
                card.setOnClickListener { startActivity(Intent(this@SkyLogActivity,AdsbDetailActivity::class.java).putExtra("icao24",f.icao24)) }
            }
        }
    }
    override fun onDestroy(){if(bound)unbindService(connection);super.onDestroy()}
}

/** Offline geographical radar. Re-centres on a known aircraft if receiver coordinates are absent. */
class SkyRadar(context:android.content.Context):View(context) {
    private var frames=emptyList<AdsbDecoder.AdsbFrame>();private var origin:Pair<Double,Double>?=null
    private var paths:Map<String,List<TrackPoint>> = emptyMap()
    fun update(f:List<AdsbDecoder.AdsbFrame>,p:Pair<Double,Double>?,routes:Map<String,List<TrackPoint>> = emptyMap()) {frames=f;origin=p?:f.firstOrNull{it.latitude!=null&&it.longitude!=null}?.let{Pair(it.latitude!!,it.longitude!!)};paths=routes;invalidate()}
    override fun onDraw(c:Canvas) {
        c.drawColor(Color.rgb(5,18,28));val cx=width/2f;val cy=height/2f;val radius=minOf(cx,cy)*0.85f
        val pen=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(30,89,102);style=Paint.Style.STROKE;strokeWidth=1f}
        (1..4).forEach{c.drawCircle(cx,cy,radius*it/4,pen)};c.drawLine(cx-radius,cy,cx+radius,cy,pen);c.drawLine(cx,cy-radius,cx,cy+radius,pen)
        pen.style=Paint.Style.FILL;pen.textSize=24f;pen.color=Color.CYAN;c.drawText("N • 250 NM",cx-70,cy-radius-10,pen)
        val p=origin?:return
        fun xy(a:Double,b:Double):Pair<Float,Float> {val x=((b-p.second)*kotlin.math.cos(Math.toRadians(p.first))*60/250*radius).toFloat();val y=((a-p.first)*60/250*radius).toFloat();return Pair(cx+x,cy-y)}
        paths.values.forEach { points ->pen.color=Color.rgb(20,100,100);pen.style=Paint.Style.STROKE;val path=Path();points.forEachIndexed {i,v->val (x,y)=xy(v.latitude,v.longitude);if(i==0)path.moveTo(x,y)else path.lineTo(x,y)};c.drawPath(path,pen)}
        frames.forEach{f->if(f.latitude!=null&&f.longitude!=null){val(x,y)=xy(f.latitude,f.longitude);pen.style=Paint.Style.FILL;pen.color=Color.rgb(56,237,176);c.drawCircle(x,y,5f,pen);pen.textSize=22f;c.drawText(f.callsign?:f.icao24,x+8,y-6,pen)}}
    }
}
