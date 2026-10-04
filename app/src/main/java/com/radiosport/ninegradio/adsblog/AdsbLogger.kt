package com.radiosport.ninegradio.adsblog

import android.util.Log
import androidx.room.withTransaction
import com.radiosport.ninegradio.data.AppDatabase
import com.radiosport.ninegradio.dsp.AdsbDecoder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Application-owned writer survives navigation to history and flushes after the radar closes. */
class AdsbLogger(private val db: AppDatabase) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val aggregator = ReceptionAggregator()
    private val writerMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()
    init {
        scope.launch {
            try {
                writerMutex.withLock {
                    // Seal interrupted sessions before any new batch can be written.
                    db.withTransaction {
                        val interrupted = db.adsbLogDao().allSessions().filter { it.endedAt == null }
                        if (interrupted.isNotEmpty()) db.adsbLogDao().saveSessions(interrupted.map { it.copy(endedAt = it.lastActiveAt) })
                    }
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                Log.e("AdsbLogger", "Cannot seal interrupted sessions", e)
                _error.value = "Cannot open reception history. Retrying checkpoints…"
            } finally { initialized.complete(Unit) }
            while (isActive) {
                delay(5_000)
                aggregator.heartbeat(System.currentTimeMillis())
                persist()
            }
        }
    }
    fun start(lat: Double?, lon: Double?): String = aggregator.start(System.currentTimeMillis(), lat, lon)
    fun accept(frame: AdsbDecoder.AdsbFrame) = aggregator.accept(frame)
    fun stop() {
        aggregator.stop(System.currentTimeMillis())
        scope.launch { persist() }
    }
    suspend fun flush() = withContext(Dispatchers.IO) { persist() }
    private suspend fun persist() {
        initialized.await()
        writerMutex.withLock {
            val batch = aggregator.snapshot()
            if (batch.sessions.isEmpty() && batch.receptions.isEmpty()) return@withLock
            try {
                db.withTransaction {
                    val dao = db.adsbLogDao()
                    if (batch.sessions.isNotEmpty()) dao.saveSessions(batch.sessions)
                    if (batch.receptions.isNotEmpty()) {
                        dao.saveReceptions(batch.receptions)
                        // Avoid SQLite's bind-parameter ceiling.
                        batch.receptions.map { it.id }.chunked(400).forEach { dao.deletePoints(it) }
                        if (batch.points.isNotEmpty()) dao.savePoints(batch.points)
                    }
                }
                aggregator.committed(batch)
                _error.value = null
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                // Keep the dirty batch; retry at the next checkpoint without failing the decoder.
                Log.e("AdsbLogger", "Checkpoint failed", e)
                _error.value = "History save failed: ${e.javaClass.simpleName}. Retrying…"
            }
        }
    }
}
