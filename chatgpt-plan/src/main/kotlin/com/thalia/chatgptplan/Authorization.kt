package com.thalia.chatgptplan

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** PKCE (RFC 7636) with S256. */
object Pkce {
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(): String = ByteArray(64).also(random::nextBytes).let(encoder::encodeToString)

    fun challenge(verifier: String): String =
        encoder.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))

    fun newToken(): String = ByteArray(24).also(random::nextBytes).let(encoder::encodeToString)
}

/**
 * One sign-in attempt: fresh state, nonce and verifier, and the exact redirect URI. The same [redirectUri], port included, goes
 * in the authorization request and the code exchange. [clientId] is the issued id, or the dynamic registration value.
 */
data class AuthorizationAttempt(
    val clientId: String,
    val registering: Boolean,
    val hostId: String,
    val redirectUri: String,
    val state: String,
    val nonce: String,
    val verifier: String,
) {
    fun url(endpoints: PlanEndpoints): String =
        endpoints.authorizeUrl
            .toHttpUrl()
            .newBuilder()
            .addQueryParameter("client_id", clientId)
            .addQueryParameter("response_type", "code")
            .addQueryParameter("redirect_uri", redirectUri)
            .addQueryParameter("scope", PlanScopes.ALL)
            .addQueryParameter("resource", endpoints.resource)
            .addQueryParameter("state", state)
            .addQueryParameter("nonce", nonce)
            .addQueryParameter("code_challenge", Pkce.challenge(verifier))
            .addQueryParameter("code_challenge_method", "S256")
            .addQueryParameter("ext_agent_host_id", hostId)
            .apply { if (registering) addQueryParameter("agent_name_hint", PlanClient.AGENT_NAME) }
            .build()
            .toString()

    companion object {
        /** [issuedClientId] is null for a first registration. The redirect is loopback 127.0.0.1, never localhost. */
        fun begin(
            issuedClientId: String?,
            hostId: String,
            port: Int,
        ) = AuthorizationAttempt(
            clientId = issuedClientId ?: PlanClient.DYNAMIC_CLIENT_ID,
            registering = issuedClientId == null,
            hostId = hostId,
            redirectUri = "http://127.0.0.1:$port${PlanClient.CALLBACK_PATH}",
            state = Pkce.newToken(),
            nonce = Pkce.newToken(),
            verifier = Pkce.newVerifier(),
        )
    }
}

sealed interface CallbackResult {
    /** [clientId] is present on a new registration and must be kept; it is checked against the attempt on reauthorization. */
    data class Code(
        val code: String,
        val clientId: String?,
        val scope: String?,
    ) : CallbackResult

    data class Denied(
        val error: String,
    ) : CallbackResult

    data object TimedOut : CallbackResult
}

/** Reads a callback request target such as `/auth/callback?code=...&state=...`. */
object CallbackParser {
    sealed interface Parsed {
        data class Ok(
            val result: CallbackResult,
        ) : Parsed

        /** Not the callback path (a favicon request, say). Answered 404 and ignored. */
        data object OtherPath : Parsed

        /** The state is missing or is not this attempt's. Never used; answered 400 and ignored. */
        data object BadState : Parsed

        data object Malformed : Parsed
    }

    fun parse(
        target: String,
        expectedState: String,
    ): Parsed {
        val url = ("http://127.0.0.1" + target).toHttpUrlOrNull() ?: return Parsed.Malformed
        if (url.encodedPath != PlanClient.CALLBACK_PATH) return Parsed.OtherPath
        if (url.queryParameter("state") != expectedState) return Parsed.BadState
        url.queryParameter("error")?.let { return Parsed.Ok(CallbackResult.Denied(it)) }
        val code = url.queryParameter("code")?.takeIf { it.isNotBlank() } ?: return Parsed.Malformed
        return Parsed.Ok(CallbackResult.Code(code, url.queryParameter("client_id"), url.queryParameter("scope")))
    }

    private fun String.toHttpUrlOrNull() = runCatching { toHttpUrl() }.getOrNull()
}

/**
 * A one-shot HTTP listener on 127.0.0.1 only, started before the browser opens. It answers the callback with a plain page and
 * ignores everything that is not a valid callback for this attempt's state. Blocking: callers run [await] off the main thread.
 *
 * A connection that is accepted after the deadline has passed is answered as expired and ends the wait as [CallbackResult.TimedOut]
 * without being read. Android can freeze a backgrounded process, and a browser callback then waits in the accept queue until the
 * thaw (observed once, see docs/slice6-chatgpt-plan-proof.md section 7o); by then the attempt is over and its code is stale, so it
 * is not exchanged. [clock] is wall time, which keeps running while the process is frozen.
 */
class LoopbackListener(
    private val clock: () -> Long = System::currentTimeMillis,
) : AutoCloseable {
    private val loopback: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    private val server = ServerSocket(0, 4, loopback)
    val port: Int get() = server.localPort

    /** The address it is bound to. Always 127.0.0.1. */
    val localAddress: String get() = server.inetAddress.hostAddress

    fun await(
        expectedState: String,
        timeoutMillis: Long,
    ): CallbackResult {
        val deadline = clock() + timeoutMillis
        while (true) {
            val left = deadline - clock()
            if (left <= 0) return CallbackResult.TimedOut
            server.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val socket =
                try {
                    server.accept()
                } catch (e: SocketTimeoutException) {
                    return CallbackResult.TimedOut
                }
            socket.use {
                if (clock() >= deadline) {
                    // The request is read and dropped so the browser gets its answer rather than a reset; its content is not used.
                    runCatching {
                        it.soTimeout = 3_000
                        it.getInputStream().bufferedReader().readLine()
                    }
                    reply(it, "400 Bad Request", "That sign-in expired. Start it again in Thalia.")
                    return CallbackResult.TimedOut
                }
                handled(it, expectedState)?.let { result -> return result }
            }
        }
    }

    private fun handled(
        socket: Socket,
        expectedState: String,
    ): CallbackResult? {
        socket.soTimeout = 3_000
        val line = runCatching { socket.getInputStream().bufferedReader().readLine() }.getOrNull().orEmpty()
        val target = line.split(' ').getOrNull(1).orEmpty()
        val parsed =
            if (line.startsWith("GET ") &&
                target.startsWith("/")
            ) {
                CallbackParser.parse(target, expectedState)
            } else {
                CallbackParser.Parsed.Malformed
            }
        val (status, body) =
            when (parsed) {
                is CallbackParser.Parsed.Ok -> "200 OK" to "You can return to Thalia now."
                CallbackParser.Parsed.OtherPath -> "404 Not Found" to "Not found."
                else -> "400 Bad Request" to "That sign-in response was not accepted."
            }
        reply(socket, status, body)
        return (parsed as? CallbackParser.Parsed.Ok)?.result
    }

    private fun reply(
        socket: Socket,
        status: String,
        body: String,
    ) {
        runCatching {
            socket.getOutputStream().apply {
                write(
                    (
                        "HTTP/1.1 $status\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Length: ${body.length}\r\n" +
                            "Connection: close\r\n\r\n$body"
                    ).toByteArray(),
                )
                flush()
            }
        }
    }

    override fun close() = server.close()
}
