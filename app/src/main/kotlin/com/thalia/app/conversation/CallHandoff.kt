package com.thalia.app.conversation

import android.content.Context
import android.content.Intent
import com.thalia.app.checkin.GuidedCheckInActivity
import com.thalia.voicecheckins.domain.CheckInType

/**
 * Slice 4C: answering a scheduled opening or closing call opens the text conversation with the matching message prefilled and
 * not sent. It is a call handoff, not a phone conversation: no audio, no spoken replies and nothing recorded. Answering never
 * opens or closes the working day; that stays on the day screen.
 */
internal object CallHandoff {
    /** The check-in type that opened the conversation, on the conversation's intent. Absent for every other way in. */
    const val EXTRA_TYPE = "com.thalia.app.conversation.CALL_HANDOFF"

    fun draft(type: CheckInType): String =
        when (type) {
            CheckInType.OPENING -> ConversationActivity.OPENING_DRAFT
            CheckInType.CLOSING -> ConversationActivity.CLOSING_DRAFT
        }

    /**
     * Starts a conversation of its own, in its own task, so it never lives in the call's task and is never shown over the lock
     * screen. MULTIPLE_TASK matters: without it the system brings an existing conversation task to the front and drops this
     * intent, so a second call would show the first call's screen with the wrong message. With it, an earlier conversation
     * and its edited draft are left untouched in their own task.
     */
    fun intent(
        context: Context,
        type: CheckInType,
        aiUsable: Boolean = true,
    ): Intent =
        (
            if (aiUsable) {
                Intent(context, ConversationActivity::class.java)
                    .putExtra(ConversationActivity.EXTRA_DRAFT, draft(type))
                    .putExtra(EXTRA_TYPE, type.name)
            } else {
                // AI is off or unavailable: the structured check-in opens instead. It only reads until a tap.
                GuidedCheckInActivity.intent(context, type)
            }
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)

    fun typeOf(intent: Intent): CheckInType? =
        intent.getStringExtra(EXTRA_TYPE)?.let { name ->
            CheckInType.entries.firstOrNull {
                it.name ==
                    name
            }
        }

    /** The line that labels this screen as a call handoff. It claims nothing about the day. */
    fun notice(type: CheckInType): String =
        when (type) {
            CheckInType.OPENING ->
                "Call handoff from your opening call. This is not a phone conversation: nothing was said. The call itself is " +
                    "recorded as answered, but answering did not open your day or record any outcome. No planning changes were " +
                    "made. The message below is a suggestion that has not been sent. Open the day from the day screen."
            CheckInType.CLOSING ->
                "Call handoff from your closing call. This is not a phone conversation: nothing was said. The call itself is " +
                    "recorded as answered, but answering did not close your day or record any outcome. No planning changes were " +
                    "made. The message below is a suggestion that has not been sent. Anything you do not report stays unknown. " +
                    "Close the day from the day screen."
        }
}

/** The call's message goes in the box once, when the screen is first made. A restored screen starts with an empty box. */
internal fun initialDraft(
    fromIntent: String?,
    restored: Boolean,
): String = if (restored) "" else fromIntent.orEmpty()
