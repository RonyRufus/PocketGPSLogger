package com.pocketgps.logger.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoskResultParserTest {

    @Test
    fun parsesFinalWordsWithTiming() {
        val json = """
            {"result":[
              {"conf":0.97,"start":0.90,"end":1.10,"word":"save"},
              {"conf":0.91,"start":1.10,"end":1.40,"word":"spot"}
            ]}
        """.trimIndent()
        val events = VoskResultParser.parseFinal(json)
        assertEquals(2, events.size)
        assertEquals("save", events[0].word)
        assertEquals(0.97f, events[0].confidence, 0.001f)
        assertEquals("spot", events[1].word)
    }

    @Test
    fun malformedJsonYieldsEmptyList() {
        assertTrue(VoskResultParser.parseFinal("{not json").isEmpty())
        assertTrue(VoskResultParser.parseFinal("").isEmpty())
    }

    @Test
    fun emptyResultArrayYieldsEmptyList() {
        assertTrue(VoskResultParser.parseFinal("""{"result":[]}""").isEmpty())
    }

    @Test
    fun parsesPartial() {
        assertEquals("save sp", VoskResultParser.parsePartial("""{"partial":"save sp"}"""))
        assertNull(VoskResultParser.parsePartial("{}"))
        assertNull(VoskResultParser.parsePartial("garbage"))
    }

    @Test
    fun detectsNonEmptyFinalResult() {
        assertTrue(VoskResultParser.hasFinalResult("""{"result":[{"word":"save"}]}"""))
        assertFalse(VoskResultParser.hasFinalResult("""{"result":[]}"""))
        assertFalse(VoskResultParser.hasFinalResult("{}"))
    }
}
