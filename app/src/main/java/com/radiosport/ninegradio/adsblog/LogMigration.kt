package com.radiosport.ninegradio.adsblog

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive migration: no existing radio table is altered or cleared. */
object LogMigration : Migration(2, 3) {
    val statements = listOf(
        """CREATE TABLE IF NOT EXISTS adsb_sessions (
            id TEXT NOT NULL PRIMARY KEY, startedAt INTEGER NOT NULL, lastActiveAt INTEGER NOT NULL,
            endedAt INTEGER, receiverLat REAL, receiverLon REAL)""",
        """CREATE TABLE IF NOT EXISTS adsb_receptions (
            id TEXT NOT NULL PRIMARY KEY, sessionId TEXT NOT NULL, icao24 TEXT NOT NULL, callsign TEXT,
            firstSeen INTEGER NOT NULL, lastSeen INTEGER NOT NULL, frameCount INTEGER NOT NULL,
            minAltitude INTEGER, maxAltitude INTEGER, maxSpeed INTEGER, heading REAL, verticalRate INTEGER,
            latitude REAL, longitude REAL, maxDistanceNm REAL, onGround INTEGER,
            FOREIGN KEY(sessionId) REFERENCES adsb_sessions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        "CREATE INDEX IF NOT EXISTS index_adsb_receptions_sessionId ON adsb_receptions(sessionId)",
        "CREATE INDEX IF NOT EXISTS index_adsb_receptions_icao24 ON adsb_receptions(icao24)",
        "CREATE INDEX IF NOT EXISTS index_adsb_receptions_firstSeen ON adsb_receptions(firstSeen)",
        """CREATE TABLE IF NOT EXISTS adsb_points (
            receptionId TEXT NOT NULL, sequence INTEGER NOT NULL, timestamp INTEGER NOT NULL,
            latitude REAL NOT NULL, longitude REAL NOT NULL, altitude INTEGER,
            PRIMARY KEY(receptionId, sequence),
            FOREIGN KEY(receptionId) REFERENCES adsb_receptions(id) ON UPDATE NO ACTION ON DELETE CASCADE)""",
        """CREATE TABLE IF NOT EXISTS adsb_identity (
            icao24 TEXT NOT NULL PRIMARY KEY, registration TEXT, manufacturer TEXT, model TEXT,
            operator TEXT, aircraftType TEXT, source TEXT NOT NULL, updatedAt INTEGER NOT NULL)"""
    )
    override fun migrate(db: SupportSQLiteDatabase) { statements.forEach(db::execSQL) }
}
