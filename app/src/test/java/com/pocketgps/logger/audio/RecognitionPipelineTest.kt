package com.pocketgps.logger.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionPipelineTest {

    private companion object {
        const val CHUNK = 2048
        const val CHUNK_NS = 128_000_000L // 2048 samples at 16 kHz
        const val SEC = CHUNK / 16_000f // one chunk in seconds
        /** Stream time of the 27th fed chunk: after 2 speech + 24 tail chunks the gate closes, so a later utterance resumes here. */
        val FED_26 = 26 * (CHUNK / 16_000f)
        val LOUD = ShortArray(CHUNK) { 3000 } // RMS ~0.09: above the gate's open threshold
        val QUIET = ShortArray(CHUNK) { 5 } // RMS ~0.0002: silence, but not an all-zero "mute"
    }

    private class FakeAudioSource(private val chunks: List<ShortArray>) : PcmAudioSource {
        private var index = 0
        override val sampleRate: Int = 16_000
        override fun start() = Unit
        override fun read(dest: ShortArray, offset: Int, length: Int): Int {
            if (index >= chunks.size) return -1
            val chunk = chunks[index++]
            val n = minOf(length, chunk.size)
            chunk.copyInto(dest, offset, 0, n)
            return n
        }

        override fun isExhausted(): Boolean = index >= chunks.size
        override fun stop() = Unit
    }

    /**
     * Scripted trigger recogniser (final events keyed by chunk index) plus a fake note
     * transcriber that records what it was fed and returns [noteResult] on finish.
     */
    private class ScriptedSpotter(
        private val script: Map<Int, List<RecognitionEvent>>,
        var noteResult: List<RecognitionEvent> = emptyList(),
        var noteAvailable: Boolean = true,
    ) : KeywordSpotter {
        private var call = 0
        /** Set by the harness: scripts are keyed by absolute chunk index, independent of gating. */
        var currentChunk = 0
        var noteSamplesFed = 0L
        var noteBegins = 0
        var noteCancels = 0
        var grammarUpdates = 0
        var triggerSamplesFed = 0L
        override val sampleRate: Int = 16_000

        override fun acceptWaveForm(pcm: ShortArray, offset: Int, length: Int): List<RecognitionEvent> {
            triggerSamplesFed += length
            call++
            return script[currentChunk] ?: emptyList()
        }

        override fun partialResult(): String? = null
        override fun streamSeconds(): Float = call * SEC
        override fun updateGrammar(phrases: List<String>) { grammarUpdates++ }
        override fun setDictation(enabled: Boolean) = Unit
        override fun beginNote(): Boolean { noteBegins++; noteSamplesFed = 0; return noteAvailable }
        override fun acceptNoteAudio(pcm: ShortArray, offset: Int, length: Int) { noteSamplesFed += length }
        override fun finishNote(): List<RecognitionEvent> = noteResult
        override fun cancelNote() { noteCancels++ }
        override fun close() = Unit
    }

    private data class Fired(val chunkIndex: Int, val note: String?, val transcript: String?)

    /** Drives the pipeline one chunk at a time with a clock that advances 128 ms per chunk. */
    private class Harness(chunks: List<ShortArray>, val spotter: ScriptedSpotter, dictation: Boolean) {
        var now = 1_000_000_000L
        var index = 0
        val fired = mutableListOf<Fired>()
        val pl = RecognitionPipeline(
            audio = FakeAudioSource(chunks),
            spotter = spotter,
            initialTriggers = listOf("save spot"),
            clock = { now },
            cooldownSeconds = 3,
        ).also {
            it.updateDictation(dictation)
            it.onTrigger = { _, _, _, note, transcript -> fired += Fired(index, note, transcript) }
        }

        fun step(): Boolean {
            now += CHUNK_NS
            spotter.currentChunk = index
            val ok = pl.processOneChunk()
            index++
            return ok
        }

        fun runAll() { while (step()) Unit }
    }

    private fun w(text: String, start: Float, end: Float, conf: Float = 0.9f) =
        RecognitionEvent(text, conf, start, end)

    private fun saveSpot(endSec: Float) = listOf(w("save", endSec - 0.2f, endSec - 0.1f), w("spot", endSec - 0.1f, endSec))

    private fun speech(n: Int) = List(n) { LOUD }
    private fun silence(n: Int) = List(n) { QUIET }

    private fun words(vararg t: String) = t.map { w(it, 0f, 0f) }

    // ------------------------------------------------------------------ notes OFF

    @Test
    fun notesOff_triggerFiresImmediatelyWithNullNote() {
        // The grammar recogniser maps "building" to [unk]; with notes off it must be ignored.
        val spotter = ScriptedSpotter(mapOf(0 to saveSpot(0.1f) + w("[unk]", 0.1f, 0.2f)))
        val h = Harness(speech(2) + silence(4), spotter, dictation = false)
        h.step()
        assertEquals(1, h.fired.size)
        assertNull(h.fired[0].note)
        assertEquals(0, spotter.noteBegins)
    }

    // ------------------------------------------------------------------ notes ON

    @Test
    fun notesOn_locationOnlyWhenNothingFollowsTrigger() {
        val spotter = ScriptedSpotter(mapOf(2 to saveSpot(2 * SEC)))
        val h = Harness(speech(2) + silence(40), spotter, dictation = true)
        h.runAll()
        assertEquals(1, h.fired.size)
        assertNull(h.fired[0].note)
        assertEquals("save spot", h.fired[0].transcript)
    }

    @Test
    fun notesOn_triggerAndNoteInSameResult_building() {
        // "save spot building" is one 3-chunk breath; Vosk emits the trigger final at chunk 5.
        val spotter = ScriptedSpotter(
            mapOf(5 to saveSpot(0.2f)),
            noteResult = words("building"),
        )
        val h = Harness(speech(3) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals(1, h.fired.size)
        assertEquals("building", h.fired[0].note)
        // The note transcriber received audio after the trigger only, never the trigger itself.
        assertTrue(spotter.noteSamplesFed > 0)
        assertTrue(spotter.noteSamplesFed < spotter.triggerSamplesFed - (0.2f * 16_000).toLong())
    }

    @Test
    fun notesOn_triggerAndNoteInSameResult_hospital() {
        val spotter = ScriptedSpotter(mapOf(5 to saveSpot(0.2f)), noteResult = words("hospital"))
        val h = Harness(speech(3) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals("hospital", h.fired.single().note)
    }

    @Test
    fun noteEchoOfTriggerAndUnkAreStripped_multiWordNote() {
        val spotter = ScriptedSpotter(
            mapOf(5 to saveSpot(0.2f)),
            noteResult = words("save", "spot", "new", "apartment", "project", "[unk]"),
        )
        val h = Harness(speech(5) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals("new apartment project", h.fired.single().note)
    }

    @Test
    fun notesOn_triggerAndNoteInSeparateResults() {
        // Trigger spoken in chunks 0-1; trigger final at chunk 3; note spoken later in 6-7.
        val spotter = ScriptedSpotter(mapOf(3 to saveSpot(2 * SEC)), noteResult = words("hospital"))
        val h = Harness(speech(2) + silence(4) + speech(2) + silence(20), spotter, dictation = true)
        h.runAll()
        val f = h.fired.single()
        assertEquals("hospital", f.note)
        assertTrue("must wait for the note speech, fired at ${f.chunkIndex}", f.chunkIndex > 7)
    }

    @Test
    fun notesOn_pauseBetweenTriggerAndNoteIsCaptured() {
        // ~1.3 s of silence (10 chunks) between trigger and note: inside the 2 s grace period.
        val spotter = ScriptedSpotter(mapOf(3 to saveSpot(2 * SEC)), noteResult = words("new", "apartment", "project"))
        val h = Harness(speech(2) + silence(12) + speech(4) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals("new apartment project", h.fired.single().note)
    }

    @Test
    fun notesOn_veryLongPauseSavesLocationOnlyAndNeverRetriggers() {
        val spotter = ScriptedSpotter(mapOf(3 to saveSpot(2 * SEC)), noteResult = words("late"))
        val h = Harness(speech(2) + silence(30) + speech(3) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals(1, h.fired.size)
        assertNull(h.fired[0].note)
    }

    // ------------------------------------------------------------------ settings changes

    @Test
    fun disablingNotesMidCaptureStillSavesLocationAndLeavesTriggerRecogniserAlone() {
        val spotter = ScriptedSpotter(mapOf(3 to saveSpot(2 * SEC)), noteResult = words("building"))
        val h = Harness(speech(2) + silence(30), spotter, dictation = true)
        repeat(4) { h.step() } // trigger final handled at chunk 3 -> capture open
        assertEquals(0, h.fired.size)
        h.pl.updateDictation(false)
        h.step()
        assertEquals(1, h.fired.size)
        assertNull(h.fired[0].note)
        assertEquals(1, spotter.noteCancels)
        assertEquals(0, spotter.grammarUpdates)
    }

    @Test
    fun enablingNotesWhileListeningAppliesToNextTrigger() {
        val spotter = ScriptedSpotter(
            mapOf(2 to saveSpot(2 * SEC), 62 to saveSpot(FED_26 + 0.2f)),
            noteResult = words("hospital"),
        )
        val chunks = speech(2) + silence(58) + speech(3) + silence(20)
        val h = Harness(chunks, spotter, dictation = false)
        repeat(30) { h.step() }
        assertNull(h.fired.single().note) // first save: notes were off
        h.pl.updateDictation(true)
        h.runAll()
        assertEquals(2, h.fired.size)
        assertEquals("hospital", h.fired[1].note)
        assertEquals(0, spotter.grammarUpdates)
    }

    // ------------------------------------------------------------------ duplicates

    @Test
    fun repeatedTriggerFinalsDuringCaptureAndCooldownCreateOneRecord() {
        val spotter = ScriptedSpotter(
            mapOf(3 to saveSpot(2 * SEC), 5 to saveSpot(0.2f), 14 to saveSpot(0.2f)),
            noteResult = words("hospital"),
        )
        val h = Harness(speech(3) + silence(30), spotter, dictation = true)
        h.runAll()
        assertEquals(1, h.fired.size)
    }

    @Test
    fun newUtteranceAfterCooldownFiresAgainWithNotesOn() {
        val spotter = ScriptedSpotter(
            mapOf(3 to saveSpot(2 * SEC), 70 to saveSpot(FED_26 + 0.2f)),
            noteResult = words("hospital"),
        )
        val h = Harness(speech(2) + silence(66) + speech(3) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals(2, h.fired.size)
    }

    // ------------------------------------------------------------------ transcription failure

    @Test
    fun unavailableNoteTranscriberStillSavesLocation() {
        val spotter = ScriptedSpotter(mapOf(0 to saveSpot(0.1f)), noteAvailable = false)
        val h = Harness(speech(2) + silence(4), spotter, dictation = true)
        h.step()
        assertEquals(1, h.fired.size)
        assertNull(h.fired[0].note)
    }

    @Test
    fun emptyTranscriptionStillSavesLocationWithNullNote() {
        val spotter = ScriptedSpotter(mapOf(3 to saveSpot(2 * SEC)), noteResult = emptyList())
        val h = Harness(speech(2) + silence(4) + speech(2) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals(1, h.fired.size)
        assertNull(h.fired[0].note)
    }

    @Test
    fun noteIsCappedToMaxWords() {
        val spotter = ScriptedSpotter(mapOf(5 to saveSpot(0.2f)), noteResult = words(*Array(100) { "word$it" }))
        val h = Harness(speech(3) + silence(20), spotter, dictation = true)
        h.runAll()
        assertEquals(64, h.fired.single().note!!.split(" ").size)
    }

    @Test
    fun sourceEndingMidCaptureStillFiresOnce() {
        val spotter = ScriptedSpotter(mapOf(3 to saveSpot(2 * SEC)), noteResult = words("hospital"))
        val h = Harness(speech(2) + silence(3), spotter, dictation = true)
        h.runAll()
        assertEquals(1, h.fired.size)
    }

    // ------------------------------------------------------------------ legacy behaviour

    @Test
    fun unrelatedWordsDoNotFire() {
        val spotter = ScriptedSpotter(mapOf(0 to words("start", "timer")))
        val h = Harness(speech(2), spotter, dictation = false)
        h.step()
        assertEquals(0, h.fired.size)
    }

    @Test
    fun singleWordDoesNotFire() {
        val spotter = ScriptedSpotter(mapOf(0 to words("save")))
        val h = Harness(speech(2), spotter, dictation = false)
        h.step()
        assertEquals(0, h.fired.size)
    }

    @Test
    fun phraseSplitAcrossSegmentsFiresOnce() {
        val spotter = ScriptedSpotter(mapOf(0 to words("save"), 1 to words("spot")))
        val h = Harness(speech(3), spotter, dictation = false)
        h.step(); h.step()
        assertEquals(1, h.fired.size)
    }

    @Test
    fun confidenceIsCalculatedFromMatchedWordsNotTrailingWords() {
        val spotter = ScriptedSpotter(
            mapOf(0 to listOf(w("save", 0f, 0f, 0.9f), w("spot", 0f, 0f, 0.9f), w("yes", 0f, 0f, 0.1f))),
        )
        val h = Harness(speech(2), spotter, dictation = false)
        h.pl.minConfidence = 0.8f
        h.step()
        assertEquals(1, h.fired.size)
    }

    @Test
    fun duplicateUtteranceSuppressedByCooldownNotesOff() {
        val spotter = ScriptedSpotter(mapOf(0 to words("save", "spot"), 1 to words("save", "spot")))
        val h = Harness(speech(3), spotter, dictation = false)
        h.step(); h.step()
        assertEquals(1, h.fired.size)
    }

    @Test
    fun exhaustedSourceStopsProcessing() {
        val h = Harness(speech(1), ScriptedSpotter(emptyMap()), dictation = false)
        assertEquals(true, h.step())
        assertEquals(false, h.step())
    }
}
