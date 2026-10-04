package com.radiosport.ninegradio

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.radiosport.ninegradio.data.AppDatabase

class RtlSdrApplication : Application() {

    companion object {
        const val CHANNEL_SDR_SERVICE = "rtlsdr_service"
        const val CHANNEL_RECORDING = "rtlsdr_recording"
        const val CHANNEL_SCANNER = "rtlsdr_scanner"

        lateinit var instance: RtlSdrApplication
            private set
    }

    val cleanupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)

    val database: AppDatabase by lazy { AppDatabase.getDatabase(this) }

    val adsbLogger by lazy { com.radiosport.ninegradio.adsblog.AdsbLogger(database) { getSharedPreferences("adsb_logger", MODE_PRIVATE).getInt("retentionDays",0) } }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val sdrChannel = NotificationChannel(
            CHANNEL_SDR_SERVICE,
            "SkyLog 1090 reception",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "ADS-B 1090 MHz USB reception and durable history"
            setShowBadge(false)
        }

        manager.createNotificationChannels(listOf(sdrChannel))
    }
}

