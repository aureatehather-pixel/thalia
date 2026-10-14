package com.thalia.chatgptplan

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The browser sign-in end to end over a real loopback socket, with the browser played by a thread and the token endpoint by a local server. */
class SignInFlowTest {
    private val server = MockWebServer()
    private var nonce = ""
    private val endpoints get() =
        PlanEndpoints(
            "https://auth.openai.com/api/accounts/authorize",
            server.url("/oauth/token").toString(),
            server.url("/v1/responses").toString(),
            server.url("/v1/models").toString(),
            "https://auth.openai.com",
            "https://api.openai.com/v1",
        )

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val idToken =
                        "h." +
                            Base64.getUrlEncoder().withoutPadding().encodeToString(
                                """{"iss":"https://auth.openai.com","aud":"oaiapp_77","exp":${System.currentTimeMillis() / 1000 + 600},"iat":${System.currentTimeMillis() / 1000},"nonce":"$nonce","sub":"u"}"""
                                    .toByteArray(),
                            ) +
                            ".s"
                    return MockResponse
                        .Builder()
                        .code(
                            200,
                        ).body(
                            """{"access_token":"at","refresh_token":"rt","id_token":"$idToken","expires_in":3600,"scope":"${PlanScopes.ALL}"}""",
                        ).build()
                }
            }
        server.start()
    }

    @AfterTest
    fun stop() = server.close()

    private val http = OkHttpClient()

    /** The browser: reads the authorization URL like a person would be sent to it, then lands on the redirect with the code. */
    private fun browser(
        state: (String) -> String = { it },
        clientId: String? = "oaiapp_77",
    ): (String) -> Unit =
        { url ->
            val auth = url.toHttpUrl()
            nonce = auth.queryParameter("nonce")!!
            val redirect = auth.queryParameter("redirect_uri")!!.toHttpUrl()
            thread {
                val target =
                    redirect
                        .newBuilder()
                        .addQueryParameter(
                            "code",
                            "the-code",
                        ).addQueryParameter("state", state(auth.queryParameter("state")!!))
                        .apply {
                            clientId?.let { addQueryParameter("client_id", it) }
                        }.build()
                runCatching { http.newCall(Request.Builder().url(target).build()).execute().close() }
            }
        }

    @Test
    fun `a full sign-in registers, exchanges the code over the loopback redirect, and saves sealed-ready credentials`() {
        val store = InMemoryCredentialStore()
        val flow = SignInFlow(store, TokenClient(http, endpoints), endpoints, { true })
        val result = runBlocking { flow.run(browser(), 10_000) }
        val saved = assertIs<SignInResult.Signed>(result).credentials
        assertEquals("oaiapp_77", saved.clientId)
        assertEquals("oaiapp_77", store.issuedClientId())
        assertTrue(saved.mayUsePlan)
        val form = server.takeRequest().body!!.utf8()
        assertTrue("code=the-code" in form && "redirect_uri=http%3A%2F%2F127.0.0.1%3A" in form)
    }

    @Test
    fun `a callback with the wrong state is ignored and the attempt times out without exchanging anything`() {
        val store = InMemoryCredentialStore()
        val flow = SignInFlow(store, TokenClient(http, endpoints), endpoints, { true })
        val result = runBlocking { flow.run(browser(state = { "forged" }), 600) }
        assertEquals(SignInResult.Failed("timed_out"), result)
        assertEquals(0, server.requestCount)
        assertNull(store.load())
    }

    @Test
    fun `a registration whose callback has no client id is incomplete and nothing is saved`() {
        val store = InMemoryCredentialStore()
        val flow = SignInFlow(store, TokenClient(http, endpoints), endpoints, { true })
        assertEquals(SignInResult.Failed("registration_incomplete"), runBlocking { flow.run(browser(clientId = null), 10_000) })
        assertNull(store.load())
    }
}
