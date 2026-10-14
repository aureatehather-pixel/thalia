package com.thalia.app.conversationcheck

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.thalia.app.ThaliaApplication
import com.thalia.app.ai.AiSettingsActivity
import com.thalia.app.ai.AiTransportOverride
import com.thalia.app.conversation.AccessStoreRunner
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.conversation.ConversationComposer
import com.thalia.app.conversation.ConversationScreen
import com.thalia.app.conversation.ConversationSession
import com.thalia.app.conversation.ConversationViewModel
import com.thalia.app.conversation.OnDeviceSpeechSource
import com.thalia.app.conversation.StoreConversationPort
import com.thalia.app.planning.PlanningActivity
import com.thalia.app.planning.PlanningClock
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.ui.ThaliaTheme
import com.thalia.planning.Action
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.PlanningSettings
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * The isolated device check for ADR 0010. Seeds synthetic planning data into this app's own storage, and opens the
 * production conversation screen with a scripted provider. The planning screen it opens is the production one, whose
 * own "Talk to Thalia" button still uses the unavailable provider.
 */
class ConversationCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        // Slice 4A: a fake that sends nothing, behind the real AI gates, for the production conversation screen. Set before
        // the first AI request; a restart that lands elsewhere first leaves the transport null, which is the safe direction.
        AiTransportOverride.transport = FakeAiTransport(ScriptedProvider(FaultSwitch()), java.io.File(filesDir, "ai-check-requests.log"))
        setContent {
            ThaliaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var status by remember { mutableStateOf("Synthetic data only. No request leaves the phone.") }
                    val scope = rememberCoroutineScope()
                    Column(
                        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Conversation check", style = MaterialTheme.typography.headlineSmall)
                        Text(status)
                        OutlinedButton(onClick = { scope.launch { status = seed(app) } }) { Text("Seed synthetic data") }
                        OutlinedButton(onClick = { scope.launch { status = seedInbox(app) } }) { Text("Seed Inbox captures") }
                        Text("Slice 4A: the production conversation below goes through the real AI gates to a fake transport.")
                        OutlinedButton(onClick = {
                            status =
                                if (app.aiServices.keys.save("synthetic-fake-key-not-a-secret") == ApiKeyStore.SaveResult.Saved) {
                                    "Synthetic fake key saved (sealed). It is not a credential."
                                } else {
                                    "The synthetic key could not be saved."
                                }
                        }) { Text("Save synthetic key (no typing)") }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@ConversationCheckActivity, AiSettingsActivity::class.java)) },
                        ) {
                            Text("AI settings")
                        }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@ConversationCheckActivity, ConversationActivity::class.java)) },
                        ) {
                            Text("Conversation (production, gated)")
                        }
                        OutlinedButton(onClick = { status = dumpAiState(app, filesDir) }) { Text("Dump AI state") }
                        OutlinedButton(onClick = { scope.launch { status = dumpLogical(app.planningAccess, filesDir) } }) {
                            Text("Dump logical records")
                        }
                        OutlinedButton(
                            onClick = { startActivity(Intent(this@ConversationCheckActivity, ScriptedConversationActivity::class.java)) },
                        ) {
                            Text("Scripted conversation")
                        }
                        OutlinedButton(onClick = { startActivity(Intent(this@ConversationCheckActivity, PlanningActivity::class.java)) }) {
                            Text("Planning screen")
                        }
                        Text("Slice 4C call handoff: each press arms one real scheduled call 2 minutes ahead (replacing an earlier one).")
                        OutlinedButton(onClick = { scope.launch { status = scheduleHandoff(app, CheckInType.OPENING) } }) {
                            Text("Schedule opening call (handoff)")
                        }
                        OutlinedButton(onClick = { scope.launch { status = scheduleHandoff(app, CheckInType.CLOSING) } }) {
                            Text("Schedule closing call (handoff)")
                        }
                        // ADR 0011: this build's planning clock only. M2, alarms and calls keep the system clock.
                        var offset by remember { mutableStateOf(PlanningClock.offsetMillis) }
                        Text(
                            "Planning clock: ${java.time.LocalDateTime.ofInstant(
                                PlanningClock.now(),
                                java.time.ZoneId.systemDefault(),
                            ).withNano(0)}",
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("+1h" to 3_600_000L, "+6h" to 21_600_000L, "+1d" to 86_400_000L).forEach { (label, millis) ->
                                OutlinedButton(onClick = {
                                    PlanningClock.offsetMillis += millis
                                    offset = PlanningClock.offsetMillis
                                }) { Text(label) }
                            }
                            OutlinedButton(onClick = {
                                PlanningClock.offsetMillis = 0
                                offset = 0
                            }) { Text("Reset") }
                        }
                        Text("Offset ${offset / 60_000} min")
                    }
                }
            }
        }
    }
}

/** The production conversation screen and session, with the scripted provider, and a way into planning that keeps it alive. */
class ScriptedConversationActivity : ComponentActivity() {
    private val model: ConversationViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as ThaliaApplication
                val faults = FaultSwitch()
                val port =
                    FaultyPort(StoreConversationPort(AccessStoreRunner(app.planningAccess), app.checkInZone, PlanningClock::now), faults)
                return ConversationViewModel(
                    ConversationSession(port, ScriptedProvider(faults)),
                    ConversationComposer(OnDeviceSpeechSource(app)),
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThaliaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    // The insets are applied once, here. Applied on the button alone, its padding took the keyboard's height and
                    // ConversationScreen then took it a second time, which left that screen no room at all.
                    Column(modifier = Modifier.safeDrawingPadding()) {
                        OutlinedButton(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            onClick = { startActivity(Intent(this@ScriptedConversationActivity, PlanningActivity::class.java)) },
                        ) { Text("Open planning on top") }
                        ConversationScreen(model)
                    }
                }
            }
        }
    }
}

/** Check-only, Slice 4C: arms one real scheduled call through the production alarm path. It touches no planning data. */
private suspend fun scheduleHandoff(
    app: ThaliaApplication,
    type: CheckInType,
): String = "Armed ${type.name.lowercase()} call: ${app.devScheduledRing.schedule(type)}"

/** M2's default check-in rows give planning its zone; reading the plan stores them and arms nothing. Then planning fixtures. */
private suspend fun seed(app: ThaliaApplication): String {
    app.occurrenceStore.plan()
    return app.planningAccess.withStore { store ->
        if (store.snapshot().counts.total != 0) return@withStore "Not seeded: planning data is not empty."
        store.saveDirection(Direction("Synthetic direction for the conversation check"))
        store.saveSettings(PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW))
        val goal = UUID.randomUUID().toString()
        check(store.saveGoal(Goal(goal, "Synthetic goal", "Synthetic success condition", null)) == null)
        val project = UUID.randomUUID().toString()
        check(store.saveProject(Project(project, "Synthetic project", goal, ProjectState.ACTIVE)) == null)
        for ((title, priority) in listOf(
            "Synthetic alpha" to Priority.MUST,
            "Synthetic beta" to Priority.IMPORTANT,
            "Synthetic gamma" to Priority.NORMAL,
        )) {
            check(store.saveAction(Action(UUID.randomUUID().toString(), title, project, priority, 30)) == null)
        }
        "Seeded: direction, goal, project, three open actions, window 12:00 to 22:00."
    }
}

/** Check-only, ADR 0012: four synthetic captures with fixed ids, so pressing it twice adds nothing. Existing data is kept. */
private suspend fun seedInbox(app: ThaliaApplication): String {
    val captures =
        listOf(
            "Synthetic: ask about the café menu, 日本 \"quoted\"",
            "Synthetic: remind me to call the dentist at 3pm",
            "Synthetic long capture\nline two\n" + "x".repeat(600),
            "Synthetic: maybe learn to juggle",
        )
    return app.planningAccess.withStore { store ->
        val results =
            captures.map { text ->
                store.captureItem(com.thalia.planning.InboxItem(UUID.nameUUIDFromBytes("inbox-check:$text".toByteArray()).toString(), text))
            }
        "Inbox seeded: ${results.count { it == com.thalia.app.planning.data.CaptureResult.CAPTURED }} new, ${results.size} total checked."
    }
}

/** Check-only: the canonical snapshot (rows only, fixed metadata) in this app's files dir, to compare across an export and restore. */
private suspend fun dumpLogical(
    access: com.thalia.app.planning.PlanningAccess,
    dir: java.io.File,
): String {
    val snapshot = access.snapshot() ?: return "Planning data is not open: ${access.state()}"
    val payload =
        com.thalia.planning.PlanningExportFormat.encode(
            com.thalia.planning.PlanningExportContent(
                com.thalia.planning.PlanningExportInfo(
                    com.thalia.app.planning.data.PlanningDatabase.SCHEMA_VERSION,
                    "logical",
                    java.time.Instant.EPOCH,
                    java.time.ZoneOffset.UTC,
                ),
                snapshot,
            ),
        )
    java.io.File(dir, "logical.json").writeBytes(payload)
    return "Wrote logical.json, ${payload.size} bytes. Rows: ${snapshot.counts}"
}

/** Check-only: consent, key presence (never the key), the budget ledger and the requests the fake received, in one file. */
private fun dumpAiState(
    app: ThaliaApplication,
    dir: java.io.File,
): String {
    val ai = app.aiServices
    val ledger = java.io.File(dir, "ai/budget.log")
    val requests = java.io.File(dir, "ai-check-requests.log")
    val text =
        buildString {
            appendLine("consentAccepted=${ai.consent.isAccepted()}")
            appendLine("keySaved=${ai.keys.hasKey()}")
            // Opens the sealed key through the real Keystore path; prints only whether it is the synthetic string, never the key.
            appendLine("keyOpensAsSynthetic=${ai.keys.load() == "synthetic-fake-key-not-a-secret"}")
            appendLine("connection=${ai.connection}")
            val s = ai.ledger.summary(java.time.Instant.now())
            appendLine(
                "summary month=${s.month} budget=${s.budgetMicros} settled=${s.settledMicros} " +
                    "held=${s.heldMicros} heldCount=${s.heldCount}",
            )
            appendLine("--- ledger")
            append(if (ledger.isFile) ledger.readText() else "(none)\n")
            appendLine("--- requests the fake received")
            append(if (requests.isFile) requests.readText() else "(none)\n")
        }
    java.io.File(dir, "ai-check-state.txt").writeText(text)
    return "Wrote ai-check-state.txt, ${text.length} chars."
}
