package com.thalia.voicecheckins.voice

/** Who said a line. */
enum class Speaker {
    THALIA,
    USER,
}

/**
 * One settled utterance.
 *
 * Only finalised text appears here. Partial, still-changing recognition output is a
 * provider implementation detail and is not part of this contract.
 */
data class TranscriptLine(
    val speaker: Speaker,
    val text: String,
) {
    init {
        require(text.isNotBlank()) { "transcript line must carry text" }
    }
}
