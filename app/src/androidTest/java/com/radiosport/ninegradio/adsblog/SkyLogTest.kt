package com.radiosport.ninegradio.adsblog

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.skylog.*
import com.radiosport.ninegradio.ui.AdsbLogActivity
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkyLogTest {
    @Test fun independentPackageAndDatabase() = kotlinx.coroutines.runBlocking {
        val c=ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.radiosport.skylog1090",c.packageName)
        assertTrue(c.getDatabasePath("skylog1090.db").absolutePath.contains("/com.radiosport.skylog1090/"))
        assertFalse(c.getDatabasePath("skylog1090.db").absolutePath.contains("/com.radiosport.ninegradio/"))
        assertEquals(2_000_000,SkyLogService.RATE)
        val db=androidx.room.Room.inMemoryDatabaseBuilder(c,com.radiosport.ninegradio.data.AppDatabase::class.java).build()
        try {
            db.adsbLogDao().saveSessions(listOf(ReceptionSession("empty",1000,5000,endedAt=5000)))
            val whole=ReceptionReportLoader.load(db,LogFilter(sessionId="empty"))
            assertEquals(0,whole.stats.aircraft);assertEquals(0L,whole.stats.frames);assertEquals(4000L,whole.stats.listeningMs)
            val day=ReceptionReportLoader.load(db,LogFilter(from=2000,until=3000,sessionId="empty"))
            assertEquals(1000L,day.stats.listeningMs)
        }finally{db.close()}

    }
    @Test fun nativeMagnitudePipelineMatchesUpstreamNormalization() = kotlinx.coroutines.runBlocking {
        val result=SkyLogIq().magnitude(byteArrayOf(0,0,(-1).toByte(),(-1).toByte(),128.toByte(),128.toByte()))
        assertEquals(3,result.size)
        assertTrue(result.all{it.isFinite()&&it>=0})
        assertTrue(result[0]>1.3f&&result[0]<1.5f)
        assertTrue(result[2]<0.02f)
        // A known CRC-valid Mode S frame straddles two real driver-sized IQ blocks.
        val bytes=ByteArray(32_768){128.toByte()}
        val offset=8000
        fun pulse(sample:Int){bytes[sample*2]=255.toByte()}
        listOf(0,2,7,9).forEach{pulse(offset+it)}
        val hex="8D40621D58C382D690C8AC2863A7"
        hex.chunked(2).map{it.toInt(16)}.forEachIndexed{n,b->(0..7).forEach{bit->pulse(offset+16+(n*8+bit)*2+if((b and (128 shr bit))!=0)0 else 1)}}
        val decoder=com.radiosport.ninegradio.dsp.AdsbDecoder()
        val received=mutableListOf<com.radiosport.ninegradio.dsp.AdsbDecoder.AdsbFrame>()
        val collector=kotlinx.coroutines.CoroutineScope(coroutineContext).launch(start=kotlinx.coroutines.CoroutineStart.UNDISPATCHED){decoder.frames.collect{received.add(it)}}
        val pipeline=SkyLogIq()
        decoder.feed(pipeline.magnitude(bytes.copyOfRange(0,16_384)))
        decoder.feed(pipeline.magnitude(bytes.copyOfRange(16_384,32_768)))
        kotlinx.coroutines.yield()
        assertEquals(1,received.size);assertEquals("40621D",received.single().icao24)
        collector.cancel()

    }
    @Test fun launchDirectlyIntoLiveAndNativeOfflineReport() {
        ActivityScenario.launch(SkyLogActivity::class.java).use { scenario->scenario.onActivity { a ->
            assertEquals("SkyLog 1090",a.applicationInfo.loadLabel(a.packageManager).toString())
            assertTrue(a.javaClass.simpleName=="SkyLogActivity")
        } }
        val c=ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<AdsbLogActivity>(Intent(c,AdsbLogActivity::class.java).putExtra("mobileReport",true)).use { scenario->scenario.onActivity{a->
            assertNotNull(a.window.decorView)
            val now=System.currentTimeMillis()
            val r=AircraftReception("r","s","ABC123",firstSeen=now-3_600_000,lastSeen=now,frameCount=40,minAltitude=14000,maxAltitude=34000,maxSpeed=420,heading=132.0,verticalRate=-800,maxDistanceNm=210.0)
            val card=ReceptionCard(r,"SP-TEST",null,"Test model","Local operator","A320",null)
            val report=ReceptionReport(now,listOf(ReceptionSession("s",now-3_600_000,now,receiverLat=51.0,receiverLon=19.0)),listOf(card),mapOf("r" to listOf(TrackPoint("r",0,now-3_600_000,52.0,20.0,14000),TrackPoint("r",1,now,53.0,21.0,34000))))
            val root=android.widget.LinearLayout(a).apply{orientation=android.widget.LinearLayout.VERTICAL}
            MobileReportUi.render(root,report,"Offline test"){}
            fun texts(v:android.view.View):List<String> = if(v is android.widget.TextView)listOf(v.text.toString()) else if(v is android.view.ViewGroup)(0 until v.childCount).flatMap{texts(v.getChildAt(it))}else emptyList()
            val labels=texts(root)
            assertTrue(labels.contains("AIRCRAFT\n${report.stats.aircraft}"))
            assertTrue(labels.contains("FRAMES\n${report.stats.frames}"))
            assertTrue(labels.any{it.contains("SP-TEST")})
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(1080,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(0,android.view.View.MeasureSpec.UNSPECIFIED))
            root.layout(0,0,1080,root.measuredHeight)
            val bitmap=android.graphics.Bitmap.createBitmap(1080,root.measuredHeight.coerceAtMost(16000),android.graphics.Bitmap.Config.ARGB_8888)
            root.draw(android.graphics.Canvas(bitmap))
            java.io.File(a.getExternalFilesDir(null),"phone-report-preview.png").outputStream().use{bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}
            bitmap.recycle()

        } }
        c.stopService(Intent(c,SkyLogService::class.java))
    }
}
