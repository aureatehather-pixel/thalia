package com.thalia.app.livecheck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thalia.app.ThaliaApplication
import com.thalia.app.ai.AiSettingsActivity
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.conversation.StreamingPlayback
import com.thalia.app.planning.PlanningActivity
import com.thalia.app.ui.ThaliaTheme
import com.thalia.conversation.OpenAiPrices
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RequestCost
import com.thalia.intentcases.IntentCases
import com.thalia.intentcases.Verdict
import com.thalia.planning.Action
import com.thalia.planning.Direction
import com.thalia.planning.PlanningSettings
import com.thalia.planning.Priority
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.util.UUID

/**
 * The isolated device check for the everyday V1 route. Synthetic data only, in this app's own storage. Every request goes through
 * the same gated provider the real conversation uses (consent, key, budget). Nothing runs on open.
 */
class LiveCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        val dir = File(filesDir, "live-check").apply { mkdirs() }
        setContent {
            ThaliaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var status by remember { mutableStateOf("Synthetic data only. Nothing is sent until you press a button that says so.") }
                    var busy by remember { mutableStateOf(false) }
                    val scope = rememberCoroutineScope()
                    Column(
                        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Live check", style = MaterialTheme.typography.headlineSmall)
                        OutlinedButton(onClick = { startActivity(Intent(this@LiveCheckActivity, AiSettingsActivity::class.java)) }) {
                            Text("AI settings (consent, keys, budget)")
                        }
                        OutlinedButton(onClick = { scope.launch { status = seed(app) } }) { Text("Seed synthetic data") }
                        OutlinedButton(onClick = { startActivity(Intent(this@LiveCheckActivity, ConversationActivity::class.java)) }) {
                            Text("Conversation (production, gated)")
                        }
                        OutlinedButton(onClick = { startActivity(Intent(this@LiveCheckActivity, PlanningActivity::class.java)) }) {
                            Text("Planning screen")
                        }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                status = runEightCases(app, dir)
                                busy = false
                            }
                        }) { Text("Run the 8 intent cases once (sends up to 8 paid requests to OpenAI)") }
                        OutlinedButton(enabled = !busy, onClick = {
                            busy = true
                            scope.launch {
                                status = speakSample(app, dir, StreamingPlayback(this@LiveCheckActivity))
                                busy = false
                            }
                        }) { Text("Speak one synthetic sample once (sends ${SAMPLE.length} characters to Cartesia)") }
                        OutlinedButton(onClick = { status = usage(app) }) { Text("Show budget and usage") }
                        SelectionContainer { Text(status) }
                    }
                }
            }
        }
    }
}

private suspend fun seed(app: ThaliaApplication): String {
    app.occurrenceStore.plan()
    return app.planningAccess.withStore { store ->
        if (store.snapshot().counts.total != 0) return@withStore "Not seeded: planning data is not empty."
        store.saveDirection(Direction("Synthetic: finish the current work and keep the week calm"))
        store.saveSettings(PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW))
        for ((title, priority) in listOf(
            "Read paper" to Priority.IMPORTANT,
            "Call vendor" to Priority.NORMAL,
            "Write report" to Priority.NORMAL,
            "Email Sam" to Priority.NORMAL,
            "Call dentist" to Priority.NORMAL,
        )) {
            check(store.saveAction(Action(UUID.randomUUID().toString(), title, null, priority, 30)) == null)
        }
        "Seeded: direction, window, five open actions (Read paper, Call vendor, Write report, Email Sam, Call dentist)."
    }
}

private const val SAMPLE = "Hello, this is Thalia. This is a short test of my voice."

/** One synthetic spoken sample, once: through the same gates as a real reply. A press stopped by a gate before sending can be repeated. */
private suspend fun speakSample(
    app: ThaliaApplication,
    dir: File,
    playback: StreamingPlayback,
): String {
    val marker = File(dir, "sample.done")
    if (marker.exists()) return "Sample already spoken once. " + usage(app)
    val problem = app.aiServices.spokenReplies(playback).speak(SAMPLE)
    val report = File(File(app.filesDir, com.thalia.app.ai.AiServices.DIRECTORY_NAME), "speech-report.txt")
    val last = if (report.exists()) report.readLines().lastOrNull() else null
    val sent = last != null && "result=" in last && !last.contains("result=CONSENT") && !last.contains("NO_KEY")
    if (sent) marker.writeText("attempted\n")
    return (problem?.let { "Not spoken: ${it.text}" } ?: "Spoke the sample.") + "\nLast request: ${last ?: "none sent"}\n" + usage(app)
}

private val GATE_FAILURES =
    setOf(
        ProviderFailure.UNAVAILABLE,
        ProviderFailure.CONSENT_NEEDED,
        ProviderFailure.KEY_MISSING,
        ProviderFailure.BUDGET_EXHAUSTED,
        ProviderFailure.TOO_LARGE,
        ProviderFailure.BUSY,
        ProviderFailure.LEDGER_UNWRITABLE,
    )

/**
 * The eight cases, once. They go through the gated provider, so consent, key and the budget all apply, and each case is one
 * request with no retry. A failed request stops the run. After any request was really attempted, the run is marked used and a
 * second press only shows the stored result. A run stopped by a gate before anything was sent can be pressed again.
 */
private suspend fun runEightCases(
    app: ThaliaApplication,
    dir: File,
): String {
    val marker = File(dir, "intent-run.done")
    val report = File(dir, "intent-cases.txt")
    if (marker.exists()) return "Already run once. Stored result:\n" + report.readText()
    var attempted = false
    val lines = mutableListOf<String>()
    var micros = 0L
    var input = 0L
    var output = 0L
    val results =
        IntentCases.runOnce(
            send = { request ->
                val response = app.aiServices.provider.respond(request)
                if (!(response is ProviderResponse.Failed && response.failure in GATE_FAILURES)) {
                    attempted = true
                    // Written before the next case, so a crash still leaves the run marked.
                    marker.writeText("attempted\n")
                }
                response
            },
            onResult = { r ->
                val u = r.usage
                val cost = RequestCost.actualMicros(u, OpenAiPrices.TEXT, Instant.now())
                if (cost != null) micros += cost
                input += u?.promptTokens ?: 0
                output += u?.outputTokens ?: 0
                lines +=
                    "${r.case.id} ${r.verdict} | expected: ${r.case.expected} | why: ${r.why} | kotlin read: ${r.decisions} | " +
                    "reply text: ${r.replyText ?: "(none)"} | tokens in ${u?.promptTokens} out ${u?.outputTokens} | est cost micro-USD $cost"
            },
        )
    val pass = results.count { it.verdict == Verdict.PASS }
    val text =
        "cases passed $pass/${results.size} (sent: ${results.size}); input tokens $input, output tokens $output, " +
            "estimated cost ${com.thalia.app.ai.Usd.format(micros)} (micro-USD $micros)\n" + lines.joinToString("\n")
    if (attempted) report.writeText(text + "\n") else return "Nothing was sent. $text"
    return text
}

private fun usage(app: ThaliaApplication): String {
    val ai = app.aiServices
    val s = ai.ledger.summary(Instant.now())
    val sp = ai.speechTestLedger.summary(Instant.now())
    val monthly = ai.speechUsage.summary(Instant.now())
    return "AI: consent ${ai.consent.isAccepted()}, key saved ${ai.keys.hasKey()}, month ${s.month} spent " +
        "${com.thalia.app.ai.Usd.format(s.spentMicros)} of ${com.thalia.app.ai.Usd.format(s.budgetMicros)} (held ${s.heldCount}).\n" +
        "Speech: consent ${ai.speechConsent.isAccepted()}, key saved ${ai.speechKeys.hasKey()}, characters submitted " +
        "${sp.spentMicros} of the ${sp.budgetMicros}-character test ledger (kept separately as evidence). Monthly meter: " +
        "${monthly.used} of ${monthly.cap ?: "no cap set"} (app-tracked, not billing)."
}
