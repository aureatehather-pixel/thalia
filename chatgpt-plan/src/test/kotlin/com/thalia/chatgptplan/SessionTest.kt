package com.thalia.chatgptplan

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SessionTest {
    private val server = MockWebServer().apply { start() }
    private var now = 10_000_000L

    private val endpoints =
        PlanEndpoints(
            server.url("/authorize").toString(),
            server.url("/api/accounts/oauth/token").toString(),
            server.url("/v1/responses").toString(),
            server.url("/v1/models").toString(),
            "https://auth.openai.com",
            "https://api.openai.com/v1",
        )

    @AfterTest
    fun stop() = server.close()

    private fun creds(
        expiresIn: Long = 3_600_000,
        scopes: List<String> = PlanScopes.ALL.split(' '),
    ) = PlanCredentials(
        "https://auth.openai.com",
        "sub",
        "oaiapp_1",
        "urn:uuid:h",
        null,
        "access-1",
        "refresh-1",
        scopes,
        now + expiresIn,
        now,
    )

    private fun session(store: CredentialStore) = PlanSession(store, TokenClient(OkHttpClient(), endpoints), { now })

    private fun json(
        code: Int,
        body: String,
    ) = server.enqueue(
        MockResponse
            .Builder()
            .code(code)
            .body(body)
            .build(),
    )

    private val refreshed = """{"access_token":"access-2","refresh_token":"refresh-2","expires_in":3600,"scope":"${PlanScopes.ALL}"}"""

    @Test
    fun `the code exchange sends exactly the documented fields and no secret`() {
        json(200, refreshed)
        val result =
            runBlocking {
                TokenClient(
                    OkHttpClient(),
                    endpoints,
                ).exchange("oaiapp_1", "the-code", "the-verifier", "http://127.0.0.1:1/auth/callback")
            }
        assertIs<TokenResult.Ok>(result)
        val form =
            server
                .takeRequest()
                .body!!
                .utf8()
                .split('&')
                .associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals(setOf("grant_type", "client_id", "code", "code_verifier", "redirect_uri", "resource"), form.keys)
        assertEquals("authorization_code", form["grant_type"])
        assertEquals("the-verifier", form["code_verifier"])
    }

    @Test
    fun `a token that is still good is used without a network call`() {
        val s = session(InMemoryCredentialStore(creds()))
        assertEquals("access-1", assertIs<AccessResult.Ok>(runBlocking { s.accessToken() }).accessToken)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `an expiring token is renewed, the rotated pair is saved before use, and the old refresh token is gone`() {
        json(200, refreshed)
        val store = InMemoryCredentialStore(creds(expiresIn = 30_000))
        val result = runBlocking { session(store).accessToken() }
        assertEquals("access-2", assertIs<AccessResult.Ok>(result).accessToken)
        assertEquals(1, store.saves)
        assertEquals("refresh-2", store.load()!!.refreshToken)
        assertEquals(now + 3_600_000, store.load()!!.expiresAtMillis)
        val form = server.takeRequest().body!!.utf8()
        assertTrue("grant_type=refresh_token" in form && "refresh_token=refresh-1" in form)
        // Only the documented refresh fields: nothing documents a resource or scope on refresh.
        assertEquals(setOf("grant_type", "client_id", "refresh_token"), form.split('&').map { it.substringBefore('=') }.toSet())
    }

    @Test
    fun `only the documented terminal refresh errors sign the person out, other refusals keep the tokens`() {
        for (error in listOf("invalid_client", "invalid_request", "invalid_target")) {
            json(400, "{\"error\":\"$error\"}")
            val store = InMemoryCredentialStore(creds(expiresIn = 0))
            assertEquals(AccessResult.Transient, runBlocking { session(store).accessToken() })
            assertEquals("refresh-1", store.load()!!.refreshToken)
        }
        for (error in listOf("refresh_token_reused", "refresh_token_expired", "invalid_refresh_token")) {
            json(400, "{\"error\":\"$error\"}")
            val store = InMemoryCredentialStore(creds(expiresIn = 0))
            assertEquals(AccessResult.SignedOut(revoked = true), runBlocking { session(store).accessToken() })
            assertNull(store.load())
        }
    }

    @Test
    fun `concurrent callers share one renewal, because a rotated refresh token cannot be spent twice`() {
        json(200, refreshed)
        val store = InMemoryCredentialStore(creds(expiresIn = 0))
        val s = session(store)
        val results = runBlocking { (1..5).map { async { s.accessToken() } }.awaitAll() }
        assertTrue(results.all { it is AccessResult.Ok && it.accessToken == "access-2" })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a rejected refresh token signs the person out locally and says it was revoked`() {
        json(400, """{"error":"invalid_grant"}""")
        val store = InMemoryCredentialStore(creds(expiresIn = 0))
        assertEquals(AccessResult.SignedOut(revoked = true), runBlocking { session(store).accessToken() })
        assertNull(store.load())
    }

    @Test
    fun `a renewal outage or a 429 changes nothing and keeps the person signed in`() {
        json(503, "{}")
        json(429, "{}")
        val store = InMemoryCredentialStore(creds(expiresIn = 0))
        val s = session(store)
        assertEquals(AccessResult.Transient, runBlocking { s.accessToken() })
        assertEquals(AccessResult.Transient, runBlocking { s.accessToken() })
        assertNotNull(store.load())
        assertEquals("refresh-1", store.load()!!.refreshToken)
    }

    @Test
    fun `a storage failure after a renewal keeps the new pair for this process instead of losing the sign-in`() {
        json(200, refreshed)
        val store = InMemoryCredentialStore(creds(expiresIn = 0), failSaves = { true })
        val s = session(store)
        assertEquals("access-2", assertIs<AccessResult.Ok>(runBlocking { s.accessToken() }).accessToken)
        assertTrue(s.storeFailed)
        // The next call uses the kept pair and does not spend the old refresh token again.
        assertEquals("access-2", assertIs<AccessResult.Ok>(runBlocking { s.accessToken() }).accessToken)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a grant without the plan scope makes no request at all`() {
        val s = session(InMemoryCredentialStore(creds(scopes = listOf("openid", "profile", "email"))))
        assertEquals(AccessResult.NoPlanScope, runBlocking { s.accessToken() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `signing out forgets the tokens here and nothing is sent`() {
        val store = InMemoryCredentialStore(creds())
        val s = session(store)
        s.signOut()
        assertNull(store.load())
        assertEquals(AccessResult.SignedOut(revoked = false), runBlocking { s.accessToken() })
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `after a 401 the token is renewed once, and not again if another caller already did`() {
        json(200, refreshed)
        val store = InMemoryCredentialStore(creds())
        val s = session(store)
        assertEquals("access-2", assertIs<AccessResult.Ok>(runBlocking { s.renewAfterRejection("access-1") }).accessToken)
        assertEquals("access-2", assertIs<AccessResult.Ok>(runBlocking { s.renewAfterRejection("access-1") }).accessToken)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ID token claims are checked for issuer, audience, expiry and nonce, issued-at and subject`() {
        fun jwt(payload: String) =
            "x." +
                java.util.Base64
                    .getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(payload.toByteArray()) + ".y"
        val claims =
            IdTokenClaims.parse(
                jwt("""{"iss":"https://auth.openai.com","aud":["oaiapp_1"],"exp":2000,"iat":900,"nonce":"N","sub":"s"}"""),
            )!!
        assertNull(claims.problem("https://auth.openai.com", "oaiapp_1", "N", 1000))
        assertEquals("issuer", claims.problem("https://evil", "oaiapp_1", "N", 1000))
        assertEquals("audience", claims.problem("https://auth.openai.com", "other", "N", 1000))
        assertEquals("expired", claims.problem("https://auth.openai.com", "oaiapp_1", "N", 3000))
        assertEquals("nonce", claims.problem("https://auth.openai.com", "oaiapp_1", "X", 1000))
        assertNull(IdTokenClaims.parse("not-a-jwt"))
    }

    @Test
    fun `credentials round-trip through their sealed JSON and never print their tokens`() {
        val c = creds()
        assertEquals(c, PlanCredentials.fromJson(c.toJson()))
        assertTrue("access-1" !in c.toString() && "refresh-1" !in c.toString())
        assertNull(PlanCredentials.fromJson("{}"))
    }
}
