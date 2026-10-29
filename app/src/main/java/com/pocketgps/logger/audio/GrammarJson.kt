package com.pocketgps.logger.audio

/**
 * Builds the grammar-constrained word list passed to Vosk.
 *
 * For a small grammar-constrained model, Vosk only emits words from this list. Including a
 * phonetic decoy set (plus `[unk]`) absorbs coughs, road noise and unrelated speech so they map
 * to harmless tokens instead of being force-aligned onto the trigger words.
 */
object GrammarJson {

    /** Phonetic absorbers included in every grammar. */
    val DECOYS: List<String> = listOf(
        "no", "wait", "one", "two", "uh", "ah", "hm", "okay", "yes", "[unk]",
    )

    /**
     * Build a JSON array string from the trigger phrase plus [DECOYS].
     * Duplicate words are collapsed, order is preserved.
     */
    fun build(triggerPhrases: List<String>): String {
        val words = linkedSetOf<String>()
        triggerPhrases.forEach { phrase -> wordsOf(phrase).forEach { words.add(it) } }
        DECOYS.forEach { words.add(it) }
        return words.joinToString(prefix = "[", postfix = "]") { "\"${escape(it)}\"" }
    }

    /** Normalise a phrase into its constituent words. */
    fun wordsOf(phrase: String): List<String> {
        if (phrase.isBlank()) return emptyList()
        return TriggerMatcher.normalize(phrase)
            .split(' ')
            .filter { it.isNotEmpty() }
    }

    private fun escape(s: String): String = buildString {
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                else -> append(c)
            }
        }
    }
}
