package com.radiosport.ninegradio.adsblog

import android.graphics.*
import android.graphics.pdf.PdfDocument
import java.io.OutputStream

/** Print-friendly aviation dossier: a cover and a full aircraft card on each following page. */
object ReceptionPdf {
    private val navy = Color.rgb(13, 35, 51)
    private val teal = Color.rgb(0, 112, 116)
    fun write(report: ReceptionReport, output: OutputStream) {
        val document = PdfDocument()
        try {
            var pageNumber = 0
            fun page(block: (Canvas) -> Unit) {
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, ++pageNumber).create())
                val c = page.canvas; c.drawColor(Color.WHITE)
                block(c)
                line(c, 36f, 801f, 559f, 801f)
                text(c, "9GRadio ADS-B Logger • Offline reception dossier", 36f, 817f, 8f, Color.DKGRAY)
                text(c, "$pageNumber / ${report.cards.size + 1}", 515f, 817f, 8f, Color.DKGRAY)
                document.finishPage(page)
            }
            page { c ->
                val p = Paint().apply { color = navy }; c.drawRect(0f, 0f, 595f, 218f, p)
                text(c, "1090 MHz  /  MODE S  /  RECEPTION LOG", 36f, 56f, 10f, Color.rgb(128, 211, 204))
                text(c, "ADS-B Reception Report", 36f, 112f, 29f, Color.WHITE, true)
                text(c, ReportFormat.time(report.generatedAt), 36f, 150f, 12f, Color.WHITE)
                text(c, "${report.sessions.size} listening session(s) • ${report.cards.size} reception card(s)",
                    36f, 179f, 11f, Color.WHITE)
                val stats = report.stats
                val metrics = listOf("DISTINCT AIRCRAFT" to stats.aircraft.toString(), "DECODED FRAMES" to stats.frames.toString(),
                    "FARTHEST RECEPTION" to ReportFormat.distance(stats.farthestNm),
                    "HIGHEST AIRCRAFT" to (stats.highestFeet?.let { "$it ft" } ?: "Unknown"),
                    "MOST RECEIVED ICAO24" to (stats.mostReceived ?: "—"),
                    "LISTENING TIME" to ReportFormat.duration(stats.listeningMs))
                metrics.forEachIndexed { i, m ->
                    val x = if (i % 2 == 0) 36f else 306f; val y = 272f + (i / 2) * 91
                    text(c, m.first, x, y, 9f, teal, true)
                    text(c, m.second, x, y + 31, 24f, navy, true)
                    line(c, x, y + 48, x + 235, y + 48)
                }
                text(c, "SESSION WINDOW / UTC", 36f, 572f, 11f, teal, true)
                val first = report.sessions.minOfOrNull { it.startedAt }
                val last = report.sessions.maxOfOrNull { it.endedAt ?: it.lastActiveAt }
                text(c, first?.let(ReportFormat::time) ?: "No reception sessions", 36f, 598f, 12f, navy)
                text(c, last?.let { "to ${ReportFormat.time(it)}" } ?: "", 36f, 622f, 12f, navy)
                text(c, "RECEIVER / REPORT NOTES", 36f, 678f, 11f, teal, true)
                text(c, "Statistics refer to the exported aircraft selection. Times use UTC.", 36f, 704f, 10f, Color.DKGRAY)
                text(c, "Unknown metadata stays unknown. Operator badges require no internet.", 36f, 724f, 10f, Color.DKGRAY)
                text(c, "Distances require a configured receiver position. Tracks use no map tiles.", 36f, 744f, 10f, Color.DKGRAY)
            }
            report.cards.forEach { card -> page { c ->
                val r = card.reception
                val p = Paint().apply { color = navy }; c.drawRect(0f, 0f, 595f, 155f, p)
                text(c, "AIRCRAFT / RECEPTION CARD", 36f, 37f, 10f, Color.rgb(128, 211, 204))
                text(c, card.registration ?: r.callsign ?: r.icao24, 36f, 82f, 28f, Color.WHITE, true)
                text(c, "${r.callsign ?: "Unknown callsign"}   •   ICAO24 ${r.icao24}", 36f, 116f, 12f, Color.WHITE)
                text(c, "OPERATOR", 36f, 194f, 9f, teal, true)
                val badge = RectF(36f, 207f, 559f, 254f)
                p.color = Color.rgb(236, 245, 246); c.drawRoundRect(badge, 8f, 8f, p)
                text(c, card.operator ?: "Unidentified operator", 49f, 237f, 17f, navy, true)
                field(c, "MANUFACTURER / MODEL", listOfNotNull(card.manufacturer, card.model).joinToString(" ").ifBlank { "Unknown" }, 36f, 288f)
                field(c, "AIRCRAFT TYPE", card.aircraftType ?: "Unknown", 306f, 288f)
                field(c, "FIRST SEEN", ReportFormat.time(r.firstSeen), 36f, 344f)
                field(c, "LAST SEEN", ReportFormat.time(r.lastSeen), 306f, 344f)
                field(c, "RECEIVED FRAMES", r.frameCount.toString(), 36f, 400f)
                field(c, "MAXIMUM DISTANCE", ReportFormat.distance(r.maxDistanceNm), 306f, 400f)
                field(c, "ALTITUDE RANGE", "${r.minAltitude ?: "?"} – ${r.maxAltitude ?: "?"} ft", 36f, 456f)
                field(c, "MAXIMUM SPEED", r.maxSpeed?.let { "$it kt" } ?: "Unknown", 306f, 456f)
                field(c, "LAST HEADING", r.heading?.let { "%.1f°".format(java.util.Locale.ROOT, it) } ?: "Unknown", 36f, 512f)
                field(c, "VERTICAL RATE", r.verticalRate?.let { "$it ft/min" } ?: "Unknown", 306f, 512f)
                TrackRenderer.draw(c, RectF(36f, 562f, 559f, 739f), report.tracks[r.id].orEmpty())
                text(c, "IDENTITY SOURCE: ${card.identitySource ?: "No local identification"}", 36f, 767f, 8f, Color.DKGRAY)
            } }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }
    private fun field(c: Canvas, label: String, value: String, x: Float, y: Float) {
        text(c, label, x, y, 9f, teal, true); text(c, value, x, y + 22, 12f, navy)
    }
    private fun line(c: Canvas, x: Float, y: Float, right: Float, bottom: Float) {
        c.drawLine(x, y, right, bottom, Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f })
    }
    private fun text(c: Canvas, value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size
            typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        }
        val maxWidth = if (x == 306f) 253f else 559f - x
        val safe = value.replace('\n', ' ').replace('\r', ' ').replace('\t', ' ')
        val count = p.breakText(safe, true, maxWidth, null)
        c.drawText(if (count < safe.length) safe.take((count - 1).coerceAtLeast(0)) + "…" else safe, x, y, p)
    }
}
