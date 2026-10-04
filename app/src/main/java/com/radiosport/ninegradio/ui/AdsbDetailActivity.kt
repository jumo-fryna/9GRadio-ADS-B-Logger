package com.radiosport.ninegradio.ui

import android.graphics.Canvas
import android.graphics.RectF
import android.os.Bundle
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.adsblog.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.MutableStateFlow

class AdsbDetailActivity : AppCompatActivity() {
    private val shown = MutableStateFlow(50)
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("shown", shown.value)
        super.onSaveInstanceState(outState)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val icao = intent.getStringExtra("icao24") ?: return finish()
        val root = logRoot(this, "AIRCRAFT / $icao")
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(content) }, LinearLayout.LayoutParams(-1, 0, 1f))
        val dao = (application as RtlSdrApplication).database.adsbLogDao()
        shown.value = savedInstanceState?.getInt("shown", 50) ?: 50
        val selectedId = intent.getStringExtra("receptionId")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(dao.filtered(LogFilter(icao24 = icao)), shown) { cards, limit -> cards to limit }
                    .collectLatest { (cards, limit) ->
                    val ordered = cards.sortedByDescending { it.reception.id == selectedId }
                    val visible = ordered.take(limit)
                    val tracks = withContext(Dispatchers.IO) { visible.associate { it.reception.id to dao.points(it.reception.id) } }
                    content.removeAllViews()
                    val latest = cards.firstOrNull()
                    content.addView(logText(this@AdsbDetailActivity, latest?.registration ?: latest?.reception?.callsign ?: icao, 28f))
                    content.addView(logText(this@AdsbDetailActivity,
                        "${latest?.operator ?: "Unknown operator"}\n${listOfNotNull(latest?.manufacturer, latest?.model, latest?.aircraftType).joinToString(" • ").ifBlank { "Unknown aircraft type" }}\nICAO24 $icao", 17f))
                    val frames = cards.sumOf { it.reception.frameCount }
                    content.addView(logText(this@AdsbDetailActivity, "${cards.size} receptions • $frames decoded frames", 15f))
                    visible.forEach { c ->
                        val r = c.reception
                        content.addView(logText(this@AdsbDetailActivity,
                            "${r.callsign ?: "Unknown callsign"} / ${ReportFormat.time(r.firstSeen)}\n" +
                            "Last seen ${ReportFormat.time(r.lastSeen)}\n${r.frameCount} frames • maximum ${ReportFormat.distance(r.maxDistanceNm)}\n" +
                            "Altitude ${r.minAltitude ?: "?"} – ${r.maxAltitude ?: "?"} ft • max speed ${r.maxSpeed ?: "?"} kt\n" +
                            "Heading ${r.heading ?: "?"}° • vertical rate ${r.verticalRate ?: "?"} ft/min\n" +
                            "Last position ${r.latitude ?: "?"}, ${r.longitude ?: "?"}\n" +
                            "Ground state: ${r.onGround?.let { if (it) "Ground" else "Airborne" } ?: "Unknown"}", 15f))
                        content.addView(object : View(this@AdsbDetailActivity) {
                            override fun onDraw(canvas: Canvas) {
                                val scale = width / 560f
                                canvas.save(); canvas.scale(scale, scale)
                                TrackRenderer.draw(canvas, RectF(12f, 8f, 548f, 210f), tracks[r.id].orEmpty())
                                canvas.restore()
                            }
                        }, LinearLayout.LayoutParams(-1, (220 * resources.displayMetrics.density).toInt()))
                    }
                    if (cards.size > limit) content.addView(android.widget.Button(this@AdsbDetailActivity).apply {
                        text = "Show 50 more receptions (${cards.size - limit} remaining)"
                        setOnClickListener { shown.value += 50 }
                    })
                }
            }
        }
    }
}
