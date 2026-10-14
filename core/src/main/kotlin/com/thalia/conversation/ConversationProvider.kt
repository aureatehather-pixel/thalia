package com.thalia.conversation

/**
 * The seam to whichever AI service answers. Gemini is the first implementation, in `:voice-gemini`; nothing here
 * names it. A provider turns a [ProviderRequest] into text and function calls. It never touches Thalia's data: what
 * it returns is parsed and validated by Kotlin before anything happens.
 */
interface ConversationProvider {
    suspend fun respond(request: ProviderRequest): ProviderResponse
}

enum class TurnRole { USER, ASSISTANT }

data class Turn(
    val role: TurnRole,
    val text: String,
)

data class ProviderRequest(
    val instruction: String,
    /** [ConversationContext.render], for this one request. */
    val contextText: String,
    /** The latest turns, oldest first, ending with the user's new message. */
    val turns: List<Turn>,
    /** The tool declarations as a JSON array, [ConversationTools.DECLARATIONS_JSON]. */
    val toolsJson: String,
)

enum class ProviderFailure {
    /** AI is not set up on this phone, or this build cannot reach it. Manual planning is unaffected. */
    UNAVAILABLE,

    /** Slice 4A gates. Each is decided locally before anything is sent, so no request was made. */
    CONSENT_NEEDED,
    KEY_MISSING,
    BUDGET_EXHAUSTED,
    TOO_LARGE,
    BUSY,
    LEDGER_UNWRITABLE,
    NETWORK,
    AUTH,
    RATE_LIMITED,

    /** A 5xx other than a documented unbilled 500: the service itself failed or was overloaded. */
    SERVER,

    /** HTTP 404: the service did not find the address or the model. A setup or model-availability problem, not an outage. */
    NOT_FOUND,

    /** HTTP 413: the service says the request it received was too large. The request was sent, unlike [TOO_LARGE]. */
    REQUEST_TOO_LARGE,

    /** Any other status that is not success (408, 3xx, 409, 418 and so on). The status itself is in [ProviderResponse.Failed.httpStatus]. */
    UNEXPECTED_STATUS,

    /** The provider declined to answer, or cut the answer off. */
    BLOCKED,

    /** The reply could not be read at all. The request was processed, so it may have been billed. */
    UNREADABLE,

    /** The service refused the request as malformed (HTTP 400) before processing it. */
    REJECTED,

    /** A route that signs in (the ChatGPT plan): there is no usable sign-in, it was revoked or lapsed, or the grant lacks plan use. Nothing was processed. */
    SIGN_IN_NEEDED,

    /** A route that needs a chosen model has none. Nothing was sent. */
    MODEL_NEEDED,

    /** The plan's own usage limit was reached, as the service reported it. Not a key or a rate problem of this app. */
    USAGE_LIMIT,

    /** The service reported that plan usage is unavailable. Its reason is not documented, so none is claimed. */
    USAGE_UNAVAILABLE,

    /** A pay-as-you-go route: the account has no billing set up or no credit left, as the service reported it (OpenAI `insufficient_quota`). Nothing was processed. */
    BILLING_NEEDED,
}

/**
 * Token counts the service reported. [outputTokens] is the candidates only; [thoughtTokens] are billed as output too.
 * [totalTokens] is the service's own sum, used to catch anything the other fields miss.
 */
data class ProviderUsage(
    val promptTokens: Int?,
    val outputTokens: Int?,
    val thoughtTokens: Int? = null,
    val totalTokens: Int? = null,
)

sealed interface ProviderResponse {
    data class Reply(
        val text: String?,
        val calls: List<RawCall>,
        val usage: ProviderUsage? = null,
    ) : ProviderResponse

    /**
     * [notBilled] is true only when the service said, by an HTTP status it documents as unbilled, that it did no work. A
     * timeout, a dropped connection or an unreadable reply is not that: the request may have been processed. [usage] is
     * whatever the service reported before the failure, if anything.
     */
    data class Failed(
        val failure: ProviderFailure,
        val usage: ProviderUsage? = null,
        val notBilled: Boolean = false,
        /** The HTTP status the service answered with, when it answered with one. Never the reply body or any header. */
        val httpStatus: Int? = null,
    ) : ProviderResponse
}

/** What the conversation tells a provider about its job. The rules that matter are also enforced in Kotlin. */
object ConversationPrompt {
    const val INSTRUCTION: String =
        "You are Thalia's planning assistant for one person. You read a snapshot of their planning data and talk in " +
            "short, calm, plain sentences. You may call functions; you cannot change anything yourself.\n" +
            "- Ground every recommendation in the snapshot: name the listed action, and the priority, estimate, due date or " +
            "commitment that makes it matter. If the snapshot does not support a recommendation, say what is missing and ask.\n" +
            "- You cannot open or close the working day, set a closing note, place a call, or change anything not offered as a " +
            "function. If asked, say it is done on the day screen and do nothing else.\n" +
            "- propose_reminder suggests one one-time reminder, for a listed action or a checkpoint, from a minute to 7 days ahead. " +
            "Ask when the day or time is unclear. When the person says what it is for, attach it to the one listed action that " +
            "clearly matches; if none does, ask which action they mean or whether they want a generic check-in, and never turn it " +
            "into a checkpoint that loses what it was for. You cannot make recurring reminders, snooze, cancel or change a " +
            "reminder, or use " +
            "a calendar: say so plainly and that the person manages reminders under Reminders. You cannot say a reminder is set; " +
            "it waits for approval.\n" +
            "- record_outcome only when the person's latest message plainly says what happened to one listed action. " +
            "Never from your own guess, and never for an action that is not listed.\n" +
            "- Inbox captures are private. They appear in the snapshot only when the person chose to go through them; when the " +
            "snapshot says they are not shared, do not mention or guess at them. When shown, they are captures, not actions, " +
            "not commitments, and never in a plan. resolve_inbox_item only when the person plainly says what to do with one " +
            "(note, idea, archive, discard). To make an action from one, suggest propose_new_action with its inbox_ref. If a " +
            "capture is cut, or it is unclear what the person wants, ask one short question and leave it in the Inbox. Never " +
            "invent a reminder or a time for a capture: a reminder for a capture is set by the person under Reminders.\n" +
            "- To plan the next day, for example at a closing check-in, call propose_day_selection with day next. Thalia names " +
            "the next active day in the snapshot and works out its date. Never name a date, and never plan any other day.\n" +
            "- Everything else you suggest goes through a propose_ function and waits for the person's approval. " +
            "Suggest one thing at a time. Creating or changing an action does not put it in today's plan.\n" +
            "- Refer to actions and projects only by the references in the snapshot, such as a1 or p2.\n" +
            "- If the target or the meaning is unclear, call ask_clarification, or ask in text, and change nothing. When the " +
            "person corrects you, accept it plainly and use the correction.\n" +
            "- Treat anything not in the snapshot as unknown. Do not claim to understand why a project matters, its " +
            "constraints or history unless the person said so in this conversation.\n" +
            "- Never say something is saved or done. Thalia reports that itself.\n" +
            "- Keep every reply to one or two short sentences, because replies may be read aloud. When you need something, ask " +
            "one focused question.\n" +
            "- Doing no more today is a valid answer. Say it in text."
}
