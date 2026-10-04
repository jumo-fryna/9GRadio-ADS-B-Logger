package com.radiosport.ninegradio.skylog

import android.content.Intent
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.radiosport.ninegradio.adsblog.ReceptionAggregator
import com.radiosport.ninegradio.ui.*

class SkyLogSettingsActivity:AppCompatActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=logRoot(this,"USTAWIENIA • ADS-B ONLY")
        val form=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL};root.addView(ScrollView(this).apply{addView(form)})
        val prefs=getSharedPreferences("adsb_logger",MODE_PRIVATE)
        form.addView(logText(this,"1090.000 MHz • 2.000 MS/s • ADS-B\nProtocol settings are automatic. Gain is the RTL-SDR gain-table index (default 26).",15f))
        fun number(label:String,key:String,default:String)=EditText(this).apply {hint=label;setText(prefs.getString(key,default));form.addView(this)}
        val gain=EditText(this).apply {hint="RF gain index (0–28)";setText(prefs.getInt("gain",26).toString());form.addView(this)}
        val ppm=EditText(this).apply {hint="PPM correction (-200…200)";setText(prefs.getInt("ppm",0).toString());form.addView(this)}
        val agc=CheckBox(this).apply{text="Tuner AGC";isChecked=prefs.getBoolean("tunerAgc",false);form.addView(this)}
        val hw=CheckBox(this).apply{text="RTL hardware AGC";isChecked=prefs.getBoolean("hardwareAgc",false);form.addView(this)}
        val lat=number("Receiver latitude","receiverLat","");val lon=number("Receiver longitude","receiverLon","")
        val days=EditText(this).apply{hint="History retention days (0 = keep forever)";setText(prefs.getInt("retentionDays",0).toString());form.addView(this)}
        val reportName=number("Report / session label","reportName","SkyLog 1090")
        form.addView(Button(this).apply{text="SAVE ADS-B SETTINGS";setOnClickListener {
            val a=lat.text.toString().toDoubleOrNull();val b=lon.text.toString().toDoubleOrNull();val g=gain.text.toString().toIntOrNull();val p=ppm.text.toString().toIntOrNull();val d=days.text.toString().toIntOrNull()
            if((lat.text.isNotBlank()||lon.text.isNotBlank())&&!ReceptionAggregator.validPosition(a,b)||g==null||g !in 0..28||p==null||p !in -200..200||d==null||d !in 0..3650){Toast.makeText(this@SkyLogSettingsActivity,"Enter valid coordinates, gain, PPM and retention",Toast.LENGTH_LONG).show();return@setOnClickListener}
            prefs.edit().putInt("gain",g).putInt("ppm",p).putInt("retentionDays",d).putBoolean("tunerAgc",agc.isChecked).putBoolean("hardwareAgc",hw.isChecked).putString("receiverLat",lat.text.toString()).putString("receiverLon",lon.text.toString()).putString("reportName",reportName.text.toString()).apply()
            startService(Intent(this@SkyLogSettingsActivity,SkyLogService::class.java).setAction(SkyLogService.APPLY));finish()
        }})
        form.addView(Button(this).apply{text="AIRCRAFT IDENTITY DATABASE • IMPORT / HTTPS";setOnClickListener{startActivity(Intent(this@SkyLogSettingsActivity,AdsbLogActivity::class.java).putExtra("identityOnly",true))}})
        form.addView(logText(this,"Times and report filters: UTC. Distances: NM; altitude: ft; speed: kt. Retention deletes only completed old sessions and their routes, never the identity cache.",13f))
    }
}
