package com.thalia.openai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okio.Buffer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

class CartesiaSpeechClientTest {
    private val server = MockWebServer().apply { start() }

    @AfterTest
    fun stop() = server.close()

    private fun reply(
        body: ByteArray,
        code: Int = 200,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(Buffer().write(body))
            .build(),
    )

    private fun client(key: String? = "c-key") = CartesiaSpeechClient({ key }, baseUrl = server.url("/").toString().trimEnd('/'))

    @Test
    fun `audio is streamed in whole samples and the request names Ella, sonic-3_6, raw PCM and the bearer key`() {
        val pcm = ByteArray(20001) { (it % 100).toByte() }
        reply(pcm)
        val got = java.io.ByteArrayOutputStream()
        val result = runBlocking { client().synthesize("  Reminder set.  ") { b, n -> got.write(b, 0, n).let { true } } }
        val streamed = assertIs<SpeechResult.Streamed>(result)
        assertEquals("Reminder set.".length, streamed.characters)
        assertEquals(20000, got.size())
        val sent = server.takeRequest()
        assertEquals("/tts/bytes", sent.url.encodedPath)
        assertEquals("Bearer c-key", sent.headers["Authorization"])
        assertEquals("2026-08-14", sent.headers["Cartesia-Version"])
        assertFalse(sent.url.toString().contains("c-key"))
        val body = Json.parseToJsonElement(sent.body!!.utf8()).jsonObject
        assertEquals("sonic-3.6", body.getValue("model_id").jsonPrimitive.content)
        assertEquals("Reminder set.", body.getValue("transcript").jsonPrimitive.content)
        assertEquals(
            "2a12b36c-7f9b-4c3a-9f7a-72731b15323a",
            body
                .getValue("voice")
                .jsonObject
                .getValue("id")
                .jsonPrimitive.content,
        )
        val format = body.getValue("output_format").jsonObject
        assertEquals("raw", format.getValue("container").jsonPrimitive.content)
        assertEquals("pcm_s16le", format.getValue("encoding").jsonPrimitive.content)
        assertEquals("24000", format.getValue("sample_rate").jsonPrimitive.content)
    }

    @Test
    fun `a listener that says stop ends the read early`() {
        reply(ByteArray(40000))
        val result = runBlocking { client().synthesize("hi") { _, _ -> false } }
        assertIs<SpeechResult.Stopped>(result)
    }

    @Test
    fun `no key, empty or long text send nothing`() {
        fun fail(
            c: CartesiaSpeechClient,
            t: String,
        ) = assertIs<SpeechResult.Failed>(runBlocking { c.synthesize(t) { _, _ -> true } }).failure
        assertEquals(SpeechFailure.NO_KEY, fail(client(null), "hi"))
        assertEquals(SpeechFailure.BAD_TEXT, fail(client(), "   "))
        assertEquals(SpeechFailure.BAD_TEXT, fail(client(), "x".repeat(CartesiaSpeechClient.MAX_CHARACTERS + 1)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `statuses and an empty body are failures with no service text`() {
        fun status(code: Int): SpeechFailure {
            reply("secret detail c-key".toByteArray(), code)
            return assertIs<SpeechResult.Failed>(runBlocking { client().synthesize("hi") { _, _ -> true } }).failure
        }
        assertEquals(SpeechFailure.AUTH, status(401))
        assertEquals(SpeechFailure.LIMITED, status(402))
        assertEquals(SpeechFailure.LIMITED, status(429))
        assertEquals(SpeechFailure.REJECTED, status(400))
        assertEquals(SpeechFailure.SERVER, status(503))
        reply(ByteArray(0))
        assertEquals(
            SpeechFailure.UNREADABLE,
            assertIs<SpeechResult.Failed>(runBlocking { client().synthesize("hi") { _, _ -> true } }).failure,
        )
    }
}
