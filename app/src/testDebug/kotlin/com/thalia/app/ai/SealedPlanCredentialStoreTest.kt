package com.thalia.app.ai

import com.thalia.app.secret.SealedSecret
import com.thalia.app.secret.SecretCipher
import com.thalia.chatgptplan.AccessResult
import com.thalia.chatgptplan.FakePlanService
import com.thalia.chatgptplan.HostAllowlist
import com.thalia.chatgptplan.PlanCredentials
import com.thalia.chatgptplan.PlanEndpoints
import com.thalia.chatgptplan.PlanScenario
import com.thalia.chatgptplan.PlanScopes
import com.thalia.chatgptplan.PlanSession
import com.thalia.chatgptplan.TokenClient
import com.thalia.chatgptplan.sampleCredentials
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SealedPlanCredentialStoreTest {
    @TempDir
    lateinit var dir: File

    /** Reversible and fake, with switches for each failure. Not encryption. */
    private class FakeCipher : SecretCipher {
        var failSeal = false

        override fun seal(plaintext: ByteArray): SealedSecret {
            if (failSeal) error("seal failed")
            return SealedSecret(byteArrayOf(1, 2, 3), plaintext.reversedArray())
        }

        override fun open(sealed: SealedSecret) = sealed.ciphertext.reversedArray()

        override fun destroyKey() = Unit
    }

    private val cipher = FakeCipher()
    private val sealedFile get() = File(dir, "plan.sealed")
    private val metaFile get() = File(dir, "plan-meta.txt")
    private val now = 1_000_000L

    private fun store() = SealedPlanCredentialStore(PlanSecretStore(sealedFile, cipher), metaFile)

    @Test
    fun `credentials round trip through the module's own representation, including a null id token and every scope`() {
        val withId = sampleCredentials(now + 5).copy(idToken = "synthetic-id", scopes = listOf("a", "b c", "plan:x"), savedAtMillis = 7)
        val s = store()
        assertNull(s.load())
        s.save(withId)
        assertEquals(withId, store().load())
        s.save(withId.copy(idToken = null))
        assertEquals(withId.copy(idToken = null), store().load())
        assertEquals(PlanScopes.ALL.split(' '), sampleCredentials(0).scopes)
    }

    @Test
    fun `a failed replacement keeps the earlier credentials, with a fixed message that holds no secret`() {
        val old = sampleCredentials(now)
        val s = store()
        s.save(old)
        cipher.failSeal = true
        val fresh = old.copy(accessToken = "fake-access-2", refreshToken = "fake-refresh-2")
        val e = assertFailsWith<IllegalStateException> { s.save(fresh) }
        assertFalse("fake-" in e.message.orEmpty() || "fake-" in e.toString())
        cipher.failSeal = false
        assertEquals(old, store().load())
    }

    @Test
    fun `clearing signs out, keeps the host and client ids, and is safe to repeat`() {
        val s = store()
        val host = s.hostId()
        s.rememberClientId("client-1")
        s.save(sampleCredentials(now))
        s.clear()
        s.clear()
        assertNull(store().load())
        assertFalse(sealedFile.exists())
        assertEquals(host, store().hostId())
        assertEquals("client-1", store().issuedClientId())
    }

    @Test
    fun `corrupt or unreadable storage is signed out and never throws`() {
        val s = store()
        s.save(sampleCredentials(now))
        val damaged = listOf(byteArrayOf(), byteArrayOf(9, 9, 9), byteArrayOf(1, 0, 5), sealedFile.readBytes().copyOf(5))
        damaged.forEach {
            sealedFile.writeBytes(it)
            assertNull(store().load(), it.size.toString())
        }
        // Sealed fine, but not credentials: not JSON, JSON of the wrong shape, a missing field, a wrong type.
        val good = sampleCredentials(now).toJson()
        val notCredentials =
            listOf(
                "not json",
                "[]",
                "{}",
                """{"issuer":"i"}""",
                good.replace("\"expires_at_millis\":", "\"x\":"),
                good.replace("\"expires_at_millis\":$now", "\"expires_at_millis\":\"soon\""),
            )
        notCredentials.forEach {
            PlanSecretStore(sealedFile, cipher).save(it)
            assertNull(store().load(), it.take(20))
        }
    }

    @Test
    fun `the host id is created once, and a corrupt meta file does not stop the store`() {
        val first = store().hostId()
        assertTrue(first.startsWith("urn:uuid:"))
        assertEquals(first, store().hostId())
        assertNull(store().issuedClientId())
        metaFile.writeText("garbage with no equals\n=\nhost=")
        val regenerated = store().hostId()
        assertTrue(regenerated.startsWith("urn:uuid:"))
        assertNotEquals(first, regenerated)
        metaFile.delete()
        metaFile.mkdir()
        assertNull(store().issuedClientId())
    }

    @Test
    fun `no credential value is in the stored file in the clear, a file name or toString`() {
        val c = sampleCredentials(now)
        store().save(c)
        val raw = sealedFile.readBytes().toString(Charsets.ISO_8859_1)
        assertFalse("fake-access-1" in raw || "fake-refresh-1" in raw)
        assertTrue(dir.listFiles().orEmpty().none { "fake-" in it.name })
        assertFalse("fake-" in c.toString())
        assertFalse(metaFile.exists() && "fake-" in metaFile.readText())
    }

    private fun session(
        store: SealedPlanCredentialStore,
        fake: FakePlanService,
    ): PlanSession {
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(HostAllowlist(PlanEndpoints.PRODUCTION.allowedHosts))
                .addInterceptor(fake)
                .build()
        return PlanSession(store, TokenClient(client, PlanEndpoints.PRODUCTION), { now })
    }

    @Test
    fun `a simulated renewal through the adapter is saved, and a new process sees the rotated pair`() =
        runTest {
            store().save(sampleCredentials(now - 1))
            val fake = FakePlanService(PlanScenario.REPLY)
            val ok = session(store(), fake).accessToken() as AccessResult.Ok
            assertEquals(1, fake.refreshCalls)
            assertNotEquals("fake-access-1", ok.accessToken)
            val reloaded = store().load()!!
            assertEquals(ok.accessToken, reloaded.accessToken)
            assertNotEquals("fake-refresh-1", reloaded.refreshToken)
            // A second session over the same files uses the stored pair and does not renew again.
            val again = session(store(), fake).accessToken() as AccessResult.Ok
            assertEquals(ok.accessToken, again.accessToken)
            assertEquals(1, fake.refreshCalls)
        }

    @Test
    fun `a failed save of a renewal keeps the earlier credentials on disk and the session carries on in memory`() =
        runTest {
            val old = sampleCredentials(now - 1)
            store().save(old)
            cipher.failSeal = true
            val fake = FakePlanService(PlanScenario.REPLY)
            val session = session(store(), fake)
            val ok = session.accessToken() as AccessResult.Ok
            assertTrue(session.storeFailed)
            cipher.failSeal = false
            assertEquals(old, store().load())
            assertEquals(ok.accessToken, session.signedIn()!!.accessToken)
            assertEquals(1, fake.refreshCalls)
            assertEquals(ok.accessToken, (session.accessToken() as AccessResult.Ok).accessToken)
            assertEquals(1, fake.refreshCalls)
        }

    @Test
    fun `signing out through the session clears the stored sign-in, and storing one never makes the route ready`() =
        runTest {
            val s = store()
            s.save(sampleCredentials(now + 3_600_000))
            val consent = AiConsentStore(File(dir, "c.txt"), PlanRouteConsentWording.VERSION).also { it.accept(1) }
            val secrets = PlanSecretStore(sealedFile, cipher)
            val sources = PlanRouteSources(consent, { "m" }, secrets::isReadable)
            assertEquals(PlanRouteReadiness.SIGN_IN_UNVERIFIED, sources.readiness())
            session(s, FakePlanService()).signOut()
            assertNull(PlanCredentials.fromJson("{}"))
            assertEquals(PlanRouteReadiness.SIGN_IN_NEEDED, sources.readiness())
            assertNull(ConversationRoutes.installed)
        }
}
