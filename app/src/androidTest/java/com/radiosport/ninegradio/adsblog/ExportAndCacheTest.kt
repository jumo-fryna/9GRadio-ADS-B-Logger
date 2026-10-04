package com.radiosport.ninegradio.adsblog

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

@RunWith(AndroidJUnit4::class)
class ExportAndCacheTest {
    @Test fun pdfIsReadableOfflineWithCoverAndAircraftCard() {
        val r = AircraftReception("r", "s", "ABC123", firstSeen = 0, lastSeen = 1000, frameCount = 25)
        val card = ReceptionCard(r, null, null, null, "Offline operator", null, null)
        val report = ReceptionReport(0, listOf(ReceptionSession("s", 0, 1000)), listOf(card), emptyMap())
        val bytes = ByteArrayOutputStream(); ReceptionPdf.write(report, bytes)
        assertTrue(bytes.toByteArray().toString(Charsets.ISO_8859_1).startsWith("%PDF"))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.cacheDir, "export-test.pdf"); file.writeBytes(bytes.toByteArray())
        try {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use { pdf ->
                assertEquals(2, pdf.pageCount)
                pdf.openPage(0).use { assertEquals(595, it.width); assertEquals(842, it.height) }
                pdf.openPage(1).use { assertEquals(595, it.width) }
            }
        } finally { file.delete() }
    }
    @Test fun localIdentityImportIsTransactionalAndAvailableWithoutNetwork() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        try {
            val cache = IdentityCache(db)
            val json = """[{"icao24":"abc123","registration":"SP-ABC","model":"737","operator":"LOT"}]"""
            assertEquals(1, cache.importDataset(json.byteInputStream(), "licensed local test"))
            assertEquals("SP-ABC", db.adsbLogDao().identity("ABC123")!!.registration)
            val bad = """[{"icao24":"abc123","registration":"REPLACED"},{"icao24":"INVALID"}]"""
            try { cache.importDataset(bad.byteInputStream(), "bad"); fail("Invalid ICAO must reject the import") }
            catch (_: IllegalArgumentException) { }
            assertEquals("SP-ABC", db.adsbLogDao().identity("ABC123")!!.registration)
        } finally { db.close() }
    }
}
