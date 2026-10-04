package com.radiosport.ninegradio.adsblog

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "adsb_sessions")
data class ReceptionSession(
    @PrimaryKey val id: String,
    val startedAt: Long,
    val lastActiveAt: Long,
    val endedAt: Long? = null,
    val receiverLat: Double? = null,
    val receiverLon: Double? = null
)

@Entity(tableName = "adsb_receptions", foreignKeys = [ForeignKey(
    entity = ReceptionSession::class, parentColumns = ["id"], childColumns = ["sessionId"],
    onDelete = ForeignKey.CASCADE
)], indices = [Index("sessionId"), Index("icao24"), Index("firstSeen")])
data class AircraftReception(
    @PrimaryKey val id: String,
    val sessionId: String,
    val icao24: String,
    val callsign: String? = null,
    val firstSeen: Long,
    val lastSeen: Long,
    val frameCount: Long = 0,
    val minAltitude: Int? = null,
    val maxAltitude: Int? = null,
    val maxSpeed: Int? = null,
    val heading: Double? = null,
    val verticalRate: Int? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val maxDistanceNm: Double? = null,
    val onGround: Boolean? = null
)

@Entity(tableName = "adsb_points", primaryKeys = ["receptionId", "sequence"], foreignKeys = [ForeignKey(
    entity = AircraftReception::class, parentColumns = ["id"], childColumns = ["receptionId"],
    onDelete = ForeignKey.CASCADE
)])
data class TrackPoint(
    val receptionId: String,
    val sequence: Int,
    val timestamp: Long,
    val latitude: Double,
    val longitude: Double,
    val altitude: Int? = null
)

/** Metadata is deliberately separate from radio observations. Never infer it from callsign. */
@Entity(tableName = "adsb_identity")
data class AircraftIdentity(
    @PrimaryKey val icao24: String,
    val registration: String? = null,
    val manufacturer: String? = null,
    val model: String? = null,
    val operator: String? = null,
    val aircraftType: String? = null,
    val source: String,
    val updatedAt: Long
)

data class ReceptionCard(
    @Embedded val reception: AircraftReception,
    val registration: String?,
    val manufacturer: String?,
    val model: String?,
    val operator: String?,
    val aircraftType: String?,
    val identitySource: String?
)

data class LogFilter(
    val from: Long = 0, val until: Long = Long.MAX_VALUE,
    val icao24: String = "", val callsign: String = "", val registration: String = "",
    val operator: String = "", val aircraftType: String = "", val sessionId: String = ""
)

@Dao
interface AdsbLogDao {
    @Query("DELETE FROM adsb_sessions WHERE endedAt IS NOT NULL AND endedAt < :cutoff")
    suspend fun pruneCompleted(cutoff: Long)
    @Upsert suspend fun saveSessions(rows: List<ReceptionSession>)
    @Upsert suspend fun saveReceptions(rows: List<AircraftReception>)
    @Insert suspend fun savePoints(rows: List<TrackPoint>)
    @Query("DELETE FROM adsb_points WHERE receptionId IN (:ids)")
    suspend fun deletePoints(ids: List<String>)
    @Upsert suspend fun saveIdentities(rows: List<AircraftIdentity>)
    @Query("SELECT * FROM adsb_identity WHERE icao24 = :icao24")
    suspend fun identity(icao24: String): AircraftIdentity?
    @Query("SELECT * FROM adsb_sessions ORDER BY startedAt DESC")
    fun sessions(): Flow<List<ReceptionSession>>
    @Query("SELECT * FROM adsb_sessions ORDER BY startedAt DESC")
    suspend fun allSessions(): List<ReceptionSession>
    @Query("SELECT * FROM adsb_sessions WHERE id = :id")
    suspend fun session(id: String): ReceptionSession?
    @Query("SELECT * FROM adsb_points WHERE receptionId = :id ORDER BY sequence")
    suspend fun points(id: String): List<TrackPoint>
    @Query("SELECT * FROM adsb_receptions WHERE sessionId = :id ORDER BY firstSeen")
    suspend fun sessionReceptions(id: String): List<AircraftReception>
    @Query("SELECT * FROM adsb_receptions WHERE icao24 = :icao ORDER BY firstSeen DESC")
    suspend fun aircraftHistory(icao: String): List<AircraftReception>

    @Query("""
        SELECT r.*, i.registration, i.manufacturer, i.model, i.operator, i.aircraftType,
               i.source AS identitySource
        FROM adsb_receptions r LEFT JOIN adsb_identity i ON r.icao24 = i.icao24
        WHERE r.lastSeen >= :from AND r.firstSeen < :until
        AND (:sessionId = '' OR r.sessionId = :sessionId)
        AND r.icao24 LIKE '%' || :icao || '%' ESCAPE '\'
        AND COALESCE(r.callsign, '') LIKE '%' || :callsign || '%' ESCAPE '\'
        AND COALESCE(i.registration, '') LIKE '%' || :registration || '%' ESCAPE '\'
        AND COALESCE(i.operator, '') LIKE '%' || :operator || '%' ESCAPE '\'
        AND (COALESCE(i.aircraftType, '') LIKE '%' || :type || '%' ESCAPE '\'
             OR COALESCE(i.model, '') LIKE '%' || :type || '%' ESCAPE '\')
        ORDER BY r.lastSeen DESC
    """)
    fun filtered(from: Long, until: Long, sessionId: String, icao: String, callsign: String,
                 registration: String, operator: String, type: String): Flow<List<ReceptionCard>>

    @Query("""
        SELECT r.*, i.registration, i.manufacturer, i.model, i.operator, i.aircraftType,
               i.source AS identitySource
        FROM adsb_receptions r LEFT JOIN adsb_identity i ON r.icao24 = i.icao24
        WHERE r.lastSeen >= :from AND r.firstSeen < :until
        AND (:sessionId = '' OR r.sessionId = :sessionId)
        AND r.icao24 LIKE '%' || :icao || '%' ESCAPE '\'
        AND COALESCE(r.callsign, '') LIKE '%' || :callsign || '%' ESCAPE '\'
        AND COALESCE(i.registration, '') LIKE '%' || :registration || '%' ESCAPE '\'
        AND COALESCE(i.operator, '') LIKE '%' || :operator || '%' ESCAPE '\'
        AND (COALESCE(i.aircraftType, '') LIKE '%' || :type || '%' ESCAPE '\'
             OR COALESCE(i.model, '') LIKE '%' || :type || '%' ESCAPE '\')
        ORDER BY r.lastSeen DESC
    """)
    suspend fun filteredOnce(from: Long, until: Long, sessionId: String, icao: String, callsign: String,
                 registration: String, operator: String, type: String): List<ReceptionCard>
}

fun sqlLiteralFilter(text: String): String = text.trim().replace("\\", "\\\\")
    .replace("%", "\\%").replace("_", "\\_")
fun AdsbLogDao.filtered(filter: LogFilter) = filtered(filter.from, filter.until, filter.sessionId,
    sqlLiteralFilter(filter.icao24), sqlLiteralFilter(filter.callsign), sqlLiteralFilter(filter.registration),
    sqlLiteralFilter(filter.operator), sqlLiteralFilter(filter.aircraftType))

suspend fun AdsbLogDao.filteredOnce(filter: LogFilter) = filteredOnce(filter.from, filter.until, filter.sessionId,
    sqlLiteralFilter(filter.icao24), sqlLiteralFilter(filter.callsign), sqlLiteralFilter(filter.registration),
    sqlLiteralFilter(filter.operator), sqlLiteralFilter(filter.aircraftType))
