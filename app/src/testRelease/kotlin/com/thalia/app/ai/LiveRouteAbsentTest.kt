package com.thalia.app.ai

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** A release build has no OpenAI or Text-to-Speech client, so the everyday route cannot be loaded and no AI or speech request exists. */
class LiveRouteAbsentTest {
    @Test
    fun `the live route is not in a release build`() {
        assertNull(LiveRoute.load())
        assertFailsWith<ClassNotFoundException> { Class.forName("com.thalia.openai.OpenAiResponsesProvider") }
        assertFailsWith<ClassNotFoundException> { Class.forName("com.thalia.openai.GoogleSpeechClient") }
    }
}
