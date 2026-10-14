package com.thalia.app.live

import com.thalia.app.ai.LiveRouteFactory
import com.thalia.app.ai.PcmSink
import com.thalia.app.ai.SpeechOutcome
import com.thalia.app.ai.SpeechProblem
import com.thalia.app.ai.SpeechSynthesizer
import com.thalia.conversation.ConversationProvider
import com.thalia.openai.CartesiaSpeechClient
import com.thalia.openai.OpenAiResponsesProvider
import com.thalia.openai.SpeechFailure
import com.thalia.openai.SpeechResult

/**
 * The only place the app touches the OpenAI and Cartesia clients. This file is compiled by the debug build (the real app) and the
 * live check build alone, from `src/openaiLive`; every other build has no such class and [com.thalia.app.ai.LiveRoute] answers
 * null there. Keys are read per request from the sealed stores and never held here.
 */
internal class OpenAiLiveRoute : LiveRouteFactory {
    override fun reasoning(apiKey: () -> String?): ConversationProvider = OpenAiResponsesProvider(apiKey)

    override fun speech(apiKey: () -> String?): SpeechSynthesizer {
        val client = CartesiaSpeechClient(apiKey)
        return object : SpeechSynthesizer {
            override suspend fun synthesize(
                text: String,
                sink: PcmSink,
            ): SpeechOutcome =
                when (val r = client.synthesize(text) { pcm, length -> sink.write(pcm, length) }) {
                    is SpeechResult.Streamed -> SpeechOutcome.Streamed(r.characters, r.firstAudioMillis)
                    is SpeechResult.Stopped -> SpeechOutcome.Stopped(r.characters)
                    is SpeechResult.Failed ->
                        SpeechOutcome.Failed(
                            when (r.failure) {
                                SpeechFailure.NO_KEY, SpeechFailure.AUTH -> SpeechProblem.AUTH
                                SpeechFailure.BAD_TEXT, SpeechFailure.REJECTED, SpeechFailure.UNREADABLE -> SpeechProblem.UNREADABLE
                                SpeechFailure.NETWORK -> SpeechProblem.NETWORK
                                SpeechFailure.LIMITED -> SpeechProblem.LIMITED
                                SpeechFailure.SERVER -> SpeechProblem.SERVICE
                            },
                        )
                }
        }
    }
}
