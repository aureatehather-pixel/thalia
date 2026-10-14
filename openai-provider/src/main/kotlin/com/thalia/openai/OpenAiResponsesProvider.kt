package com.thalia.openai

import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.ProviderUsage
import com.thalia.conversation.RawCall
import com.thalia.conversation.TurnRole
import kotlinx.coroutines.suspendCancellableCoroutine
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
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * OpenAI's Responses API, with a normal API key, as a [ConversationProvider]: one non-streaming POST per reply, the declared
 * functions as function tools, and the model's function calls returned as [RawCall] text. It never runs a function and never
 * sends a function result back, so what a call may do is decided by Thalia's validator and the model only proposes. This is the
 * API-key route and has nothing to do with Sign in with ChatGPT or plan usage.
 *
 * The key comes from [apiKey] per request and is sent in the `Authorization` header only, never in a URL, a log or a failure.
 * A missing key is [ProviderFailure.UNAVAILABLE] without a request. Consent, budget and the one-at-a-time rule belong to the
 * caller's gates, before this is called. `store` is false, so OpenAI is asked not to keep the response.
 *
 * Exactly one network attempt is made per call: OkHttp's own resend on a connection failure, on a 408 and across redirects is
 * switched off, because each would be a second billable request the gates know nothing about.
 *
 * The request and reply shapes follow OpenAI's Responses reference (developers.openai.com, read 2026-10-12). They are exercised
 * here against a local fake server only; the first real request is the owner's, behind the gates.
 */
class OpenAiResponsesProvider(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxOutputTokens: Int = ContextLimits.OUTPUT_TOKENS,
    /** `low` keeps the reasoning tokens, which are billed as output, small. Null leaves the model's default. */
    private val reasoningEffort: String? = "low",
) : ConversationProvider {
    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        val key = apiKey() ?: return ProviderResponse.Failed(ProviderFailure.UNAVAILABLE)
        val http =
            Request
                .Builder()
                .url("$baseUrl/responses")
                .header("Authorization", "Bearer $key")
                .post(body(request).toString().toRequestBody(JSON))
                .build()
        val firstStatus = AtomicInteger(0)
        return try {
            execute(http, firstStatus).use { response ->
                val text = response.body.string()
                if (response.isSuccessful) parse(text) else failed(response.code, text)
            }
        } catch (e: IOException) {
            firstStatus.get().takeIf { it != 0 && it !in 200..299 }?.let { failed(it, null) }
                ?: ProviderResponse.Failed(ProviderFailure.NETWORK)
        }
    }

    /**
     * Only the status and, for a 429, the error `code` field are read: never the message, the body or a header. A 400 is not
     * billed (the request was refused before processing); a 429 with `insufficient_quota` is a billing problem, not a rate limit.
     */
    private fun failed(
        code: Int,
        body: String?,
    ): ProviderResponse.Failed =
        when {
            code == 401 || code == 403 -> ProviderResponse.Failed(ProviderFailure.AUTH, httpStatus = code)
            code == 429 ->
                if (errorCode(body) == "insufficient_quota") {
                    ProviderResponse.Failed(ProviderFailure.BILLING_NEEDED, notBilled = true, httpStatus = code)
                } else {
                    ProviderResponse.Failed(ProviderFailure.RATE_LIMITED, httpStatus = code)
                }
            code == 400 -> ProviderResponse.Failed(ProviderFailure.REJECTED, notBilled = true, httpStatus = code)
            code == 404 -> ProviderResponse.Failed(ProviderFailure.NOT_FOUND, httpStatus = code)
            code == 413 -> ProviderResponse.Failed(ProviderFailure.REQUEST_TOO_LARGE, httpStatus = code)
            code in 500..599 -> ProviderResponse.Failed(ProviderFailure.SERVER, httpStatus = code)
            else -> ProviderResponse.Failed(ProviderFailure.UNEXPECTED_STATUS, httpStatus = code)
        }

    private fun errorCode(body: String?): String? =
        body?.let {
            runCatching {
                Json
                    .parseToJsonElement(it)
                    .jsonObject["error"]
                    ?.jsonObject
                    ?.get("code")
                    ?.jsonPrimitive
                    ?.contentOrNull
            }.getOrNull()
        }

    private fun body(request: ProviderRequest): JsonObject =
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
            val declared = tools(request.toolsJson)
            if (declared.isNotEmpty()) {
                put("tools", declared)
                put("tool_choice", "auto")
            }
            put("store", false)
            put("max_output_tokens", maxOutputTokens)
            reasoningEffort?.let { putJsonObject("reasoning") { put("effort", it) } }
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

    private fun parse(text: String): ProviderResponse {
        val root =
            try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                return ProviderResponse.Failed(ProviderFailure.UNREADABLE)
            }
        // What the service reports it used is kept even when the reply is refused, so the cost of that request is known.
        val usage = usage(root)
        when (root["status"]?.jsonPrimitive?.contentOrNull) {
            null, "completed" -> Unit
            // A cut-off reply may hold half a function call, so it is not used.
            "incomplete" -> return ProviderResponse.Failed(ProviderFailure.BLOCKED, usage)
            else -> return ProviderResponse.Failed(ProviderFailure.UNREADABLE, usage)
        }
        val output = root["output"] as? JsonArray ?: return ProviderResponse.Failed(ProviderFailure.UNREADABLE, usage)
        val texts = mutableListOf<String>()
        val calls = mutableListOf<RawCall>()
        for (item in output.mapNotNull { it as? JsonObject }) {
            when (item["type"]?.jsonPrimitive?.contentOrNull) {
                "message" ->
                    for (part in (item["content"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
                        when (part["type"]?.jsonPrimitive?.contentOrNull) {
                            "output_text" -> part["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
                            // A refusal is the model declining to answer: nothing usable, nothing changed.
                            "refusal" -> return ProviderResponse.Failed(ProviderFailure.BLOCKED, usage)
                        }
                    }
                "function_call" -> {
                    val name =
                        item["name"]?.jsonPrimitive?.contentOrNull ?: return ProviderResponse.Failed(ProviderFailure.UNREADABLE, usage)
                    calls += RawCall(name, item["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}")
                }
            }
        }
        return ProviderResponse.Reply(text = texts.joinToString("").ifBlank { null }, calls = calls, usage = usage)
    }

    /**
     * `output_tokens` already includes the reasoning tokens, so they are not added again as thought tokens: the cost arithmetic
     * would count them twice.
     */
    private fun usage(root: JsonObject): ProviderUsage? =
        (root["usage"] as? JsonObject)?.let {
            fun count(name: String) = (it[name] as? JsonPrimitive)?.intOrNull
            ProviderUsage(count("input_tokens"), count("output_tokens"), null, count("total_tokens"))
        }

    private fun singleAttempt(firstStatus: AtomicInteger): OkHttpClient {
        val attempts = AtomicInteger()
        return client
            .newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .addNetworkInterceptor { chain ->
                if (attempts.incrementAndGet() > 1) throw IOException("a second attempt for one request was refused")
                chain.proceed(chain.request()).also { firstStatus.set(it.code) }
            }.build()
    }

    private suspend fun execute(
        request: Request,
        firstStatus: AtomicInteger,
    ): Response =
        suspendCancellableCoroutine { continuation ->
            val call = singleAttempt(firstStatus).newCall(request)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        if (continuation.isActive) continuation.resume(response) else response.close()
                    }
                },
            )
        }

    companion object {
        /** The model id on OpenAI's models page, read 2026-10-12. */
        const val DEFAULT_MODEL = "gpt-6-luna"
        const val DEFAULT_BASE_URL = "https://api.openai.com/v1"
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(75, TimeUnit.SECONDS)
                .build()
    }
}
