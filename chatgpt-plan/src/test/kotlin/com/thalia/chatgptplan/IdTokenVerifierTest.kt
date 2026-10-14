package com.thalia.chatgptplan

import kotlinx.coroutines.runBlocking
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.OkHttpClient
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdTokenVerifierTest {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    private fun pair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private val signer = pair()
    private val other = pair()

    private fun jwk(
        kid: String,
        key: RSAPublicKey,
    ) = """{"kty":"RSA","kid":"$kid","n":"${b64.encodeToString(
        unsigned(key.modulus),
    )}","e":"${b64.encodeToString(unsigned(key.publicExponent))}"}"""

    private fun unsigned(n: BigInteger) = n.toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }

    private fun token(
        kid: String = "k1",
        alg: String = "RS256",
        key: java.security.PrivateKey = signer.private,
        payload: String = """{"sub":"u"}""",
    ): String {
        val input = b64.encodeToString("""{"alg":"$alg","kid":"$kid"}""".toByteArray()) + "." + b64.encodeToString(payload.toByteArray())
        val sig =
            Signature
                .getInstance("SHA256withRSA")
                .apply {
                    initSign(key)
                    update(input.toByteArray())
                }.sign()
        return input + "." + b64.encodeToString(sig)
    }

    private var keySet = """{"keys":[${jwk("k1", signer.public as RSAPublicKey)}]}"""
    private var jwksUri: (String) -> String = { base -> "$base/jwks" }
    private var jwksHits = 0
    private val server: MockWebServer =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        when (request.url.encodedPath) {
                            "/.well-known/openid-configuration" ->
                                MockResponse
                                    .Builder()
                                    .body(
                                        """{"jwks_uri":"${jwksUri(request.url.toString().substringBefore("/.well-known"))}"}""",
                                    ).build()
                            "/jwks" -> {
                                jwksHits++
                                MockResponse.Builder().body(keySet).build()
                            }
                            else -> MockResponse.Builder().code(404).build()
                        }
                }
            start()
        }

    @AfterTest
    fun stop() = server.close()

    private fun verifier() = JwksIdTokenVerifier(OkHttpClient(), server.url("/.well-known/openid-configuration").toString())

    private fun ok(jwt: String) = runBlocking { verifier().verify(jwt) }

    @Test
    fun `a token signed by the published key verifies`() = assertTrue(ok(token()))

    @Test
    fun `a token signed by another key, or altered after signing, does not`() {
        assertFalse(ok(token(key = other.private)))
        val parts = token().split('.')
        assertFalse(ok(parts[0] + "." + b64.encodeToString("""{"sub":"attacker"}""".toByteArray()) + "." + parts[2]))
    }

    @Test
    fun `only RS256 is accepted, so alg none and HS256 are refused`() {
        assertFalse(ok(token(alg = "none").substringBeforeLast('.') + "."))
        assertFalse(ok(token(alg = "HS256")))
    }

    @Test
    fun `an unknown kid refreshes the key set once and then fails`() {
        assertFalse(ok(token(kid = "missing")))
        assertTrue(jwksHits == 1)
    }

    @Test
    fun `a key set that moves to another host is not trusted`() {
        jwksUri = { "https://evil.example/jwks" }
        assertFalse(ok(token()))
    }
}
