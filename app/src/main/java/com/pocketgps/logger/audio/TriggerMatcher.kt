package com.pocketgps.logger.audio

/**
 * Validates recognised text against the configured trigger phrase.
 *
 * Matching is deliberately conservative: the complete trigger must appear as a contiguous run
 * of whole words. Punctuation is normalised to spaces first, then an escaped regex is used so
 * trigger text is treated as literal words rather than executable regex syntax.
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
     * True when [recognized] contains [trigger] as a contiguous sequence of whole words.
     * Regex metacharacters in the supplied phrase are never interpreted as regex syntax.
     */
    fun matches(recognized: String, trigger: String): Boolean {
        val triggerTokens = tokens(trigger)
        if (triggerTokens.size < 2) return false
        val normalizedText = normalize(recognized)
        if (normalizedText.isEmpty()) return false
        val phrasePattern = triggerTokens.joinToString(separator = "\\s+") {
            Regex.escape(it)
        }
        return Regex("(?:^|\\s)$phrasePattern(?:\\s|$)").containsMatchIn(normalizedText)
    }

    /**
     * Returns the first event index of a contiguous trigger, using the same escaped whole-word
     * regex policy as [matches]. Recognition events are normally one word each; normalising here
     * also makes punctuation/case handling consistent if a backend returns decorated tokens.
     */
    fun matchStartIndex(recognized: List<String>, trigger: List<String>): Int {
        if (recognized.isEmpty() || trigger.size < 2) return -1
        val triggerWords = trigger.flatMap(::tokens)
        if (triggerWords.size < 2) return -1

        val normalizedText = normalize(recognized.joinToString(" "))
        if (normalizedText.isEmpty()) return -1
        val phrasePattern = triggerWords.joinToString(separator = "\\s+") {
            Regex.escape(it)
        }
        val match = Regex("(?:^|\\s)($phrasePattern)(?=\\s|$)").find(normalizedText)
            ?: return -1
        val phraseStart = match.groups[1]?.range?.first ?: return -1
        val wordsBefore = normalizedText.substring(0, phraseStart).trim()
        if (wordsBefore.isEmpty()) return 0
        return wordsBefore.split(WHITESPACE).size
    }

    fun matchesTokens(recognized: List<String>, trigger: List<String>): Boolean =
        matchStartIndex(recognized, trigger) >= 0

    /** True when the text is a usable trigger definition (>= 2 words, alphanumeric). */
    fun isValidTrigger(trigger: String): Boolean {
        val t = tokens(trigger)
        return t.size >= 2 && t.all { it.isNotEmpty() }
    }

    /** Returns the matched phrase text if [recognized] satisfies [trigger], else null. */
    fun acceptedPhrase(recognized: String, trigger: String): String? =
        if (matches(recognized, trigger)) normalize(trigger) else null

    /** True when every word of the trigger is present (used only for test diagnostics). */
    fun containsAllWords(recognized: String, trigger: String): Boolean {
        val r = tokens(recognized).toSet()
        return tokens(trigger).all { it in r }
    }
}
