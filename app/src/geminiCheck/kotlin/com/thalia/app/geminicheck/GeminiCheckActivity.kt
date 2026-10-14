package com.thalia.app.geminicheck

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
import com.thalia.app.ai.AiTransportOverride
import com.thalia.app.conversation.AccessStoreRunner
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.conversation.StoreConversationPort
import com.thalia.app.planning.PlanningActivity
import com.thalia.app.planning.PlanningClock
import com.thalia.app.planning.data.CaptureResult
import com.thalia.app.ui.ThaliaTheme
import com.thalia.conversation.ContextLimits
import com.thalia.conversation.ConversationPrompt
import com.thalia.conversation.ConversationTools
import com.thalia.conversation.GeminiPrices
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.RequestCost
import com.thalia.conversation.Turn
import com.thalia.conversation.TurnRole
import com.thalia.planning.Action
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.InboxItem
import com.thalia.planning.PlanningSettings
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.voicecheckins.gemini.GeminiApiKey
import com.thalia.voicecheckins.gemini.GeminiTextConversationProvider
import kotlinx.coroutines.launch
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The isolated synthetic live-model check, ADR 0013. It seeds invented planning data into this app's own storage and puts the
 * real Gemini transport behind the production consent, key and budget gates. Nothing is sent until the owner turns AI on,
 * enters a key in AI settings and presses Send. Every request is recorded, with its synthetic content, in this app's private
 * files. It never touches com.thalia.app.debug.
 */
class GeminiCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        // Before the first AI request. The key is read when a request is made, from the sealed store, never earlier.
        AiTransportOverride.fake = false
        AiTransportOverride.consentVersion = GeminiCheckWording.VERSION
        AiTransportOverride.consentText = GeminiCheckWording.TEXT
        AiTransportOverride.estimateNote = GeminiCheckWording.ESTIMATE_NOTE
        AiTransportOverride.transport =
            RecordingTransport(
                GeminiTextConversationProvider(apiKey = {
                    app.aiServices.keys
                        .load()
                        ?.let(::GeminiApiKey)
                }),
                File(filesDir, "ai-live-requests.log"),
                File(filesDir, "ai-live-stop.txt"),
                File(filesDir, "ai-live-count.txt"),
            )
        setContent {
            ThaliaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var status by remember {
                        mutableStateOf(
                            "Synthetic data only. No request is sent until AI is on, a key is saved and Send is pressed.",
                        )
                    }
                    val scope = rememberCoroutineScope()
                    Column(
                        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Live check (synthetic)", style = MaterialTheme.typography.headlineSmall)
                        Text("LIVE CHECK BUILD: Send goes to Google's Gemini with the key you enter. The data is invented.")
                        Text(status)
                        OutlinedButton(onClick = { scope.launch { status = seed(app) } }) { Text("Seed synthetic data") }
                        OutlinedButton(onClick = { scope.launch { status = seedInbox(app) } }) { Text("Seed Inbox captures") }
                        OutlinedButton(
                            onClick = { scope.launch { status = worstCase(app) } },
                        ) { Text("Show worst-case cost (sends nothing)") }
                        OutlinedButton(onClick = { startActivity(Intent(this@GeminiCheckActivity, AiSettingsActivity::class.java)) }) {
                            Text("AI settings")
                        }
                        OutlinedButton(onClick = { startActivity(Intent(this@GeminiCheckActivity, PlanningActivity::class.java)) }) {
                            Text("Planning screen")
                        }
                        OutlinedButton(onClick = { startActivity(Intent(this@GeminiCheckActivity, ConversationActivity::class.java)) }) {
                            Text("Conversation")
                        }
                        OutlinedButton(onClick = { status = dumpState(app, filesDir) }) { Text("Dump AI state") }
                    }
                }
            }
        }
    }
}

private fun id(name: String) = UUID.nameUUIDFromBytes("gemini-check:$name".toByteArray()).toString()

/** M2's default check-in rows give planning its zone (reading the plan stores them and arms nothing), then invented fixtures. */
private suspend fun seed(app: ThaliaApplication): String {
    app.occurrenceStore.plan()
    return app.planningAccess.withStore { store ->
        if (store.snapshot().counts.total != 0) return@withStore "Not seeded: planning data is not empty."
        store.saveDirection(Direction("Synthetic: ship a small prototype of the Synthetic Garden app"))
        store.saveSettings(PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW))
        check(store.saveGoal(Goal(id("goal"), "Synthetic: demo the prototype", "Synthetic: five testers try it", null)) == null)
        check(store.saveProject(Project(id("project"), "Synthetic Garden app", id("goal"), ProjectState.ACTIVE)) == null)
        val today = LocalDate.now()
        listOf(
            Action(id("a1"), "Synthetic: build the watering schedule screen", id("project"), Priority.MUST, 90, today.plusDays(1)),
            Action(id("a2"), "Synthetic: fix the plant photo upload bug", id("project"), Priority.IMPORTANT, 60, today.plusDays(4)),
            Action(id("a3"), "Synthetic: write the demo script", id("project"), Priority.IMPORTANT, 45, today.plusDays(7)),
            Action(id("a4"), "Synthetic: reply to the beta testers", null, Priority.NORMAL, 20),
            Action(id("a5"), "Synthetic: tidy the old notes folder", null, Priority.OPTIONAL, 30),
        ).forEach { check(store.saveAction(it) == null) }
        "Seeded: direction, goal, project, five open actions, window 12:00 to 22:00. All invented."
    }
}

/** Three invented captures with fixed ids: one vague, one idea, one long enough to be cut. Pressing it twice adds nothing. */
private suspend fun seedInbox(app: ThaliaApplication): String {
    val captures =
        listOf(
            "Synthetic: the thing with the vendor",
            "Synthetic: maybe offer a dark theme in the prototype",
            "Synthetic long capture: " + "this note rambles about the garden app and ".repeat(14),
        )
    return app.planningAccess.withStore { store ->
        val results = captures.map { store.captureItem(InboxItem(id(it.take(40)), it)) }
        "Inbox seeded: ${results.count { it == CaptureResult.CAPTURED }} new, ${results.size} total checked."
    }
}

/** What one Send would reserve right now, from the real snapshot and a typical message. Reads local data only. */
private suspend fun worstCase(app: ThaliaApplication): String {
    val port = StoreConversationPort(AccessStoreRunner(app.planningAccess), app.checkInZone, PlanningClock::now)
    val ids = app.planningAccess.withStore { s -> s.inbox().map { it.value.id }.toSet() }

    suspend fun request(inboxIds: Set<String>?): ProviderRequest? {
        val context = port.snapshot(inboxIds)?.context ?: return null
        return ProviderRequest(
            ConversationPrompt.INSTRUCTION,
            context.render(),
            listOf(Turn(TurnRole.USER, "What should I put in today's plan, and why?")),
            ConversationTools.DECLARATIONS_JSON,
        )
    }
    val ordinary = request(null) ?: return "Planning is not ready (seed the synthetic data first)."
    val triage = request(ids) ?: return "Planning is not ready."

    fun line(
        label: String,
        r: ProviderRequest,
    ): String {
        val w = RequestCost.worstCaseMicros(r, ContextLimits.OUTPUT_TOKENS, GeminiPrices.TEXT, Instant.now())
        return "$label: ${RequestCost.requestBytes(r)} bytes, reserves $w micro-USD (USD ${w / 1_000_000.0})."
    }
    return line("Ordinary send", ordinary) + " " + line("Triage send", triage)
}

/** Consent, key presence (never the key), the ledger, and the requests this build made, in one file. */
private fun dumpState(
    app: ThaliaApplication,
    dir: File,
): String {
    val ai = app.aiServices
    val text =
        buildString {
            appendLine("consentAccepted=${ai.consent.isAccepted()}")
            appendLine("keySaved=${ai.keys.hasKey()}")
            appendLine("connection=${ai.connection}")
            appendLine("consentVersion=${GeminiCheckWording.VERSION}")
            appendLine("ledger amounts below are paid-equivalent estimates, not charges")
            val s = ai.ledger.summary(Instant.now())
            appendLine(
                "summary month=${s.month} budget=${s.budgetMicros} settled=${s.settledMicros} held=${s.heldMicros} heldCount=${s.heldCount}",
            )
            appendLine("--- ledger")
            File(dir, "ai/budget.log").let { append(if (it.isFile) it.readText() else "(none)\n") }
            appendLine("--- requests and responses")
            File(dir, "ai-live-requests.log").let { append(if (it.isFile) it.readText() else "(none)\n") }
        }
    File(dir, "ai-live-state.txt").writeText(text)
    return "Wrote ai-live-state.txt, ${text.length} chars."
}
