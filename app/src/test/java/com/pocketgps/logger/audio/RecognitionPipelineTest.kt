package com.pocketgps.logger.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionPipelineTest {

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

    private class ScriptedSpotter(private val script: List<List<RecognitionEvent>>) : KeywordSpotter {
        private var call = 0
        override val sampleRate: Int = 16_000
        override fun acceptWaveForm(
            pcm: ShortArray,
            offset: Int,
            length: Int,
        ): List<RecognitionEvent> {
            val result = script.getOrElse(call) { emptyList() }
            call++
            return result
        }

        override fun partialResult(): String? = null
        override fun streamSeconds(): Float = call * 0.128f
        override fun updateGrammar(phrases: List<String>) = Unit
        override fun close() = Unit
    }

    private fun word(text: String) = RecognitionEvent(
        word = text,
        confidence = 0.9f,
        startStreamSeconds = 0f,
        endStreamSeconds = 0f,
    )

    private fun pipeline(
        script: List<List<RecognitionEvent>>,
        clock: () -> Long,
    ): Pair<RecognitionPipeline, MutableList<Long>> {
        val chunks = List(script.size) { ShortArray(2048) { 1 } }
        val triggers = mutableListOf<Long>()
        val pl = RecognitionPipeline(
            audio = FakeAudioSource(chunks),
            spotter = ScriptedSpotter(script),
            initialTriggerPhrase = "save spot",
            clock = clock,
            cooldownSeconds = 3,
        )
        pl.onTrigger = { ns, _, _ -> triggers.add(ns) }
        return pl to triggers
    }

    @Test
    fun matchingPhraseFiresOnce() {
        var now = 0L
        val (pl, triggers) = pipeline(
            script = listOf(listOf(word("save"), word("spot"))),
            clock = { now },
        )
        pl.processOneChunk()
        assertEquals(1, triggers.size)
    }

    @Test
    fun unrelatedWordsDoNotFire() {
        var now = 0L
        val (pl, triggers) = pipeline(
            script = listOf(listOf(word("start"), word("timer"))),
            clock = { now },
        )
        pl.processOneChunk()
        assertEquals(0, triggers.size)
    }

    @Test
    fun singleWordDoesNotFire() {
        var now = 0L
        val (pl, triggers) = pipeline(
            script = listOf(listOf(word("save"))),
            clock = { now },
        )
        pl.processOneChunk()
        assertEquals(0, triggers.size)
    }

    @Test
    fun phraseSplitAcrossSegmentsFiresOnce() {
        var now = 0L
        val (pl, triggers) = pipeline(
            script = listOf(
                listOf(word("save")),
                listOf(word("spot")),
            ),
            clock = { now },
        )
        pl.processOneChunk()
        pl.processOneChunk()
        assertEquals(1, triggers.size)
    }

    @Test
    fun duplicateUtteranceSuppressedByCooldown() {
        var now = 1_000_000_000L
        val (pl, triggers) = pipeline(
            script = listOf(
                listOf(word("save"), word("spot")),
                listOf(word("save"), word("spot")),
            ),
            clock = { now },
        )
        pl.processOneChunk()
        // Second utterance arrives 1 second later: still inside the 3 s cooldown.
        now += 1_000_000_000L
        pl.processOneChunk()
        assertEquals(1, triggers.size)
    }

    @Test
    fun newUtteranceAfterCooldownFires() {
        var now = 1_000_000_000L
        val (pl, triggers) = pipeline(
            script = listOf(
                listOf(word("save"), word("spot")),
                listOf(word("save"), word("spot")),
            ),
            clock = { now },
        )
        pl.processOneChunk()
        now += 4_000_000_000L // past the 3 s cooldown
        pl.processOneChunk()
        assertEquals(2, triggers.size)
    }

    @Test
    fun exhaustedSourceStopsProcessing() {
        var now = 0L
        val (pl, _) = pipeline(script = listOf(listOf(word("save"), word("spot"))), clock = { now })
        assertEquals(true, pl.processOneChunk())
        // Second call has no more audio: read returns -1 and the source is exhausted.
        assertEquals(false, pl.processOneChunk())
    }
}
