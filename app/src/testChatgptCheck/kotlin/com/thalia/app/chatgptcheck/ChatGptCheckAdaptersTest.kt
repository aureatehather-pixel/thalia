package com.thalia.app.chatgptcheck

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.FakeSealer
import com.thalia.chatgptplan.PlanCredentials
import com.thalia.chatgptplan.PlanScopes
import com.thalia.chatgptplan.ProofRecord
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The proof's platform adapters on Robolectric's plain storage and a fake Keystore: what they keep, how, and what they never print. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ChatGptCheckAdaptersTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dir get() = File(context.filesDir, "chatgpt-test")

    private fun creds(access: String = "access-secret-1") =
        PlanCredentials(
            "https://auth.openai.com",
            "sub",
            "oaiapp_1",
            "urn:uuid:h",
            "idtoken-secret",
            access,
            "refresh-secret-1",
            PlanScopes.ALL.split(' '),
            5_000,
            1_000,
        )

    private fun store(sealer: FakeSealer = FakeSealer()) = SealedFileCredentialStore(dir, sealer)

    @org.junit.After
    fun clean() {
        dir.deleteRecursively()
        File(context.filesDir, "ev").deleteRecursively()
        context
            .getSharedPreferences("chatgpt_proof", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
    }

    @Test
    fun `credentials are written sealed, never as plain text, and read back whole`() {
        val s = store()
        s.save(creds())
        assertEquals(creds(), s.load())
        val onDisk = File(dir, "chatgpt-credentials.sealed").readBytes()
        listOf("access-secret-1", "refresh-secret-1", "idtoken-secret").forEach {
            assertFalse(String(onDisk, Charsets.ISO_8859_1).contains(it), it)
        }
    }

    @Test
    fun `a newer save replaces the older one whole, and no temporary file is left`() {
        val s = store()
        s.save(creds("access-A"))
        s.save(creds("access-B"))
        assertEquals("access-B", s.load()!!.accessToken)
        assertFalse(File(dir, "chatgpt-credentials.tmp").exists())
    }

    @Test
    fun `a sealed file that cannot be opened is reported as unusable and is not mistaken for a sign-in`() {
        val sealer = FakeSealer()
        val s = store(sealer)
        s.save(creds())
        sealer.refuseToOpen = true
        assertNull(s.load())
        assertEquals("IllegalStateException", s.lastLoadProblem)
        File(dir, "chatgpt-credentials.sealed").writeBytes(byteArrayOf(1, 2, 3))
        sealer.refuseToOpen = false
        assertNull(s.load())
        assertTrue(s.lastLoadProblem != null)
    }

    @Test
    fun `signing out removes the tokens but keeps the host id and the issued client id`() {
        val s = store()
        val host = s.hostId()
        s.rememberClientId("oaiapp_42")
        s.save(creds())
        s.clear()
        assertNull(s.load())
        assertEquals(host, s.hostId())
        assertEquals("oaiapp_42", s.issuedClientId())
        assertEquals(host, store().hostId())
        assertTrue(host.startsWith("urn:uuid:"))
    }

    @Test
    fun `discovery is allowed twice in total keeping the count already used, inference once, and they never borrow from each other`() {
        // The state the phone is in: one discovery already counted, no inference.
        context
            .getSharedPreferences("chatgpt_proof", Context.MODE_PRIVATE)
            .edit()
            .putInt("discovery_used", 1)
            .commit()
        val prefs = ProofPrefs(context)
        assertEquals(1, prefs.discoveryUsed)
        assertEquals(0, prefs.inferenceUsed)
        assertTrue(prefs.tryReserveDiscovery(), "the one additional discovery is allowed")
        val restarted = ProofPrefs(context)
        assertEquals(2, restarted.discoveryUsed)
        assertFalse(restarted.tryReserveDiscovery(), "a third discovery request must be refused")
        assertEquals(0, restarted.inferenceUsed, "discovery does not use the inference allowance")
        assertTrue(restarted.tryReserve())
        assertFalse(restarted.tryReserve(), "a second inference request must be refused")
        assertFalse(ProofPrefs(context).tryReserve())
        assertEquals(3, ProofPrefs(context).used)
        assertEquals(0, ProofPrefs(context).remaining)
        assertEquals(2, ProofPrefs.MAX_DISCOVERY)
        assertEquals(1, ProofPrefs.MAX_INFERENCE)
    }

    @Test
    fun `nothing is consented to by default, and the choices are kept`() {
        val prefs = ProofPrefs(context, modelRequestsEnabled = true)
        assertFalse(prefs.consented())
        assertNull(prefs.model)
        prefs.consent = true
        assertFalse(
            ProofPrefs(context, modelRequestsEnabled = true).consented(),
            "consent alone must not allow a request: the credits check is also required",
        )
        prefs.creditsChecked = true
        prefs.model = "some-slug"
        assertTrue(ProofPrefs(context, modelRequestsEnabled = true).consented())
        prefs.creditsChecked = false
        assertFalse(ProofPrefs(context, modelRequestsEnabled = true).consented())
        assertEquals("some-slug", ProofPrefs(context).model)
    }

    @Test
    fun `the wording names OpenAI, the plan, the credits check and the hard stop`() {
        val consent = ChatGptCheckWording.CONSENT
        assertTrue("OpenAI" in consent && "ChatGPT plan" in consent && "not unlimited" in consent)
        assertTrue("no API key" in consent && "2 model-discovery requests in total and 1 inference request" in consent)
        assertTrue("credits" in ChatGptCheckWording.AFTER_SIGN_IN && "cannot read or change" in ChatGptCheckWording.AFTER_SIGN_IN)
        assertFalse("100%" in ChatGptCheckWording.AFTER_SIGN_IN, "an unverified claim must not be presented as a control")
        assertTrue("only your own record, not a protection" in ChatGptCheckWording.AFTER_SIGN_IN)
        assertTrue("until you tap Approve" in ChatGptCheckWording.PHASE_NOTE && "in-memory fake" in ChatGptCheckWording.PHASE_NOTE)
        assertTrue("not show that inference is accepted" in ChatGptCheckWording.DISCOVERY_NOT_ACCEPTANCE)
        assertTrue("does not show how the plan was metered" in ChatGptCheckWording.INFERENCE_NOT_METERING)
        assertEquals("Using ChatGPT plan", ChatGptCheckWording.USING_PLAN)
        assertTrue("Nothing else was tried" in ChatGptCheckWording.limitMessage())
    }

    @Test
    fun `only offline installed English voices are offered, best quality first, and a networked voice never is`() {
        fun v(
            name: String,
            locale: String = "en-US",
            quality: Int = 300,
            network: Boolean = false,
            installed: Boolean = true,
        ) = VoiceChoice(name, locale, quality, network, installed)
        val chosen =
            VoiceSelection.usable(
                listOf(
                    v("b-normal"),
                    v("a-high", quality = 400),
                    v("net", quality = 500, network = true),
                    v("gone", installed = false),
                    v("fr", "fr-FR"),
                    v("gb", "en-GB", 400),
                ),
            )
        assertEquals(listOf("a-high", "gb", "b-normal"), chosen.map { it.name })
        assertEquals(emptyList(), VoiceSelection.usable(listOf(v("net", network = true))))
    }

    @Test
    fun `an evidence line is appended and read back, and carries no credential`() {
        val log = EvidenceLog(File(context.filesDir, "ev/evidence.jsonl"))
        log.append(
            ProofRecord(
                1,
                "fixture",
                "plain-outcome",
                "ok",
                detail = "Bearer abcdefghijklmnop and eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJ4In0.c2ln",
            ),
        )
        log.append(ProofRecord(2, "reply", "x", "ok"))
        val tail = log.tail(5)
        assertEquals(2, tail.size)
        assertFalse(tail.joinToString().contains("abcdefghijklmnop"))
        assertNotEquals(tail[0], tail[1])
    }

    private class Calls(
        val inference: (okhttp3.Request) -> Pair<Int, String>,
    ) {
        val seen = mutableListOf<String>()

        fun client(): okhttp3.OkHttpClient =
            okhttp3.OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    seen += request.method + " " + request.url.encodedPath
                    val (code, body) =
                        when (request.url.encodedPath) {
                            "/v1/models" -> 200 to """{"models":[{"slug":"m-1","visibility":"list"}]}"""
                            "/v1/responses" -> inference(request)
                            else -> 500 to "{}"
                        }
                    okhttp3.Response
                        .Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(code)
                        .message("scripted")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                }.build()
    }

    private fun providerFor(
        prefs: ProofPrefs,
        calls: Calls,
        retry: Boolean = false,
        gates: com.thalia.chatgptplan.PlanGates = prefs,
    ): Pair<com.thalia.chatgptplan.ChatGptPlanProvider, okhttp3.OkHttpClient> {
        val s = store()
        s.save(creds().copy(expiresAtMillis = Long.MAX_VALUE / 2))
        val client = calls.client()
        val endpoints = com.thalia.chatgptplan.PlanEndpoints.PRODUCTION
        val session = com.thalia.chatgptplan.PlanSession(s, com.thalia.chatgptplan.TokenClient(client, endpoints))
        return com.thalia.chatgptplan.ChatGptPlanProvider(session, endpoints, client, { prefs.model }, gates, retry) to client
    }

    private fun readyPrefs(): ProofPrefs =
        ProofPrefs(context).also {
            it.consent = true
            it.creditsChecked = true
            it.model = "m-1"
        }

    private val ask = com.thalia.conversation.ProviderRequest("i", "c", emptyList(), "[]")

    @Test
    fun `phase B1 sends one discovery and one inference, a reply is final, and nothing else can leave`() {
        val prefs = readyPrefs()
        val sse = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"
        val calls = Calls { 200 to sse }
        val (provider, client) = providerFor(prefs, calls)
        // Discovery: reserved first, then one GET. It is not an inference and the inference allowance is untouched.
        assertTrue(prefs.tryReserveDiscovery())
        client
            .newCall(
                okhttp3.Request
                    .Builder()
                    .url(com.thalia.chatgptplan.PlanEndpoints.PRODUCTION.modelsUrl)
                    .build(),
            ).execute()
            .close()
        assertEquals(0, prefs.inferenceUsed)
        // The one inference.
        val first = kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertTrue(first is com.thalia.conversation.ProviderResponse.Reply, first.toString())
        // Every further attempt, through the same provider, is refused before any network call.
        val second = kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertEquals(
            com.thalia.conversation.ProviderFailure.BUDGET_EXHAUSTED,
            (second as com.thalia.conversation.ProviderResponse.Failed).failure,
        )
        assertTrue(prefs.tryReserveDiscovery(), "the second of two discovery requests")
        assertFalse(prefs.tryReserveDiscovery(), "a third is refused")
        assertEquals(listOf("GET /v1/models", "POST /v1/responses"), calls.seen)
    }

    @Test
    fun `a rejected inference is final, with no token renewal and no second inference`() {
        val prefs = readyPrefs()
        val calls = Calls { 401 to """{"error":{"code":"subscription_sharing_invalid_user"}}""" }
        val (provider, _) = providerFor(prefs, calls)
        val first = kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertEquals(
            com.thalia.conversation.ProviderFailure.SIGN_IN_NEEDED,
            (first as com.thalia.conversation.ProviderResponse.Failed).failure,
        )
        kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertEquals(listOf("POST /v1/responses"), calls.seen, "one inference, no renewal at the token endpoint, no retry")
    }

    @Test
    fun `without the owner's record nothing is sent and nothing is counted`() {
        val prefs = readyPrefs()
        prefs.creditsChecked = false
        val calls = Calls { 200 to "" }
        val (provider, _) = providerFor(prefs, calls)
        val response = kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertEquals(
            com.thalia.conversation.ProviderFailure.CONSENT_NEEDED,
            (response as com.thalia.conversation.ProviderResponse.Failed).failure,
        )
        assertTrue(calls.seen.isEmpty())
        assertEquals(0, prefs.used)
    }

    @Test
    fun `the merged manifest holds only the proof, none of the main app's screens, alarms, receivers or permissions`() {
        val pm = context.packageManager
        val flags =
            android.content.pm.PackageManager.GET_ACTIVITIES or
                android.content.pm.PackageManager.GET_SERVICES or
                android.content.pm.PackageManager.GET_RECEIVERS or
                android.content.pm.PackageManager.GET_PERMISSIONS
        val info = pm.getPackageInfo(context.packageName, flags)
        assertEquals(listOf("com.thalia.app.chatgptcheck.ChatGptCheckActivity"), info.activities.orEmpty().map { it.name })
        val mainApp = { name: String -> name.startsWith("com.thalia.app.") && !name.startsWith("com.thalia.app.chatgptcheck.") }
        assertTrue(info.services.orEmpty().none { mainApp(it.name) })
        assertTrue(info.receivers.orEmpty().none { mainApp(it.name) })
        assertEquals("android.app.Application", info.applicationInfo!!.className)
        val permissions =
            info.requestedPermissions
                .orEmpty()
                .filterNot { it.startsWith(context.packageName) }
                .toSet()
        assertEquals(setOf("android.permission.INTERNET", "android.permission.RECORD_AUDIO"), permissions)
    }

    private fun exhaustedEarlierAllowances() {
        // The state the phone is in after Phase B1.
        context
            .getSharedPreferences(
                "chatgpt_proof",
                Context.MODE_PRIVATE,
            ).edit()
            .putInt("discovery_used", 2)
            .putInt("inference_used", 1)
            .commit()
    }

    @Test
    fun `proposal attempts have their own cap of ten, and the used discovery and inference allowances stay used`() {
        exhaustedEarlierAllowances()
        val prefs = ProofPrefs(context)
        assertFalse(prefs.tryReserveDiscovery())
        assertFalse(prefs.tryReserve())
        repeat(ProofPrefs.MAX_PROPOSAL) { assertTrue(prefs.tryReserveProposal(), "attempt ${it + 1}") }
        assertFalse(prefs.tryReserveProposal(), "an eleventh attempt must be refused")
        val restarted = ProofPrefs(context)
        assertEquals(10, restarted.proposalUsed)
        assertFalse(restarted.tryReserveProposal())
        assertEquals(2, restarted.discoveryUsed)
        assertEquals(1, restarted.inferenceUsed)
        assertEquals(10, ProofPrefs.MAX_PROPOSAL)
    }

    @Test
    fun `a proposal case is marked attempted on disk before its request and stays attempted across a restart`() {
        val prefs = ProofPrefs(context)
        assertTrue(prefs.proposalAttempted.isEmpty())
        assertTrue(prefs.markProposalAttempted("outcome-plain"))
        assertTrue(prefs.markProposalAttempted("ambiguous-target"))
        assertEquals(setOf("outcome-plain", "ambiguous-target"), ProofPrefs(context).proposalAttempted)
    }

    @Test
    fun `the ten cases send ten requests on the proposal counter and nothing more, with the earlier allowances exhausted`() {
        exhaustedEarlierAllowances()
        val prefs = readyPrefs()
        val sse = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"
        val calls = Calls { 200 to sse }
        val (provider, _) = providerFor(prefs, calls, gates = ProposalGates(prefs))
        val run = {
            kotlinx.coroutines.runBlocking {
                com.thalia.chatgptplan.ProposalRun.run(com.thalia.chatgptplan.ProposalCases.all, prefs.proposalAttempted, {
                    prefs.markProposalAttempted(it)
                }, { provider.respond(it) }, {})
            }
        }
        assertEquals(10, run().size)
        assertEquals(List(10) { "POST /v1/responses" }, calls.seen)
        assertEquals(10, prefs.proposalUsed)
        assertEquals(10, prefs.proposalAttempted.size)
        // Run again: every case is attempted, so nothing is sent.
        assertEquals(0, run().size)
        assertEquals(10, calls.seen.size)
        assertEquals(1, prefs.inferenceUsed)
        assertEquals(2, prefs.discoveryUsed)
    }

    @Test
    fun `a rejected proposal request stops the run with no retry and no token renewal, and later cases stay unattempted`() {
        exhaustedEarlierAllowances()
        val prefs = readyPrefs()
        val calls = Calls { 401 to """{"error":{"code":"subscription_sharing_invalid_user"}}""" }
        val (provider, _) = providerFor(prefs, calls, gates = ProposalGates(prefs))
        val results =
            kotlinx.coroutines.runBlocking {
                com.thalia.chatgptplan.ProposalRun.run(com.thalia.chatgptplan.ProposalCases.all, prefs.proposalAttempted, {
                    prefs.markProposalAttempted(it)
                }, { provider.respond(it) }, {})
            }
        assertEquals(1, results.size)
        assertEquals(listOf("POST /v1/responses"), calls.seen)
        assertEquals(1, prefs.proposalUsed)
        assertEquals(setOf("outcome-plain"), prefs.proposalAttempted)
    }

    @Test
    fun `the proposal gate also needs consent and the owner's record, so nothing is counted without them`() {
        exhaustedEarlierAllowances()
        val prefs = readyPrefs()
        prefs.creditsChecked = false
        val calls = Calls { 200 to "" }
        val (provider, _) = providerFor(prefs, calls, gates = ProposalGates(prefs))
        val response =
            kotlinx.coroutines.runBlocking {
                provider.respond(
                    com.thalia.chatgptplan.ProposalCases.all
                        .first()
                        .request(),
                )
            }
        assertEquals(
            com.thalia.conversation.ProviderFailure.CONSENT_NEEDED,
            (response as com.thalia.conversation.ProviderResponse.Failed).failure,
        )
        assertTrue(calls.seen.isEmpty())
        assertEquals(0, prefs.proposalUsed)
    }

    private fun exhaustedAllEarlierAllowances() {
        exhaustedEarlierAllowances()
        context
            .getSharedPreferences("chatgpt_proof", Context.MODE_PRIVATE)
            .edit()
            .putInt("proposal_used", 10)
            .commit()
    }

    @Test
    fun `the trial sends exactly one inference on its own counter, with every earlier allowance exhausted and untouched`() {
        exhaustedAllEarlierAllowances()
        val prefs = readyPrefs()
        val sse = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[]}}\n\n"
        val calls = Calls { 200 to sse }
        val (provider, _) = providerFor(prefs, calls, gates = TrialGates(prefs))
        val request =
            com.thalia.conversation.ProviderRequest(
                "i",
                "c",
                listOf(com.thalia.conversation.Turn(com.thalia.conversation.TurnRole.USER, ChatGptCheckWording.TRIAL_PROMPT)),
                "[]",
            )
        val first = kotlinx.coroutines.runBlocking { provider.respond(request) }
        assertTrue(first is com.thalia.conversation.ProviderResponse.Reply)
        val second = kotlinx.coroutines.runBlocking { provider.respond(request) }
        assertTrue(second is com.thalia.conversation.ProviderResponse.Failed)
        assertEquals(listOf("POST /v1/responses"), calls.seen)
        val restarted = ProofPrefs(context)
        assertEquals(1, restarted.trialInferenceUsed)
        assertFalse(restarted.tryReserveTrialInference(), "a restart must not refund the allowance")
        assertEquals(2, restarted.discoveryUsed)
        assertEquals(1, restarted.inferenceUsed)
        assertEquals(10, restarted.proposalUsed)
        assertEquals("Reply with exactly: Thalia connection test.", ChatGptCheckWording.TRIAL_PROMPT)
    }

    @Test
    fun `the trial sign-in is counted once, survives a restart, and the earlier counters are not read or raised`() {
        exhaustedAllEarlierAllowances()
        val prefs = ProofPrefs(context)
        assertTrue(prefs.tryReserveTrialSignIn())
        assertFalse(prefs.tryReserveTrialSignIn())
        assertFalse(ProofPrefs(context).tryReserveTrialSignIn())
        assertEquals(1, ProofPrefs.TRIAL_MAX_SIGN_IN)
        assertEquals(1, ProofPrefs.TRIAL_MAX_INFERENCE)
        assertEquals(2, ProofPrefs.MAX_DISCOVERY)
        assertEquals(1, ProofPrefs.MAX_INFERENCE)
        assertEquals(10, ProofPrefs.MAX_PROPOSAL)
        assertFalse(prefs.tryReserveDiscovery())
        assertFalse(prefs.tryReserve())
        assertFalse(prefs.tryReserveProposal())
    }

    @Test
    fun `the trial gate needs consent and the owner record, so nothing is sent or counted without them`() {
        exhaustedAllEarlierAllowances()
        val prefs = readyPrefs()
        prefs.creditsChecked = false
        val calls = Calls { 200 to "" }
        val (provider, _) = providerFor(prefs, calls, gates = TrialGates(prefs))
        val response = kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertEquals(
            com.thalia.conversation.ProviderFailure.CONSENT_NEEDED,
            (response as com.thalia.conversation.ProviderResponse.Failed).failure,
        )
        assertTrue(calls.seen.isEmpty())
        assertEquals(0, prefs.trialInferenceUsed)
    }

    @Test
    fun `a rejected trial inference is final with one request, no renewal, no retry, and the allowance stays used`() {
        exhaustedAllEarlierAllowances()
        val prefs = readyPrefs()
        val calls = Calls { 401 to """{"error":{"code":"subscription_sharing_invalid_user"}}""" }
        val (provider, _) = providerFor(prefs, calls, gates = TrialGates(prefs))
        kotlinx.coroutines.runBlocking { provider.respond(ask) }
        kotlinx.coroutines.runBlocking { provider.respond(ask) }
        assertEquals(listOf("POST /v1/responses"), calls.seen)
        assertEquals(1, prefs.trialInferenceUsed)
    }
}
