package com.pocketgps.logger.audio

/**
 * Validates recognised text against the configured trigger phrase(s) and, in dictation mode,
 * splits the utterance into trigger and observation text.
 *
 * Matching is deliberately conservative: the complete trigger must appear as a contiguous run
 * of whole words. Punctuation is normalised to spaces first, then an escaped regex is used so
 * trigger text is treated as literal words rather than executable regex syntax.
 *
 * This object is the single matching policy: [matches], [matchStartIndex] and [findFirstMatch]
 * all go through the same escaped whole-word regex, and [extractAfter] derives the dictated
 * note from the same word offsets, so detection and extraction can never disagree.
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
     * A trigger occurrence inside recognised text: which configured trigger matched
     * ([triggerIndex]), and where in the recognised word list it starts ([wordStart]) and how
     * many words it spans ([wordCount]).
     */
    data class MultiMatch(val triggerIndex: Int, val wordStart: Int, val wordCount: Int)

    /**
     * True when [recognized] contains [trigger] as a contiguous sequence of whole words.
     * Regex metacharacters in the supplied phrase are never interpreted as regex syntax.
     */
    fun matches(recognized: String, trigger: String): Boolean {
        val triggerTokens = tokens(trigger)
        if (triggerTokens.size < 2) return false
        val normalizedText = normalize(recognized)
        if (normalizedText.isEmpty()) return false
        return phraseRegex(triggerTokens).containsMatchIn(normalizedText)
    }

    /**
     * Returns the first event index of a contiguous trigger, using the same escaped whole-word
     * regex policy as [matches]. Recognition events are normally one word each; normalising here
     * also makes punctuation/case handling consistent if a backend returns decorated tokens.
     */
    fun matchStartIndex(recognized: List<String>, trigger: List<String>): Int =
        matchInNormalizedText(normalize(recognized.joinToString(" ")), trigger) ?: -1

    /**
     * Find the earliest occurrence of any configured trigger in the recognised words.
     * Ties on word position resolve to the trigger declared first, so multi-trigger behaviour
     * is deterministic. Returns null when nothing matches.
     */
    fun findFirstMatch(recognizedWords: List<String>, triggers: List<List<String>>): MultiMatch? {
        if (recognizedWords.isEmpty()) return null
        val normalizedText = normalize(recognizedWords.joinToString(" "))
        if (normalizedText.isEmpty()) return null
        var best: MultiMatch? = null
        triggers.forEachIndexed { index, trigger ->
            val start = matchInNormalizedText(normalizedText, trigger) ?: return@forEachIndexed
            val candidate = MultiMatch(index, start, wordCountOf(trigger))
            val current = best
            if (current == null || start < current.wordStart) best = candidate
        }
        return best
    }

    /**
     * The dictated observation: the words of [recognizedWords] that follow the matched trigger.
     * Words spoken before the trigger are dropped (they are typically address words such as
     * "okay" or "please"); the raw utterance is preserved separately by the caller.
     */
    fun extractAfter(recognizedWords: List<String>, match: MultiMatch): String? {
        val end = match.wordStart + match.wordCount
        if (end >= recognizedWords.size) return null
        val remainder = recognizedWords.drop(end).joinToString(" ").trim()
        return remainder.takeIf { it.isNotEmpty() }
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

    // ------------------------------------------------------------------ internals

    /**
     * Word offset of the first contiguous [trigger] occurrence inside the already-normalised
     * [normalizedText], or null. Escaped whole-word policy: the trigger words are matched
     * literally with regex metacharacters escaped, bounded by whitespace/string edges.
     */
    private fun matchInNormalizedText(normalizedText: String, trigger: List<String>): Int? {
        val triggerWords = trigger.flatMap(::tokens)
        if (triggerWords.size < 2 || normalizedText.isEmpty()) return null
        val match = phraseRegex(triggerWords).find(normalizedText) ?: return null
        val phraseStart = match.groups[1]?.range?.first ?: return null
        val wordsBefore = normalizedText.substring(0, phraseStart).trim()
        if (wordsBefore.isEmpty()) return 0
        return wordsBefore.split(WHITESPACE).size
    }

    private fun wordCountOf(trigger: List<String>): Int = trigger.flatMap(::tokens).size

    /** Escaped whole-word pattern for a tokenised trigger; group 1 is the matched phrase. */
    private fun phraseRegex(triggerWords: List<String>): Regex =
        Regex("(?:^|\\s)(" + triggerWords.joinToString(separator = "\\s+") { Regex.escape(it) } + ")(?=\\s|$)")
}
