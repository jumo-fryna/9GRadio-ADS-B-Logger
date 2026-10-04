package com.radiosport.ninegradio.adsblog

import androidx.room.withTransaction
import com.google.gson.Gson
import com.radiosport.ninegradio.data.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** User supplies a licensed JSON dataset; imports and optional refresh use exactly the same format. */
class IdentityCache(private val db: AppDatabase) {
    data class Entry(val icao24: String?, val registration: String?, val manufacturer: String?,
                     val model: String?, val operator: String?, val aircraftType: String?)
    suspend fun importDataset(stream: InputStream, source: String): Int = withContext(Dispatchers.IO) {
        val bytes = stream.use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "Identity dataset exceeds 8 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
        require(bytes.size <= MAX_BYTES) { "Identity dataset exceeds 8 MiB" }
        val entries = Gson().fromJson(bytes.toString(Charsets.UTF_8), Array<Entry>::class.java)
            ?: error("Expected JSON array")
        require(entries.size <= 50_000) { "Maximum 50000 aircraft per import" }
        val now = System.currentTimeMillis()
        val rows = entries.map { e ->
            val icao = e.icao24?.trim()?.uppercase(java.util.Locale.ROOT) ?: error("Missing ICAO24")
            require(icao.matches(Regex("[0-9A-F]{6}"))) { "Invalid ICAO24: $icao" }
            AircraftIdentity(icao, clean(e.registration), clean(e.manufacturer), clean(e.model),
                clean(e.operator), clean(e.aircraftType), source.take(256), now)
        }
        db.withTransaction { rows.chunked(500).forEach { db.adsbLogDao().saveIdentities(it) } }
        rows.size
    }
    suspend fun refresh(url: String): Int = withContext(Dispatchers.IO) {
        val endpoint = URL(url)
        require(endpoint.protocol == "https" && endpoint.userInfo == null) { "Use an HTTPS dataset URL" }
        val connection = endpoint.openConnection() as HttpsURLConnection
        connection.connectTimeout = 10_000; connection.readTimeout = 15_000
        connection.instanceFollowRedirects = false
        try {
            require(connection.responseCode == 200) { "Dataset HTTP ${connection.responseCode}" }
            importDataset(connection.inputStream, endpoint.toString())
        } finally { connection.disconnect() }
    }
    private fun clean(value: String?): String? = value?.trim()?.take(160)?.takeIf { it.isNotBlank() }
    companion object { private const val MAX_BYTES = 8 * 1024 * 1024 }
}
