package com.radiosport.ninegradio.adsblog

import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.radiosport.ninegradio.skylog.*
import com.radiosport.ninegradio.ui.AdsbLogActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SkyLogTest {
    @Test fun independentPackageAndDatabase() {
        val c=ApplicationProvider.getApplicationContext<Context>()
        assertEquals("com.radiosport.skylog1090",c.packageName)
        assertTrue(c.getDatabasePath("skylog1090.db").absolutePath.contains("/com.radiosport.skylog1090/"))
        assertFalse(c.getDatabasePath("skylog1090.db").absolutePath.contains("/com.radiosport.ninegradio/"))
        assertEquals(2_000_000,SkyLogService.RATE)
    }
    @Test fun nativeMagnitudePipelineMatchesUpstreamNormalization() {
        val result=SkyLogIq().magnitude(byteArrayOf(0,0,(-1).toByte(),(-1).toByte(),128.toByte(),128.toByte()))
        assertEquals(3,result.size)
        assertTrue(result.all{it.isFinite()&&it>=0})
        assertTrue(result[0]>1.3f&&result[0]<1.5f)
        assertTrue(result[2]<0.02f)
    }
    @Test fun launchDirectlyIntoLiveAndNativeOfflineReport() {
        ActivityScenario.launch(SkyLogActivity::class.java).use { scenario->scenario.onActivity { a ->
            assertEquals("SkyLog 1090",a.applicationInfo.loadLabel(a.packageManager).toString())
            assertTrue(a.javaClass.simpleName=="SkyLogActivity")
        } }
        val c=ApplicationProvider.getApplicationContext<Context>()
        ActivityScenario.launch<AdsbLogActivity>(Intent(c,AdsbLogActivity::class.java).putExtra("mobileReport",true)).use { scenario->scenario.onActivity{assertNotNull(it.window.decorView)} }
        c.stopService(Intent(c,SkyLogService::class.java))
    }
}
