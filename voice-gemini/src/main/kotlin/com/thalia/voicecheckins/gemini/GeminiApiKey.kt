package com.thalia.voicecheckins.gemini

/**
 * The Gemini API key, held so that it cannot be printed by accident.
 *
 * ADR 0008 decision 4 allows a standard API key on the phone for the M3A spike only, entered at
 * runtime and never logged. This type exists for the "never logged" half: its [toString] never
 * shows the key, it is not a data class, and the only way to read the key is [value], which is
 * internal to this module.
 */
class GeminiApiKey(
    key: String,
) {
    internal val value: String = key.trim()

    init {
        // The messages name the problem and never echo the input.
        require(value.isNotEmpty()) { "the API key must not be blank" }
        require(value.none { it.isWhitespace() }) { "the API key must not contain whitespace" }
    }

    override fun toString(): String = "GeminiApiKey(redacted)"
}
