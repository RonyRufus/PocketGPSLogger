package com.pocketgps.logger.export

import com.pocketgps.logger.data.LocationRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportFormatTest {

    private fun record(
        id: Long = 1L,
        notes: String? = null,
        phrase: String = "save spot",
    ) = LocationRecord(
        id = id,
        latitude = 12.971599,
        longitude = 77.594566,
        triggerTimestamp = 1_721_234_567_000L, // 2024-07-17T16:42:47Z
        locationTimestamp = 1_721_234_560_000L,
        accuracyMeters = 12.4f,
        locationSource = "fresh",
        locationAgeSeconds = null,
        triggerPhrase = phrase,
        saveStatus = "success",
        notes = notes,
    )

    // ------------------------------------------------------------------ GPX

    @Test
    fun gpxHasValidStructure() {
        val xml = GpxExporter.export(listOf(record()))
        assertTrue(xml.startsWith("<?xml"))
        assertTrue(xml.contains("<gpx version=\"1.1\""))
        assertTrue(xml.contains("lat=\"12.971599\" lon=\"77.594566\""))
        assertTrue(xml.contains("<time>2024-07-17T16:42:47Z</time>"))
        assertTrue(xml.trimEnd().endsWith("</gpx>"))
    }

    @Test
    fun gpxEscapesXmlSpecialsInNotes() {
        val xml = GpxExporter.export(listOf(record(notes = "Poor GPS accuracy (<150 m) & \"bad\"")))
        assertFalse(xml.contains("<150"))
        assertTrue(xml.contains("&lt;150 m"))
        assertTrue(xml.contains("&amp;"))
        assertTrue(xml.contains("&quot;"))
    }

    @Test
    fun gpxHandlesManyRecords() {
        val xml = GpxExporter.export((1L..50L).map { record(id = it) })
        assertEquals(50, Regex("<wpt ").let { r -> r.findAll(xml).count() })
    }

    @Test
    fun gpxEmptyListProducesValidDocument() {
        val xml = GpxExporter.export(emptyList())
        assertTrue(xml.contains("<gpx"))
        assertTrue(xml.trimEnd().endsWith("</gpx>"))
        assertFalse(xml.contains("<wpt"))
    }

    // ------------------------------------------------------------------ CSV

    @Test
    fun csvHeaderMatchesColumnCount() {
        val csv = CsvExporter.export(listOf(record()))
        val lines = csv.trimEnd().split("\n")
        assertEquals(2, lines.size)
        assertEquals(CsvExporter.HEADER.size, lines[0].split(",").size)
        assertEquals(CsvExporter.HEADER.size, lines[1].split(",").size)
    }

    @Test
    fun csvQuotesAndEscapesEmbeddedQuotes() {
        val csv = CsvExporter.export(listOf(record(notes = "He said \"run\", then walked")))
        assertTrue(csv.contains("\"He said \"\"run\"\", then walked\""))
    }

    @Test
    fun csvFlattensNewlinesInsideFields() {
        val csv = CsvExporter.export(listOf(record(notes = "line one\nline two")))
        assertEquals(2, csv.trimEnd().split("\n").size)
        assertTrue(csv.contains("line one line two"))
    }

    @Test
    fun csvKeepsStableColumnOrder() {
        val csv = CsvExporter.export(listOf(record()))
        val header = csv.trimEnd().split("\n").first()
        assertEquals(
            "\"id\",\"latitude\",\"longitude\",\"trigger_time\",\"location_time\"," +
                "\"accuracy_meters\",\"source\",\"fix_age_seconds\",\"trigger_phrase\"," +
                "\"save_status\",\"notes\"",
            header,
        )
    }

    @Test
    fun csvEmptyListStillEmitsHeader() {
        val csv = CsvExporter.export(emptyList())
        assertEquals(1, csv.trimEnd().split("\n").size)
    }
}
