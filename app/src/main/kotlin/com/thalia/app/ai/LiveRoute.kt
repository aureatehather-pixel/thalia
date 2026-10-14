package com.thalia.app.ai

import com.thalia.conversation.ConversationProvider

/**
 * The everyday route's two live services. The implementation lives in `src/openaiLive`, which only the debug build (the real
 * app) and the live check build compile, so a release, recoveryCheck, conversationCheck or chatgptCheck build has no OpenAI or
 * Text-to-Speech client and no network code to reach. [LiveRoute.load] finds it by class name and answers null where it is absent.
 */
internal interface LiveRouteFactory {
    /** The reasoning provider, reading the OpenAI key from [apiKey] on each request. */
    fun reasoning(apiKey: () -> String?): ConversationProvider

    /** The Cartesia synthesizer, reading the Cartesia key from [apiKey] on each request. */
    fun speech(apiKey: () -> String?): SpeechSynthesizer
}

internal object LiveRoute {
    private const val IMPLEMENTATION = "com.thalia.app.live.OpenAiLiveRoute"

    fun load(): LiveRouteFactory? =
        try {
            Class.forName(IMPLEMENTATION).getDeclaredConstructor().newInstance() as? LiveRouteFactory
        } catch (e: ReflectiveOperationException) {
            null
        }
}

/** What the person agrees to before Thalia may send anything to OpenAI. Changing a word changes [VERSION]. */
internal object OpenAiConsentWording {
    const val VERSION: String = "ai-openai-paid-2026-10-12"

    /** The validation budget: USD 1 a month until the person raises it, never above the app's own ceiling. */
    const val INITIAL_BUDGET_MICROS: Long = 1_000_000

    const val TEXT: String =
        "Thalia can ask OpenAI's GPT-6 Luna (model gpt-6-luna, through the OpenAI API) to help you plan, using the API key you " +
            "save on this screen. This is OpenAI's pay-as-you-go API with its own key. It is separate from ChatGPT, from signing " +
            "in with ChatGPT and from any ChatGPT subscription.\n\n" +
            "Nothing is sent until you press Send, or until you finish speaking if sending after speaking is on. Each time, " +
            "Thalia sends OpenAI:\n" +
            "- a fixed instruction telling the model its job and limits;\n" +
            "- a snapshot of your planning data: your direction, active goals, projects, open actions with priority, estimate " +
            "and due date, today's plan and the outcomes recorded for it, today's fixed commitments, your stated focus capacity " +
            "and the last closing note;\n" +
            "- up to the last 10 lines of this conversation, including what you said or typed;\n" +
            "- a list of the function names the model may suggest.\n" +
            "Inbox captures are never included, except the up to 5 you choose with Triage with Thalia, each cut to its first " +
            "400 characters, and the screen shows you which before you press Send. Your voice is recognised on the phone; only " +
            "the recognised words are sent.\n\n" +
            "OpenAI returns text and suggestions and reports how many tokens were used. Thalia asks OpenAI not to store the " +
            "response, and does not store the conversation. OpenAI's own retention and data-use terms for its API apply to what " +
            "is sent; read them on OpenAI's site before you agree. Anything the model suggests changes nothing until you tap " +
            "Approve. Typing or saying yes never approves.\n\n" +
            "Each request is counted against a monthly budget kept on this phone, which starts at USD 1. That count is an " +
            "estimate from the prices Thalia last read, not OpenAI's invoice or a hard cap. Set a spend limit and keep automatic " +
            "top-ups off in your OpenAI account too. Turning AI off stops all requests; planning, outcomes, check-ins and the " +
            "Inbox work without it."
}
