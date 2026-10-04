package com.radiosport.ninegradio.adsblog

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

class ReceptionXlsxTest {
    private fun report(): ReceptionReport {
        fun card(id: String, count: Long, time: Long) = ReceptionCard(
            AircraftReception(id, "s", "ABC123", callsign = "LOT123", firstSeen = time, lastSeen = time + 1000,
                frameCount = count, minAltitude = 10000, maxAltitude = 33000, maxSpeed = 400,
                latitude = 52.0, longitude = 21.0, maxDistanceNm = 123.4),
            "=HYPERLINK(\"http://example.invalid\")", "Boeing & Co", "737", "LOT <badge>", "B738", "licensed local")
        return ReceptionReport(1700000000000, listOf(ReceptionSession("s", 1700000000000, 1700000010000)),
            listOf(card("r1", 10, 1700000000000), card("r2", 20, 1700000005000)),
            mapOf("r1" to listOf(TrackPoint("r1", 0, 1700000000000, 52.0, 21.0))))
    }
    private fun entries(report: ReceptionReport): Map<String, ByteArray> {
        val bytes = ByteArrayOutputStream(); ReceptionXlsx.write(report, bytes)
        return ZipInputStream(bytes.toByteArray().inputStream()).use { zip ->
            val values = linkedMapOf<String, ByteArray>()
            while (true) { val entry = zip.nextEntry ?: break; values[entry.name] = zip.readBytes() }
            values
        }
    }
    private fun parse(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(bytes.inputStream())
    private fun cell(document: Document, reference: String): Element {
        val cells = document.getElementsByTagNameNS("*", "c")
        return (0 until cells.length).map { cells.item(it) as Element }.first { it.getAttribute("r") == reference }
    }
    @Test fun everyOoxmlPartIsWellFormedAndAllFiveSheetsExist() {
        val files = entries(report())
        files.forEach { (name, bytes) -> if (name.endsWith(".xml") || name.endsWith(".rels")) parse(bytes) }
        val workbook = parse(files.getValue("xl/workbook.xml"))
        val sheets = workbook.getElementsByTagNameNS("*", "sheet")
        assertEquals(listOf("PODSUMOWANIE", "SAMOLOTY", "TRASY", "SESJE", "RAW DATA"),
            (0 until sheets.length).map { (sheets.item(it) as Element).getAttribute("name") })
        assertTrue(files.containsKey("[Content_Types].xml")); assertTrue(files.containsKey("xl/styles.xml"))
    }
    @Test fun aircraftAreAggregatedDatesAreNumericAndImportedTextCannotBecomeFormula() {
        val files = entries(report()); val sheet = parse(files.getValue("xl/worksheets/sheet2.xml"))
        assertEquals(2, sheet.getElementsByTagNameNS("*", "row").length)
        assertEquals("30", cell(sheet, "H2").textContent)
        assertEquals("inlineStr", cell(sheet, "B2").getAttribute("t"))
        assertTrue(cell(sheet, "B2").textContent.startsWith("=HYPERLINK"))
        assertEquals(0, cell(sheet, "B2").getElementsByTagNameNS("*", "f").length)
        assertEquals("4", cell(sheet, "F2").getAttribute("s"))
        assertTrue(cell(sheet, "F2").textContent.toDouble() > 25569)
        assertEquals("LOT <badge>", cell(sheet, "D2").textContent)
    }
    @Test fun dataSheetsHaveFiltersAndFrozenHeadersAndRawDataRetainsVisits() {
        val files = entries(report())
        for (i in 2..5) {
            val sheet = parse(files.getValue("xl/worksheets/sheet$i.xml"))
            assertEquals(1, sheet.getElementsByTagNameNS("*", "autoFilter").length)
            val pane = sheet.getElementsByTagNameNS("*", "pane").item(0) as Element
            assertEquals("frozen", pane.getAttribute("state")); assertEquals("1", pane.getAttribute("ySplit"))
        }
        assertEquals(3, parse(files.getValue("xl/worksheets/sheet5.xml")).getElementsByTagNameNS("*", "row").length)
    }
    @Test fun dashboardContainsLiveFormulasAndTwoEditableCharts() {
        val files = entries(report())
        val summary = parse(files.getValue("xl/worksheets/sheet1.xml"))
        assertEquals(6, summary.getElementsByTagNameNS("*", "f").length)
        for (i in 1..2) {
            val chart = parse(files.getValue("xl/charts/chart$i.xml"))
            assertEquals(1, chart.getElementsByTagNameNS("*", "barChart").length)
            assertEquals(1, chart.getElementsByTagNameNS("*", "numRef").length)
            val formula = chart.getElementsByTagNameNS("*", "f").item(0).textContent
            assertEquals("SAMOLOTY!\$A\$2:\$A\$2", formula)
        }
    }
    @Test fun emptyReportRemainsAValidEditableWorkbook() {
        val files = entries(ReceptionReport(0, emptyList(), emptyList(), emptyMap()))
        files.forEach { (name, bytes) -> if (name.endsWith(".xml") || name.endsWith(".rels")) parse(bytes) }
        assertEquals(1, parse(files.getValue("xl/worksheets/sheet2.xml")).getElementsByTagNameNS("*", "row").length)
    }
}
