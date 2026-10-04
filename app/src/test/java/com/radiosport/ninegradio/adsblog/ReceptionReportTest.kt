package com.radiosport.ninegradio.adsblog

import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter

class ReceptionReportTest {
    private fun row(id: String, icao: String, frames: Long) = AircraftReception(id, "session", icao,
        callsign = "LOT,\"123\"", firstSeen = 0, lastSeen = 1000, frameCount = frames,
        minAltitude = -100, maxAltitude = 33000, maxDistanceNm = 123.5)
    @Test fun statisticsAggregateUniqueAircraftAcrossVisits() {
        val rows = listOf(row("a", "ABC123", 10), row("b", "ABC123", 20), row("c", "DEF456", 25))
        val s = ReceptionSession("session", 0, 10000, 12000)
        val stats = ReceptionStats.from(rows, listOf(s, s))
        assertEquals(2, stats.aircraft); assertEquals(55L, stats.frames)
        assertEquals("ABC123", stats.mostReceived); assertEquals(12000L, stats.listeningMs)
        assertEquals(33000, stats.highestFeet); assertEquals(123.5, stats.farthestNm!!, 0.001)
    }
    @Test fun csvQuotesTextAndProtectsFormulasWhilePreservingNumericNegatives() {
        assertEquals("\"LOT,\"\"123\"\"\"", ReceptionCsv.cell("LOT,\"123\""))
        assertEquals("\"'=HYPERLINK(1)\"", ReceptionCsv.cell("=HYPERLINK(1)"))
        assertEquals("\"-100\"", ReceptionCsv.cell(-100))
        assertEquals("\"\"", ReceptionCsv.cell(null))
        assertEquals("\"a\nb\"", ReceptionCsv.cell("a\nb"))
    }
    @Test fun exportsAllRequiredFieldsAndUsesUtc() {
        val card = ReceptionCard(row("a", "ABC123", 42), "SP-ABC", "Boeing", "737", "=operator", "B738", "Local JSON")
        val report = ReceptionReport(0, listOf(ReceptionSession("session", 0, 1000)), listOf(card), emptyMap())
        val writer = StringWriter(); ReceptionCsv.write(report, writer)
        val csv = writer.toString()
        assertTrue(csv.contains("1970-01-01 00:00:00 UTC")); assertTrue(csv.contains("\"SP-ABC\""))
        assertTrue(csv.contains("\"'=operator\"")); assertTrue(csv.contains("\"42\"")); assertTrue(csv.endsWith("\r\n"))
        assertEquals(22, ReceptionCsv.headers.size)
    }
    @Test fun emptySelectionProducesSensibleStatistics() {
        val stats = ReceptionStats.from(emptyList(), emptyList())
        assertEquals(0, stats.aircraft); assertEquals(0L, stats.frames); assertNull(stats.mostReceived)
        assertEquals("01:01:01", ReportFormat.duration(3661000))
    }
}
