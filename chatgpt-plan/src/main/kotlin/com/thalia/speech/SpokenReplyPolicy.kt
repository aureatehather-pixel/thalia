package com.thalia.speech

/** How a conversation was started. A voice session speaks its replies by default; a typed one stays quiet. */
enum class SessionMode { VOICE, TYPED }

/** What happened in the conversation that might be spoken. */
sealed interface SpeechEvent {
    /** The assistant's text, complete. Nothing has been executed by it. */
    data class Reply(
        val text: String,
    ) : SpeechEvent

    /** A proposal is waiting for a tap. Its content is shown, never read out. */
    data class ProposalWaiting(
        val count: Int,
    ) : SpeechEvent

    /** Thalia's own execution finished. Only ever raised after the store returned: [succeeded] true means the write committed. */
    data class Executed(
        val succeeded: Boolean,
    ) : SpeechEvent

    /** The person asked to hear a line again. The only way a typed session ever speaks. */
    data class Replay(
        val text: String,
    ) : SpeechEvent
}

/**
 * What Thalia says aloud. Spoken replies are the default only in a voice session; a typed session is quiet unless the person
 * replays a line; mute silences everything but the person's own replay. **Nothing here ever says that something is done, saved or
 * added except [SpeechEvent.Executed] with `succeeded` true**, and a model's own claim of completion is dropped from what is read,
 * because a model cannot know. Proposal contents are not read, only that one is waiting; approval stays a tap.
 */
class SpokenReplyPolicy(
    var mode: SessionMode,
    var muted: Boolean = false,
) {
    fun lines(event: SpeechEvent): List<String> =
        when (event) {
            is SpeechEvent.Replay -> clean(event.text, dropClaims = true)
            is SpeechEvent.Reply -> if (mode == SessionMode.VOICE && !muted) clean(event.text, dropClaims = true) else emptyList()
            is SpeechEvent.ProposalWaiting ->
                if (mode == SessionMode.VOICE && !muted && event.count > 0) {
                    listOf(
                        if (event.count ==
                            1
                        ) {
                            "I have a proposal. Tap to review it."
                        } else {
                            "I have ${event.count} proposals. Tap to review them."
                        },
                    )
                } else {
                    emptyList()
                }
            is SpeechEvent.Executed ->
                if (mode == SessionMode.VOICE && !muted) {
                    listOf(if (event.succeeded) "Done." else "That did not go through. Everything is as it was.")
                } else {
                    emptyList()
                }
        }

    companion object {
        const val MAX_CHARS = 500
        private val claim =
            Regex(
                """(?i)\b(done|saved|added|created|updated|scheduled|recorded|marked|completed|deleted|removed|set|booked|changed|finished|logged|stored|submitted|applied|approved|moved)\b|\bi(?:'ve| have| did| will)\b|\ball set\b""",
            )
        private val link = Regex("""\[([^\]]*)]\([^)]*\)""")
        private val markup = Regex("""[*_`#>]+|https?://\S+""")

        /** Plain sentences, without markup or links, without any sentence that claims something was carried out, and cut short. */
        fun clean(
            text: String,
            dropClaims: Boolean,
        ): List<String> {
            val plain =
                text
                    .replace(link, "$1")
                    .replace(markup, " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
            val sentences =
                Regex("""[^.!?]+[.!?]?""")
                    .findAll(plain)
                    .map { it.value.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
            val kept = if (dropClaims) sentences.filterNot { claim.containsMatchIn(it) } else sentences
            var used = 0
            val out = mutableListOf<String>()
            for (sentence in kept) {
                if (used + sentence.length > MAX_CHARS) break
                out += sentence
                used += sentence.length
            }
            return out
        }
    }
}
