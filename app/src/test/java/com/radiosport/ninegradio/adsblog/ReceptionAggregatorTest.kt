package com.radiosport.ninegradio.adsblog

import com.radiosport.ninegradio.dsp.AdsbDecoder
import org.junit.Assert.*
import org.junit.Test

class ReceptionAggregatorTest {
    private fun frame(time: Long = 1000, icao: String = "ABC123", callsign: String? = null,
                      alt: Int? = null, lat: Double? = null, lon: Double? = null,
                      speed: Int? = null, heading: Double? = null, vr: Int? = null) =
        AdsbDecoder.AdsbFrame(icao, 17, if (lat != null) 11 else 19, alt, callsign, lat, lon, speed, heading, vr, false, time)
    @Test fun mergesPartialFramesWithoutLosingKnownFields() {
        val a = ReceptionAggregator(); a.start(0, null, null)
        a.accept(frame(callsign = "LOT123", alt = 20000))
        a.accept(frame(2000, lat = 52.0, lon = 21.0))
        a.accept(frame(3000, alt = 18000, speed = 410, heading = 90.0, vr = -640))
        a.accept(frame(4000, speed = 390))
        val r = a.snapshot().receptions.single()
        assertEquals("LOT123", r.callsign); assertEquals(4L, r.frameCount)
        assertEquals(18000, r.minAltitude); assertEquals(20000, r.maxAltitude); assertEquals(410, r.maxSpeed)
        assertEquals(52.0, r.latitude!!, 0.0001); assertEquals(-640, r.verticalRate)
        assertEquals(1000L, r.firstSeen); assertEquals(4000L, r.lastSeen)
        assertNull(r.maxDistanceNm)
    }
    @Test fun gapCreatesNewReceptionAndStartIsIdempotent() {
        val a = ReceptionAggregator(gapMs = 10000); val session = a.start(0, null, null)
        assertEquals(session, a.start(1000, null, null))
        a.accept(frame()); a.accept(frame(11001))
        assertEquals(2, a.snapshot().receptions.size)
        assertEquals(1, a.snapshot().sessions.size)
    }
    @Test fun listeningRestartCreatesNewSession() {
        val a = ReceptionAggregator(); val first = a.start(0, null, null)
        a.accept(frame()); a.stop(2000); val second = a.start(3000, null, null); a.accept(frame(4000))
        assertNotEquals(first, second); assertEquals(2, a.snapshot().sessions.size)
        assertEquals(2, a.snapshot().receptions.size)
        assertEquals(2000L, a.snapshot().sessions.first().endedAt)
    }
    @Test fun pointsStayBoundedAndRetainFlightEndpoints() {
        val a = ReceptionAggregator(pointLimit = 32); a.start(0, 0.0, 0.0)
        repeat(1000) { a.accept(frame(it * 10000L, lat = it * 0.001, lon = 0.0)) }
        val points = a.snapshot().points
        assertTrue(points.size <= 32); assertEquals(0L, points.first().timestamp)
        assertEquals(9990000L, points.last().timestamp)
        assertEquals(1000L, a.snapshot().receptions.single().frameCount)
    }
    @Test fun checkpointIncludesLatestUnsampledPositionAsEndpoint() {
        val a = ReceptionAggregator(); a.start(0, null, null)
        a.accept(frame(0, lat = 52.0, lon = 21.0))
        a.accept(frame(1000, lat = 52.01, lon = 21.01))
        val points = a.snapshot().points
        assertEquals(2, points.size); assertEquals(1000L, points.last().timestamp)
    }
    @Test fun framesArrivingDuringCheckpointRemainDirty() {
        val a = ReceptionAggregator(); a.start(0, null, null); a.accept(frame())
        val batch = a.snapshot(); a.accept(frame(2000)); a.committed(batch)
        assertEquals(2L, a.snapshot().receptions.single().frameCount)
        a.committed(a.snapshot()); assertTrue(a.snapshot().receptions.isEmpty())
    }
    @Test fun staleFramesCannotMoveLastPositionOrCallsignBackwards() {
        val a = ReceptionAggregator(); a.start(0, null, null)
        a.accept(frame(2000, callsign = "NEW", lat = 52.0, lon = 21.0))
        a.accept(frame(1000, callsign = "OLD", lat = 51.0, lon = 20.0))
        val r = a.snapshot().receptions.single()
        assertEquals("NEW", r.callsign); assertEquals(52.0, r.latitude!!, 0.001)
        assertEquals(1000L, r.firstSeen); assertEquals(2000L, r.lastSeen); assertEquals(2L, r.frameCount)
    }
    @Test fun invalidPositionsDoNotContaminateDistanceAndZeroCoordinatesAreValid() {
        val a = ReceptionAggregator(); a.start(0, 0.0, 0.0)
        a.accept(frame(lat = 0.0, lon = 0.0)); a.accept(frame(2000, lat = Double.NaN, lon = 0.0))
        a.accept(frame(3000, lat = 1.0, lon = 0.0))
        assertEquals(60.04, a.snapshot().receptions.single().maxDistanceNm!!, 0.05)
    }
}
