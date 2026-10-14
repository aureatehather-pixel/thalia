package com.thalia.app.conversation

import com.thalia.conversation.ConversationContext

/**
 * What the person reads, apart from the session so it is tested. The model and the validator write in the snapshot's short
 * references (`a2`, `i1`, `p1`), which mean something only to the model: a reference is replaced by the title it points at, and
 * one that points at nothing becomes "that item". A proof-data "Synthetic: " prefix is not shown. A capture is never quoted in
 * full, only its opening.
 */
internal object ReplyText {
    private val ref = Regex("""\b[aip]\d+\b""")

    /** The model often writes the reference and then the title: `a2, “Title,”`. The title is said once. */
    private val quotedAfterRef = Regex("""\b([aip]\d+)\b,?\s*[“"]([^”"]+)[”"]""")

    const val NO_ANSWER = "I did not get an answer I can use, so nothing changed."
    const val NO_CHANGES = "No planning changes were applied."
    const val AI_RESPONSE = "AI response: "
    const val AI_EXPLANATION = "AI explanation: "

    /** Removes the proof-data prefix that must not reach the person. */
    fun display(text: String): String = text.replace("Synthetic: ", "")

    /** Replaces every reference in [text] with a title; see the object comment. */
    fun titled(
        text: String,
        context: ConversationContext,
    ): String {
        fun titleOf(reference: String): String? =
            context.action(reference)?.action?.title
                ?: context.inboxItem(reference)?.let { opening(it.item.text) }
                ?: context.project(reference)?.project?.title
        val once =
            quotedAfterRef.replace(text) { m ->
                val title = titleOf(m.groupValues[1])
                if (title != null && m.groupValues[2].trimEnd(',', ' ') == title) "\"$title\"" else m.value
            }
        return display(ref.replace(once) { m -> titleOf(m.value)?.let { "\"$it\"" } ?: "that item" })
    }

    /** The model's words when it only talked. They are the model's, not a status. */
    fun response(
        text: String,
        context: ConversationContext,
    ) = AI_RESPONSE + titled(text, context)

    /** The model's words beside something Kotlin decided. The decision's own line is the status, never these words. */
    fun explanation(
        text: String,
        context: ConversationContext,
    ) = AI_EXPLANATION + titled(text, context)

    fun opening(text: String): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        return if (flat.length > 40) flat.take(40).trimEnd() + "..." else flat
    }
}
