package com.pocketgps.logger.ui.savedlocations

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pocketgps.logger.ServiceLocator
import com.pocketgps.logger.data.LocationRecord
import com.pocketgps.logger.export.CsvExporter
import com.pocketgps.logger.export.GpxExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.Calendar

/** Backs the Saved Locations screen: listing, stats, deletion and GPX/CSV export. */
class SavedLocationsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ServiceLocator.locationRepository

    val records: StateFlow<List<LocationRecord>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats

    sealed interface ExportOp {
        data object Idle : ExportOp
        data object Working : ExportOp
        data class Done(val message: String) : ExportOp
        data class Failed(val message: String) : ExportOp
    }

    private val _exportOp = MutableStateFlow<ExportOp>(ExportOp.Idle)
    val exportOp: StateFlow<ExportOp> = _exportOp

    data class Stats(val total: Int = 0, val today: Int = 0, val last7Days: Int = 0)

    init {
        // Recompute the cheap COUNT(*)-based stats whenever the record set changes.
        viewModelScope.launch {
            records.collect { refreshStats() }
        }
    }

    suspend fun refreshStats() {
        val total = records.value.size
        val zone = java.util.TimeZone.getDefault()
        val now = Calendar.getInstance(zone)
        val todayStart = now.apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val weekStart = todayStart - 7L * 24 * 60 * 60 * 1000
        val today = repository.countBetween(todayStart, Long.MAX_VALUE)
        val last7 = repository.countBetween(weekStart, Long.MAX_VALUE)
        _stats.value = Stats(total = total, today = today, last7Days = last7)
    }

    fun delete(id: Long) {
        viewModelScope.launch { repository.delete(id) }
    }

    fun clearAll() {
        viewModelScope.launch { repository.clearAll() }
    }

    /** Write every record to the SAF-provided [uri] as GPX or CSV, chosen by [gpx]. */
    fun export(uri: Uri, gpx: Boolean) {
        viewModelScope.launch {
            _exportOp.value = ExportOp.Working
            try {
                val records = withContext(Dispatchers.IO) { repository.listAll() }
                if (records.isEmpty()) {
                    _exportOp.value = ExportOp.Failed("Nothing to export yet")
                    return@launch
                }
                val content = withContext(Dispatchers.Default) {
                    if (gpx) GpxExporter.export(records) else CsvExporter.export(records)
                }
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(content.toByteArray(Charsets.UTF_8))
                    } ?: throw IOException("Could not open the selected file for writing")
                }
                _exportOp.value = ExportOp.Done("Exported ${records.size} spot(s)")
            } catch (t: Throwable) {
                _exportOp.value = ExportOp.Failed(t.message ?: "Export failed")
            }
        }
    }

    fun clearExportOp() {
        _exportOp.value = ExportOp.Idle
    }
}
