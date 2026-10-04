package com.radiosport.ninegradio.adsblog

import com.radiosport.ninegradio.dsp.AdsbDecoder
import java.util.UUID
import kotlin.math.*

/** One lock, short in-memory work only; neither Room nor network is called by accept(). */
class ReceptionAggregator(private val gapMs: Long = 300_000L, private val pointLimit: Int = 512) {
    init { require(gapMs > 0); require(pointLimit >= 4) }
    data class Batch(val sessions: List<ReceptionSession>, val receptions: List<AircraftReception>,
                     val points: List<TrackPoint>, val revision: Long)
    private var active: ReceptionSession? = null
    private val sessions = linkedMapOf<String, ReceptionSession>()
    private val current = mutableMapOf<String, String>()
    private val rows = linkedMapOf<String, AircraftReception>()
    private val tails = mutableMapOf<String, TrackPoint>()
    private val tracks = mutableMapOf<String, MutableList<TrackPoint>>()
    private val dirty = mutableMapOf<String, Long>()
    private var revision = 0L

    @Synchronized fun start(time: Long, lat: Double?, lon: Double?): String {
        active?.let { return it.id }
        require((lat == null && lon == null) || validPosition(lat, lon))
        val s = ReceptionSession(UUID.randomUUID().toString(), time, time, receiverLat = lat, receiverLon = lon)
        active = s; sessions[s.id] = s; current.clear(); revision++
        return s.id
    }
    @Synchronized fun heartbeat(time: Long) {
        current.entries.removeAll { (_, id) -> rows[id]?.let { time - it.lastSeen > gapMs } ?: true }
        active?.let { active = it.copy(lastActiveAt = maxOf(it.lastActiveAt, time)); sessions[it.id] = active!!; revision++ }
    }
    @Synchronized fun stop(time: Long) {
        active?.let { sessions[it.id] = it.copy(endedAt = maxOf(time, it.lastActiveAt)); revision++ }
        active = null; current.clear()
    }
    @Synchronized fun accept(f: AdsbDecoder.AdsbFrame) {
        val s = active ?: return
        val icao = f.icao24.uppercase(java.util.Locale.ROOT)
        if (!icao.matches(icaoPattern)) return
        val old = current[icao]?.let(rows::get)?.takeIf { f.timestamp - it.lastSeen <= gapMs }
        val id = old?.id ?: UUID.randomUUID().toString()
        val r = old ?: AircraftReception(id, s.id, icao, firstSeen = f.timestamp, lastSeen = f.timestamp)
        // Older frames count, but cannot move the last known position backwards.
        val fresh = f.timestamp >= r.lastSeen
        val position = fresh && validPosition(f.latitude, f.longitude)
        val distance = if (position && validPosition(s.receiverLat, s.receiverLon))
            distanceNm(s.receiverLat!!, s.receiverLon!!, f.latitude!!, f.longitude!!) else null
        val next = r.copy(
            firstSeen = minOf(r.firstSeen, f.timestamp), lastSeen = maxOf(r.lastSeen, f.timestamp),
            frameCount = r.frameCount + 1,
            callsign = if (fresh) f.callsign?.trim()?.takeIf(String::isNotBlank) ?: r.callsign else r.callsign,
            minAltitude = minNullable(r.minAltitude, f.altitude), maxAltitude = maxNullable(r.maxAltitude, f.altitude),
            maxSpeed = maxNullable(r.maxSpeed, f.velocity?.takeIf { it >= 0 }),
            heading = if (fresh) f.heading?.takeIf { it.isFinite() && it in 0.0..360.0 } ?: r.heading else r.heading,
            verticalRate = if (fresh) f.verticalRate ?: r.verticalRate else r.verticalRate,
            latitude = if (position) f.latitude else r.latitude, longitude = if (position) f.longitude else r.longitude,
            maxDistanceNm = listOfNotNull(r.maxDistanceNm, distance).maxOrNull(),
            onGround = if (!fresh) r.onGround else when (f.typeCode) {
                in 5..8 -> true; in 9..18 -> false; else -> r.onGround
            }
        )
        rows[id] = next; current[icao] = id; dirty[id] = ++revision
        active = s.copy(lastActiveAt = maxOf(s.lastActiveAt, f.timestamp))
        sessions[s.id] = active!!
        if (position) {
            tails[id] = TrackPoint(id, 0, f.timestamp, f.latitude!!, f.longitude!!, f.altitude)
            val points = tracks.getOrPut(id) { mutableListOf() }
            val last = points.lastOrNull()
            if (last == null || (f.timestamp - last.timestamp >= 10_000 &&
                    distanceNm(last.latitude, last.longitude, f.latitude!!, f.longitude!!) >= 0.1)) {
                points.add(TrackPoint(id, 0, f.timestamp, f.latitude!!, f.longitude!!, f.altitude))
                if (points.size > pointLimit) {
                    // Retain endpoints and thin the interior over the whole flight, not just its tail.
                    val thinned = points.filterIndexed { i, _ -> i == 0 || i == points.lastIndex || i % 2 == 0 }
                    points.clear(); points.addAll(thinned)
                }
            }
        }
    }
    @Synchronized fun snapshot(): Batch = Batch(sessions.values.toList(), dirty.keys.mapNotNull(rows::get),
        dirty.keys.flatMap { id ->
            val points = tracks[id].orEmpty().toMutableList()
            tails[id]?.let { tail ->
                if (points.lastOrNull()?.timestamp != tail.timestamp) {
                    if (points.size >= pointLimit) points.removeAt(points.lastIndex)
                    points.add(tail)
                }
            }
            points.mapIndexed { i, p -> p.copy(sequence = i) }
        }, revision)

    @Synchronized fun committed(batch: Batch) {
        dirty.entries.removeAll { it.value <= batch.revision }
        // Old visits leave memory only after successful persistence.
        val keep = current.values.toSet() + dirty.keys
        rows.keys.filter { it !in keep }.forEach { rows.remove(it); tracks.remove(it); tails.remove(it) }
        batch.sessions.forEach { s -> if (s.id != active?.id && sessions[s.id] == s) sessions.remove(s.id) }
    }
    companion object {
        private val icaoPattern = Regex("[0-9A-F]{6}")
        fun validPosition(lat: Double?, lon: Double?) = lat != null && lon != null &&
            lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0
        fun distanceNm(a: Double, b: Double, c: Double, d: Double): Double {
            val dl = Math.toRadians(c - a); val dn = Math.toRadians(d - b)
            val h = sin(dl / 2).pow(2) + cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(dn / 2).pow(2)
            return 3440.065 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }
        private fun minNullable(a: Int?, b: Int?) = listOfNotNull(a, b).minOrNull()
        private fun maxNullable(a: Int?, b: Int?) = listOfNotNull(a, b).maxOrNull()
    }
}
