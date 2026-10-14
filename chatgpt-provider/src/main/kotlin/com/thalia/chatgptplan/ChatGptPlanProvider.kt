package com.thalia.chatgptplan

import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The local gates in front of every request: consent for this route, and a hard count of requests. Decided before anything is sent. */
interface PlanGates {
    fun consented(): Boolean

    /** Counts one request before it is sent. False means the cap is reached and nothing may be sent. Uncertain requests stay counted. */
    fun tryReserve(): Boolean

    companion object {
        val OPEN =
            object : PlanGates {
                override fun consented() = true

                override fun tryReserve() = true
            }
    }
}

/** Why the last request ended as it did, in the plan's own terms, for the screen. Never a body, header or token. */
enum class PlanIssue {
    NONE,
    USAGE_LIMIT,
    USAGE_UNAVAILABLE,
    SIGNED_OUT,
    NO_PLAN_SCOPE,
    NO_MODEL,
    STREAM_INTERRUPTED,
    INCOMPLETE,
    SERVICE_ERROR,
    NETWORK,
}

/** Refuses any host that is not one of OpenAI's, and any redirect. A plan token goes nowhere else. */
class HostAllowlist(
    private val allowed: Set<String>,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        if (chain.request().url.host !in allowed) throw IOException("host not allowed")
        val response = chain.proceed(chain.request())
        if (response.isRedirect) {
            response.close()
            throw IOException("redirects are not followed")
        }
        return response
    }
}

fun planHttpClient(endpoints: PlanEndpoints): OkHttpClient =
    OkHttpClient
        .Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(HostAllowlist(endpoints.allowedHosts))
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

/**
 * A reply from the signed-in user's ChatGPT plan, through the Responses API, as a [ConversationProvider]: one request, one
 * reply, function calls returned as [com.thalia.conversation.RawCall] text and never run. There is **no other credential and no
 * fallback**: no API key parameter exists, a limit or an error is reported and nothing else is tried, and the only token ever
 * sent is the plan access token from [session]. A 401 renews the token once and retries once; every other failure is final.
 */
class ChatGptPlanProvider(
    private val session: PlanSession,
    private val endpoints: PlanEndpoints,
    private val client: OkHttpClient,
    private val model: () -> String?,
    private val gates: PlanGates = PlanGates.OPEN,
    /** False: a 401 is final. No token renewal and no second inference request follow it. */
    private val renewAndRetryOn401: Boolean = true,
) : ConversationProvider {
    @Volatile var lastIssue: PlanIssue = PlanIssue.NONE
        private set

    private fun fail(
        failure: ProviderFailure,
        issue: PlanIssue,
        status: Int? = null,
    ): ProviderResponse {
        lastIssue = issue
        return ProviderResponse.Failed(failure, httpStatus = status)
    }

    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        lastIssue = PlanIssue.NONE
        if (!gates.consented()) return fail(ProviderFailure.CONSENT_NEEDED, PlanIssue.NONE)
        val chosen = model() ?: return fail(ProviderFailure.MODEL_NEEDED, PlanIssue.NO_MODEL)
        val body = ResponsesRequest.body(chosen, request).toString().toRequestBody(JSON)
        var token =
            when (val access = session.accessToken()) {
                is AccessResult.Ok -> access.accessToken
                is AccessResult.SignedOut -> return fail(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.SIGNED_OUT)
                AccessResult.NoPlanScope -> return fail(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.NO_PLAN_SCOPE)
                AccessResult.Transient -> return fail(ProviderFailure.NETWORK, PlanIssue.NETWORK)
            }
        repeat(2) { attempt ->
            if (!gates.tryReserve()) return fail(ProviderFailure.BUDGET_EXHAUSTED, PlanIssue.NONE)
            val outcome = send(token, body)
            if (!renewAndRetryOn401 || outcome !is Sent.Status || outcome.code != 401 || attempt == 1) return outcome.toResponse()
            token =
                when (val renewed = session.renewAfterRejection(token)) {
                    is AccessResult.Ok -> renewed.accessToken
                    is AccessResult.SignedOut -> return fail(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.SIGNED_OUT, 401)
                    AccessResult.NoPlanScope -> return fail(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.NO_PLAN_SCOPE, 401)
                    AccessResult.Transient -> return fail(ProviderFailure.NETWORK, PlanIssue.NETWORK, 401)
                }
        }
        return fail(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.SIGNED_OUT, 401)
    }

    private sealed interface Sent {
        data class Status(
            val code: Int,
            val errorCode: String?,
        ) : Sent

        data class Stream(
            val outcome: StreamOutcome,
        ) : Sent

        data object Network : Sent
    }

    private suspend fun send(
        token: String,
        body: okhttp3.RequestBody,
    ): Sent =
        withContext(Dispatchers.IO) {
            val http =
                Request
                    .Builder()
                    .url(endpoints.responsesUrl)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "text/event-stream")
                    .post(body)
                    .build()
            try {
                client.newCall(http).execute().use { response ->
                    if (!response.isSuccessful) {
                        Sent.Status(response.code, errorCode(response.peekBody(MAX_ERROR_BYTES).string()))
                    } else {
                        val source = response.body.source()
                        Sent.Stream(SseParser.parse(generateSequence { source.readUtf8Line() }.iterator()))
                    }
                }
            } catch (e: IOException) {
                Sent.Network
            }
        }

    private fun errorCode(text: String): String? =
        runCatching {
            Json
                .parseToJsonElement(text)
                .jsonObject["error"]
                ?.jsonObject
                ?.get("code")
                ?.jsonPrimitive
                ?.contentOrNull
        }.getOrNull()

    private fun Sent.toResponse(): ProviderResponse =
        when (this) {
            Sent.Network -> fail(ProviderFailure.NETWORK, PlanIssue.NETWORK)
            is Sent.Status -> status(code, errorCode)
            is Sent.Stream -> stream(outcome)
        }

    private fun status(
        code: Int,
        errorCode: String?,
    ): ProviderResponse =
        when {
            errorCode == LIMIT_CODE -> fail(ProviderFailure.USAGE_LIMIT, PlanIssue.USAGE_LIMIT, code)
            errorCode == UNAVAILABLE_CODE -> fail(ProviderFailure.USAGE_UNAVAILABLE, PlanIssue.USAGE_UNAVAILABLE, code)
            code == 429 -> fail(ProviderFailure.RATE_LIMITED, PlanIssue.USAGE_LIMIT, code)
            code == 401 -> fail(ProviderFailure.SIGN_IN_NEEDED, PlanIssue.SIGNED_OUT, code)
            // What a 403 means on this route is not documented, so no sign-out is claimed: it is reported as an unexpected status.
            code == 403 -> fail(ProviderFailure.UNEXPECTED_STATUS, PlanIssue.SERVICE_ERROR, code)
            code == 400 -> fail(ProviderFailure.REJECTED, PlanIssue.SERVICE_ERROR, code)
            code == 404 -> fail(ProviderFailure.NOT_FOUND, PlanIssue.NO_MODEL, code)
            code == 413 -> fail(ProviderFailure.REQUEST_TOO_LARGE, PlanIssue.SERVICE_ERROR, code)
            code in 500..599 -> fail(ProviderFailure.SERVER, PlanIssue.SERVICE_ERROR, code)
            else -> fail(ProviderFailure.UNEXPECTED_STATUS, PlanIssue.SERVICE_ERROR, code)
        }

    private fun stream(outcome: StreamOutcome): ProviderResponse =
        when (outcome) {
            is StreamOutcome.Completed -> ProviderResponse.Reply(outcome.text, outcome.calls, outcome.usage)
            is StreamOutcome.Incomplete -> fail(ProviderFailure.BLOCKED, PlanIssue.INCOMPLETE)
            is StreamOutcome.Failed ->
                when (outcome.code) {
                    LIMIT_CODE -> fail(ProviderFailure.USAGE_LIMIT, PlanIssue.USAGE_LIMIT)
                    UNAVAILABLE_CODE -> fail(ProviderFailure.USAGE_UNAVAILABLE, PlanIssue.USAGE_UNAVAILABLE)
                    else -> fail(ProviderFailure.SERVER, PlanIssue.SERVICE_ERROR)
                }
            StreamOutcome.Interrupted -> fail(ProviderFailure.UNREADABLE, PlanIssue.STREAM_INTERRUPTED)
            StreamOutcome.Malformed -> fail(ProviderFailure.UNREADABLE, PlanIssue.SERVICE_ERROR)
        }

    companion object {
        const val LIMIT_CODE = "subscription_sharing_usage_limit_exceeded"
        const val UNAVAILABLE_CODE = "subscription_sharing_usage_unavailable"
        private const val MAX_ERROR_BYTES = 8_192L
        private val JSON = "application/json".toMediaType()
    }
}
