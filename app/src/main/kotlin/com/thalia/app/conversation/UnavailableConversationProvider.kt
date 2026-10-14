package com.thalia.app.conversation

import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse

/**
 * The provider this build has until AI consent, a paid-terms key and a cost cap exist (ADR 0010). It answers every request
 * as unavailable without sending anything, so the screen is honest and no data can leave the phone.
 */
internal object UnavailableConversationProvider : ConversationProvider {
    override suspend fun respond(request: ProviderRequest) = ProviderResponse.Failed(ProviderFailure.UNAVAILABLE)
}
