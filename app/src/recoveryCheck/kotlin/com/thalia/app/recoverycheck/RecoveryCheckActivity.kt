package com.thalia.app.recoverycheck

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import com.thalia.app.BuildConfig
import com.thalia.app.ThaliaApplication
import com.thalia.app.planning.PlanningAccess
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.CallAuthorization
import com.thalia.app.planning.data.KeystorePassphraseSealer
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.ui.SettingsActivity
import com.thalia.app.ui.ThaliaTheme
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningExportContent
import com.thalia.planning.PlanningExportFormat
import com.thalia.planning.PlanningExportInfo
import com.thalia.planning.PlanningSettings
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The isolated recovery check, `com.thalia.app.recoverycheck` only. ADR 0009 slice 1B, criterion 5.
 *
 * - **Run storage checks** runs [RecoveryChecks] on scratch files, on real SQLCipher and the Keystore.
 * - **Add synthetic planning data**, **Fingerprint** and **Open planning** drive the recovery without
 *   the original Keystore: seed this app's planning data, note the fingerprint, export through the
 *   planning screen to a file outside this app, clear only this app's data, import, and compare.
 */
class RecoveryCheckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        setContent {
            ThaliaTheme {
                val scope = rememberCoroutineScope()
                var busy by remember { mutableStateOf(false) }
                var lines by remember { mutableStateOf(listOf<String>()) }

                fun runOff(work: suspend () -> List<String>) {
                    busy = true
                    scope.launch {
                        lines =
                            try {
                                withContext(Dispatchers.IO) { work() }
                            } catch (e: Exception) {
                                listOf("FAIL ${e.javaClass.simpleName}: ${e.message}")
                            }
                        lines.forEach { Log.i(RecoveryChecks.LOG_TAG, it) }
                        busy = false
                    }
                }

                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column(
                        modifier =
                            Modifier
                                .safeDrawingPadding()
                                .verticalScroll(rememberScrollState())
                                .padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Thalia recovery check", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "App ${BuildConfig.APPLICATION_ID}, planning files \"${BuildConfig.PLANNING_STORAGE_NAME}\", " +
                                "Keystore alias \"${BuildConfig.PLANNING_KEY_ALIAS}\". Synthetic data only.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(enabled = !busy, onClick = {
                            runOff {
                                RecoveryChecks(applicationContext).runAll().map { r ->
                                    "${if (r.passed) "PASS" else "FAIL"} ${r.name}: ${r.detail}"
                                }
                            }
                        }) { Text("Run storage checks") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { runOff { presence(applicationContext) } },
                        ) { Text("Key and file status (does not open planning)") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { runOff { presence(applicationContext) + fingerprint(app.planningAccess) } },
                        ) {
                            Text("Fingerprint (opens planning, creating it if absent)")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { runOff { seed(app.planningAccess) + fingerprint(app.planningAccess) } },
                        ) {
                            Text("Add synthetic planning data")
                        }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { runOff { listOf(dumpLogical(app.planningAccess, filesDir)) } },
                        ) { Text("Dump logical records") }
                        // Export and import moved from the planning screen to Settings, under Data, with the four-tab shell.
                        OutlinedButton(onClick = { startActivity(Intent(this@RecoveryCheckActivity, SettingsActivity::class.java)) }) {
                            Text("Open settings: export and import")
                        }
                        if (busy) Text("Running...")
                        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

/**
 * Whether this app's planning Keystore key and files exist, read without opening planning data, so
 * neither is created by looking. Run it first after clearing this app's data.
 */
private fun presence(context: Context): List<String> {
    val files = PlanningFiles.of(context, BuildConfig.PLANNING_STORAGE_NAME)
    val present = (files.live + files.staged + listOf(files.journal, files.setAsideRoot)).filter(File::exists).map(File::getName)
    return listOf(
        "Keystore key present: ${KeystorePassphraseSealer(BuildConfig.PLANNING_KEY_ALIAS).hasKey()}",
        "Planning files present: ${present.ifEmpty { listOf("none") }.joinToString()}",
    )
}

/** A fingerprint of every planning row, to compare across an export and import. Opens planning data. */
private suspend fun fingerprint(access: PlanningAccess): List<String> {
    val snapshot = access.snapshot() ?: return listOf("Planning data is not open: ${access.state()}")
    // The rows only: fixed metadata, so the same rows always give the same fingerprint.
    val payload =
        PlanningExportFormat.encode(
            PlanningExportContent(
                PlanningExportInfo(PlanningDatabase.SCHEMA_VERSION, "fingerprint", Instant.EPOCH, ZoneOffset.UTC),
                snapshot,
            ),
        )
    val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
    // The digest covers outcomes (with their marker) and each plan's opening time, not the call rows: those are
    // facts about this phone's M2 and are never exported. The call statuses are shown beside it as a separate
    // check: a source phone shows REQUESTED (seeded through the explicit authorization), and a restore must
    // show NOT_LINKED, never REQUESTED.
    val calls =
        access
            .withStore { it.calls() }
            .groupingBy { it.status }
            .eachCount()
            .toSortedMap()
    return listOf("Rows: ${snapshot.counts}", "Fingerprint: ${digest.take(32)}", "Opening call rows by status: $calls")
}

/** Synthetic rows, written only into an empty planning database, through the store's normal saves. */
private suspend fun seed(access: PlanningAccess): List<String> =
    access.withStore { store ->
        if (store.snapshot().counts.total != 0) {
            listOf("Not seeded: planning data is not empty")
        } else {
            seedInto(store)
            listOf("Seeded synthetic planning data")
        }
    }

private suspend fun seedInto(store: PlanningStore) {
    store.saveDirection(Direction("Synthetic direction: récupération 日本 \"quoted\""))
    store.saveSettings(PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW))
    val goal = UUID.randomUUID().toString()
    check(store.saveGoal(Goal(goal, "Synthetic goal", "Synthetic success condition", LocalDate.of(2027, 1, 31))) == null)
    val project = UUID.randomUUID().toString()
    check(store.saveProject(Project(project, "Synthetic active project", goal, ProjectState.ACTIVE)) == null)
    check(store.saveProject(Project(UUID.randomUUID().toString(), "Synthetic someday project", null, ProjectState.SOMEDAY)) == null)
    val must = Action(UUID.randomUUID().toString(), "Synthetic must action", project, Priority.MUST, 90, LocalDate.of(2026, 10, 5))
    check(store.saveAction(must) == null)
    val standalone = Action(UUID.randomUUID().toString(), "Synthetic standalone action")
    check(store.saveAction(standalone) == null)
    // Two revisions of one day's plan, so the round trip covers plans, items, snapshots and superseding.
    val day = ActiveDay(LocalDate.of(2026, 10, 3))
    val utc = ZoneOffset.UTC
    val selected = setOf(must.id, standalone.id)
    check(store.approve(checkNotNull(store.propose(day, utc, null, selected, emptyMap())), utc) is ApprovalResult.Approved)
    val second = store.approve(checkNotNull(store.propose(day, utc, null, selected, emptyMap())), utc, openingTime = LocalTime.of(8, 0))
    // The explicit confirmation, as the user's tick would be: approval alone only keeps the time, NOT_LINKED.
    check(store.authorizeOpeningCall((second as ApprovalResult.Approved).plan.plan.id, day) == CallAuthorization.AUTHORIZED)
    // Outcomes on the latest revision, one of them closing its action, so the round trip carries a marker too.
    for ((action, result) in listOf(must to OutcomeResult.DONE, standalone to OutcomeResult.BLOCKED)) {
        val item = checkNotNull(second.plan.items.singleOrNull { it.actionId == action.id })
        check(store.recordOutcome(item.id, result, day) is OutcomeRecording.Recorded)
    }
}

/** Check-only: the canonical snapshot (rows only, fixed metadata) written to this app's files dir, to compare across an export and restore. */
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
