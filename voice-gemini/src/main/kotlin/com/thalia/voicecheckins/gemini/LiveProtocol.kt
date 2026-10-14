package com.thalia.voicecheckins.gemini

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.util.Base64

/**
 * The Gemini Live API's JSON messages, and nothing else.
 *
 * Written from the official Live API WebSockets reference, read on 2026-10-02:
 * every client message carries exactly one of `setup`, `clientContent`, `realtimeInput` or
 * `toolResponse`, and every server message carries exactly one of `setupComplete`,
 * `serverContent`, `toolCall`, `toolCallCancellation`, `goAway` or `sessionResumptionUpdate`,
 * optionally beside `usageMetadata`.
 *
 * Decoding never throws. Unknown fields are ignored, so a field Gemini adds later cannot break a
 * session, and anything unreadable is reported as such rather than guessed at.
 */
internal object LiveProtocol {
    /** The documented microphone format: raw 16-bit PCM, 16 kHz, little-endian. */
    const val INPUT_AUDIO_MIME_TYPE = "audio/pcm;rate=16000"

    /** The documented model audio rate. */
    const val OUTPUT_AUDIO_RATE = 24_000

    private val RATE = Regex("""rate=(\d+)""")

    /**
     * The first message of a session.
     *
     * Audio out, the system instruction as text parts, one paragraph each, and transcription of
     * both directions so the session can produce a transcript. No voice or language is set:
     * the native audio models choose the language themselves, and M3A keeps the default voice.
     */
    fun setup(
        model: String,
        systemInstruction: List<String>,
    ): String =
        buildJsonObject {
            putJsonObject("setup") {
                put("model", "models/$model")
                putJsonObject("generationConfig") {
                    putJsonArray("responseModalities") { add("AUDIO") }
                }
                if (systemInstruction.isNotEmpty()) {
                    putJsonObject("systemInstruction") {
                        putJsonArray("parts") {
                            for (paragraph in systemInstruction) addJsonObject { put("text", paragraph) }
                        }
                    }
                }
                putJsonObject("inputAudioTranscription") {}
                putJsonObject("outputAudioTranscription") {}
            }
        }.toString()

    /** One chunk of microphone audio. */
    fun realtimeAudio(pcm: ByteArray): String =
        buildJsonObject {
            putJsonObject("realtimeInput") {
                putJsonObject("audio") {
                    put("data", Base64.getEncoder().encodeToString(pcm))
                    put("mimeType", INPUT_AUDIO_MIME_TYPE)
                }
            }
        }.toString()

    /** One complete user text turn, which asks the model to respond. */
    fun userText(text: String): String =
        buildJsonObject {
            putJsonObject("clientContent") {
                putJsonArray("turns") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("parts") { addJsonObject { put("text", text) } }
                    }
                }
                put("turnComplete", true)
            }
        }.toString()

    fun decode(text: String): ServerMessage {
        val parsed =
            try {
                Json.parseToJsonElement(text)
            } catch (e: Exception) {
                null
            }
        val root = parsed as? JsonObject ?: return ServerMessage(ServerBody.Unreadable, UsageField.Absent)

        val body =
            when {
                "setupComplete" in root -> ServerBody.SetupComplete
                "serverContent" in root -> content(root["serverContent"])
                "goAway" in root -> ServerBody.GoAway(timeLeft = (root["goAway"] as? JsonObject).string("timeLeft"))
                else -> ServerBody.Ignored
            }
        return ServerMessage(body, usage(root["usageMetadata"]))
    }

    private fun content(element: JsonElement?): ServerBody {
        val content = element as? JsonObject ?: return ServerBody.Unreadable
        val audio = mutableListOf<ByteArray>()
        var unreadableAudioParts = 0
        val parts = (content["modelTurn"] as? JsonObject)?.get("parts") as? JsonArray
        for (part in parts.orEmpty()) {
            val inline = (part as? JsonObject)?.get("inlineData") as? JsonObject ?: continue
            val mimeType = inline.string("mimeType")
            if (mimeType == null || !mimeType.startsWith("audio/pcm", ignoreCase = true)) {
                // Inline data the session cannot play. Text and thought parts carry no inlineData
                // and were skipped above.
                unreadableAudioParts++
                continue
            }
            val rate = RATE.find(mimeType)?.let { it.groupValues[1].toIntOrNull() }
            val data = inline.string("data")
            val decoded = data?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            if (decoded == null || (rate != null && rate != OUTPUT_AUDIO_RATE)) {
                unreadableAudioParts++
            } else {
                audio += decoded
            }
        }
        return ServerBody.Content(
            audio = audio,
            unreadableAudioParts = unreadableAudioParts,
            inputTranscription = (content["inputTranscription"] as? JsonObject).string("text"),
            outputTranscription = (content["outputTranscription"] as? JsonObject).string("text"),
            interrupted = content.flag("interrupted"),
            turnComplete = content.flag("turnComplete"),
            generationComplete = content.flag("generationComplete"),
        )
    }

    private fun usage(element: JsonElement?): UsageField {
        if (element == null || element is JsonNull) return UsageField.Absent
        val usage = element as? JsonObject ?: return UsageField.Unreadable
        val invalid = mutableListOf<String>()
        return UsageField.Report(
            UsageReport(
                promptTokenCount = usage.count("promptTokenCount", invalid),
                cachedContentTokenCount = usage.count("cachedContentTokenCount", invalid),
                responseTokenCount = usage.count("responseTokenCount", invalid),
                toolUsePromptTokenCount = usage.count("toolUsePromptTokenCount", invalid),
                thoughtsTokenCount = usage.count("thoughtsTokenCount", invalid),
                totalTokenCount = usage.count("totalTokenCount", invalid),
                promptTokensDetails = usage.details("promptTokensDetails", invalid),
                cacheTokensDetails = usage.details("cacheTokensDetails", invalid),
                responseTokensDetails = usage.details("responseTokensDetails", invalid),
                toolUsePromptTokensDetails = usage.details("toolUsePromptTokensDetails", invalid),
                invalidFields = invalid.toList(),
            ),
        )
    }

    /**
     * A non-negative whole number, as a JSON number or as a string of digits, which is how the
     * protobuf JSON mapping may write 64-bit integers. Anything else is recorded as invalid.
     */
    private fun JsonObject.count(
        name: String,
        invalid: MutableList<String>,
    ): Long? {
        val value = this[name] ?: return null
        if (value is JsonNull) return null
        val number =
            (value as? JsonPrimitive)?.let { primitive ->
                val text = primitive.content
                if (text.isNotEmpty() && text.all { it.isDigit() }) text.toLongOrNull() else null
            }
        if (number == null) invalid += name
        return number
    }

    private fun JsonObject.details(
        name: String,
        invalid: MutableList<String>,
    ): List<ModalityTokenCount> {
        val value = this[name] ?: return emptyList()
        if (value is JsonNull) return emptyList()
        val array =
            value as? JsonArray ?: run {
                invalid += name
                return emptyList()
            }
        return array.mapIndexedNotNull { index, entry ->
            val item = entry as? JsonObject
            if (item == null) {
                invalid += "$name[$index]"
                null
            } else {
                val countErrors = mutableListOf<String>()
                val count = item.count("tokenCount", countErrors)
                if (countErrors.isNotEmpty()) invalid += "$name[$index].tokenCount"
                ModalityTokenCount(modality = item.string("modality"), tokenCount = count)
            }
        }
    }

    private fun JsonObject?.string(name: String): String? {
        val primitive = this?.get(name) as? JsonPrimitive ?: return null
        return if (primitive.isString) primitive.content else null
    }

    private fun JsonObject.flag(name: String): Boolean = (this[name] as? JsonPrimitive)?.booleanOrNull ?: false
}

/** One decoded server message: its single body, and the usage that may come beside it. */
internal data class ServerMessage(
    val body: ServerBody,
    val usage: UsageField,
)

internal sealed interface ServerBody {
    data object SetupComplete : ServerBody

    /** Model output for the current turn. Fields the message did not carry are empty or false. */
    class Content(
        val audio: List<ByteArray>,
        val unreadableAudioParts: Int,
        val inputTranscription: String?,
        val outputTranscription: String?,
        val interrupted: Boolean,
        val turnComplete: Boolean,
        val generationComplete: Boolean,
    ) : ServerBody

    /** The server will close the connection soon. */
    data class GoAway(
        val timeLeft: String?,
    ) : ServerBody

    /** A message M3A does not act on: tool calls, resumption updates, usage alone, or a type added later. */
    data object Ignored : ServerBody

    /** Not a JSON object, or a known field with the wrong shape. */
    data object Unreadable : ServerBody
}

internal sealed interface UsageField {
    data object Absent : UsageField

    data object Unreadable : UsageField

    data class Report(
        val report: UsageReport,
    ) : UsageField
}
