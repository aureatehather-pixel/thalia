package com.thalia.chatgptplan

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AuthorizationTest {
    @Test
    fun `the S256 challenge matches the RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Pkce.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `verifiers and tokens are long, url-safe and never repeat`() {
        val verifiers = (1..50).map { Pkce.newVerifier() }
        assertEquals(50, verifiers.toSet().size)
        assertTrue(verifiers.all { it.length in 43..128 && Regex("[A-Za-z0-9_-]+").matches(it) })
        assertNotEquals(Pkce.newToken(), Pkce.newToken())
    }

    @Test
    fun `a first registration asks as the dynamic client with the documented parameters and a loopback redirect on 127_0_0_1`() {
        val attempt = AuthorizationAttempt.begin(null, "urn:uuid:abc", 51234)
        val url = attempt.url(PlanEndpoints.PRODUCTION).toHttpUrl()
        assertEquals(
            "https://auth.openai.com/api/accounts/authorize",
            url
                .newBuilder()
                .query(null)
                .build()
                .toString(),
        )
        assertEquals("dynamic_agent_client", url.queryParameter("client_id"))
        assertEquals("Thalia", url.queryParameter("agent_name_hint"))
        assertEquals("urn:uuid:abc", url.queryParameter("ext_agent_host_id"))
        assertEquals("code", url.queryParameter("response_type"))
        assertEquals("http://127.0.0.1:51234/auth/callback", url.queryParameter("redirect_uri"))
        assertEquals(PlanScopes.ALL, url.queryParameter("scope"))
        assertEquals("https://api.openai.com/v1", url.queryParameter("resource"))
        assertEquals("S256", url.queryParameter("code_challenge_method"))
        assertEquals(Pkce.challenge(attempt.verifier), url.queryParameter("code_challenge"))
        assertEquals(attempt.state, url.queryParameter("state"))
        assertEquals(attempt.nonce, url.queryParameter("nonce"))
        assertFalse("localhost" in attempt.redirectUri)
        assertFalse(attempt.verifier in url.toString(), "the verifier never travels in the authorization request")
    }

    @Test
    fun `a later sign-in uses the issued client id and no name hint`() {
        val url = AuthorizationAttempt.begin("oaiapp_123", "urn:uuid:abc", 40000).url(PlanEndpoints.PRODUCTION).toHttpUrl()
        assertEquals("oaiapp_123", url.queryParameter("client_id"))
        assertNull(url.queryParameter("agent_name_hint"))
    }

    @Test
    fun `every attempt has its own state, nonce and verifier`() {
        val a = AuthorizationAttempt.begin(null, "h", 1)
        val b = AuthorizationAttempt.begin(null, "h", 1)
        assertNotEquals(a.state, b.state)
        assertNotEquals(a.nonce, b.nonce)
        assertNotEquals(a.verifier, b.verifier)
    }

    @Test
    fun `the callback parser accepts only this state on this path`() {
        val ok = CallbackParser.parse("/auth/callback?code=abc&state=S&client_id=oaiapp_1&scope=x+y", "S")
        assertEquals(CallbackResult.Code("abc", "oaiapp_1", "x y"), assertIs<CallbackParser.Parsed.Ok>(ok).result)
        assertEquals(CallbackParser.Parsed.BadState, CallbackParser.parse("/auth/callback?code=abc&state=OTHER", "S"))
        assertEquals(CallbackParser.Parsed.BadState, CallbackParser.parse("/auth/callback?code=abc", "S"))
        assertEquals(CallbackParser.Parsed.OtherPath, CallbackParser.parse("/callback?code=abc&state=S", "S"))
        assertEquals(CallbackParser.Parsed.OtherPath, CallbackParser.parse("/favicon.ico", "S"))
        assertEquals(CallbackParser.Parsed.Malformed, CallbackParser.parse("/auth/callback?state=S", "S"))
        assertEquals(
            CallbackResult.Denied("access_denied"),
            assertIs<CallbackParser.Parsed.Ok>(CallbackParser.parse("/auth/callback?error=access_denied&state=S", "S")).result,
        )
    }

    private fun get(
        port: Int,
        target: String,
    ): String =
        Socket(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port).use { socket ->
            socket.getOutputStream().write("GET $target HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
            socket.getInputStream().bufferedReader().readText()
        }

    @Test
    fun `the listener binds loopback only, ignores wrong states and other paths, and returns the real callback`() {
        LoopbackListener().use { listener ->
            assertEquals("127.0.0.1", listener.localAddress)
            var result: CallbackResult? = null
            val waiter = thread { result = listener.await("GOOD", 10_000) }
            assertTrue("404" in get(listener.port, "/favicon.ico").lines().first())
            assertTrue("400" in get(listener.port, "/auth/callback?code=evil&state=BAD").lines().first())
            assertTrue("200" in get(listener.port, "/auth/callback?code=real&state=GOOD").lines().first())
            waiter.join(5_000)
            assertEquals(CallbackResult.Code("real", null, null), result)
        }
    }

    @Test
    fun `the listener gives up after its timeout`() {
        LoopbackListener().use { assertEquals(CallbackResult.TimedOut, it.await("S", 200)) }
    }

    @Test
    fun `a callback accepted after the deadline is answered as expired and never returned, as after a frozen process thaws`() {
        // Simulated: the clock jumps past the deadline while the listener is waiting, as wall time does while a process is frozen.
        val now = AtomicLong(0)
        LoopbackListener { now.get() }.use { listener ->
            var result: CallbackResult? = null
            val waiter = thread { result = listener.await("GOOD", 5_000) }
            Thread.sleep(200)
            now.set(10_000)
            val response = get(listener.port, "/auth/callback?code=stale&state=GOOD")
            waiter.join(5_000)
            assertEquals(CallbackResult.TimedOut, result)
            assertTrue("400" in response.lines().first() && "expired" in response, response)
            assertFalse("stale" in response)
        }
    }

    @Test
    fun `a callback before the deadline still succeeds with the injected clock`() {
        val now = AtomicLong(0)
        LoopbackListener { now.get() }.use { listener ->
            var result: CallbackResult? = null
            val waiter = thread { result = listener.await("GOOD", 5_000) }
            now.set(4_000)
            assertTrue("200" in get(listener.port, "/auth/callback?code=fresh&state=GOOD").lines().first())
            waiter.join(5_000)
            assertEquals(CallbackResult.Code("fresh", null, null), result)
        }
    }
}
