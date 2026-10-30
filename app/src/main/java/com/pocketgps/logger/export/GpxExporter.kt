package com.pocketgps.logger.export

import com.pocketgps.logger.data.LocationRecord
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Renders saved locations as a GPX 1.1 waypoint file.
 *
 * GPX is the interchange format understood by OsmAnd, Organic Maps, Google Earth, Garmin
 * BaseCamp, Gaia and nearly every mapping tool, so it is the default export target. Each
 * record becomes a `<wpt>`; trigger time, accuracy, source and any warnings ride along in
 * `<ext>`-style child elements that mapping apps safely ignore when unknown.
 *
 * Pure Kotlin so it can be unit-tested on the JVM without a device.
 */
object GpxExporter {

    fun export(records: List<LocationRecord>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Pocket GPS Logger\" ")
        append("xmlns=\"http://www.topografix.com/GPX/1/1\" ")
        append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" ")
        append("xsi:schemaLocation=\"http://www.topografix.com/GPX/1/1 ")
        append("http://www.topografix.com/GPX/1/1/gpx.xsd\">\n")
        // GPX waypoints require coordinates; unlocated observations remain available in CSV.
        records.forEach { record ->
            val lat = record.latitude
            val lon = record.longitude
            if (lat != null && lon != null) append(wpt(record, lat, lon))
        }
        append("</gpx>\n")
    }

    private fun wpt(record: LocationRecord, latitude: Double, longitude: Double): String = buildString {
        append("  <wpt lat=\"")
        append(format(latitude))
        append("\" lon=\"")
        append(format(longitude))
        append("\">\n")
        append("    <time>")
        append(iso8601(record.triggerTimestamp))
        append("</time>\n")
        append("    <name>")
        append(escape("Spot " + isoDate(record.triggerTimestamp)))
        append("</name>\n")
        append("    <desc>")
        append(escape(describe(record)))
        append("</desc>\n")
        append("    <sym>Flag, Blue</sym>\n")
        append("  </wpt>\n")
    }

    private fun describe(record: LocationRecord): String = buildString {
        append("Trigger: ").append(record.triggerPhrase)
        append(" | Source: ").append(record.locationSource)
        record.accuracyMeters?.let { append(" | Accuracy: ±").append(it.toInt()).append(" m") }
        record.locationAgeSeconds?.let { append(" | Fix age: ").append(it).append(" s") }
        record.observation?.let { append(" | Observation: ").append(it) }
        record.transcript?.let { append(" | Transcript: ").append(it) }
        record.category?.let { append(" | Category: ").append(it) }
        record.notes?.let { append(" | ").append(it) }
    }

    internal fun escape(text: String): String = buildString {
        text.forEach { ch ->
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }

    internal fun format(value: Double): String = String.format(Locale.US, "%.6f", value)

    private fun iso8601(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(Date(ms))
    }

    private fun isoDate(ms: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        return fmt.format(Date(ms))
    }
}
