package com.pocketgps.logger.audio

/**
 * Validates recognised text against the configured trigger phrase.
 *
 * Rules:
 *  - Case, punctuation and repeated whitespace are normalised away.
 *  - The trigger must appear as a contiguous run of whole words.
 *  - Regex metacharacters in user-configured phrases are always escaped.
 *  - A trigger must contain at least two words to reduce accidental activations.
 */
object TriggerMatcher {

    private val NON_ALNUM = Regex("[^a-z0-9\\s]")
    private val WHITESPACE = Regex("\\s+")

    /** Lowercase, strip punctuation, collapse whitespace. */
    fun normalize(text: String): String =
        text.lowercase()
            .replace(NON_ALNUM, " ")
            .replace(WHITESPACE, " ")
            .trim()

    fun tokens(text: String): List<String> =
        normalize(text).split(' ').filter { it.isNotEmpty() }

    /**
     * Match a trigger using an escaped regular expression with whole-phrase boundaries.
     * Normalisation happens before regex construction, and every token is escaped, so a
     * configured phrase can never inject regex operators or change the matching rules.
     */
    fun matches(recognized: String, trigger: String): Boolean =
        matchesTokens(tokens(recognized), tokens(trigger))

    fun matchesTokens(recognized: List<String>, trigger: List<String>): Boolean {
        if (recognized.isEmpty() || trigger.size < 2 || recognized.size < trigger.size) return false

        val phrasePattern = trigger.joinToString(separator = "\\s+") { Regex.escape(it) }
        val pattern = Regex("(^|\\s)$phrasePattern(?=\\s|$)")
        return pattern.containsMatchIn(recognized.joinToString(" "))
    }

    /** True when the text is a usable trigger definition (>= 2 words, alphanumeric). */
    fun isValidTrigger(trigger: String): Boolean {
        val t = tokens(trigger)
        return t.size >= 2 && t.all { it.isNotEmpty() }
    }

    /** Returns the normalised trigger definition when accepted, else null. */
    fun acceptedPhrase(recognized: String, trigger: String): String? =
        if (matches(recognized, trigger)) normalize(trigger) else null

    /** True when every word of the trigger is present (used only for test diagnostics). */
    fun containsAllWords(recognized: String, trigger: String): Boolean {
        val r = tokens(recognized).toSet()
        return tokens(trigger).all { it in r }
    }
}
