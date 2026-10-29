package com.pocketgps.logger.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceGateTest {

    private fun gate() = VoiceGate()

    @Test
    fun silenceKeepsGateClosed() {
        val g = gate()
        repeat(100) { assertFalse(g.process(0.0001f)) }
    }

    @Test
    fun loudChunkOpensGate() {
        val g = gate()
        assertTrue(g.process(VoiceGate.DEFAULT_OPEN_RMS + 0.01f))
    }

    @Test
    fun gateStaysOpenThroughTailThenCloses() {
        val g = gate()
        assertTrue(g.process(0.05f)) // speech
        // Quiet chunks inside the tail window keep the gate open…
        repeat(VoiceGate.DEFAULT_TAIL_CHUNKS) { assertTrue(g.process(0.0001f)) }
        // …after the tail is exhausted the gate closes.
        assertFalse(g.process(0.0001f))
    }

    @Test
    fun weakAudioInsideUtteranceKeepsGateOpen() {
        val g = gate()
        assertTrue(g.process(0.05f)) // opens
        // Consume half the tail window…
        repeat(VoiceGate.DEFAULT_TAIL_CHUNKS / 2) { assertTrue(g.process(0.0001f)) }
        // …then a weakly-active chunk (word gap inside an utterance) resets the window…
        assertTrue(g.process(0.012f))
        // …so a full tail's worth of quiet chunks still gets decoded.
        repeat(VoiceGate.DEFAULT_TAIL_CHUNKS) { assertTrue(g.process(0.0001f)) }
        // …and only after the refreshed window is exhausted does the gate close.
        assertFalse(g.process(0.0001f))
    }

    @Test
    fun hysteresisDoesNotChatterAroundThreshold() {
        val g = gate()
        // Alternate just above close (not open) threshold: the gate should neither reopen
        // after closing nor waste the tail.
        assertTrue(g.process(0.05f))
        repeat(VoiceGate.DEFAULT_TAIL_CHUNKS) { g.process(0.0001f) }
        assertFalse(g.process(0.0001f)) // closed
        assertFalse(g.process(0.012f)) // weak signal while closed does not reopen
        assertTrue(g.process(0.05f)) // real speech reopens
    }

    @Test
    fun sustainedNoiseForceClosesToBoundCpuUse() {
        val g = gate()
        // Constant wind/engine-level energy above the open threshold.
        repeat(VoiceGate.DEFAULT_MAX_OPEN_CHUNKS) { g.process(0.05f) }
        // The guard must have closed the gate despite the "loud" input.
        assertFalse(g.open)
        // Silence now confirms closure…
        assertFalse(g.process(0.0001f))
        // …but speech after quiet reopens normally.
        assertTrue(g.process(0.05f))
    }

    @Test
    fun forceCloseClosesImmediatelyAndSpeechReopens() {
        val g = gate()
        assertTrue(g.process(0.05f))
        g.forceClose()
        assertFalse(g.open)
        // One quiet chunk while closed stays closed…
        assertFalse(g.process(0.0001f))
        // …and the next loud chunk reopens from scratch.
        assertTrue(g.process(0.05f))
    }

    @Test
    fun reopensOnlyAboveOpenThreshold() {
        val g = gate()
        g.forceClose()
        // Between close and open thresholds: not enough to open a closed gate.
        assertFalse(g.process(0.014f))
        assertTrue(g.process(0.02f))
    }
}
