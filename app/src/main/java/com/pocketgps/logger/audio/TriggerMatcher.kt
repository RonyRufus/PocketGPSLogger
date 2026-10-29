package com.pocketgps.logger.audio

/**
 * Validates recognised text against the configured trigger phrase.
 *
 * Rules (all pure Kotlin, unit-tested):
 *  - Case and harmless punctuation are normalised away.
 *  - The trigger must appear as a **contiguous run of whole words**. A single matching word is
 *    never enough, so "save" alone does not fire "save spot".
 *  - Empty, partial or malformed input is rejected.
 *  - A phrase shorter than two words is rejected as too weak for always-on use.
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
     * True when [recognized] contains [trigger] as a contiguous word sequence.
     * Both arguments are raw text; normalisation happens here.
     */
    fun matches(recognized: String, trigger: String): Boolean =
        matchesTokens(tokens(recognized), tokens(trigger))

    fun matchesTokens(recognized: List<String>, trigger: List<String>): Boolean {
        if (recognized.isEmpty() || trigger.isEmpty()) return false
        if (trigger.size < 2) return false // a lone word is too risky for always-on
        if (recognized.size < trigger.size) return false
        val last = recognized.size - trigger.size
        for (start in 0..last) {
            var ok = true
            for (k in trigger.indices) {
                if (recognized[start + k] != trigger[k]) {
                    ok = false
                    break
                }
            }
            if (ok) return true
        }
        return false
    }

    /** True when the text is a usable trigger definition (>= 2 words, alphanumeric). */
    fun isValidTrigger(trigger: String): Boolean {
        val t = tokens(trigger)
        return t.size >= 2 && t.all { it.isNotEmpty() }
    }

    /**
     * Returns the matched phrase text if [recognized] satisfies [trigger], else null.
     * The returned string is the normalised trigger definition.
     */
    fun acceptedPhrase(recognized: String, trigger: String): String? =
        if (matches(recognized, trigger)) normalize(trigger) else null

    /** True when every word of the trigger is present (used only for test diagnostics). */
    fun containsAllWords(recognized: String, trigger: String): Boolean {
        val r = tokens(recognized).toSet()
        return tokens(trigger).all { it in r }
    }
}
