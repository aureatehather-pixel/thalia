package com.thalia.chatgptplan

import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderUsage
import com.thalia.conversation.RawCall
import com.thalia.conversation.TurnRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The Responses API request for one reply, shaped by the documented plan-usage route: `store` false and `stream` true,
 * `instructions` rather than a system message, function tools, and none of the unsupported fields (no `temperature`,
 * `max_output_tokens`, `previous_response_id`, `metadata`, `user`, `truncation` and the rest). The key set is fixed and a test
 * pins it, so an unsupported field cannot slip in.
 */
object ResponsesRequest {
    val KEYS = setOf("model", "instructions", "input", "tools", "tool_choice", "store", "stream")

    fun body(
        model: String,
        request: ProviderRequest,
    ): JsonObject =
        buildJsonObject {
            put("model", model)
            put("instructions", request.instruction + "\n\nSnapshot of the person's planning data:\n" + request.contextText)
            put(
                "input",
                buildJsonArray {
                    for (turn in request.turns) {
                        add(
                            buildJsonObject {
                                put("role", if (turn.role == TurnRole.USER) "user" else "assistant")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(
                                            buildJsonObject {
                                                put("type", if (turn.role == TurnRole.USER) "input_text" else "output_text")
                                                put("text", turn.text)
                                            },
                                        )
                                    },
                                )
                            },
                        )
                    }
                },
            )
            // No declared tools means no tools and no tool_choice at all, rather than an empty list the service may not accept.
            val declared = tools(request.toolsJson)
            if (declared.isNotEmpty()) {
                put("tools", declared)
                put("tool_choice", "auto")
            }
            put("store", false)
            put("stream", true)
        }

    /** The declarations are `{name, description, parameters}`; the Responses API wants each as a `function` tool. */
    private fun tools(declarationsJson: String): JsonArray =
        buildJsonArray {
            for (declaration in Json.parseToJsonElement(declarationsJson).jsonArray) {
                val d = declaration.jsonObject
                add(
                    buildJsonObject {
                        put("type", "function")
                        put("name", d.getValue("name"))
                        d["description"]?.let { put("description", it) }
                        put("parameters", d.getValue("parameters"))
                    },
                )
            }
        }
}

sealed interface StreamOutcome {
    /** Only a terminal `response.completed` makes a reply. */
    data class Completed(
        val text: String?,
        val calls: List<RawCall>,
        val usage: ProviderUsage?,
    ) : StreamOutcome

    data class Incomplete(
        val reason: String?,
    ) : StreamOutcome

    /** `response.failed` or an `error` event. [code] is the service's error code, nothing else of the body. */
    data class Failed(
        val code: String?,
    ) : StreamOutcome

    /** The stream ended with no terminal event. Never a success, whatever text had arrived. */
    data object Interrupted : StreamOutcome

    data object Malformed : StreamOutcome
}

/** Reads a server-sent-event stream, line by line. Events are told apart by the JSON `type`, not the `event:` line. */
object SseParser {
    const val MAX_BYTES = 1_000_000

    fun parse(lines: Iterator<String>): StreamOutcome {
        val text = StringBuilder()
        val items = mutableListOf<RawCall>()
        var data = StringBuilder()
        var bytes = 0

        fun dispatch(): StreamOutcome? {
            if (data.isEmpty()) return null
            val payload = data.toString()
            data = StringBuilder()
            if (payload == "[DONE]") return null
            val event = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return StreamOutcome.Malformed
            return when (event["type"]?.jsonPrimitive?.contentOrNull) {
                "response.output_text.delta" -> {
                    event["delta"]?.jsonPrimitive?.contentOrNull?.let(text::append)
                    null
                }
                "response.output_item.done" -> {
                    event["item"]?.jsonObject?.let(::call)?.let(items::add)
                    null
                }
                "response.completed" -> completed(event, text.toString(), items)
                "response.incomplete" ->
                    StreamOutcome.Incomplete(
                        event["response"]
                            ?.jsonObject
                            ?.get("incomplete_details")
                            ?.jsonObject
                            ?.get("reason")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                    )
                "response.failed" ->
                    StreamOutcome.Failed(
                        event["response"]
                            ?.jsonObject
                            ?.get("error")
                            ?.jsonObject
                            ?.get("code")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                    )
                "error" ->
                    StreamOutcome.Failed(
                        event["code"]?.jsonPrimitive?.contentOrNull ?: event["error"]
                            ?.jsonObject
                            ?.get("code")
                            ?.jsonPrimitive
                            ?.contentOrNull,
                    )
                else -> null
            }
        }

        while (lines.hasNext()) {
            val line = lines.next()
            bytes += line.length + 1
            if (bytes > MAX_BYTES) return StreamOutcome.Malformed
            when {
                line.isEmpty() -> dispatch()?.let { return it }
                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }
                else -> Unit
            }
        }
        // A last event with no blank line after it still counts, but only if it is terminal.
        return dispatch() ?: StreamOutcome.Interrupted
    }

    private fun call(item: JsonObject): RawCall? =
        if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
            val name = item["name"]?.jsonPrimitive?.contentOrNull
            val arguments = item["arguments"]?.jsonPrimitive?.contentOrNull
            if (name != null) RawCall(name, arguments ?: "{}") else null
        } else {
            null
        }

    /** The final response object, when it lists its output, is authoritative; otherwise what streamed in is used. */
    private fun completed(
        event: JsonObject,
        streamedText: String,
        streamedCalls: List<RawCall>,
    ): StreamOutcome {
        val response = event["response"] as? JsonObject
        val output = response?.get("output") as? JsonArray
        var text = streamedText
        var calls = streamedCalls
        if (output != null && output.isNotEmpty()) {
            val finalCalls = output.mapNotNull { (it as? JsonObject)?.let(::call) }
            val finalText =
                output
                    .mapNotNull { it as? JsonObject }
                    .filter { it["type"]?.jsonPrimitive?.contentOrNull == "message" }
                    .flatMap { (it["content"] as? JsonArray).orEmpty() }
                    .mapNotNull { (it as? JsonObject)?.takeIf { part -> part["type"]?.jsonPrimitive?.contentOrNull == "output_text" } }
                    .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            calls = finalCalls
            text = finalText
        }
        val usage = (response?.get("usage") as? JsonObject)?.let { u -> ProviderUsage(u.int("input_tokens"), u.int("output_tokens")) }
        return StreamOutcome.Completed(text.takeIf { it.isNotBlank() }, calls, usage)
    }

    private fun JsonObject.int(name: String): Int? = (this[name] as? JsonPrimitive)?.intOrNull
}
