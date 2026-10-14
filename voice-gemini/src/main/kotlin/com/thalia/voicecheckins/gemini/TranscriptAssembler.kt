package com.thalia.voicecheckins.gemini

import com.thalia.voicecheckins.voice.Speaker
import com.thalia.voicecheckins.voice.TranscriptLine

/**
 * Turns Gemini's transcription fragments into settled transcript lines.
 *
 * Gemini transcribes both directions in pieces. What the user said is settled when the model
 * starts answering, and both sides are settled when a turn completes or is interrupted, or when
 * the session ends. Only settled lines leave here, as the `:core` contract requires.
 */
internal class TranscriptAssembler {
    private val fromUser = StringBuilder()
    private val fromThalia = StringBuilder()
    private val settled = mutableListOf<TranscriptLine>()

    /** Every line settled so far, in order. */
    val lines: List<TranscriptLine> get() = settled.toList()

    /** Applies one content message and returns the lines it settled, in order. */
    fun accept(
        input: String?,
        output: String?,
        modelSpoke: Boolean,
        turnEnded: Boolean,
    ): List<TranscriptLine> {
        val newLines = mutableListOf<TranscriptLine>()
        input?.let { fromUser.append(it) }
        if (modelSpoke || output != null) take(Speaker.USER, fromUser, newLines)
        output?.let { fromThalia.append(it) }
        if (turnEnded) {
            take(Speaker.USER, fromUser, newLines)
            take(Speaker.THALIA, fromThalia, newLines)
        }
        return newLines
    }

    /** Settles whatever is left, because a half-finished line is still worth keeping. */
    fun finish(): List<TranscriptLine> =
        mutableListOf<TranscriptLine>().also {
            take(Speaker.USER, fromUser, it)
            take(Speaker.THALIA, fromThalia, it)
        }

    private fun take(
        speaker: Speaker,
        buffer: StringBuilder,
        into: MutableList<TranscriptLine>,
    ) {
        val text = buffer.toString().trim()
        buffer.setLength(0)
        if (text.isEmpty()) return
        val line = TranscriptLine(speaker, text)
        settled += line
        into += line
    }
}
