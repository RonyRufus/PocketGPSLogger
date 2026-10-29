package com.pocketgps.logger.audio

import com.google.gson.JsonParser

/**
 * Parses Vosk JSON output. Pure JVM (Gson) so it is unit-testable without Android.
 *
 * Final:   `{"result":[{"conf":0.97,"end":1.23,"start":0.90,"word":"save"}]}`
 * Partial: `{"partial":"save sp"}`
 */
object VoskResultParser {

    fun parseFinal(json: String): List<RecognitionEvent> {
        if (json.isBlank()) return emptyList()
        return runCatching {
            val result = JsonParser.parseString(json).asJsonObject.get("result")
                ?: return emptyList()
            result.asJsonArray.mapNotNull { element ->
                val obj = element.asJsonObject
                val word = obj.get("word")?.asString ?: return@mapNotNull null
                RecognitionEvent(
                    word = word.trim().lowercase(),
                    confidence = obj.get("conf")?.asFloat ?: 0f,
                    startStreamSeconds = obj.get("start")?.asFloat ?: 0f,
                    endStreamSeconds = obj.get("end")?.asFloat ?: 0f,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** Returns the raw partial text, or null on malformed input. */
    fun parsePartial(json: String): String? = runCatching {
        JsonParser.parseString(json).asJsonObject.get("partial")?.asString
    }.getOrNull()

    /** True when the JSON object contains a non-empty `result` array. */
    fun hasFinalResult(json: String): Boolean = runCatching {
        val result = JsonParser.parseString(json).asJsonObject.get("result")
        result != null && result.asJsonArray.size() > 0
    }.getOrDefault(false)
}
