package com.thalia.voicecheckins.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LiveProtocolTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject

    private fun content(text: String): ServerBody.Content = assertIs<ServerBody.Content>(LiveProtocol.decode(text).body)

    private fun report(text: String): UsageReport = assertIs<UsageField.Report>(LiveProtocol.decode(text).usage).report

    @Test
    fun `the setup message names the model, asks for audio, and turns on both transcriptions`() {
        val setup = LiveProtocol.setup("gemini-3.8-live", listOf("You are testing.", "Synthetic note."))

        assertEquals(
            json(
                """
                {"setup": {
                  "model": "models/gemini-3.8-live",
                  "generationConfig": {"responseModalities": ["AUDIO"]},
                  "systemInstruction": {"parts": [{"text": "You are testing."}, {"text": "Synthetic note."}]},
                  "inputAudioTranscription": {},
                  "outputAudioTranscription": {}
                }}
                """,
            ),
            json(setup),
        )
    }

    @Test
    fun `microphone audio is sent as base64 realtime input with the documented mime type`() {
        val pcm = byteArrayOf(0, 1, -1, 127, -128, 42)

        val audio = json(LiveProtocol.realtimeAudio(pcm))["realtimeInput"]!!.jsonObject["audio"]!!.jsonObject

        assertEquals("\"audio/pcm;rate=16000\"", audio["mimeType"].toString())
        assertContentEquals(pcm, Base64.getDecoder().decode(audio["data"].toString().trim('"')))
        assertEquals(setOf("realtimeInput"), json(LiveProtocol.realtimeAudio(pcm)).keys)
    }

    @Test
    fun `the opening prompt is one complete user turn`() {
        assertEquals(
            json("""{"clientContent": {"turns": [{"role": "user", "parts": [{"text": "Say hello."}]}], "turnComplete": true}}"""),
            json(LiveProtocol.userText("Say hello.")),
        )
    }

    @Test
    fun `setup complete is recognised`() {
        assertEquals(ServerMessage(ServerBody.SetupComplete, UsageField.Absent), LiveProtocol.decode("""{"setupComplete": {}}"""))
    }

    @Test
    fun `server content carries model audio, both transcriptions and the turn flags`() {
        val first = Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3))
        val second = Base64.getEncoder().encodeToString(byteArrayOf(4, 5))
        val decoded =
            content(
                """
                {"serverContent": {
                  "modelTurn": {"parts": [
                    {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$first"}},
                    {"text": "a text part is not audio"},
                    {"inlineData": {"mimeType": "audio/pcm", "data": "$second"}}
                  ]},
                  "inputTranscription": {"text": "Hi", "languageCode": "en-US"},
                  "outputTranscription": {"text": "Hello"},
                  "interrupted": true,
                  "turnComplete": true,
                  "generationComplete": true
                }}
                """,
            )

        assertEquals(2, decoded.audio.size)
        assertContentEquals(byteArrayOf(1, 2, 3), decoded.audio[0])
        assertContentEquals(byteArrayOf(4, 5), decoded.audio[1])
        assertEquals(0, decoded.unreadableAudioParts)
        assertEquals("Hi", decoded.inputTranscription)
        assertEquals("Hello", decoded.outputTranscription)
        assertTrue(decoded.interrupted)
        assertTrue(decoded.turnComplete)
        assertTrue(decoded.generationComplete)
    }

    @Test
    fun `absent fields read as nothing rather than as an error`() {
        val decoded = content("""{"serverContent": {}}""")

        assertTrue(decoded.audio.isEmpty())
        assertNull(decoded.inputTranscription)
        assertNull(decoded.outputTranscription)
        assertFalse(decoded.interrupted)
        assertFalse(decoded.turnComplete)
    }

    @Test
    fun `audio that cannot be played is counted and skipped, and the rest is kept`() {
        val good = Base64.getEncoder().encodeToString(byteArrayOf(7))
        val decoded =
            content(
                """
                {"serverContent": {"modelTurn": {"parts": [
                  {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "not base64 !!"}},
                  {"inlineData": {"mimeType": "audio/pcm;rate=16000", "data": "$good"}},
                  {"inlineData": {"mimeType": "image/png", "data": "$good"}},
                  {"inlineData": {"data": "$good"}},
                  {"inlineData": {"mimeType": "audio/pcm;rate=24000", "data": "$good"}}
                ]}}}
                """,
            )

        assertEquals(1, decoded.audio.size)
        assertContentEquals(byteArrayOf(7), decoded.audio.single())
        assertEquals(4, decoded.unreadableAudioParts)
    }

    @Test
    fun `go away carries the time left`() {
        assertEquals(ServerBody.GoAway("5s"), LiveProtocol.decode("""{"goAway": {"timeLeft": "5s"}}""").body)
        assertEquals(ServerBody.GoAway(null), LiveProtocol.decode("""{"goAway": {}}""").body)
    }

    @Test
    fun `messages M3A does not act on are ignored, not treated as errors`() {
        for (message in listOf(
            """{"toolCall": {"functionCalls": []}}""",
            """{"toolCallCancellation": {"ids": ["a"]}}""",
            """{"sessionResumptionUpdate": {"newHandle": "h", "resumable": true}}""",
            """{"someTypeAddedLater": {"anything": 1}}""",
            """{}""",
        )) {
            assertEquals(ServerBody.Ignored, LiveProtocol.decode(message).body, message)
        }
    }

    @Test
    fun `unknown fields are ignored at every level`() {
        val decoded =
            LiveProtocol.decode(
                """
                {"serverContent": {"outputTranscription": {"text": "Hi", "futureField": 1},
                                   "futureFlag": true, "turnComplete": true},
                 "futureTopLevel": {"x": [1, 2]}}
                """,
            )

        val body = assertIs<ServerBody.Content>(decoded.body)
        assertEquals("Hi", body.outputTranscription)
        assertTrue(body.turnComplete)
        assertEquals(UsageField.Absent, decoded.usage)
    }

    @Test
    fun `text that is not a JSON object is unreadable and never throws`() {
        for (message in listOf("", "not json", "[1, 2]", "\"text\"", "{\"serverContent\": [1]}", "{\"setupComplete\": ")) {
            assertEquals(ServerBody.Unreadable, LiveProtocol.decode(message).body, message)
        }
    }

    @Test
    fun `usage metadata is read in full, as Long, from numbers or digit strings`() {
        val usage =
            report(
                """
                {"usageMetadata": {
                  "promptTokenCount": 3000000000,
                  "cachedContentTokenCount": 0,
                  "responseTokenCount": "120",
                  "toolUsePromptTokenCount": 4,
                  "thoughtsTokenCount": 5,
                  "totalTokenCount": 3000000129,
                  "promptTokensDetails": [{"modality": "AUDIO", "tokenCount": 90}, {"modality": "TEXT", "tokenCount": 10}],
                  "cacheTokensDetails": [],
                  "responseTokensDetails": [{"modality": "AUDIO", "tokenCount": "120"}],
                  "toolUsePromptTokensDetails": [{"modality": "TEXT"}]
                }}
                """,
            )

        assertEquals(3_000_000_000L, usage.promptTokenCount)
        assertEquals(0L, usage.cachedContentTokenCount)
        assertEquals(120L, usage.responseTokenCount)
        assertEquals(4L, usage.toolUsePromptTokenCount)
        assertEquals(5L, usage.thoughtsTokenCount)
        assertEquals(3_000_000_129L, usage.totalTokenCount)
        assertEquals(listOf(ModalityTokenCount("AUDIO", 90), ModalityTokenCount("TEXT", 10)), usage.promptTokensDetails)
        assertEquals(emptyList(), usage.cacheTokensDetails)
        assertEquals(listOf(ModalityTokenCount("AUDIO", 120)), usage.responseTokensDetails)
        assertEquals(listOf(ModalityTokenCount("TEXT", null)), usage.toolUsePromptTokensDetails)
        assertEquals(emptyList(), usage.invalidFields)
    }

    @Test
    fun `partial usage metadata leaves what was not sent as null, and is not an error`() {
        val usage = report("""{"usageMetadata": {"totalTokenCount": 42}}""")

        assertEquals(42L, usage.totalTokenCount)
        assertNull(usage.promptTokenCount)
        assertNull(usage.responseTokenCount)
        assertEquals(emptyList(), usage.responseTokensDetails)
        assertEquals(emptyList(), usage.invalidFields)
    }

    @Test
    fun `malformed usage values become null and are named, never guessed or zeroed`() {
        val usage =
            report(
                """
                {"usageMetadata": {
                  "promptTokenCount": "abc",
                  "responseTokenCount": -5,
                  "totalTokenCount": 1.5,
                  "thoughtsTokenCount": true,
                  "cachedContentTokenCount": {},
                  "toolUsePromptTokenCount": 99999999999999999999999,
                  "promptTokensDetails": "not a list",
                  "responseTokensDetails": ["bad", {"modality": "AUDIO", "tokenCount": "n/a"}, {"modality": "TEXT", "tokenCount": 4}]
                }}
                """,
            )

        assertNull(usage.promptTokenCount)
        assertNull(usage.responseTokenCount)
        assertNull(usage.totalTokenCount)
        assertNull(usage.thoughtsTokenCount)
        assertNull(usage.cachedContentTokenCount)
        assertNull(usage.toolUsePromptTokenCount)
        assertEquals(emptyList(), usage.promptTokensDetails)
        assertEquals(listOf(ModalityTokenCount("AUDIO", null), ModalityTokenCount("TEXT", 4)), usage.responseTokensDetails)
        assertEquals(
            listOf(
                "promptTokenCount",
                "cachedContentTokenCount",
                "responseTokenCount",
                "toolUsePromptTokenCount",
                "thoughtsTokenCount",
                "totalTokenCount",
                "promptTokensDetails",
                "responseTokensDetails[0]",
                "responseTokensDetails[1].tokenCount",
            ),
            usage.invalidFields,
        )
    }

    @Test
    fun `usage metadata that is not an object is unreadable, and null usage is absent`() {
        assertEquals(UsageField.Unreadable, LiveProtocol.decode("""{"setupComplete": {}, "usageMetadata": "oops"}""").usage)
        assertEquals(UsageField.Unreadable, LiveProtocol.decode("""{"usageMetadata": [1]}""").usage)
        assertEquals(UsageField.Absent, LiveProtocol.decode("""{"usageMetadata": null}""").usage)
    }

    @Test
    fun `usage beside content is read with it, and usage alone is an ignored body with a report`() {
        val beside = LiveProtocol.decode("""{"serverContent": {"turnComplete": true}, "usageMetadata": {"totalTokenCount": 7}}""")
        assertIs<ServerBody.Content>(beside.body)
        assertEquals(7L, assertIs<UsageField.Report>(beside.usage).report.totalTokenCount)

        val alone = LiveProtocol.decode("""{"usageMetadata": {"totalTokenCount": 8}}""")
        assertEquals(ServerBody.Ignored, alone.body)
        assertEquals(8L, assertIs<UsageField.Report>(alone.usage).report.totalTokenCount)
    }
}
