package com.thalia.planning

/**
 * Where a captured item stands. ADR 0012. OPEN is the Inbox; every other state has left it. [ACTION] items became an
 * action and are not moved back, because the action stays behind; the other filed states can return to OPEN.
 */
enum class InboxState {
    OPEN,
    ACTION,
    NOTE,
    IDEA,
    ARCHIVED,
    DISCARDED,
    ;

    /** Filed away and still held as a row, so it can be put back in the Inbox. */
    val canReopen: Boolean get() = this == NOTE || this == IDEA || this == ARCHIVED || this == DISCARDED

    /** A note or an idea keeps an editable [InboxItem.body]. */
    val hasBody: Boolean get() = this == NOTE || this == IDEA
}

/** The limit on a capture, and the rule that an excess is rejected, never cut. */
object InboxText {
    /** Generous on purpose: a capture is whatever the person had in mind, not a title. */
    const val MAX = 10_000

    /** Why [raw] cannot be kept, in words for the person, or null. Nothing is trimmed, so a refusal never changes text. */
    fun problem(raw: String): String? =
        when {
            raw.isBlank() -> "There is nothing to capture."
            raw.length > MAX ->
                "That is ${raw.length} characters and the limit is $MAX. Nothing was saved and nothing was cut. Shorten it or split it."
            else -> null
        }
}

/**
 * One captured thought. It is not an action, not a commitment and never part of a day's plan. [text] is what the person
 * entered, exactly, and never changes. [body] is the editable text of a note or an idea, and starts as [text]; it is kept
 * if the item is put back in the Inbox, so edited work is never lost. [actionId] is set exactly when [state] is ACTION.
 * [resolvedAtMillis] is set exactly when the item is not OPEN. Creation and last-change times are stored beside it.
 */
data class InboxItem(
    val id: String,
    val text: String,
    val state: InboxState = InboxState.OPEN,
    val body: String? = null,
    val actionId: String? = null,
    val resolvedAtMillis: Long? = null,
) {
    init {
        require(id.isNotBlank()) { "an inbox item needs an id" }
        InboxText.problem(text)?.let { throw IllegalArgumentException(it) }
        body?.let { b -> InboxText.problem(b)?.let { throw IllegalArgumentException(it) } }
        require((state == InboxState.ACTION) == (actionId != null)) { "an inbox item has an action exactly when it became one" }
        require((state == InboxState.OPEN) == (resolvedAtMillis == null)) { "an inbox item is resolved exactly when it is not open" }
        require(!state.hasBody || body != null) { "a note or an idea has a body" }
    }
}

/**
 * A deliberately blunt hint that a capture may be a reminder. It is not detection: a miss and a false alarm are both
 * expected, so the words only ever say "may". Thalia schedules nothing for a capture unless the person sets a reminder for it, whatever this says.
 */
object ReminderHint {
    const val TEXT = "This may be a reminder. Unless you set one under Reminders, nothing is scheduled for it."

    private val pattern =
        Regex(
            """\bremind(er|ers)?\b|\bdon'?t forget\b|\balarm\b|\bat \d{1,2}(:\d{2})?\s?(am|pm)\b|\bin \d+ (minutes?|hours?)\b""",
            RegexOption.IGNORE_CASE,
        )

    fun mayBeReminder(text: String): Boolean = pattern.containsMatchIn(text)
}

/** The action an Inbox item becomes. Derived from the item, so converting the same item twice means the same action. */
object InboxConversion {
    fun actionIdFor(itemId: String): String =
        java.util.UUID
            .nameUUIDFromBytes("thalia-inbox-action:$itemId".toByteArray())
            .toString()
}
