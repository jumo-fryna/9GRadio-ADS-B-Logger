package com.radiosport.ninegradio.adsblog

import java.io.Writer
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class ReceptionStats(
    val aircraft: Int, val frames: Long, val farthestNm: Double?, val highestFeet: Int?,
    val mostReceived: String?, val listeningMs: Long
) {
    companion object {
        fun from(rows: List<AircraftReception>, sessions: List<ReceptionSession>): ReceptionStats {
            val counts = rows.groupBy { it.icao24 }.mapValues { (_, values) -> values.sumOf { it.frameCount } }
            return ReceptionStats(counts.size, rows.sumOf { it.frameCount }, rows.mapNotNull { it.maxDistanceNm }.maxOrNull(),
                rows.mapNotNull { it.maxAltitude }.maxOrNull(), counts.entries.sortedWith(
                    compareByDescending<Map.Entry<String, Long>> { it.value }.thenBy { it.key }).firstOrNull()?.key,
                sessions.distinctBy { it.id }.sumOf { ((it.endedAt ?: it.lastActiveAt) - it.startedAt).coerceAtLeast(0) })
        }
    }
}

data class ReceptionReport(
    val generatedAt: Long, val sessions: List<ReceptionSession>, val cards: List<ReceptionCard>,
    val tracks: Map<String, List<TrackPoint>>
) {
    val stats get() = ReceptionStats.from(cards.map { it.reception }, sessions)
}

object ReportFormat {
    private val utc = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss 'UTC'").withZone(ZoneOffset.UTC)
    fun time(value: Long) = utc.format(Instant.ofEpochMilli(value))
    fun duration(ms: Long): String {
        val seconds = ms.coerceAtLeast(0) / 1000
        return "%02d:%02d:%02d".format(java.util.Locale.ROOT, seconds / 3600, seconds / 60 % 60, seconds % 60)
    }
    fun distance(nm: Double?) = nm?.let { "%.1f NM".format(java.util.Locale.ROOT, it) } ?: "Unknown"
}

object ReceptionCsv {
    val headers = listOf("session_id", "reception_id", "icao24", "callsign", "registration", "manufacturer",
        "model", "operator", "aircraft_type", "first_seen_utc", "last_seen_utc", "frames", "min_altitude_ft",
        "max_altitude_ft", "max_speed_kt", "heading_deg", "vertical_rate_ft_min", "last_latitude",
        "last_longitude", "max_distance_nm", "on_ground", "identity_source")
    // RFC 4180 plus spreadsheet-formula protection for imported identity text.
    fun cell(value: Any?): String {
        var text = value?.toString().orEmpty()
        if (value is String && text.trimStart().firstOrNull() in listOf('=', '+', '-', '@', '\t', '\r', '\n')) text = "'" + text
        return "\"" + text.replace("\"", "\"\"") + "\""
    }
    fun write(report: ReceptionReport, writer: Writer) {
        writer.write(headers.joinToString(",", transform = ::cell) + "\r\n")
        report.cards.forEach { c ->
            val r = c.reception
            val values = listOf(r.sessionId, r.id, r.icao24, r.callsign, c.registration, c.manufacturer,
                c.model, c.operator, c.aircraftType, ReportFormat.time(r.firstSeen), ReportFormat.time(r.lastSeen),
                r.frameCount, r.minAltitude, r.maxAltitude, r.maxSpeed, r.heading, r.verticalRate,
                r.latitude, r.longitude, r.maxDistanceNm, r.onGround, c.identitySource)
            writer.write(values.joinToString(",", transform = ::cell) + "\r\n")
        }
        writer.flush()
    }
}
