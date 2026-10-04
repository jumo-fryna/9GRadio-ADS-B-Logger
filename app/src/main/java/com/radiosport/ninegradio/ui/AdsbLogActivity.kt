package com.radiosport.ninegradio.ui

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.radiosport.ninegradio.RtlSdrApplication
import com.radiosport.ninegradio.adsblog.*
import androidx.room.withTransaction
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class AdsbLogActivity : AppCompatActivity() {
    private val app get() = application as RtlSdrApplication
    private val dao get() = app.database.adsbLogDao()
    private val filter = MutableStateFlow(LogFilter())
    private lateinit var stats: TextView
    private lateinit var caption: TextView
    private lateinit var sessionPicker: Spinner
    private val rows = mutableListOf<ReceptionCard>()
    private var sessions = emptyList<ReceptionSession>()
    private lateinit var adapter: BaseAdapter
    private var mobileBody: LinearLayout? = null
    private var reportJob: Job? = null
    private var displayedReport: ReceptionReport? = null
    private var displayedFilter: LogFilter? = null
    private val exportState: AdsbExportViewModel by viewModels()
    private var pendingExport: ReceptionReport?
        get() = exportState.report
        set(value) { exportState.report = value }
    private enum class ExportFormat { XLSX, PDF, CSV }
    private var pendingFormat: ExportFormat
        get() = ExportFormat.valueOf(exportState.format)
        set(value) { exportState.format = value.name }
    private var exportBusy: Boolean
        get() = exportState.busy
        set(value) { exportState.busy = value }
    private val exportDocument = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val report = pendingExport; pendingExport = null
        val uri = result.data?.data
        if (result.resultCode != RESULT_OK || uri == null || report == null) { exportBusy = false; return@registerForActivityResult }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    contentResolver.openOutputStream(uri, "wt")?.use { output ->
                        when (pendingFormat) {
                            ExportFormat.XLSX -> ReceptionXlsx.write(report, output)
                            ExportFormat.PDF -> ReceptionPdf.write(report, output)
                            ExportFormat.CSV -> ReceptionCsv.write(report, output.writer(Charsets.UTF_8))
                        }
                    } ?: error("Cannot open destination")
                }
                message("Report saved")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message("Export failed: ${e.message}")
            } finally { exportBusy = false }
        }
    }
    private val importDocument = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) lifecycleScope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    IdentityCache(app.database).importDataset(contentResolver.openInputStream(uri) ?: error("Cannot open file"), "Local JSON import")
                }
                message("Imported $count aircraft identities")
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { message("Import failed: ${e.message}") }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val mobile = intent.getBooleanExtra("mobileReport", false)
        val root = logRoot(this, if(mobile) "RAPORT • SKYLOG 1090" else "HISTORIA • SKYLOG 1090")
        if(intent.getBooleanExtra("identityOnly",false)) {
            root.addView(Button(this).apply { text="IMPORT / REFRESH IDENTITIES";setOnClickListener {identityOptions()} })
            identityOptions(); return
        }
        if (savedInstanceState != null) {
            filter.value = LogFilter(savedInstanceState.getLong("from", 0), savedInstanceState.getLong("until", Long.MAX_VALUE),
                savedInstanceState.getString("icao").orEmpty(), savedInstanceState.getString("callsign").orEmpty(),
                savedInstanceState.getString("registration").orEmpty(), savedInstanceState.getString("operator").orEmpty(),
                savedInstanceState.getString("type").orEmpty(), savedInstanceState.getString("session").orEmpty())
        }
        stats = logText(this, "No reception history yet", 15f); root.addView(stats)
        sessionPicker = Spinner(this); root.addView(sessionPicker)
        sessionPicker.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                val session = sessions.getOrNull(pos - 1)?.id.orEmpty()
                if (filter.value.sessionId != session) filter.value = filter.value.copy(sessionId = session)
            }
        }
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Filtry" to { showFilters() }, "Excel" to { export(ExportFormat.XLSX) },
            "PDF" to { export(ExportFormat.PDF) }, "CSV" to { export(ExportFormat.CSV) }).forEach { (label, action) ->
            controls.addView(Button(this).apply { text = label; textSize = 12f; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(controls)
        if(mobile) {
            root.addView(Button(this).apply { text="UDOSTĘPNIJ • EXCEL";setOnClickListener { shareReport() } })
            root.addView(Button(this).apply { text="WYBIERZ DZIEŃ";setOnClickListener {
                val now=LocalDate.now(ZoneOffset.UTC)
                android.app.DatePickerDialog(this@AdsbLogActivity,{_,y,m,d->val day=LocalDate.of(y,m+1,d);filter.value=filter.value.copy(from=day.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),until=day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli(),sessionId="")},now.year,now.monthValue-1,now.dayOfMonth).show()
            } })
        }
        caption = logText(this, "All dates • UTC", 12f); root.addView(caption)
        val list = ListView(this).apply { dividerHeight = 1 }
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = rows[position].reception.id.hashCode().toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val card = rows[position]; val r = card.reception
                return (convertView as? TextView ?: logText(this@AdsbLogActivity, "", 15f)).apply {
                    text = "${card.registration ?: r.callsign ?: r.icao24}   ›\n" +
                        "${r.icao24} • ${r.callsign ?: "—"} • ${card.aircraftType ?: card.model ?: "Unknown type"}\n" +
                        "${card.operator ?: "Unknown operator"}\n" +
                        "${ReportFormat.time(r.firstSeen)} • ${r.frameCount} frames • ${ReportFormat.distance(r.maxDistanceNm)}"
                    setPadding(18, 18, 18, 18)
                }
            }
        }
        list.adapter = adapter
        if(!mobile) list.emptyView = logText(this, "No matching aircraft. LIVE records automatically when a receiver is connected.", 16f).also { root.addView(it) }
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, AdsbDetailActivity::class.java).putExtra("icao24", rows[pos].reception.icao24).putExtra("receptionId", rows[pos].reception.id))
        }
        if(mobile) {
            val body=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL};mobileBody=body
            root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        } else root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { dao.sessions().collect { values ->
                    val selected = filter.value.sessionId
                    val sessionListChanged = sessions.map { it.id } != values.map { it.id }
                    sessions = values
                    if (sessionListChanged || sessionPicker.adapter == null) {
                    sessionPicker.adapter = ArrayAdapter(this@AdsbLogActivity, android.R.layout.simple_spinner_dropdown_item,
                        listOf("All listening sessions") + values.map { ReportFormat.time(it.startedAt) })
                    sessionPicker.setSelection(values.indexOfFirst { it.id == selected }.let { if (it < 0) 0 else it + 1 })
                    }
                } }
                launch { filter.flatMapLatest { dao.filtered(it) }.collect { cards ->
                    rows.clear(); rows.addAll(cards); adapter.notifyDataSetChanged()
                    caption.text = "${cards.size} reception(s) • same selection for screen / exports • UTC"
                    reportJob?.cancel()
                    reportJob=launch {
                        val selection=filter.value
                        val report=prepareReport(selection)
                        displayedReport=report;displayedFilter=selection
                        val s=report.stats
                        stats.text="${s.aircraft} aircraft • ${s.frames} frames • ${ReportFormat.duration(s.listeningMs)} listening\nFarthest ${ReportFormat.distance(s.farthestNm)} • Highest ${s.highestFeet?:"—"} ft"
                        mobileBody?.let{body->com.radiosport.ninegradio.skylog.MobileReportUi.render(body,report,getSharedPreferences("adsb_logger",MODE_PRIVATE).getString("reportName","SkyLog 1090").orEmpty()){card->startActivity(Intent(this@AdsbLogActivity,AdsbDetailActivity::class.java).putExtra("icao24",card.reception.icao24).putExtra("receptionId",card.reception.id))}}
                    }
                } }

            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {
        val f = filter.value
        outState.putLong("from", f.from); outState.putLong("until", f.until)
        outState.putString("icao", f.icao24); outState.putString("callsign", f.callsign)
        outState.putString("registration", f.registration); outState.putString("operator", f.operator)
        outState.putString("type", f.aircraftType); outState.putString("session", f.sessionId)
        super.onSaveInstanceState(outState)
    }
    private fun showFilters() {
        val form = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(22, 8, 22, 8) }
        val f = filter.value
        fun input(hint: String, value: String) = EditText(this).apply { this.hint = hint; setText(value); form.addView(this) }
        val from = input("From date YYYY-MM-DD (UTC)", if (f.from == 0L) "" else java.time.Instant.ofEpochMilli(f.from).atOffset(ZoneOffset.UTC).toLocalDate().toString())
        val until = input("Through date YYYY-MM-DD (UTC)", if (f.until == Long.MAX_VALUE) "" else java.time.Instant.ofEpochMilli(f.until - 1).atOffset(ZoneOffset.UTC).toLocalDate().toString())
        val icao = input("ICAO24", f.icao24); val call = input("Callsign", f.callsign)
        val registration = input("Registration", f.registration); val operator = input("Operator", f.operator)
        val type = input("Aircraft type / model", f.aircraftType)
        val dialog = AlertDialog.Builder(this).setTitle("Reception filters")
            .setView(ScrollView(this).apply { addView(form) }).setNegativeButton("Cancel", null)
            .setNeutralButton("Clear") { _, _ -> filter.value = LogFilter(sessionId = f.sessionId) }
            .setPositiveButton("Apply", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    fun date(input: EditText, end: Boolean): Long {
                        if (input.text.isBlank()) return if (end) Long.MAX_VALUE else 0
                        var d = LocalDate.parse(input.text.toString().trim()); if (end) d = d.plusDays(1)
                        return d.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
                    }
                    val a = date(from, false); val b = date(until, true); require(a < b) { "Invalid date range" }
                    filter.value = LogFilter(a, b, icao.text.toString(), call.text.toString(), registration.text.toString(),
                        operator.text.toString(), type.text.toString(), f.sessionId)
                    dialog.dismiss()
                } catch (e: Exception) { message("Use YYYY-MM-DD and a valid date range") }
            }
        }
        dialog.show()
    }
    private fun export(format: ExportFormat) {
        if (exportBusy) { message("Export already in progress"); return }
        if (rows.isEmpty()) { message("No matching receptions to export"); return }
        val visible=if(mobileBody!=null&&displayedFilter==filter.value)displayedReport else null
        exportBusy = true
        lifecycleScope.launch {
            try {
                app.adsbLogger.flush()
                val selected = filter.value
                val report = visible ?: prepareReport(selected)
                pendingExport = report; pendingFormat = format
                exportDocument.launch(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = when (format) {
                        ExportFormat.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                        ExportFormat.PDF -> "application/pdf"
                        ExportFormat.CSV -> "text/csv"
                    }
                    putExtra(Intent.EXTRA_TITLE, "ADS-B-${LocalDate.now(ZoneOffset.UTC)}.${format.name.lowercase(java.util.Locale.ROOT)}")
                })
            } catch (e: CancellationException) { exportBusy = false; throw e
            } catch (e: Exception) { exportBusy = false; message("Export failed: ${e.message}") }
        }
    }
    private suspend fun prepareReport(selected: LogFilter): ReceptionReport = withContext(Dispatchers.IO) {
        app.database.withTransaction {
            val cards=dao.filteredOnce(selected);val ids=cards.map{it.reception.sessionId}.toSet()
            ReceptionReport(System.currentTimeMillis(),dao.allSessions().filter{it.id in ids},cards,cards.associate{it.reception.id to dao.points(it.reception.id)})
        }
    }
    private fun shareReport() {
        if(exportBusy)return
        val visible=if(displayedFilter==filter.value)displayedReport else null
        exportBusy=true
        lifecycleScope.launch {
            try {
                app.adsbLogger.flush();val report=visible ?: prepareReport(filter.value)
                val file=withContext(Dispatchers.IO) {
                    java.io.File(cacheDir,"SkyLog-1090-report.xlsx").also { f->f.outputStream().use{ReceptionXlsx.write(report,it)} }
                }
                val uri=androidx.core.content.FileProvider.getUriForFile(this@AdsbLogActivity,"${packageName}.fileprovider",file)
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM,uri);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData=android.content.ClipData.newRawUri("SkyLog report",uri)
                },"Udostępnij raport"))
            } catch(e:CancellationException){throw e
            } catch(e:Exception){message("Share failed: ${e.message}")
            } finally{exportBusy=false}
        }
    }
    private fun identityOptions() {
        AlertDialog.Builder(this).setTitle("Local aircraft identities")
            .setMessage("Import a licensed JSON array: icao24, registration, manufacturer, model, operator, aircraftType. Unknown fields can be omitted. Reception logging always works offline.")
            .setPositiveButton("Import JSON") { _, _ -> importDocument.launch(arrayOf("application/json", "text/plain")) }
            .setNeutralButton("Refresh HTTPS") { _, _ ->
                val url = EditText(this).apply { hint = "https://…/aircraft.json" }
                AlertDialog.Builder(this).setTitle("Optional dataset refresh").setView(url)
                    .setNegativeButton("Cancel", null).setPositiveButton("Refresh") { _, _ -> lifecycleScope.launch {
                        try { message("Imported ${IdentityCache(app.database).refresh(url.text.toString().trim())} identities")
                        } catch (e: CancellationException) { throw e
                        } catch (e: Exception) { message("Refresh failed: ${e.message}") }
                    } }.show()
            }.setNegativeButton("Close", null).show()
    }
    private fun message(text: String) { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
}

internal fun logText(context: android.content.Context, value: String, size: Float): TextView = TextView(context).apply {
    text = value; textSize = size; setTextColor(Color.rgb(200, 226, 235)); setPadding(16, 12, 16, 12)
}
internal fun logRoot(activity: AppCompatActivity, title: String): LinearLayout {
    activity.supportActionBar?.hide()
    val root = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(5, 18, 28)) }
    activity.setContentView(root)
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val system = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        v.setPadding(system.left, system.top, system.right, system.bottom); insets
    }
    root.addView(Button(activity).apply { text = "‹  $title"; setOnClickListener { activity.finish() } })
    return root
}

/** Keep a prepared report through configuration changes while the document picker is open. */
class AdsbExportViewModel : androidx.lifecycle.ViewModel() {
    var report: ReceptionReport? = null
    var format: String = "XLSX"
    var busy: Boolean = false
}
