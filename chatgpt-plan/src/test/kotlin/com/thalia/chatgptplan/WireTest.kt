package com.thalia.chatgptplan

import com.thalia.conversation.ConversationTools
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WireTest {
    private val request =
        ProviderRequest(
            "Be brief.",
            "Active day: 2026-10-08.",
            listOf(Turn(TurnRole.USER, "hello"), Turn(TurnRole.ASSISTANT, "hi"), Turn(TurnRole.USER, "again")),
            ConversationTools.DECLARATIONS_JSON,
        )

    @Test
    fun `a request with no declared tools carries no tools and no tool choice, and nothing else changes`() {
        val body = ResponsesRequest.body("some-model", request.copy(toolsJson = "[]"))
        assertEquals(ResponsesRequest.KEYS - setOf("tools", "tool_choice"), body.keys)
        assertEquals("false", body.getValue("store").jsonPrimitive.content)
        assertEquals("true", body.getValue("stream").jsonPrimitive.content)
    }

    @Test
    fun `the request uses only the documented plan-usage fields, with store false and stream true`() {
        val body = ResponsesRequest.body("some-model", request)
        assertEquals(ResponsesRequest.KEYS, body.keys)
        listOf(
            "temperature",
            "max_output_tokens",
            "previous_response_id",
            "metadata",
            "user",
            "truncation",
            "background",
            "conversation",
            "prompt",
            "top_p",
        ).forEach { assertFalse(it in body.keys, it) }
        assertEquals("false", body["store"]!!.jsonPrimitive.content)
        assertEquals("true", body["stream"]!!.jsonPrimitive.content)
        assertEquals("some-model", body["model"]!!.jsonPrimitive.content)
        val instructions = body["instructions"]!!.jsonPrimitive.content
        assertTrue("Be brief." in instructions && "Active day: 2026-10-08." in instructions)
    }

    @Test
    fun `no system message is sent, user turns are input_text and assistant turns are output_text`() {
        val input = ResponsesRequest.body("m", request)["input"]!!.jsonArray
        assertEquals(listOf("user", "assistant", "user"), input.map { it.jsonObject["role"]!!.jsonPrimitive.content })
        assertEquals(
            listOf("input_text", "output_text", "input_text"),
            input.map {
                it.jsonObject["content"]!!
                    .jsonArray[0]
                    .jsonObject["type"]!!
                    .jsonPrimitive.content
            },
        )
    }

    @Test
    fun `every declared operation becomes a function tool with its schema`() {
        val tools = ResponsesRequest.body("m", request)["tools"]!!.jsonArray
        val declared =
            kotlinx.serialization.json.Json
                .parseToJsonElement(ConversationTools.DECLARATIONS_JSON)
                .jsonArray
        assertEquals(declared.size, tools.size)
        assertTrue(tools.all { it.jsonObject["type"]!!.jsonPrimitive.content == "function" && "parameters" in it.jsonObject })
        assertEquals(declared.map { it.jsonObject["name"] }, tools.map { it.jsonObject["name"] })
    }

    private fun sse(vararg events: String) = events.joinToString("") { "event: e\ndata: $it\n\n" }.lines().iterator()

    private fun delta(t: String) = """{"type":"response.output_text.delta","delta":"$t"}"""

    private val done = """{"type":"response.completed","response":{"usage":{"input_tokens":10,"output_tokens":5}}}"""

    @Test
    fun `text deltas become the reply only after response completed, with the usage reported`() {
        val out = assertIs<StreamOutcome.Completed>(SseParser.parse(sse(delta("Hel"), delta("lo"), done)))
        assertEquals("Hello", out.text)
        assertEquals(10, out.usage?.promptTokens)
        assertEquals(5, out.usage?.outputTokens)
    }

    @Test
    fun `a stream that ends without a terminal event is interrupted, however much text arrived`() {
        assertEquals(StreamOutcome.Interrupted, SseParser.parse(sse(delta("I have put it in your plan and it is done."))))
        assertEquals(StreamOutcome.Interrupted, SseParser.parse(emptyList<String>().iterator()))
    }

    @Test
    fun `function calls are read from output item events, and the completed output wins when it lists one`() {
        val item =
            """{"type":"response.output_item.done","item":{"type":"function_call","name":"record_outcome",""" +
                """"arguments":"{\"action_ref\":\"a1\",\"result\":\"done\"}"}}"""
        val streamed = assertIs<StreamOutcome.Completed>(SseParser.parse(sse(item, done)))
        assertEquals("record_outcome", streamed.calls.single().name)
        assertEquals("""{"action_ref":"a1","result":"done"}""", streamed.calls.single().argsJson)
        val finalOnly =
            """{"type":"response.completed","response":{"output":[{"type":"function_call","name":"propose_new_action","arguments":"{}"},{"type":"message","content":[{"type":"output_text","text":"Final."}]}]}}"""
        val final = assertIs<StreamOutcome.Completed>(SseParser.parse(sse(delta("draft"), item, finalOnly)))
        assertEquals(listOf("propose_new_action"), final.calls.map { it.name })
        assertEquals("Final.", final.text)
    }

    @Test
    fun `incomplete, failed and error events are not replies and carry only the code`() {
        assertEquals(
            StreamOutcome.Incomplete("max_output_tokens"),
            SseParser.parse(
                sse(delta("x"), """{"type":"response.incomplete","response":{"incomplete_details":{"reason":"max_output_tokens"}}}"""),
            ),
        )
        assertEquals(
            StreamOutcome.Failed("subscription_sharing_usage_limit_exceeded"),
            SseParser.parse(
                sse(
                    """{"type":"response.failed","response":{"error":{"code":"subscription_sharing_usage_limit_exceeded","message":"secret detail"}}}""",
                ),
            ),
        )
        assertEquals(StreamOutcome.Failed("rate_limit"), SseParser.parse(sse("""{"type":"error","code":"rate_limit"}""")))
    }

    @Test
    fun `unknown events and DONE are ignored, bad JSON and over-large streams are malformed`() {
        assertIs<StreamOutcome.Completed>(SseParser.parse(sse("""{"type":"response.created"}""", "[DONE]", done)))
        assertEquals(StreamOutcome.Malformed, SseParser.parse(sse("{not json")))
        val big = generateSequence { "data: " + "x".repeat(10_000) }.take(200).iterator()
        assertEquals(StreamOutcome.Malformed, SseParser.parse(big))
    }
}
