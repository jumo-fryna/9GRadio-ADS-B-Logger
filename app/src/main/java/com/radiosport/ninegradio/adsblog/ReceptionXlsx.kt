package com.radiosport.ninegradio.adsblog

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native OOXML: editable cells, Excel date/number types, formulas and editable charts. No POI on Android. */
object ReceptionXlsx {
    private const val MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
    private const val REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
    private val names = listOf("PODSUMOWANIE", "SAMOLOTY", "TRASY", "SESJE", "RAW DATA")
    private data class Plane(val icao: String, val registration: String?, val callsign: String?, val operator: String?,
        val type: String?, val first: Long, val last: Long, val frames: Long, val minAlt: Int?, val maxAlt: Int?,
        val speed: Int?, val heading: Double?, val vertical: Int?, val distance: Double?, val lat: Double?,
        val lon: Double?, val manufacturer: String?, val model: String?, val visits: Int)

    fun write(report: ReceptionReport, output: OutputStream) {
        val planes = report.cards.groupBy { it.reception.icao24 }.map { (icao, cards) ->
            val sorted = cards.sortedByDescending { it.reception.lastSeen }; val latest = sorted.first()
            val position = sorted.firstOrNull { it.reception.latitude != null && it.reception.longitude != null }?.reception
            Plane(icao, latest.registration, sorted.firstNotNullOfOrNull { it.reception.callsign }, latest.operator,
                latest.aircraftType, cards.minOf { it.reception.firstSeen }, cards.maxOf { it.reception.lastSeen },
                cards.sumOf { it.reception.frameCount }, cards.mapNotNull { it.reception.minAltitude }.minOrNull(),
                cards.mapNotNull { it.reception.maxAltitude }.maxOrNull(), cards.mapNotNull { it.reception.maxSpeed }.maxOrNull(),
                sorted.firstNotNullOfOrNull { it.reception.heading }, sorted.firstNotNullOfOrNull { it.reception.verticalRate },
                cards.mapNotNull { it.reception.maxDistanceNm }.maxOrNull(), position?.latitude, position?.longitude,
                latest.manufacturer, latest.model, cards.size)
        }.sortedWith(compareByDescending<Plane> { it.frames }.thenBy { it.icao })
        ZipOutputStream(output).use { zip ->
            zip.setLevel(1)
            fun entry(path: String, xml: String) {
                zip.putNextEntry(ZipEntry(path).apply { time = report.generatedAt })
                zip.write(("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" + xml).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            entry("[Content_Types].xml", contentTypes())
            entry("_rels/.rels", relationships(listOf(
                Triple("rId1", "$REL/officeDocument", "xl/workbook.xml"),
                Triple("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "docProps/core.xml"))))
            entry("docProps/core.xml", """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>ADS-B Reception Report</dc:title><dc:creator>9GRadio ADS-B Logger</dc:creator></cp:coreProperties>""")
            entry("xl/workbook.xml", """<workbook xmlns="$MAIN" xmlns:r="$REL"><bookViews><workbookView activeTab="0"/></bookViews><sheets>""" +
                names.mapIndexed { i, name -> "<sheet name=\"${xml(name)}\" sheetId=\"${i + 1}\" r:id=\"rId${i + 1}\"/>" }.joinToString("") +
                "</sheets>" + "<calcPr calcId=\"191029\" fullCalcOnLoad=\"1\"/></workbook>")
            entry("xl/_rels/workbook.xml.rels", relationships(names.mapIndexed { i, _ ->
                Triple("rId${i + 1}", "$REL/worksheet", "worksheets/sheet${i + 1}.xml")
            } + Triple("rId6", "$REL/styles", "styles.xml")))
            entry("xl/styles.xml", styles())
            entry("xl/worksheets/sheet1.xml", summary(report, planes))
            entry("xl/worksheets/sheet2.xml", table(
                listOf("ICAO24", "Registration", "Callsign", "Operator", "Aircraft type", "First seen UTC", "Last seen UTC",
                    "Frame count", "Min altitude ft", "Max altitude ft", "Max speed kt", "Heading deg", "Vertical rate ft/min",
                    "Max distance NM", "Last latitude", "Last longitude", "Manufacturer", "Model", "Reception count"),
                planes.map { p -> listOf(p.icao, p.registration, p.callsign, p.operator ?: "Unknown operator", p.type,
                    DateValue(p.first), DateValue(p.last), p.frames, p.minAlt, p.maxAlt, p.speed, p.heading,
                    p.vertical, p.distance, p.lat, p.lon, p.manufacturer, p.model, p.visits) }, badgeColumn = 3))
            val cardById = report.cards.associateBy { it.reception.id }
            entry("xl/worksheets/sheet3.xml", table(
                listOf("Session ID", "Reception ID", "ICAO24", "Sequence", "Timestamp UTC", "Latitude", "Longitude", "Altitude ft"),
                report.tracks.values.flatten().sortedWith(compareBy<TrackPoint> { it.receptionId }.thenBy { it.sequence }).map { p ->
                    val r = cardById[p.receptionId]?.reception
                    listOf(r?.sessionId, p.receptionId, r?.icao24, p.sequence, DateValue(p.timestamp), p.latitude, p.longitude, p.altitude)
                }))
            entry("xl/worksheets/sheet4.xml", table(
                listOf("Session ID", "Started UTC", "Ended / checkpoint UTC", "Listening duration", "Receiver latitude", "Receiver longitude",
                    "Selected unique aircraft", "Selected frames", "State", "Last active UTC"),
                report.sessions.map { s ->
                    val rows = report.cards.filter { it.reception.sessionId == s.id }
                    listOf(s.id, DateValue(s.startedAt), DateValue(s.endedAt ?: s.lastActiveAt),
                        DurationValue(((s.endedAt ?: s.lastActiveAt) - s.startedAt).coerceAtLeast(0)), s.receiverLat, s.receiverLon,
                        rows.map { it.reception.icao24 }.distinct().size, rows.sumOf { it.reception.frameCount },
                        if (s.endedAt == null) "Listening" else "Finished", DateValue(s.lastActiveAt))
                }))
            entry("xl/worksheets/sheet5.xml", table(
                listOf("Reception ID", "Session ID", "ICAO24", "Callsign", "First seen UTC", "Last seen UTC", "Frame count",
                    "Min altitude ft", "Max altitude ft", "Max speed kt", "Heading deg", "Vertical rate ft/min", "Max distance NM",
                    "Last latitude", "Last longitude", "On ground", "Registration", "Manufacturer", "Model", "Operator", "Aircraft type", "Identity source"),
                report.cards.map { c -> val r = c.reception
                    listOf(r.id, r.sessionId, r.icao24, r.callsign, DateValue(r.firstSeen), DateValue(r.lastSeen), r.frameCount,
                        r.minAltitude, r.maxAltitude, r.maxSpeed, r.heading, r.verticalRate, r.maxDistanceNm,
                        r.latitude, r.longitude, r.onGround?.toString(), c.registration, c.manufacturer, c.model,
                        c.operator, c.aircraftType, c.identitySource)
                }))
            entry("xl/worksheets/_rels/sheet1.xml.rels", relationships(listOf(Triple("rId1", "$REL/drawing", "../drawings/drawing1.xml"))))
            entry("xl/drawings/drawing1.xml", drawing())
            entry("xl/drawings/_rels/drawing1.xml.rels", relationships(listOf(
                Triple("rId1", "$REL/chart", "../charts/chart1.xml"), Triple("rId2", "$REL/chart", "../charts/chart2.xml"))))
            entry("xl/charts/chart1.xml", chart("Najczęściej odbierane samoloty / ramki", "H", planes.take(10).map { it.icao to it.frames.toDouble() }, "00A88F"))
            entry("xl/charts/chart2.xml", chart("Maksymalna wysokość / ft", "J", planes.take(10).map { it.icao to it.maxAlt?.toDouble() }, "4F83CC"))
        }
    }
    private data class DateValue(val ms: Long)
    private data class DurationValue(val ms: Long)
    private fun excelDate(ms: Long) = ms / 86_400_000.0 + 25569.0
    private fun column(index: Int): String {
        var n = index + 1; var result = ""
        while (n > 0) { n--; result = ('A'.code + n % 26).toChar() + result; n /= 26 }
        return result
    }
    private fun cell(ref: String, value: Any?, style: Int = 0): String = when (value) {
        null -> "<c r=\"$ref\" s=\"$style\"/>"
        is DateValue -> cell(ref, excelDate(value.ms), 4)
        is DurationValue -> cell(ref, value.ms / 86_400_000.0, 6)
        is Number -> if (value.toDouble().isFinite()) "<c r=\"$ref\" s=\"$style\"><v>$value</v></c>" else cell(ref, null, style)
        else -> "<c r=\"$ref\" s=\"$style\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value.toString())}</t></is></c>"
    }
    private fun formula(ref: String, expression: String, cached: Any, style: Int = 3): String =
        "<c r=\"$ref\" s=\"$style\"${if (cached is String) " t=\"str\"" else ""}><f>${xml(expression)}</f><v>${xml(cached.toString())}</v></c>"

    private fun table(headers: List<String>, rows: List<List<Any?>>, badgeColumn: Int = -1): String {
        require(rows.size < 1_048_576) { "Excel row limit exceeded; narrow the date/session filter" }
        val end = "${column(headers.lastIndex)}${rows.size + 1}"
        return buildString {
            append("<worksheet xmlns=\"$MAIN\"><dimension ref=\"A1:$end\"/><sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"1\" topLeftCell=\"A2\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"A2\" sqref=\"A2\"/></sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"20\"/><cols>")
            headers.forEachIndexed { i, header ->
                val width = when { header.contains("UTC") -> 25; header.contains("ID") -> 38; else -> (header.length + 3).coerceIn(14, 26) }
                append("<col min=\"${i + 1}\" max=\"${i + 1}\" width=\"$width\" customWidth=\"1\"/>")
            }
            append("</cols><sheetData><row r=\"1\" ht=\"30\" customHeight=\"1\">")
            headers.forEachIndexed { i, h -> append(cell("${column(i)}1", h, 1)) }; append("</row>")
            rows.forEachIndexed { index, values ->
                val row = index + 2; append("<row r=\"$row\">")
                values.forEachIndexed { i, value -> append(cell("${column(i)}$row", value,
                    if (i == badgeColumn) 7 else if (value is Double) 5 else 0)) }
                append("</row>")
            }
            append("</sheetData><autoFilter ref=\"A1:$end\"/><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.5\" bottom=\"0.5\" header=\"0.2\" footer=\"0.2\"/></worksheet>")
        }
    }
    private fun summary(report: ReceptionReport, planes: List<Plane>): String {
        val stats = report.stats; val last = maxOf(2, planes.size + 1)
        val first = report.sessions.minOfOrNull { it.startedAt }; val end = report.sessions.maxOfOrNull { it.endedAt ?: it.lastActiveAt }
        val metrics = listOf(
            Triple("UNIKALNE SAMOLOTY", "SUMPRODUCT((SAMOLOTY!A2:A$last<>\"\")/COUNTIF(SAMOLOTY!A2:A$last,SAMOLOTY!A2:A$last&\"\"))", stats.aircraft),
            Triple("ODEBRANE RAMKI", "SUM(SAMOLOTY!H2:H$last)", stats.frames),
            Triple("NAJDALSZY ODBIÓR / NM", "IF(COUNT(SAMOLOTY!N2:N$last)=0,\"Unknown\",MAX(SAMOLOTY!N2:N$last))", stats.farthestNm ?: "Unknown"),
            Triple("NAJWYŻSZY LOT / ft", "IF(COUNT(SAMOLOTY!J2:J$last)=0,\"Unknown\",MAX(SAMOLOTY!J2:J$last))", stats.highestFeet ?: "Unknown"),
            Triple("NAJCZĘŚCIEJ ODBIERANY", "IF(COUNTA(SAMOLOTY!A2:A$last)=0,\"Unknown\",INDEX(SAMOLOTY!A2:A$last,MATCH(MAX(SAMOLOTY!H2:H$last),SAMOLOTY!H2:H$last,0)))", stats.mostReceived ?: "Unknown"),
            Triple("CZAS NASŁUCHU", "SUM(SESJE!D2:D${maxOf(2, report.sessions.size + 1)})", stats.listeningMs / 86_400_000.0)
        )
        return buildString {
            append("<worksheet xmlns=\"$MAIN\" xmlns:r=\"$REL\"><dimension ref=\"A1:L38\"/><sheetViews><sheetView showGridLines=\"0\" workbookViewId=\"0\"/></sheetViews><sheetFormatPr defaultRowHeight=\"22\"/><cols><col min=\"1\" max=\"12\" width=\"12\" customWidth=\"1\"/></cols><sheetData>")
            fun row(number: Int, content: String, height: Int = 22) { append("<row r=\"$number\" ht=\"$height\" customHeight=\"1\">$content</row>") }
            row(1, cell("A1", "ADS-B Reception Report", 2), 38)
            row(3, cell("A3", "1090 MHz / Mode S  •  ${ReportFormat.time(report.generatedAt)}"))
            row(5, cell("A5", "Sesja: ${first?.let(ReportFormat::time) ?: "—"}  →  ${end?.let(ReportFormat::time) ?: "—"}"))
            row(6, cell("A6", "${report.sessions.size} sesji • ${planes.size} samolotów • ${report.cards.size} zagregowanych odbiorów"))
            for (group in 0..1) {
                val labelRow = 8 + group * 4; val valueRow = labelRow + 1
                row(labelRow, (0..2).joinToString("") { j -> cell("${column(j * 4)}$labelRow", metrics[group * 3 + j].first, 1) }, 26)
                row(valueRow, (0..2).joinToString("") { j ->
                    val index = group * 3 + j; val metric = metrics[index]
                    formula("${column(j * 4)}$valueRow", metric.second, metric.third, if (index == 5) 8 else 3)
                }, 38)
            }
            row(16, cell("A16", "Statystyki obejmują wybrane samoloty. Daty: UTC. Dystans wymaga ustawienia pozycji odbiornika."))
            row(18, cell("A18", "RAW DATA zawiera agregaty odbiorów, a nie surowe ramki IQ/Mode S. Badge operatora działa offline."))
            row(38, cell("A38", "Edytuj komórki SAMOLOTY / SESJE — formuły i wykresy podsumowania przeliczają się w Excelu."))
            append("</sheetData><mergeCells count=\"19\">")
            val merges = mutableListOf("A1:L2", "A3:L3", "A5:L5", "A6:L6", "A16:L16", "A18:L18", "A38:L38")
            for (group in 0..1) for (j in 0..2) {
                val r = 8 + group * 4; val a = column(j * 4); val b = column(j * 4 + 3)
                merges += "$a$r:$b$r"; merges += "$a${r + 1}:$b${r + 2}"
            }
            merges.forEach { append("<mergeCell ref=\"$it\"/>") }
            append("</mergeCells><pageMargins left=\"0.3\" right=\"0.3\" top=\"0.4\" bottom=\"0.4\" header=\"0.2\" footer=\"0.2\"/><pageSetup orientation=\"landscape\" paperSize=\"9\" fitToWidth=\"1\" fitToHeight=\"1\"/><drawing r:id=\"rId1\"/></worksheet>")
        }
    }
    private fun relationships(values: List<Triple<String, String, String>>) =
        "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" + values.joinToString("") {
            "<Relationship Id=\"${it.first}\" Type=\"${it.second}\" Target=\"${it.third}\"/>"
        } + "</Relationships>"
    private fun contentTypes() = """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/><Override PartName="/docProps/core.xml" ContentType="application/vnd.openxmlformats-package.core-properties+xml"/>""" +
        (1..5).joinToString("") { "<Override PartName=\"/xl/worksheets/sheet$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" } +
        "<Override PartName=\"/xl/drawings/drawing1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawing+xml\"/>" +
        (1..2).joinToString("") { "<Override PartName=\"/xl/charts/chart$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.drawingml.chart+xml\"/>" } + "</Types>"
    private fun styles() = """<styleSheet xmlns="$MAIN">
      <numFmts count="3"><numFmt numFmtId="164" formatCode="yyyy-mm-dd hh:mm:ss"/><numFmt numFmtId="165" formatCode="0.0000"/><numFmt numFmtId="166" formatCode="[h]:mm:ss"/></numFmts>
      <fonts count="4"><font><sz val="11"/><name val="Calibri"/><color rgb="FF173344"/></font><font><b/><sz val="11"/><name val="Calibri"/><color rgb="FFFFFFFF"/></font><font><b/><sz val="26"/><name val="Calibri"/><color rgb="FFFFFFFF"/></font><font><b/><sz val="24"/><name val="Calibri"/><color rgb="FF006F71"/></font></fonts>
      <fills count="5"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0D2333"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FF00777A"/><bgColor indexed="64"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEAF4F6"/><bgColor indexed="64"/></patternFill></fill></fills>
      <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders><cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
      <cellXfs count="9">
        <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
        <xf numFmtId="0" fontId="1" fillId="3" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center" wrapText="1"/></xf>
        <xf numFmtId="0" fontId="2" fillId="2" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="0" fontId="3" fillId="4" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="165" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="166" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
        <xf numFmtId="0" fontId="0" fillId="4" borderId="0" xfId="0" applyAlignment="1"><alignment vertical="center"/></xf>
        <xf numFmtId="166" fontId="3" fillId="4" borderId="0" xfId="0" applyNumberFormat="1" applyAlignment="1"><alignment vertical="center"/></xf>
      </cellXfs><cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles></styleSheet>"""
    private fun drawing(): String {
        val body = (1..2).joinToString("") { i ->
            val left = if (i == 1) 0 else 6; val right = left + 6
            """<xdr:twoCellAnchor><xdr:from><xdr:col>$left</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>19</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>$right</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>35</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=""><xdr:nvGraphicFramePr><xdr:cNvPr id="$i" name="Reception chart $i"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/></xdr:xfrm><a:graphic><a:graphicData uri="http://schemas.openxmlformats.org/drawingml/2006/chart"><c:chart xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:r="$REL" r:id="rId$i"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>"""
        }
        return """<xdr:wsDr xmlns:xdr="http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">$body</xdr:wsDr>"""
    }
    private fun chart(title: String, dataColumn: String, values: List<Pair<String, Double?>>, color: String): String {
        val end = maxOf(2, values.size + 1)
        val categoryRef = "SAMOLOTY!" + '$' + "A" + '$' + "2:" + '$' + "A" + '$' + end
        val dataRef = "SAMOLOTY!" + '$' + dataColumn + '$' + "2:" + '$' + dataColumn + '$' + end
        val cats = values.mapIndexed { i, v -> "<c:pt idx=\"$i\"><c:v>${xml(v.first)}</c:v></c:pt>" }.joinToString("")
        val nums = values.mapIndexedNotNull { i, v -> v.second?.let { "<c:pt idx=\"$i\"><c:v>$it</c:v></c:pt>" } }.joinToString("")
        return """<c:chartSpace xmlns:c="http://schemas.openxmlformats.org/drawingml/2006/chart" xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"><c:lang val="pl-PL"/><c:chart><c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:r><a:rPr lang="pl-PL" sz="1200" b="1"/><a:t>${xml(title)}</a:t></a:r></a:p></c:rich></c:tx><c:overlay val="0"/></c:title><c:plotArea><c:layout/><c:barChart><c:barDir val="col"/><c:grouping val="clustered"/><c:ser><c:idx val="0"/><c:order val="0"/><c:spPr><a:solidFill><a:srgbClr val="$color"/></a:solidFill></c:spPr><c:cat><c:strRef><c:f>$categoryRef</c:f><c:strCache><c:ptCount val="${values.size}"/>$cats</c:strCache></c:strRef></c:cat><c:val><c:numRef><c:f>$dataRef</c:f><c:numCache><c:formatCode>0</c:formatCode><c:ptCount val="${values.size}"/>$nums</c:numCache></c:numRef></c:val></c:ser><c:axId val="100"/><c:axId val="200"/></c:barChart><c:catAx><c:axId val="100"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:axPos val="b"/><c:tickLblPos val="nextTo"/><c:crossAx val="200"/><c:crosses val="autoZero"/><c:auto val="1"/><c:lblAlgn val="ctr"/><c:lblOffset val="100"/></c:catAx><c:valAx><c:axId val="200"/><c:scaling><c:orientation val="minMax"/></c:scaling><c:axPos val="l"/><c:majorGridlines/><c:numFmt formatCode="0" sourceLinked="1"/><c:tickLblPos val="nextTo"/><c:crossAx val="100"/><c:crosses val="autoZero"/><c:crossBetween val="between"/></c:valAx></c:plotArea><c:plotVisOnly val="1"/><c:dispBlanksAs val="gap"/></c:chart></c:chartSpace>"""
    }
    private fun xml(value: String) = value.filter { it >= ' ' || it == '\n' || it == '\r' || it == '\t' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&apos;")
}
