package com.thalia.voicecheckins.gemini

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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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
 * Gemini's `generateContent` as a [ConversationProvider]: one POST per reply, function declarations from the caller, and
 * the model's function calls returned as [RawCall] text. It never runs a function and never sends a function result back:
 * what a call may do is decided by Thalia's validator, so the model only ever proposes. The calling mode is AUTO, so the
 * model may also answer in plain text.
 *
 * Why this and not a Gemini SDK: the choice and its evidence are in ADR 0010. In short, it is one request and one reply
 * on an OkHttp this module already has, so an SDK would add a dependency tree and a mapping layer for no less code.
 *
 * The key is read from [apiKey] per request, sent in the `x-goog-api-key` header and never in a URL, a log or a failure.
 * A missing key is [ProviderFailure.UNAVAILABLE] without a request. Nothing here decides whether a request may be sent:
 * consent and cost gating belong to the caller, before it constructs or calls this.
 *
 * The request and reply shapes follow Gemini's REST reference for `v1beta`, read on 2026-10-06. They are exercised here
 * only against a local fake server; no request to Google has been made.
 */
class GeminiTextConversationProvider(
    private val apiKey: () -> GeminiApiKey?,
    private val client: OkHttpClient = defaultClient(),
    private val model: String = DEFAULT_MODEL,
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val maxOutputTokens: Int = ContextLimits.OUTPUT_TOKENS,
) : ConversationProvider {
    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        val key = apiKey() ?: return ProviderResponse.Failed(ProviderFailure.UNAVAILABLE)
        val http =
            Request
                .Builder()
                .url("$baseUrl/models/$model:generateContent")
                .header("x-goog-api-key", key.value)
                .post(body(request).toString().toRequestBody(JSON))
                .build()
        val firstStatus = AtomicInteger(0)
        return try {
            execute(http, firstStatus).use { response ->
                val text = response.body.string()
                when {
                    response.isSuccessful -> parse(text)
                    // notBilled only for the two statuses Google's billing page documents as uncharged: "If your request fails
                    // with a 400 or 500 error, you won't be charged for the tokens used." Every other status, including 401,
                    // 403 and 429, is undocumented, so its allowance stays counted as uncertain (ADR 0013).
                    // The status is kept (and only the status: never the body or a header) so a failure can be told apart.
                    else -> failed(response.code)
                }
            }
        } catch (e: IOException) {
            // A refused second attempt is the answer to the first one, so report that status. A success whose body then failed to
            // arrive, or no answer at all, is a network failure.
            firstStatus.get().takeIf { it != 0 && it !in 200..299 }?.let(::failed) ?: ProviderResponse.Failed(ProviderFailure.NETWORK)
        }
    }

    private fun failed(code: Int): ProviderResponse.Failed =
        when {
            code == 401 || code == 403 -> ProviderResponse.Failed(ProviderFailure.AUTH, httpStatus = code)
            code == 429 -> ProviderResponse.Failed(ProviderFailure.RATE_LIMITED, httpStatus = code)
            code == 400 -> ProviderResponse.Failed(ProviderFailure.REJECTED, notBilled = true, httpStatus = code)
            code == 404 -> ProviderResponse.Failed(ProviderFailure.NOT_FOUND, httpStatus = code)
            code == 413 -> ProviderResponse.Failed(ProviderFailure.REQUEST_TOO_LARGE, httpStatus = code)
            code in 500..599 -> ProviderResponse.Failed(ProviderFailure.SERVER, notBilled = code == 500, httpStatus = code)
            else -> ProviderResponse.Failed(ProviderFailure.UNEXPECTED_STATUS, httpStatus = code)
        }

    private fun body(request: ProviderRequest): JsonObject =
        buildJsonObject {
            putJsonObject("systemInstruction") {
                putJsonArray("parts") {
                    add(
                        buildJsonObject {
                            put(
                                "text",
                                request.instruction + "\n\nSnapshot of the person's planning data:\n" + request.contextText,
                            )
                        },
                    )
                }
            }
            putJsonArray("contents") {
                for (turn in request.turns) {
                    add(
                        buildJsonObject {
                            put("role", if (turn.role == TurnRole.USER) "user" else "model")
                            putJsonArray("parts") { add(buildJsonObject { put("text", turn.text) }) }
                        },
                    )
                }
            }
            putJsonArray("tools") {
                add(buildJsonObject { put("functionDeclarations", Json.parseToJsonElement(request.toolsJson)) })
            }
            putJsonObject("toolConfig") { putJsonObject("functionCallingConfig") { put("mode", "AUTO") } }
            // No temperature: for Gemini 3 models Google "strongly recommend[s] keeping the temperature parameter at its default
            // value of 1.0" because lowering it may cause looping or degraded performance (ai.google.dev/gemini-api/docs/gemini-3,
            // read 2026-10-08). No thinking setting either: the generateContent field for it is not documented on the pages read,
            // so thinking stays at the model's default, and its tokens count against maxOutputTokens (ADR 0013).
            putJsonObject("generationConfig") {
                put("maxOutputTokens", maxOutputTokens)
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
        if (root["promptFeedback"]?.jsonObject?.get("blockReason") != null) return ProviderResponse.Failed(ProviderFailure.BLOCKED, usage)
        val candidate =
            (root["candidates"] as? JsonArray)?.firstOrNull()?.jsonObject
                ?: return ProviderResponse.Failed(ProviderFailure.BLOCKED, usage)
        // A reply cut off or stopped for another reason may hold half a function call, so it is not used.
        when (candidate["finishReason"]?.jsonPrimitive?.contentOrNull) {
            null, "STOP" -> Unit
            "MALFORMED_FUNCTION_CALL" -> return ProviderResponse.Failed(ProviderFailure.UNREADABLE, usage)
            else -> return ProviderResponse.Failed(ProviderFailure.BLOCKED, usage)
        }
        val parts =
            candidate["content"]
                ?.jsonObject
                ?.get("parts")
                ?.jsonArray
                .orEmpty()
        val texts = mutableListOf<String>()
        val calls = mutableListOf<RawCall>()
        for (part in parts.map { it.jsonObject }) {
            if ((part["thought"] as? JsonPrimitive)?.booleanOrNull == true) continue
            part["text"]?.jsonPrimitive?.contentOrNull?.let { texts += it }
            part["functionCall"]?.jsonObject?.let { call ->
                val name = call["name"]?.jsonPrimitive?.contentOrNull ?: return ProviderResponse.Failed(ProviderFailure.UNREADABLE, usage)
                calls += RawCall(name, (call["args"] ?: JsonObject(emptyMap())).toString())
            }
        }
        return ProviderResponse.Reply(text = texts.joinToString("").ifBlank { null }, calls = calls, usage = usage)
    }

    private fun usage(root: JsonObject): ProviderUsage? =
        (root["usageMetadata"] as? JsonObject)?.let {
            fun count(name: String) = (it[name] as? JsonPrimitive)?.intOrNull
            ProviderUsage(count("promptTokenCount"), count("candidatesTokenCount"), count("thoughtsTokenCount"), count("totalTokenCount"))
        }

    /**
     * A client that makes exactly one network attempt per call. OkHttp would otherwise resend by itself on a connection
     * failure, on a 408, on a 503 that says `Retry-After: 0`, and across redirects, each a second billable request the
     * gates know nothing about. The interceptor is new for every call, so it counts that call's attempts only.
     */
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
        /** The stable Flash model listed on Gemini's models page on 2026-10-06. Confirm it before the first real request. */
        const val DEFAULT_MODEL = "gemini-3.8-flash"
        const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
