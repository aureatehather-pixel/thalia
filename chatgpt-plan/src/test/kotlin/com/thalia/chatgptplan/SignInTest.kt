package com.thalia.chatgptplan

import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignInTest {
    private val server = MockWebServer().apply { start() }
    private val now = 1_800_000_000_000L

    private val endpoints =
        PlanEndpoints(
            server.url("/authorize").toString(),
            server.url("/oauth/token").toString(),
            server.url("/v1/responses").toString(),
            server.url("/v1/models").toString(),
            "https://auth.openai.com",
            "https://api.openai.com/v1",
        )

    @AfterTest
    fun stop() = server.close()

    private fun jwt(
        aud: String,
        nonce: String,
        iss: String = "https://auth.openai.com",
        exp: Long = now / 1000 + 600,
    ) = "h." +
        Base64.getUrlEncoder().withoutPadding().encodeToString(
            """{"iss":"$iss","aud":"$aud","exp":$exp,"iat":${now / 1000},"nonce":"$nonce","sub":"user-1"}""".toByteArray(),
        ) +
        ".s"

    private fun grant(
        id: String,
        scope: String = PlanScopes.ALL,
        refresh: Boolean = true,
    ) = """{"access_token":"at","${if (refresh) "refresh_token" else "x"}":"rt","id_token":"$id","expires_in":3600,"scope":"$scope"}"""

    private val store = InMemoryCredentialStore()
    private val signIn = SignIn(TokenClient(OkHttpClient(), endpoints), store, endpoints, { true }) { now }

    private fun attempt(issued: String? = null) = AuthorizationAttempt.begin(issued, store.hostId(), 4000)

    private fun finish(
        a: AuthorizationAttempt,
        cb: CallbackResult,
    ) = runBlocking { signIn.complete(a, cb) }

    @Test
    fun `a first registration saves the issued client id and credentials with the plan scope`() {
        val a = attempt()
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(grant(jwt("oaiapp_9", a.nonce)))
                .build(),
        )
        val result = finish(a, CallbackResult.Code("c", "oaiapp_9", null))
        val saved = assertIs<SignInResult.Signed>(result).credentials
        assertEquals("oaiapp_9", saved.clientId)
        assertEquals("oaiapp_9", store.issuedClientId())
        assertTrue(saved.mayUsePlan)
        assertEquals(saved, store.load())
        val form = server.takeRequest().body!!.utf8()
        assertTrue("client_id=oaiapp_9" in form && "code_verifier=${a.verifier}" in form)
    }

    @Test
    fun `a registration without an issued client id is incomplete and saves nothing`() {
        val a = attempt()
        assertEquals(SignInResult.Failed("registration_incomplete"), finish(a, CallbackResult.Code("c", null, null)))
        assertNull(store.load())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a reauthorization that comes back with a different client id is refused before the code is spent`() {
        assertEquals(SignInResult.Failed("client_id_changed"), finish(attempt("oaiapp_1"), CallbackResult.Code("c", "oaiapp_2", null)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a denial or a timeout spends no code`() {
        assertEquals(SignInResult.Failed("denied:access_denied"), finish(attempt(), CallbackResult.Denied("access_denied")))
        assertEquals(SignInResult.Failed("timed_out"), finish(attempt(), CallbackResult.TimedOut))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an ID token for another audience, issuer or nonce, or a missing refresh token, saves nothing`() {
        val a = attempt()
        listOf(
            grant(jwt("someone-else", a.nonce)) to "id_token_audience",
            grant(jwt("oaiapp_9", a.nonce, iss = "https://evil")) to "id_token_issuer",
            grant(jwt("oaiapp_9", "other-nonce")) to "id_token_nonce",
            grant(jwt("oaiapp_9", a.nonce, exp = 5)) to "id_token_expired",
            grant(jwt("oaiapp_9", a.nonce), refresh = false) to "no_refresh_token",
        ).forEach { (body, problem) ->
            server.enqueue(
                MockResponse
                    .Builder()
                    .code(200)
                    .body(body)
                    .build(),
            )
            assertEquals(SignInResult.Failed(problem), finish(a, CallbackResult.Code("c", "oaiapp_9", null)))
        }
        assertNull(store.load())
    }

    @Test
    fun `a rejected or unavailable exchange is reported by code only`() {
        val a = attempt()
        server.enqueue(
            MockResponse
                .Builder()
                .code(400)
                .body("""{"error":"invalid_grant","error_description":"secret"}""")
                .build(),
        )
        assertEquals(SignInResult.Failed("exchange_rejected:invalid_grant"), finish(a, CallbackResult.Code("c", "oaiapp_9", null)))
        server.enqueue(MockResponse.Builder().code(503).build())
        assertEquals(SignInResult.Failed("exchange_unavailable"), finish(a, CallbackResult.Code("c", "oaiapp_9", null)))
    }

    @Test
    fun `a grant without the plan scope is saved and shown as unable to use the plan`() {
        val a = attempt()
        server.enqueue(
            MockResponse
                .Builder()
                .code(200)
                .body(grant(jwt("oaiapp_9", a.nonce), scope = PlanScopes.IDENTITY))
                .build(),
        )
        val saved = assertIs<SignInResult.Signed>(finish(a, CallbackResult.Code("c", "oaiapp_9", null))).credentials
        assertEquals(false, saved.mayUsePlan)
    }

    private fun analyze(
        body: String,
        type: String? = "application/json; charset=utf-8",
    ) = ModelCatalog.analyze(body, type)

    @Test
    fun `the documented shape keeps entries whose visibility is list, by slug, in the server's order`() {
        val r =
            analyze(
                """{"models":[{"slug":"b","display_name":"B","visibility":"list"},{"slug":"hidden","visibility":"hide"},{"slug":"a","visibility":"list"}]}""",
            )
        assertEquals(DiscoveryOutcome.MODELS, r.outcome)
        assertEquals(listOf("b", "a"), r.slugs)
        assertTrue("content-type=application/json" in r.summary && "charset" !in r.summary, r.summary)
        assertTrue("entries=3" in r.summary && "listed=2" in r.summary && "visibility=[list:2,hide:1]" in r.summary, r.summary)
        assertTrue("firstEntryKeys=[slug,display_name,visibility]" in r.summary, r.summary)
    }

    @Test
    fun `invalid json, an unsupported shape, an empty list, all hidden and missing slugs are told apart`() {
        assertEquals(DiscoveryOutcome.INVALID_JSON, analyze("not json").outcome)
        assertEquals(DiscoveryOutcome.INVALID_JSON, analyze("").outcome)
        // Shapes the documentation does not describe are reported, not accepted.
        for (body in listOf(
            """[{"slug":"y","visibility":"list"}]""",
            """{"data":[{"slug":"x","visibility":"list"}]}""",
            """{"models":{}}""",
            "42",
            "null",
        )) {
            val r = analyze(body)
            assertEquals(DiscoveryOutcome.UNSUPPORTED_SHAPE, r.outcome, body)
            assertEquals(emptyList(), r.slugs, body)
        }
        assertEquals(DiscoveryOutcome.EMPTY, analyze("""{"models":[]}""").outcome)
        assertEquals(
            DiscoveryOutcome.ALL_FILTERED_BY_VISIBILITY,
            analyze("""{"models":[{"slug":"a","visibility":"hide"},{"slug":"b"}]}""").outcome,
        )
        // The documented filter is visibility == "list": an entry with no visibility is not listed, and id is not slug.
        assertEquals(emptyList(), analyze("""{"models":[{"slug":"b"}]}""").slugs)
        assertEquals(
            DiscoveryOutcome.MISSING_IDENTIFIERS,
            analyze("""{"models":[{"id":"x","visibility":"list"},{"slug":"","visibility":"list"}]}""").outcome,
        )
    }

    @Test
    fun `the summary has counts and plain names only, never a value, a header or any part of the body`() {
        val secret = "sk-SECRET-TOKEN-1234567890"
        val r =
            analyze(
                """{"models":[{"slug":"$secret","display_name":"$secret","visibility":"list","weird key with spaces":"$secret"},""" +
                    """{"slug":"h","visibility":"$secret"}, 7],"$secret":1}""",
                "application/json; boundary=$secret",
            )
        assertEquals(DiscoveryOutcome.MODELS, r.outcome)
        assertFalse(secret in r.summary, r.summary)
        assertFalse("Bearer" in r.summary || "Authorization" in r.summary)
        assertTrue("nonObject=1" in r.summary && "listedWithoutSlug=0" in r.summary, r.summary)
        assertTrue("keys=[models,?]" in r.summary && "visibility=[list:1,?:1]" in r.summary, r.summary)
        assertTrue("firstEntryKeys=[slug,display_name,visibility,?]" in r.summary, r.summary)
        assertEquals("content-type=none", analyze("{}", "not a media type!").summary.substringBefore(" bytes"))
        assertTrue("truncated=true" in ModelCatalog.analyze("{", null, truncated = true).summary)
    }

    @Test
    fun `an ID token whose signature does not verify saves nothing`() {
        val strict = SignIn(TokenClient(OkHttpClient(), endpoints), store, endpoints, { false }) { now }
        val a = attempt()
        server.enqueue(MockResponse.Builder().body(grant(jwt("oaiapp_1", a.nonce))).build())
        val result = runBlocking { strict.complete(a, CallbackResult.Code("c", "oaiapp_1", null)) }
        assertEquals(SignInResult.Failed("id_token_signature"), result)
        assertNull(store.load())
    }
}
