package com.pocketgps.logger.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GrammarJsonTest {

    @Test
    fun includesEveryTriggerWord() {
        val json = GrammarJson.build(listOf("save spot"))
        assertTrue(json.contains("\"save\""))
        assertTrue(json.contains("\"spot\""))
    }

    @Test
    fun alwaysIncludesUnknownToken() {
        val json = GrammarJson.build(listOf("mark this location"))
        assertTrue(json.contains("\"[unk]\""))
    }

    @Test
    fun deduplicatesWords() {
        val json = GrammarJson.build(listOf("save spot", "save now"))
        val occurrences = Regex("\"save\"").findAll(json).count()
        assertEquals(1, occurrences)
    }

    @Test
    fun isValidJsonArray() {
        val json = GrammarJson.build(listOf("save spot"))
        assertTrue(json.startsWith("["))
        assertTrue(json.endsWith("]"))
    }

    @Test
    fun wordsOfNormalises() {
        assertEquals(listOf("save", "spot"), GrammarJson.wordsOf("  Save   SPOT! "))
    }
}
