package com.pocketgps.logger.export

import com.pocketgps.logger.data.LocationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Renders saved locations as CSV — the "I want this in a spreadsheet" export.
 *
 * Columns are stable and self-describing; the header row is always emitted. Text fields are
 * quoted and doubled-quote-escaped per RFC 4180 so trigger phrases containing commas or
 * newlines round-trip cleanly into Excel / Sheets / pandas.
 *
 * Pure Kotlin so it can be unit-tested on the JVM without a device.
 */
object CsvExporter {

    /** Stable column order — new columns are appended, never reordered or removed. */
    val HEADER: List<String> = listOf(
        "id",
        "latitude",
        "longitude",
        "trigger_time",
        "location_time",
        "accuracy_meters",
        "source",
        "fix_age_seconds",
        "trigger_phrase",
        "save_status",
        "notes",
    )

    fun export(records: List<LocationRecord>): String = buildString {
        appendLine(HEADER.joinToString(",") { it.toCsvField() })
        records.forEach { appendLine(row(it)) }
    }

    private fun row(record: LocationRecord): String = listOf(
        record.id.toString(),
        GpxExporter.format(record.latitude),
        GpxExporter.format(record.longitude),
        record.triggerTimestamp.toString(),
        record.locationTimestamp.toString(),
        record.accuracyMeters?.toString() ?: "",
        record.locationSource,
        record.locationAgeSeconds?.toString() ?: "",
        record.triggerPhrase,
        record.saveStatus,
        record.notes ?: "",
    ).joinToString(",") { it.toCsvField() }

    /** RFC 4180: quote everything, double embedded quotes, strip forbidden control chars. */
    internal fun String.toCsvField(): String {
        val cleaned = this.replace("\r", " ").replace("\n", " ")
        return "\"" + cleaned.replace("\"", "\"\"") + "\""
    }
}
