package com.thalia.app.chatgptcheck

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.thalia.app.conversation.OnDeviceSpeechSource
import com.thalia.app.conversation.SpeechOutcome
import com.thalia.app.conversation.SpeechReadiness
import com.thalia.app.planning.data.KeystorePassphraseSealer
import com.thalia.app.ui.ThaliaTheme
import com.thalia.chatgptplan.AccessResult
import com.thalia.chatgptplan.ChatGptPlanProvider
import com.thalia.chatgptplan.FakeStore
import com.thalia.chatgptplan.JwksIdTokenVerifier
import com.thalia.chatgptplan.ModelCatalog
import com.thalia.chatgptplan.PendingApprovals
import com.thalia.chatgptplan.PlanEndpoints
import com.thalia.chatgptplan.PlanSession
import com.thalia.chatgptplan.ProofData
import com.thalia.chatgptplan.ProofRecord
import com.thalia.chatgptplan.ProposalCases
import com.thalia.chatgptplan.ProposalData
import com.thalia.chatgptplan.ProposalReport
import com.thalia.chatgptplan.ProposalResult
import com.thalia.chatgptplan.ProposalRun
import com.thalia.chatgptplan.ProposalScore
import com.thalia.chatgptplan.ProposalSummary
import com.thalia.chatgptplan.Scenario
import com.thalia.chatgptplan.ScenarioRunner
import com.thalia.chatgptplan.SignInFlow
import com.thalia.chatgptplan.SignInResult
import com.thalia.chatgptplan.TokenClient
import com.thalia.chatgptplan.Verdict
import com.thalia.chatgptplan.planHttpClient
import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.OperationParser
import com.thalia.conversation.ParseResult
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import com.thalia.speech.SessionMode
import com.thalia.speech.SpeechEvent
import com.thalia.speech.SpokenReplyPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File

/** How much of a model-list response is parsed. Anything longer is reported as truncated. */
private const val DISCOVERY_PARSE_CHARS = 1_000_000

/**
 * The Slice 6 feasibility proof, on a device: Sign in with ChatGPT, a completed reply, a proposal scored on meaning, token
 * renewal, safe failure, and spoken replies. Synthetic data only. **Nothing is sent until consent is given, the person is signed
 * in, and a button that says it sends is pressed**, and a hard count of two discovery requests and one inference request applies. It has no API
 * key and no fallback. It never reads or writes com.thalia.app.debug.
 */
class ChatGptCheckActivity : ComponentActivity() {
    private lateinit var prefs: ProofPrefs
    private lateinit var store: SealedFileCredentialStore
    private lateinit var evidence: EvidenceLog
    private lateinit var verifier: JwksIdTokenVerifier
    private lateinit var session: PlanSession
    private lateinit var provider: ChatGptPlanProvider
    private lateinit var proposalProvider: ChatGptPlanProvider
    private lateinit var trialProvider: ChatGptPlanProvider
    private lateinit var tokens: TokenClient
    private lateinit var speaker: TtsSpeaker
    private lateinit var speech: OnDeviceSpeechSource
    private val endpoints = PlanEndpoints.PRODUCTION
    private val client by lazy { planHttpClient(endpoints) }
    private val policy = SpokenReplyPolicy(SessionMode.TYPED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = ProofPrefs(this)
        store = SealedFileCredentialStore(File(filesDir, "chatgpt"), KeystorePassphraseSealer("thalia-chatgptcheck-credentials-v1"))
        evidence = EvidenceLog(File(filesDir, "chatgpt-proof/evidence.jsonl"))
        tokens = TokenClient(client, endpoints)
        verifier = JwksIdTokenVerifier(client, "${endpoints.issuer}/.well-known/openid-configuration")
        session = PlanSession(store, tokens)
        provider = ChatGptPlanProvider(session, endpoints, client, { prefs.model }, prefs, renewAndRetryOn401 = false)
        // The ten proposal cases count against their own cap, not the exhausted discovery and inference counters.
        proposalProvider =
            ChatGptPlanProvider(session, endpoints, client, { prefs.model }, ProposalGates(prefs), renewAndRetryOn401 = false)
        // The connection trial counts against its own allowance, not the exhausted ones.
        trialProvider = ChatGptPlanProvider(session, endpoints, client, { prefs.model }, TrialGates(prefs), renewAndRetryOn401 = false)
        speaker = TtsSpeaker(this) { evidence.append(ProofRecord(System.currentTimeMillis(), "speech", "tts", it)) }
        speech = OnDeviceSpeechSource(this)
        policy.muted = prefs.muted
        setContent { ThaliaTheme { Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { Screen() } } }
    }

    override fun onStop() {
        // Spoken replies are foreground only: leaving the screen stops them.
        speaker.stop()
        super.onStop()
    }

    override fun onDestroy() {
        speech.release()
        speaker.shutdown()
        super.onDestroy()
    }

    private fun log(
        kind: String,
        subject: String,
        outcome: String,
        verdict: Verdict? = null,
        detail: String = "",
        status: Int? = null,
        ms: Long? = null,
    ) = evidence.append(ProofRecord(System.currentTimeMillis(), kind, subject, outcome, status, ms, verdict, detail))

    private fun opsOf(response: ProviderResponse): String =
        (response as? ProviderResponse.Reply)
            ?.calls
            ?.joinToString("; ") { call ->
                when (val parsed = OperationParser.parse(call)) {
                    is ParseResult.Parsed -> parsed.operation.toString().take(160)
                    is ParseResult.Unsupported -> "unsupported ${parsed.name}"
                }
            }.orEmpty()

    private suspend fun ask(
        turns: List<Turn>,
        shareInbox: Boolean = false,
    ): Pair<ProviderResponse, Long> {
        val request =
            ProviderRequest(
                ConversationPrompt.INSTRUCTION,
                ProofData.context(shareInbox).render(),
                turns,
                ConversationTools.DECLARATIONS_JSON,
            )
        val start = System.currentTimeMillis()
        val response = provider.respond(request)
        return response to (System.currentTimeMillis() - start)
    }

    /** What the evidence line holds for a proposal case: the score, what Kotlin decided, the calls and the start of the reply. */
    private fun proposalDetail(result: ProposalResult): String {
        val reply = result.response as? ProviderResponse.Reply
        val calls = reply?.calls.orEmpty().joinToString("; ") { "${it.name}(${it.argsJson.take(110)})" }
        return "why: ${result.score.reasons.joinToString("; ").take(160)} | kotlin: ${result.score.decisions.joinToString(", ")} | " +
            "calls: ${calls.take(220)} | text: ${reply?.text.orEmpty().take(140)}"
    }

    /** The one inference: a short synthetic sentence, no planning data and no tools. */
    private suspend fun askOnce(): Pair<ProviderResponse, Long> {
        val request =
            ProviderRequest(
                "You are checking a connection. Answer with one short, plain sentence.",
                "(synthetic test, no planning data)",
                listOf(Turn(TurnRole.USER, "Please say hello.")),
                "[]",
            )
        val start = System.currentTimeMillis()
        val response = provider.respond(request)
        return response to (System.currentTimeMillis() - start)
    }

    /** The trial's one inference: a fixed sentence, no planning data and no tools. */
    private suspend fun askTrial(): Pair<ProviderResponse, Long> {
        val request =
            ProviderRequest(
                "You are checking a connection.",
                "(connection test, no planning data)",
                listOf(Turn(TurnRole.USER, ChatGptCheckWording.TRIAL_PROMPT)),
                "[]",
            )
        val start = System.currentTimeMillis()
        val response = trialProvider.respond(request)
        return response to (System.currentTimeMillis() - start)
    }

    @Composable
    private fun Screen() {
        val scope = rememberCoroutineScope()
        val lines = remember { mutableStateListOf<String>() }
        var busy by remember { mutableStateOf(false) }
        var tick by remember { mutableIntStateOf(0) }
        var models by remember { mutableStateOf(emptyList<String>()) }
        var draft by remember { mutableStateOf("") }
        var draftMode by remember { mutableStateOf(SessionMode.TYPED) }
        var lastReply by remember { mutableStateOf("") }
        var confirmInference by remember { mutableStateOf(false) }
        var confirmProposals by remember { mutableStateOf(false) }
        var confirmTrial by remember { mutableStateOf(false) }
        val proposalResults = remember { mutableStateListOf<ProposalResult>() }
        var lastProposalMs = 0L
        val fakeStore = remember { FakeStore(ProposalData.context(false)) }
        val approvals = remember { PendingApprovals(fakeStore) }
        val proposalScores = remember { mutableStateMapOf<String, ProposalScore>() }
        var approvalTick by remember { mutableIntStateOf(0) }
        var muted by remember { mutableStateOf(prefs.muted) }
        var voices by remember { mutableStateOf(emptyList<VoiceChoice>()) }

        fun say(text: String) {
            lines.add(0, text)
            if (lines.size > 60) lines.removeAt(lines.lastIndex)
        }

        fun run(
            label: String,
            work: suspend () -> Unit,
        ) {
            if (busy) return
            busy = true
            scope.launch {
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    say("$label stopped: ${e.javaClass.simpleName}")
                    log("error", label, e.javaClass.simpleName)
                } finally {
                    busy = false
                    tick++
                }
            }
        }

        fun speakEvent(event: SpeechEvent) = speaker.speak(policy.lines(event))

        fun describe(
            response: ProviderResponse,
            ms: Long,
        ): String =
            when (response) {
                is ProviderResponse.Reply ->
                    "reply in $ms ms, ${response.calls.size} call(s), ${response.usage?.promptTokens ?: "?"}/" +
                        "${response.usage?.outputTokens ?: "?"} tokens"
                is ProviderResponse.Failed -> "failed ${response.failure} (${provider.lastIssue}) in $ms ms"
            }

        val mic =
            rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                if (!granted) {
                    say("Microphone permission denied. Typing still works.")
                    return@rememberLauncherForActivityResult
                }
                scope.launch {
                    when (val ready = speech.readiness()) {
                        is SpeechReadiness.Unavailable -> say(ready.reason)
                        is SpeechReadiness.Available ->
                            if ("en-US" !in ready.installed) {
                                say("en-US is not installed on this device for on-device speech.")
                            } else {
                                say("Listening (en-US, on device)...")
                                speech.listen("en-US") { outcome ->
                                    when (outcome) {
                                        is SpeechOutcome.Heard -> {
                                            draft = outcome.text
                                            draftMode = SessionMode.VOICE
                                            say("Heard. Edit if needed, then Send. This is a voice session, so the reply is spoken.")
                                        }
                                        is SpeechOutcome.Failed -> say(outcome.message)
                                        else -> say("Listening ended.")
                                    }
                                }
                            }
                    }
                }
            }

        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("ChatGPT plan proof", style = MaterialTheme.typography.headlineSmall)
            Text(ChatGptCheckWording.PHASE, style = MaterialTheme.typography.titleMedium)
            Text(ChatGptCheckWording.PHASE_NOTE, style = MaterialTheme.typography.bodySmall)
            Text("Synthetic data only. Isolated from the real app.", style = MaterialTheme.typography.bodySmall)
            Text(ChatGptCheckWording.CONSENT)
            Row {
                Checkbox(checked = prefs.consent, onCheckedChange = {
                    prefs.consent = it
                    tick++
                })
                Text("I understand and consent.", modifier = Modifier.padding(top = 12.dp))
            }
            HorizontalDivider()

            val signed = remember(tick) { store.load() }
            Text(
                if (signed == null) {
                    "Not signed in." + (store.lastLoadProblem?.let { " (stored sign-in unreadable: $it)" } ?: "")
                } else {
                    "Signed in. Client ${signed.clientId.take(
                        12,
                    )}. Plan scope: ${if (signed.mayUsePlan) "yes" else "NO"}. Token valid for " +
                        "${((signed.expiresAtMillis - System.currentTimeMillis()) / 60_000).coerceAtLeast(0)} min." +
                        (if (session.storeFailed) " Last renewal could not be saved." else "")
                },
            )
            Text(
                "Discovery ${prefs.discoveryUsed} of ${ProofPrefs.MAX_DISCOVERY}, inference ${prefs.inferenceUsed} of " +
                    "${ProofPrefs.MAX_INFERENCE}. Model: ${prefs.model ?: "none chosen"}.",
            )
            if (signed !=
                null
            ) {
                Text(
                    "${ChatGptCheckWording.USING_PLAN}. ${ChatGptCheckWording.MANAGE_USAGE}",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
            Text(ChatGptCheckWording.SIGNATURE_NOTE, style = MaterialTheme.typography.bodySmall)
            Text(ChatGptCheckWording.AFTER_SIGN_IN, style = MaterialTheme.typography.bodySmall)
            Row {
                Checkbox(enabled = signed != null, checked = prefs.creditsChecked, onCheckedChange = {
                    prefs.creditsChecked = it
                    tick++
                })
                Text(ChatGptCheckWording.CREDITS_CHECKED, modifier = Modifier.padding(top = 12.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !busy && prefs.consent, onClick = {
                    run("sign-in") {
                        // The trial allows one sign-in attempt, counted before the browser opens so an interruption cannot refund it.
                        if (!prefs.tryReserveTrialSignIn()) {
                            say("The trial's one sign-in attempt has been used. Nothing was opened.")
                            log("signin", "browser", "refused: trial sign-in attempt used")
                            return@run
                        }
                        say("Opening the browser to sign in...")
                        val flow = SignInFlow(store, tokens, endpoints, verifier)
                        val result = flow.run({ startActivity(Intent(Intent.ACTION_VIEW, it.toUri())) })
                        when (result) {
                            is SignInResult.Signed ->
                                say(
                                    "Signed in. Plan scope ${if (result.credentials.mayUsePlan) "granted" else "NOT granted"}.",
                                )
                            is SignInResult.Failed -> say("Sign-in failed: ${result.problem}")
                        }
                        log("signin", "browser", if (result is SignInResult.Signed) "signed" else (result as SignInResult.Failed).problem)
                    }
                }) { Text("Sign in with ChatGPT") }
                OutlinedButton(enabled = !busy && signed != null, onClick = {
                    session.signOut()
                    prefs.creditsChecked = false
                    say("Signed out on this phone. Revoke access in ChatGPT settings too.")
                    log("signout", "local", "cleared")
                    tick++
                }) { Text("Sign out here") }
            }
            OutlinedButton(enabled = !busy && signed != null, onClick = {
                run("renewal") {
                    val before = store.load()?.accessToken
                    val result = if (before == null) null else session.renewAfterRejection(before)
                    say("Renewal: ${result?.let { it::class.simpleName } ?: "not signed in"}")
                    log("renewal", "forced", result?.let { it::class.simpleName } ?: "none")
                }
            }) { Text("Renew token now (token endpoint only)") }
            HorizontalDivider()

            Text("Connection trial", style = MaterialTheme.typography.titleSmall)
            Text(ChatGptCheckWording.TRIAL_NOTE, style = MaterialTheme.typography.bodySmall)
            Text(
                "Trial sign-in ${prefs.trialSignInUsed} of ${ProofPrefs.TRIAL_MAX_SIGN_IN}, trial inference " +
                    "${prefs.trialInferenceUsed} of ${ProofPrefs.TRIAL_MAX_INFERENCE}. Model: ${prefs.model ?: "none chosen"}.",
            )
            val trialReady = !busy && signed != null && prefs.consented() && prefs.model != null
            OutlinedButton(
                enabled = trialReady && prefs.trialInferenceUsed < ProofPrefs.TRIAL_MAX_INFERENCE,
                onClick = { confirmTrial = true },
            ) { Text("Send the one trial inference (sends 1, final)") }
            if (confirmTrial) {
                AlertDialog(
                    onDismissRequest = { confirmTrial = false },
                    title = { Text("Send the trial inference?") },
                    text = {
                        Text(
                            "The fixed sentence \"${ChatGptCheckWording.TRIAL_PROMPT}\" to ${prefs.model}, using your ChatGPT plan. " +
                                "Nothing is retried, whatever the answer. Your credit balance is zero and app credit use is off.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmTrial = false
                            run("trial") {
                                val (response, ms) = askTrial()
                                val text = (response as? ProviderResponse.Reply)?.text.orEmpty()
                                val exact = text.trim() == ChatGptCheckWording.TRIAL_EXPECTED
                                val calls = (response as? ProviderResponse.Reply)?.calls?.size ?: 0
                                val outcome =
                                    when (response) {
                                        is ProviderResponse.Reply -> "reply in $ms ms, $calls call(s), exact=$exact"
                                        is ProviderResponse.Failed -> "failed ${response.failure} (${trialProvider.lastIssue}) in $ms ms"
                                    }
                                say("Trial: $outcome. ${ChatGptCheckWording.INFERENCE_NOT_METERING}")
                                if (text.isNotEmpty()) say("Trial reply text: $text")
                                log("trial-inference", "fixed-prompt", outcome, ms = ms, detail = "text: ${text.take(200)}")
                                lastReply = text
                            }
                        }) { Text("Send") }
                    },
                    dismissButton = { TextButton(onClick = { confirmTrial = false }) { Text("Cancel") } },
                )
            }
            HorizontalDivider()

            Text("Model (the list is the account's own)", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(enabled = !busy && signed != null && prefs.consented(), onClick = {
                run("models") {
                    if (!prefs.consented()) {
                        say(ChatGptCheckWording.PHASE)
                        return@run
                    }
                    if (prefs.discoveryUsed >= ProofPrefs.MAX_DISCOVERY) {
                        say("The discovery requests have been used.")
                        return@run
                    }
                    // The token first, so a failed renewal does not use up the discovery request. It is counted just before it goes.
                    val access = session.accessToken() as? AccessResult.Ok
                    if (access == null) {
                        say("No usable sign-in. Nothing was counted.")
                        return@run
                    }
                    if (!prefs.tryReserveDiscovery()) {
                        say("The discovery requests have been used.")
                        return@run
                    }
                    val body =
                        withContext(Dispatchers.IO) {
                            runCatching {
                                client
                                    .newCall(
                                        Request
                                            .Builder()
                                            .url(endpoints.modelsUrl)
                                            .header("Authorization", "Bearer ${access.accessToken}")
                                            .build(),
                                    ).execute()
                                    .use {
                                        val text = it.body.string()
                                        Triple(it.code, text.take(DISCOVERY_PARSE_CHARS), it.header("Content-Type")) to
                                            (text.length > DISCOVERY_PARSE_CHARS)
                                    }
                            }.getOrNull()
                        }
                    val response = body?.first
                    val analysis =
                        response?.takeIf { it.first == 200 }?.let {
                            ModelCatalog.analyze(
                                it.second,
                                it.third,
                                truncated = body.second,
                            )
                        }
                    models = analysis?.slugs.orEmpty()
                    val result = analysis?.let { "${it.outcome}: ${it.summary}" } ?: "no usable body"
                    say(
                        "Models: HTTP ${response?.first ?: "no answer"}, ${models.size} listed (${analysis?.outcome ?: "no analysis"}). " +
                            ChatGptCheckWording.DISCOVERY_NOT_ACCEPTANCE,
                    )
                    log(
                        "discovery",
                        "models",
                        "HTTP ${response?.first}",
                        status = response?.first,
                        detail = "$result; ${ChatGptCheckWording.DISCOVERY_NOT_ACCEPTANCE}",
                    )
                }
            }) { Text("List models (a discovery request, 2 in total)") }
            models.forEach { slug ->
                TextButton(onClick = {
                    prefs.model = slug
                    tick++
                }) { Text((if (prefs.model == slug) "Selected: " else "") + slug) }
            }
            HorizontalDivider()

            Text("Replies and proposals", style = MaterialTheme.typography.titleSmall)
            val ready = !busy && signed != null && prefs.consented() && prefs.model != null
            OutlinedButton(enabled = ready && prefs.inferenceUsed < ProofPrefs.MAX_INFERENCE, onClick = {
                confirmInference = true
            }) { Text("Send the one synthetic inference (sends 1, final)") }
            Text(ChatGptCheckWording.INFERENCE_NOT_METERING, style = MaterialTheme.typography.bodySmall)
            if (confirmInference) {
                AlertDialog(
                    onDismissRequest = { confirmInference = false },
                    title = { Text("Send one inference?") },
                    text = {
                        Text(
                            "One short synthetic sentence to ${prefs.model}, using your ChatGPT plan. Nothing is retried, whatever " +
                                "the answer. Your credit balance is zero and automatic recharge is off.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmInference = false
                            run("inference") {
                                val (response, ms) = askOnce()
                                say("Inference: ${describe(response, ms)}. ${ChatGptCheckWording.INFERENCE_NOT_METERING}")
                                log(
                                    "inference",
                                    "single",
                                    describe(response, ms),
                                    ms = ms,
                                    detail = ChatGptCheckWording.INFERENCE_NOT_METERING,
                                )
                                (response as? ProviderResponse.Reply)?.text?.let { lastReply = it }
                            }
                        }) { Text("Send") }
                    },
                    dismissButton = { TextButton(onClick = { confirmInference = false }) { Text("Cancel") } },
                )
            }
            HorizontalDivider()

            Text("Proposal behavior (ten fixed synthetic cases)", style = MaterialTheme.typography.titleSmall)
            val attempted = prefs.proposalAttempted
            Text(
                "Attempts ${prefs.proposalUsed} of ${ProofPrefs.MAX_PROPOSAL}. " +
                    "Cases started ${attempted.size} of ${ProposalCases.all.size}. No retry. Approving writes only to an in-memory fake store.",
                style = MaterialTheme.typography.bodySmall,
            )
            val pending = ProposalCases.all.count { it.id !in attempted }
            OutlinedButton(enabled = ready && pending > 0 && prefs.proposalUsed < ProofPrefs.MAX_PROPOSAL, onClick = {
                confirmProposals = true
            }) { Text("Run the remaining proposal cases (up to $pending requests)") }
            if (confirmProposals) {
                AlertDialog(
                    onDismissRequest = { confirmProposals = false },
                    title = { Text("Send up to $pending requests?") },
                    text = {
                        Text(
                            "One synthetic request per case to ${prefs.model}, using your ChatGPT plan, stopping at the first failure. " +
                                "Nothing is retried. Your credit balance is zero and automatic recharge is off.",
                        )
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            confirmProposals = false
                            run("proposals") {
                                ProposalRun.run(
                                    ProposalCases.all,
                                    prefs.proposalAttempted,
                                    onAttempt = { prefs.markProposalAttempted(it) },
                                    send = { request ->
                                        val started = System.currentTimeMillis()
                                        proposalProvider.respond(request).also { lastProposalMs = System.currentTimeMillis() - started }
                                    },
                                    onResult = { result ->
                                        proposalResults.add(0, result)
                                        proposalScores[result.caseId] = result.score
                                        // Everything the reply asked for, a stated outcome included, waits for a tap.
                                        approvals.offer(result)
                                        say("${result.caseId}: ${result.score.verdict} ${result.score.reasons.firstOrNull().orEmpty()}")
                                        log(
                                            "proposal",
                                            result.caseId,
                                            describe(result.response, lastProposalMs),
                                            result.score.verdict,
                                            proposalDetail(result),
                                            ms = lastProposalMs,
                                        )
                                        // The whole reply, text and every call with its arguments, so nothing is judged from a fragment.
                                        evidence.append(
                                            ProofRecord(
                                                System.currentTimeMillis(),
                                                "proposal-full",
                                                result.caseId,
                                                result.score.verdict.name,
                                                detail = ProposalReport.of(result),
                                                detailLimit = ProofRecord.FULL_DETAIL,
                                            ),
                                        )
                                    },
                                )
                                val summary = ProposalSummary.of(ProposalCases.all, proposalScores.toMap())
                                log(
                                    "proposal-summary",
                                    "overall",
                                    summary.status.name,
                                    detail = summary.lines.joinToString(" | ") { it.take(64) },
                                )
                            }
                        }) { Text("Send") }
                    },
                    dismissButton = { TextButton(onClick = { confirmProposals = false }) { Text("Cancel") } },
                )
            }
            proposalResults.forEach { result ->
                Text(
                    "${result.caseId}: ${result.score.verdict}. ${result.score.reasons.joinToString("; ")} " +
                        "[${result.score.decisions.joinToString(", ")}]",
                    style = MaterialTheme.typography.bodySmall,
                )
                SelectionContainer {
                    Text(ProposalReport.of(result), style = MaterialTheme.typography.bodySmall)
                }
            }
            // Pending approvals: nothing here has been applied. Each tap applies one item to the in-memory fake store.
            if (approvalTick >= 0) {
                approvals.items.forEach { item ->
                    TextButton(enabled = !item.approved, onClick = {
                        val applied = approvals.approve(item)
                        approvalTick++
                        say("Fake store: ${item.label} ${if (applied) "approved (nothing real written)" else "not applied"}")
                        log("fake-approve", item.caseId, item.label, detail = "applied=$applied")
                    }) { Text((if (item.approved) "Approved: " else "Approve in the fake store: ") + "${item.caseId}, ${item.label}") }
                }
            }
            if (proposalScores.isNotEmpty()) {
                val summary = ProposalSummary.of(ProposalCases.all, proposalScores.toMap())
                Text("Overall: ${summary.status}. A refused call is containment, not a success. Review cases need your reading.")
                summary.lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            HorizontalDivider()

            Text("Voice session", style = MaterialTheme.typography.titleSmall)
            Text(
                "Speak, then Send: the reply is spoken by default. Typed messages stay quiet. Mute and Stop always work.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(draft, {
                draft = it
                draftMode =
                    if (draftMode == SessionMode.VOICE &&
                        it.isNotBlank()
                    ) {
                        draftMode
                    } else {
                        SessionMode.TYPED
                    }
            }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !busy, onClick = { mic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("Speak") }
                Button(enabled = false, onClick = {
                    val text = draft
                    val mode = draftMode
                    run("message") {
                        draft = ""
                        draftMode = SessionMode.TYPED
                        policy.mode = mode
                        val (response, ms) = ask(listOf(Turn(TurnRole.USER, text)))
                        say("Message (${mode.name.lowercase()}): ${describe(response, ms)}")
                        log("message", mode.name, describe(response, ms), detail = "ops: ${opsOf(response)}", ms = ms)
                        if (response is ProviderResponse.Reply) {
                            response.text?.let {
                                lastReply = it
                                speakEvent(SpeechEvent.Reply(it))
                            }
                            val proposals =
                                response.calls.count {
                                    (OperationParser.parse(it) as? ParseResult.Parsed)?.operation?.toString()?.contains("Propose") ==
                                        true
                                }
                            if (proposals > 0) speakEvent(SpeechEvent.ProposalWaiting(proposals))
                        }
                    }
                }) { Text("Send (disabled in this build)") }
            }
            if (lastReply.isNotEmpty()) Text("Last reply: $lastReply")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { speaker.stop() }) { Text("Stop") }
                OutlinedButton(enabled = lastReply.isNotEmpty(), onClick = {
                    speaker.speak(policy.lines(SpeechEvent.Replay(lastReply)))
                }) { Text("Replay") }
                OutlinedButton(onClick = {
                    muted = !muted
                    prefs.muted = muted
                    policy.muted = muted
                    if (muted) speaker.stop()
                }) { Text(if (muted) "Unmute" else "Mute") }
            }
            Text("Execution speech, simulated (nothing is written):", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    policy.mode = SessionMode.VOICE
                    speakEvent(SpeechEvent.Executed(true))
                }) { Text("Succeeded") }
                OutlinedButton(onClick = {
                    policy.mode = SessionMode.VOICE
                    speakEvent(SpeechEvent.Executed(false))
                }) { Text("Failed") }
            }
            HorizontalDivider()

            Text("Simulated failures", style = MaterialTheme.typography.titleSmall)
            Text(ChatGptCheckWording.SIMULATION, style = MaterialTheme.typography.bodySmall)
            OutlinedButton(enabled = !busy, onClick = {
                run("simulate") {
                    val reports = withContext(Dispatchers.IO) { Scenario.entries.map(ScenarioRunner::run) }
                    reports.forEach {
                        say("${if (it.passed) "ok  " else "FAIL"} ${it.scenario}: ${it.detail}")
                        log("simulate", it.scenario.name, it.detail, if (it.passed) Verdict.PASS else Verdict.FAIL)
                    }
                    say(ChatGptCheckWording.limitMessage())
                }
            }) { Text("Run all ${Scenario.entries.size} simulated scenarios") }
            HorizontalDivider()

            Text("Voices (offline, installed, English)", style = MaterialTheme.typography.titleSmall)
            OutlinedButton(onClick = { voices = VoiceSelection.usable(speaker.voices()) }) { Text("List voices") }
            voices.forEach { v ->
                TextButton(onClick = {
                    prefs.voice = v.name
                    speaker.choose(v.name)
                    speaker.speak(
                        listOf(
                            "Today I would start with the watering schedule screen, because it is a must.",
                            "I have a proposal. Tap to review it.",
                        ),
                    )
                }) { Text("${v.name}  ${v.locale}  quality ${v.quality}") }
            }
            if (voices.isEmpty() &&
                speaker.isReady
            ) {
                Text(
                    "Press List voices. If none appear, no offline English voice is installed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            HorizontalDivider()

            Text("Log", style = MaterialTheme.typography.titleSmall)
            Text("Evidence file: ${evidence.path}", style = MaterialTheme.typography.bodySmall)
            lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }

    private suspend fun fixtureAsk(fixture: com.thalia.chatgptplan.ProofFixture): Pair<ProviderResponse, Long> {
        val start = System.currentTimeMillis()
        val response = provider.respond(fixture.request())
        return response to (System.currentTimeMillis() - start)
    }
}
