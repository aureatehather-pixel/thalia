package com.thalia.app.conversationcheck

import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.ProviderUsage
import java.io.File

/**
 * Slice 4A device check: stands behind the real consent, key and budget gates in place of Google. It sends nothing anywhere
 * and writes each request it receives to this app's private files, so the check can read exactly what a Send would have
 * carried. It answers with the scripted commands and attaches the usage a service would report, so the budget settles.
 * `/fail rejected` is the one documented-unbilled failure; other `/fail` kinds stay uncertain.
 */
internal class FakeAiTransport(
    private val scripted: ScriptedProvider,
    private val log: File,
) : ConversationProvider {
    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        log.appendText("--- request at ${System.currentTimeMillis()}\nmessage: ${request.turns.last().text}\n${request.contextText}\n")
        return when (val r = scripted.respond(request)) {
            is ProviderResponse.Reply -> if (r.usage == null) r.copy(usage = ProviderUsage(1_500, 120, 80, 1_700)) else r
            is ProviderResponse.Failed -> if (r.failure == ProviderFailure.REJECTED) r.copy(notBilled = true) else r
        }
    }
}
