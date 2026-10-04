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
        val root = logRoot(this, "ADS-B LOG / HISTORIA")
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
        listOf("Filter" to { showFilters() }, "XLSX" to { export(ExportFormat.XLSX) },
            "PDF" to { export(ExportFormat.PDF) }, "CSV" to { export(ExportFormat.CSV) }).forEach { (label, action) ->
            controls.addView(Button(this).apply { text = label; textSize = 12f; setOnClickListener { action() } },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        root.addView(controls)
        root.addView(Button(this).apply { text = "Local aircraft identity cache"; setOnClickListener { identityOptions() } })
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
        list.emptyView = logText(this, "No matching aircraft. Open ADS-B Radar to record reception.", 16f).also { root.addView(it) }
        list.setOnItemClickListener { _, _, pos, _ ->
            startActivity(Intent(this, AdsbDetailActivity::class.java).putExtra("icao24", rows[pos].reception.icao24).putExtra("receptionId", rows[pos].reception.id))
        }
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
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
                    caption.text = "${cards.size} reception(s) • filters apply to displayed list and exports • UTC"
                } }
                launch {
                    combine(filter.map { it.sessionId }.distinctUntilChanged(), dao.sessions()) { id, all ->
                        if (id.isBlank()) all else all.filter { it.id == id }
                    }.collectLatest { selected ->
                        val receptions = withContext(Dispatchers.IO) { selected.flatMap { dao.sessionReceptions(it.id) } }
                        val s = ReceptionStats.from(receptions, selected)
                        stats.text = "SESSION STATISTICS\n${s.aircraft} aircraft • ${s.frames} frames • ${ReportFormat.duration(s.listeningMs)} listening\n" +
                            "Farthest ${ReportFormat.distance(s.farthestNm)} • highest ${s.highestFeet?.let { "$it ft" } ?: "Unknown"}\n" +
                            "Most received: ${s.mostReceived ?: "—"}"
                    }
                }
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
        exportBusy = true
        lifecycleScope.launch {
            try {
                app.adsbLogger.flush()
                val selected = filter.value
                val report = withContext(Dispatchers.IO) {
                    app.database.withTransaction {
                        val cards = dao.filteredOnce(selected)
                        val ids = cards.map { it.reception.sessionId }.toSet()
                        ReceptionReport(System.currentTimeMillis(), dao.allSessions().filter { it.id in ids }, cards,
                            cards.associate { it.reception.id to dao.points(it.reception.id) })
                    }
                }
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
