package com.radiosport.ninegradio.skylog

import android.graphics.*
import android.view.*
import android.widget.*
import com.radiosport.ninegradio.adsblog.*
import com.radiosport.ninegradio.ui.logText

/** Native offline dashboard, reading the very same immutable report used by Excel/PDF. */
object MobileReportUi {
    fun render(root:LinearLayout,report:ReceptionReport,label:String,details:(ReceptionCard)->Unit) {
        root.removeAllViews();val ctx=root.context
        fun text(value:String,size:Float=15f){root.addView(logText(ctx,value,size))}
        val s=report.stats
        text("✈ ADS-B RECEPTION REPORT",22f);text(label,18f)
        val from=report.sessions.minOfOrNull{it.startedAt};val to=report.sessions.maxOfOrNull{it.endedAt?:it.lastActiveAt}
        text("${from?.let(ReportFormat::time)?:"No session"}\n${to?.let(ReportFormat::time)?:"—"}\nListening ${ReportFormat.duration(s.listeningMs)}",12f)
        val values=listOf("AIRCRAFT" to s.aircraft.toString(),"FRAMES" to s.frames.toString(),"MAX RANGE" to ReportFormat.distance(s.farthestNm),"HIGHEST" to "${s.highestFeet?:"—"} ft","MOST RECEIVED" to (s.mostReceived?:"—"))
        values.chunked(2).forEach { pair->val line=LinearLayout(ctx);pair.forEach { (a,b)->line.addView(logText(ctx,"$a\n$b",17f).apply{background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.rgb(12,43,57));cornerRadius=14f;setStroke(1,Color.rgb(27,90,103))}},LinearLayout.LayoutParams(0,-2,1f).apply{setMargins(6,6,6,6)}) };root.addView(line) }
        val groups=report.cards.groupBy{it.reception.icao24}
        val counts=groups.mapValues{it.value.sumOf{v->v.reception.frameCount}}
        val arrivals=groups.values.groupBy{g->g.minOf{it.reception.firstSeen}/3_600_000L}.toSortedMap()
        text("AIRCRAFT DETECTED IN TIME",17f)
        text("Unique ICAO24 first detected per UTC hour in this selection; aggregated reception data.",11f)
        root.addView(ReportChart(ctx,arrivals.map{it.key*3_600_000.0 to it.value.size.toDouble()},"UTC hour","aircraft"),LinearLayout.LayoutParams(-1,(180*ctx.resources.displayMetrics.density).toInt()))
        text("MOST RECEIVED",17f)
        counts.entries.sortedByDescending{it.value}.take(10).forEachIndexed{i,e->text("${i+1}. ${groups[e.key]!!.first().registration?:groups[e.key]!!.first().reception.callsign?:e.key}  •  ${e.value} frames")}
        text("FARTHEST RECEPTION",17f)
        groups.values.sortedByDescending{g->g.mapNotNull{it.reception.maxDistanceNm}.maxOrNull()?:-1.0}.take(10).forEach{g->text("${g.first().registration?:g.first().reception.icao24}  •  ${ReportFormat.distance(g.mapNotNull{it.reception.maxDistanceNm}.maxOrNull())}")}
        text("OPERATORS / AIRCRAFT TYPES",17f)
        listOf("Operator" to groups.values.groupingBy{it.first().operator?:"Unknown"}.eachCount(),"Type" to groups.values.groupingBy{it.first().aircraftType?:it.first().model?:"Unknown"}.eachCount()).forEach{(title,entries)->text(title,12f);entries.entries.sortedByDescending{it.value}.take(20).forEach{e->text("▰ ${e.key}  •  ${e.value}")}}
        text("SELECT AIRCRAFT • ALTITUDE / RANGE / ROUTE",17f)
        val selector=Spinner(ctx);val keys=counts.entries.sortedByDescending{it.value}.map{it.key}
        selector.adapter=ArrayAdapter(ctx,android.R.layout.simple_spinner_dropdown_item,keys.map{groups[it]!!.first().registration?:it});root.addView(selector)
        val charts=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL};root.addView(charts)
        selector.onItemSelectedListener=object:AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(p:AdapterView<*>?)=Unit
            override fun onItemSelected(p:AdapterView<*>?,v:View?,pos:Int,id:Long) {
                charts.removeAllViews();val g=groups[keys[pos]].orEmpty();val points=g.flatMap{report.tracks[it.reception.id].orEmpty()}.sortedBy{it.timestamp}
                charts.addView(ReportChart(ctx,points.mapNotNull{v->v.altitude?.let{v.timestamp.toDouble() to it.toDouble()}},"UTC time","altitude ft"),LinearLayout.LayoutParams(-1,(180*ctx.resources.displayMetrics.density).toInt()))
                val distances=g.flatMap{card->val session=report.sessions.find{it.id==card.reception.sessionId};if(session?.receiverLat==null||session.receiverLon==null)emptyList() else report.tracks[card.reception.id].orEmpty().map{it.timestamp.toDouble() to ReceptionAggregator.distanceNm(session.receiverLat,session.receiverLon,it.latitude,it.longitude)}}
                charts.addView(ReportChart(ctx,distances,"UTC time","range NM"),LinearLayout.LayoutParams(-1,(180*ctx.resources.displayMetrics.density).toInt()))
                charts.addView(object:View(ctx){override fun onDraw(c:Canvas){TrackRenderer.draw(c,RectF(12f,12f,width-12f,height-12f),points)}},LinearLayout.LayoutParams(-1,(220*ctx.resources.displayMetrics.density).toInt()))
            }
        }
        text("ALL AIRCRAFT • ${groups.size} unique ICAO24",18f)
        val cards=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL};root.addView(cards)
        val ordered=groups.values.sortedByDescending{g->g.maxOf{it.reception.lastSeen}}
        var shown=0
        val more=Button(ctx).apply{text="MORE AIRCRAFT"}
        fun append() {
            ordered.drop(shown).take(50).forEach { g ->
                val latest=g.maxBy{it.reception.lastSeen};val r=latest.reception
                val card=LinearLayout(ctx).apply{orientation=LinearLayout.VERTICAL;background=android.graphics.drawable.GradientDrawable().apply{setColor(Color.rgb(12,36,49));cornerRadius=16f;setStroke(1,Color.rgb(27,90,103))};setPadding(12,12,12,12)}
                card.addView(logText(ctx,"${latest.registration?:r.callsign?:r.icao24}  ›",20f))
                card.addView(OperatorBadge(ctx).apply { bind(latest.operator) })
                card.addView(logText(ctx,"ICAO24 ${r.icao24} • ${r.callsign?:"—"}\n${latest.aircraftType?:latest.model?:"Unknown type"}\nFirst ${ReportFormat.time(g.minOf{it.reception.firstSeen})}\nLast ${ReportFormat.time(g.maxOf{it.reception.lastSeen})}\nAltitude ${g.mapNotNull{it.reception.minAltitude}.minOrNull()?:"—"} – ${g.mapNotNull{it.reception.maxAltitude}.maxOrNull()?:"—"} ft\nSpeed ${g.mapNotNull{it.reception.maxSpeed}.maxOrNull()?:"—"} kt • Heading ${r.heading?:"—"}°\nVertical rate ${r.verticalRate?:"—"} ft/min\nMax range ${ReportFormat.distance(g.mapNotNull{it.reception.maxDistanceNm}.maxOrNull())}\n${g.sumOf{it.reception.frameCount}} frames • ${g.size} reception(s)",13f))
                card.setOnClickListener{details(latest)};cards.addView(card,LinearLayout.LayoutParams(-1,-2).apply{setMargins(10,6,10,6)})
            };shown=minOf(shown+50,ordered.size);more.visibility=if(shown<ordered.size)View.VISIBLE else View.GONE
        }
        more.setOnClickListener{append()};root.addView(more);append()
    }
}

/** Optional locally supplied Drawable slot, no remote-image dependency. */
class OperatorBadge(ctx:android.content.Context):LinearLayout(ctx) {
    private val logo=ImageView(ctx).apply{visibility=View.GONE}
    private val label=logText(ctx,"",13f)
    init {orientation=HORIZONTAL;addView(logo,LayoutParams(48,48));addView(label)}
    fun bind(operator:String?,image:android.graphics.drawable.Drawable?=null){label.text="▰ ${operator?:"Unidentified operator"}";logo.setImageDrawable(image);logo.visibility=if(image==null)View.GONE else View.VISIBLE}
}

/** Responsive Canvas chart, numeric data in local memory. */
class ReportChart(ctx:android.content.Context,private val values:List<Pair<Double,Double>>,private val xLabel:String,private val yLabel:String):View(ctx) {
    override fun onDraw(c:Canvas) {
        val d=resources.displayMetrics.density;val sp=resources.displayMetrics.scaledDensity
        c.drawColor(Color.rgb(8,28,40));val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.rgb(150,202,214);textSize=12f*sp}
        c.drawText("$yLabel / $xLabel",14f*d,30f*d,p)
        if(values.isEmpty()){c.drawText("No recorded data",14f*d,65f*d,p);return}
        val sorted=values.sortedBy{it.first};val minX=sorted.first().first;val spanX=(sorted.last().first-minX).coerceAtLeast(1.0)
        val minY=minOf(0.0,sorted.minOf{it.second});val maxY=sorted.maxOf{it.second}.coerceAtLeast(minY+1)
        val left=62f*d;val top=50f*d;val bottom=height-45f*d;val right=width-16f*d
        p.color=Color.rgb(32,81,95);p.strokeWidth=1f;(0..4).forEach{i->val y=top+(bottom-top)*i/4;c.drawLine(left,y,right,y,p)}
        val path=Path();sorted.forEachIndexed {i,v->val x=(left+(v.first-minX)/spanX*(right-left)).toFloat();val y=(bottom-(v.second-minY)/(maxY-minY)*(bottom-top)).toFloat();if(i==0)path.moveTo(x,y)else path.lineTo(x,y)}
        p.color=Color.rgb(58,231,181);p.style=Paint.Style.STROKE;p.strokeWidth=2f*d;c.drawPath(path,p);p.style=Paint.Style.FILL
        sorted.forEach{v->val x=(left+(v.first-minX)/spanX*(right-left)).toFloat();val y=(bottom-(v.second-minY)/(maxY-minY)*(bottom-top)).toFloat();c.drawCircle(x,y,2f*d,p)}
        p.textSize=10f*sp;c.drawText("%.0f".format(java.util.Locale.ROOT,maxY),4f*d,top+12*d,p);c.drawText("%.0f".format(java.util.Locale.ROOT,minY),4f*d,bottom,p)
        if(sorted.first().first>1e11){c.drawText(java.time.Instant.ofEpochMilli(minX.toLong()).atOffset(java.time.ZoneOffset.UTC).toLocalTime().toString(),left,height-12f*d,p)}
    }
}
