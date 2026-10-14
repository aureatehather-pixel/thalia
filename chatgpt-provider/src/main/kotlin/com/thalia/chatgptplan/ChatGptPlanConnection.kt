package com.thalia.chatgptplan

import okhttp3.OkHttpClient

/**
 * The production composition of the ChatGPT-plan route, in one place: the signed-in session, its renewal and the provider. Android
 * supplies the three things that are not plain Kotlin: sealed storage ([store]), the consent decision ([consented]) and the model
 * choice ([model]). Nothing here signs in, creates a credential, stores a key or reads planning data; the planning core sees only a
 * [com.thalia.conversation.ConversationProvider]. There is no API-key parameter and no fallback to another route.
 *
 * What is **not** here, and blocks use in a shipped build: the sign-in flow, a consent wording and screen, the source of [model], and
 * the release dependency decision (a shipped build has no INTERNET permission or OkHttp).
 */
class ChatGptPlanConnection(
    val session: PlanSession,
    val provider: ChatGptPlanProvider,
) {
    companion object {
        fun create(
            store: CredentialStore,
            model: () -> String?,
            consented: () -> Boolean,
            endpoints: PlanEndpoints = PlanEndpoints.PRODUCTION,
            client: OkHttpClient = planHttpClient(endpoints),
            clock: () -> Long = System::currentTimeMillis,
        ): ChatGptPlanConnection {
            val session = PlanSession(store, TokenClient(client, endpoints), clock)
            val gates =
                object : PlanGates {
                    override fun consented() = consented()

                    // No request cap here: the plan's metering is not established, so none is invented. The conversation
                    // session already allows one request at a time.
                    override fun tryReserve() = true
                }
            return ChatGptPlanConnection(session, ChatGptPlanProvider(session, endpoints, client, model, gates))
        }
    }
}
