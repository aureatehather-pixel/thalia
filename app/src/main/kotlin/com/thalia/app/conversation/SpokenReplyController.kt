package com.thalia.app.conversation

import com.thalia.app.ai.AudioPlayback
import com.thalia.app.ai.ConversationPrefs
import com.thalia.app.ai.SpeechProblem
import com.thalia.app.ai.SpokenReplies
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** What the speech row shows: whether Thalia is speaking now, the muted switch, and the reason the last reply was not spoken. */
internal data class SpeechUi(
    val speaking: Boolean = false,
    val muted: Boolean = false,
    val note: String? = null,
)

/**
 * Reads Thalia's own new lines aloud, one utterance per change of the conversation, and only lines that were already on screen
 * as text: a question, a proposal waiting for the Approve tap, what was saved, what was refused. Words the person said or typed
 * are never spoken back. Entries that existed when this was created are not read, and a newer utterance replaces one still playing.
 *
 * Speaking never gates anything: if it cannot happen the reason is shown and the text on screen is the whole answer. It never
 * approves, and a spoken or typed "yes" is handled by the session exactly as before.
 */
internal class SpokenReplyController(
    private val replies: SpokenReplies,
    private val playback: AudioPlayback,
    private val prefs: ConversationPrefs,
    private val scope: CoroutineScope,
) {
    private val state = MutableStateFlow(SpeechUi(muted = !prefs.speakReplies))
    val ui: StateFlow<SpeechUi> = state

    private var spokenUpTo: Int? = null
    private var job: Job? = null

    /** Called with every new conversation state. */
    fun onState(conversation: ConversationState) {
        val last = conversation.entries.maxOfOrNull { it.id } ?: -1
        val from = spokenUpTo
        spokenUpTo = last
        // The first call only marks what is already there.
        if (from == null || last <= from) return
        if (!prefs.speakReplies) return
        val fresh = conversation.entries.filter { it.id > from }
        val text = spokenText(Presentation.rows(fresh)) ?: return
        speak(text)
    }

    private fun speak(text: String) {
        job?.cancel()
        state.value = state.value.copy(speaking = true, note = null)
        job =
            scope.launch {
                val problem =
                    try {
                        replies.speak(text)
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SpeechProblem.SERVICE
                    }
                state.value = state.value.copy(speaking = false, note = problem?.text?.let { "Not spoken: $it" })
            }
    }

    fun stop() {
        job?.cancel()
        replies.stop()
        state.value = state.value.copy(speaking = false)
    }

    /** The mute switch. Muting stops what is speaking. */
    fun setMuted(muted: Boolean) {
        prefs.setSpeakReplies(!muted)
        if (muted) stop()
        state.value = state.value.copy(muted = muted, note = if (muted) null else state.value.note)
    }

    fun release() {
        job?.cancel()
        replies.stop()
        playback.stop()
    }

    companion object {
        /**
         * What is worth saying for these new rows, or null for nothing. Kotlin's own lines come first and are the status; the model's
         * long explanations are left on screen. A proposal is followed by what to do, so the person is never told something is done.
         */
        fun spokenText(rows: List<RowView>): String? {
            val parts =
                rows.mapNotNull { row ->
                    when (row.style) {
                        RowStyle.YOU, RowStyle.EXPLANATION, RowStyle.STATUS -> null
                        RowStyle.PROPOSED -> row.lines.joinToString(" ") + " Tap Approve to apply it, or Dismiss."
                        RowStyle.SAVED -> row.spoken ?: row.lines.joinToString(" ")
                        else -> row.lines.joinToString(" ")
                    }?.trim()?.takeIf { it.isNotEmpty() }
                }
            return parts.joinToString(" ").takeIf { it.isNotBlank() }
        }
    }
}
