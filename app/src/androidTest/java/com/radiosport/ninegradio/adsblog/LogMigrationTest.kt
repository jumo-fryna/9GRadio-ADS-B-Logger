package com.radiosport.ninegradio.adsblog

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.data.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Opens a genuine legacy database through Room, including Room's complete schema validation. */
@RunWith(AndroidJUnit4::class)
class LogMigrationTest {
    @Test fun versionTwoPreservesRadioDataAndValidatesNewSchema() = migration(2)
    @Test fun versionOneMigratesThroughTwoWithoutLosingBookmarks() = migration(1)
    private fun migration(version: Int) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val name = "migration-$version-${System.nanoTime()}.db"
        val file = context.getDatabasePath(name); file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            legacyTables(version).forEach(db::execSQL)
            if (version == 1) db.execSQL("INSERT INTO bookmarks VALUES (7,1090000000,'ADS-B test',123,1000)")
            else db.execSQL("INSERT INTO bookmarks VALUES (7,1090000000,'ADS-B test','ADSB',0,-100,'keep me',123,1,NULL,1000,1000)")
            db.execSQL("INSERT INTO memory_channels VALUES (9,'Saved memory',145500000,'NFM',1920000,26,-100,0,0,0,'Default','preserve',1000,1000)")
            db.execSQL("INSERT INTO recordings VALUES (5,'/test.iq','IQ',1090000000,1920000,'ADSB',1000,4096,1000)")
            db.version = version
        }
        val room = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_1_2, LogMigration).build()
        try {
            assertEquals(3, room.openHelper.writableDatabase.version)
            assertEquals("ADS-B test", room.bookmarkDao().getById(7)!!.label)
            assertEquals("Saved memory", room.memoryChannelDao().getById(9)!!.name)
            room.openHelper.writableDatabase.query("SELECT filePath FROM recordings WHERE id=5").use {
                assertTrue(it.moveToFirst()); assertEquals("/test.iq", it.getString(0))
            }
            val dao = room.adsbLogDao()
            dao.saveSessions(listOf(ReceptionSession("s", 0, 1000)))
            dao.saveReceptions(listOf(AircraftReception("r", "s", "ABC123", firstSeen = 0, lastSeen = 1000, frameCount = 42)))
            dao.savePoints(listOf(TrackPoint("r", 0, 1000, 52.0, 21.0)))
            assertEquals(42L, dao.sessionReceptions("s").single().frameCount)
            assertEquals(1, dao.points("r").size)
        } finally { room.close(); context.deleteDatabase(name) }
    }
    private fun legacyTables(version: Int): List<String> {
        val tables = mutableListOf(
            "CREATE TABLE memory_channels (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, frequencyHz INTEGER NOT NULL, demodMode TEXT NOT NULL, sampleRate INTEGER NOT NULL, gain INTEGER NOT NULL, squelch REAL NOT NULL, biasTee INTEGER NOT NULL, directSampling INTEGER NOT NULL, ppmCorrection INTEGER NOT NULL, `group` TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, lastUsedAt INTEGER NOT NULL)",
            "CREATE TABLE scan_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, startFreqHz INTEGER NOT NULL, stopFreqHz INTEGER NOT NULL, stepHz INTEGER NOT NULL, demodMode TEXT NOT NULL, squelch REAL NOT NULL, dwellTimeMs INTEGER NOT NULL, enabled INTEGER NOT NULL)",
            "CREATE TABLE signal_log (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, frequencyHz INTEGER NOT NULL, demodMode TEXT NOT NULL, signalDb REAL NOT NULL, timestamp INTEGER NOT NULL, notes TEXT NOT NULL)",
            "CREATE TABLE recordings (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, filePath TEXT NOT NULL, type TEXT NOT NULL, frequencyHz INTEGER NOT NULL, sampleRate INTEGER NOT NULL, demodMode TEXT NOT NULL, durationMs INTEGER NOT NULL, fileSizeBytes INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
        )
        if (version == 1) tables += "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, frequencyHz INTEGER NOT NULL, label TEXT NOT NULL, color INTEGER NOT NULL, createdAt INTEGER NOT NULL)"
        else tables += listOf(
            "CREATE TABLE bookmark_lists (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, notes TEXT NOT NULL, color INTEGER NOT NULL, createdAt INTEGER NOT NULL)",
            "CREATE TABLE bookmarks (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, frequencyHz INTEGER NOT NULL, label TEXT NOT NULL, demodMode TEXT NOT NULL, bandwidth INTEGER NOT NULL, squelch REAL NOT NULL, notes TEXT NOT NULL, color INTEGER NOT NULL, favorite INTEGER NOT NULL, bookmarkListId INTEGER, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, FOREIGN KEY(bookmarkListId) REFERENCES bookmark_lists(id) ON UPDATE NO ACTION ON DELETE SET NULL)",
            "CREATE INDEX index_bookmarks_bookmarkListId ON bookmarks(bookmarkListId)",
            "CREATE INDEX index_bookmarks_frequencyHz ON bookmarks(frequencyHz)",
            "CREATE INDEX index_bookmarks_favorite ON bookmarks(favorite)"
        )
        return tables
    }
}
