package com.pocketgps.logger.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {

    @Test
    fun exactPhraseMatches() {
        assertTrue(TriggerMatcher.matches("save spot", "save spot"))
    }

    @Test
    fun caseAndPunctuationAreIgnored() {
        assertTrue(TriggerMatcher.matches("Save Spot!", "save spot"))
        assertTrue(TriggerMatcher.matches("  SAVE,   SPOT.  ", "save spot"))
    }

    @Test
    fun phraseInsideLongerSentenceMatches() {
        assertTrue(TriggerMatcher.matches("please save spot now", "save spot"))
        assertTrue(TriggerMatcher.matches("okay save spot", "save spot"))
    }

    @Test
    fun singleWordDoesNotFire() {
        assertFalse(TriggerMatcher.matches("save", "save spot"))
        assertFalse(TriggerMatcher.matches("spot", "save spot"))
    }

    @Test
    fun regexMatcherRequiresWholeWordBoundaries() {
        assertFalse(TriggerMatcher.matches("save spots", "save spot"))
        assertFalse(TriggerMatcher.matches("unsave spot", "save spot"))
        assertTrue(TriggerMatcher.matches("please, save... spot!", "save spot"))
    }

    @Test
    fun unrelatedSentenceDoesNotMatch() {
        assertFalse(TriggerMatcher.matches("start the timer", "save spot"))
        assertFalse(TriggerMatcher.matches("save the file", "save spot"))
    }

    @Test
    fun reversedOrderDoesNotMatch() {
        assertFalse(TriggerMatcher.matches("spot save", "save spot"))
    }

    @Test
    fun nonContiguousWordsDoNotMatch() {
        assertFalse(TriggerMatcher.matches("save the spot", "save spot"))
    }

    @Test
    fun emptyInputRejected() {
        assertFalse(TriggerMatcher.matches("", "save spot"))
        assertFalse(TriggerMatcher.matches("   ", "save spot"))
        assertNull(TriggerMatcher.acceptedPhrase("", "save spot"))
    }

    @Test
    fun singleWordTriggerDefinitionRejected() {
        assertFalse(TriggerMatcher.isValidTrigger("save"))
        assertTrue(TriggerMatcher.isValidTrigger("save spot"))
        assertTrue(TriggerMatcher.isValidTrigger("mark this location"))
    }

    @Test
    fun normalizeStripsPunctuation() {
        assertEquals("save spot", TriggerMatcher.normalize("  Save-Spot!! "))
    }

    @Test
    fun acceptedPhraseReturnsNormalisedTrigger() {
        assertEquals("save spot", TriggerMatcher.acceptedPhrase("Please SAVE SPOT.", "save spot"))
    }

    @Test
    fun repeatedPhraseStillMatches() {
        // Matching is stateless; dedupe is the pipeline's job.
        assertTrue(TriggerMatcher.matches("save spot save spot", "save spot"))
    }
}
