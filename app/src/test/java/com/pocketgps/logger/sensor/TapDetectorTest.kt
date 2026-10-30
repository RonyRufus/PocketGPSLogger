package com.pocketgps.logger.sensor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TapDetectorTest {

    /** Manual clock so tests are deterministic. */
    private var nowMs = 10_000L
    private val clock: () -> Long = { nowMs }

    private fun detector(spikeG: Float = 2.2f) = TapDetector(spikeG = spikeG, clock = clock)

    /** Simulate a single impulse: one above-threshold sample at [atMs]. */
    private fun TapDetector.tap(atMs: Long, g: Float = 3.0f): Int {
        nowMs = atMs
        return feed(atMs, g)
    }

    /** Feed resting-gravity samples every 20 ms between the two timestamps. */
    private fun TapDetector.quiet(fromMs: Long, toMs: Long) {
        var t = fromMs
        while (t <= toMs) {
            nowMs = t
            feed(t, 0.95f)
            t += 20
        }
    }

    @Test
    fun `two clean spikes confirm exactly one double tap`() {
        val d = detector()
        assertEquals(0, d.tap(10_000))
        assertEquals(0, d.tap(10_150))
        d.quiet(10_170, 10_700)
        assertEquals(1, d.doubleTaps)
        assertEquals(10_150 + 420, d.lastDoubleTapAtMs)
    }

    @Test
    fun `single spike is never a save`() {
        val d = detector()
        d.tap(10_000)
        d.quiet(10_020, 10_900)
        assertEquals(0, d.doubleTaps)
        assertEquals(1, d.spikesSeen)
    }

    @Test
    fun `sustained vibration is cancelled not saved`() {
        val d = detector()
        // Bike / engine rumble: many spikes in quick succession.
        var t = 10_000L
        repeat(8) {
            d.tap(t)
            t += 40
        }
        d.quiet(t, t + 1_000)
        assertEquals(0, d.doubleTaps)
    }

    @Test
    fun `spikes further apart than gapMax are two single taps`() {
        val d = detector()
        d.tap(10_000)
        assertEquals(0, d.tap(10_500)) // 500 ms > 380 ms gap → starts a new attempt
        d.quiet(10_520, 11_200)
        assertEquals(0, d.doubleTaps)
    }

    @Test
    fun `debounce swallows immediate repeat taps`() {
        val d = detector()
        d.tap(10_000)
        d.tap(10_150)
        d.quiet(10_170, 10_700) // confirmed at ~10_570
        assertEquals(1, d.doubleTaps)
        // A second double tap inside the debounce window must be ignored.
        d.tap(10_800)
        d.tap(10_950)
        d.quiet(10_970, 11_600)
        assertEquals(1, d.doubleTaps)
        // After the debounce elapses, taps work again.
        d.tap(12_000)
        d.tap(12_150)
        d.quiet(12_170, 12_700)
        assertEquals(2, d.doubleTaps)
    }

    @Test
    fun `below threshold samples are not spikes`() {
        val d = detector(spikeG = 2.2f)
        d.tap(10_000, g = 2.0f)
        d.tap(10_150, g = 2.0f)
        d.quiet(10_170, 10_700)
        assertEquals(0, d.spikesSeen)
        assertEquals(0, d.doubleTaps)
    }

    @Test
    fun `gentle preset accepts lighter taps than firm preset`() {
        val gentle = detector(spikeG = TapDetector.spikeFor(TapSensitivity.GENTLE))
        val firm = detector(spikeG = TapDetector.spikeFor(TapSensitivity.FIRM))
        val light = 2.0f
        gentle.tap(10_000, light)
        gentle.tap(10_150, light)
        gentle.quiet(10_170, 10_700)
        firm.tap(20_000, light)
        firm.tap(20_150, light)
        firm.quiet(20_170, 20_700)
        assertEquals(1, gentle.doubleTaps)
        assertEquals(0, firm.doubleTaps)
    }

    @Test
    fun `reset clears pending pattern`() {
        val d = detector()
        d.tap(10_000)
        d.reset()
        assertEquals(0, d.tap(10_100))
        d.quiet(10_120, 10_700)
        assertEquals(0, d.doubleTaps)
    }

    @Test
    fun `three spikes inside window cancel the pattern`() {
        val d = detector()
        d.tap(10_000)
        d.tap(10_150)
        assertEquals(0, d.tap(10_300)) // third impact → classified as rumble
        d.quiet(10_320, 11_000)
        assertEquals(0, d.doubleTaps)
    }

    @Test
    fun `repeated use over a long session stays consistent`() {
        val d = detector()
        var t = 10_000L
        repeat(1_000) {
            d.tap(t)
            d.tap(t + 150)
            d.quiet(t + 170, t + 700)
            t += 2_500 // fully past debounce
        }
        assertEquals(1_000, d.doubleTaps)
        assertTrue(d.spikesSeen >= 2_000)
    }
}
