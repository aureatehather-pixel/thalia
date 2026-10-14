package com.thalia.app.planning

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.PlanningOpenProblem
import com.thalia.planning.ImportProblem
import com.thalia.planning.ImportRefusedException
import com.thalia.planning.PlanningArchive
import com.thalia.planning.PlanningCounts
import com.thalia.planning.PlanningExportContent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Export and import, ADR 0009 slice 1B. The password lives only in this screen's memory: it is never
 * saved, logged, shown again, or kept across a restart of the screen. [onChanged] runs, with what
 * happened, after planning data was restored or replaced, so the screen reopens it.
 */
@Composable
internal fun TransferSection(
    access: PlanningAccess,
    transfer: PlanningTransfer,
    onChanged: (String) -> Unit,
) {
    val resolver = LocalContext.current.applicationContext.contentResolver
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var exportPassword by remember { mutableStateOf("") }
    var exportConfirm by remember { mutableStateOf("") }
    var importPassword by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<Preview?>(null) }

    /** Runs [work] off the main thread with the controls disabled; every failure becomes a message. */
    fun run(
        label: String,
        work: suspend () -> String,
    ) {
        busy = label
        message = null
        scope.launch {
            message =
                try {
                    work()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ImportRefusedException) {
                    describe(e.problem)
                } catch (e: PlanningTransfer.ExportTooLargeException) {
                    "Not exported: planning data is larger than one export file can hold (8 MiB)."
                } catch (e: Exception) {
                    "$label failed: ${e.javaClass.simpleName}. Nothing was deleted."
                } finally {
                    busy = null
                }
        }
    }

    val createDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
            if (uri == null) {
                message = "Export cancelled."
                return@rememberLauncherForActivityResult
            }
            if (exportPassword.length < PlanningArchive.MIN_PASSWORD_LENGTH) {
                // The screen restarted while the picker was open, and the password, kept only in memory, went with it.
                message = "Export not started: type the password again. " + LEFT_IN_PLACE
                return@rememberLauncherForActivityResult
            }
            val password = exportPassword.toCharArray()
            run("Export") {
                try {
                    when (val outcome = transfer.exportToDocument(resolver, uri, password)) {
                        is ExportOutcome.Exported -> {
                            exportPassword = ""
                            exportConfirm = ""
                            "Exported and read back: ${summary(outcome.counts)}. Keep the file and its password; neither can be recovered."
                        }
                        ExportOutcome.NotEmpty ->
                            "Not exported: the chosen file is not empty, or its size is unknown. As a safeguard against overwriting, " +
                                "Thalia writes only into a file the storage reports as empty; nothing was written. Choose a new file name."
                        is ExportOutcome.Failed ->
                            when (outcome.error) {
                                is PlanningTransfer.ExportTooLargeException ->
                                    "Not exported: planning data is larger than one export file can hold (8 MiB). "
                                else -> "Export failed: ${outcome.error.javaClass.simpleName}. "
                            } + LEFT_IN_PLACE
                    }
                } finally {
                    password.fill('\u0000')
                }
            }
        }

    val openDocument =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            if (importPassword.isEmpty()) {
                message = "Import cancelled: type the export's password again."
                return@rememberLauncherForActivityResult
            }
            val password = importPassword.toCharArray()
            run("Import") {
                try {
                    val archive = withContext(Dispatchers.IO) { PlanningDocuments.read(resolver, uri) }
                    val content = transfer.open(archive, password)
                    val current = access.snapshot()?.counts ?: error("planning data is not open")
                    preview = Preview(content, current)
                    importPassword = ""
                    "Checked and decrypted. Nothing is written until you confirm below."
                } finally {
                    password.fill('\u0000')
                }
            }
        }

    Text("Export and import", style = MaterialTheme.typography.titleMedium)
    busy?.let { Text("$it in progress. Encrypting or decrypting takes a few seconds...", color = MaterialTheme.colorScheme.primary) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    val current = preview
    if (current != null) {
        return ImportPreview(current, enabled = busy == null, onCancel = { preview = null }, onRestore = {
            run("Restore") {
                if (access.restoreIntoEmpty(current.content.snapshot)) {
                    "Restored: ${summary(current.content.snapshot.counts)}.".also(onChanged)
                } else {
                    "Not restored: planning data on this phone is no longer empty. Nothing was changed."
                }
            }
        }, onReplace = {
            run("Replace") {
                try {
                    val folder = access.replace(current.content.snapshot)
                    "Replaced. The previous planning data is kept on this phone in ${folder.name}.".also(onChanged)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Past the commit point, reopening finishes the replacement; before it, nothing changed. Either way, reload.
                    "Replace stopped: ${e.javaClass.simpleName}. Planning data was reopened as it now is; nothing was deleted.".also(
                        onChanged,
                    )
                }
            }
        })
    }

    Text(
        "Export writes all planning data to one new file, encrypted with a password you choose. " +
            "Choose a new file name; Thalia will not write into an existing file. " +
            "It holds no key from this phone, so it restores on any phone. A forgotten password cannot be recovered.",
        style = MaterialTheme.typography.bodySmall,
    )
    PasswordField(exportPassword, { exportPassword = it }, "Export password, at least ${PlanningArchive.MIN_PASSWORD_LENGTH} characters")
    PasswordField(exportConfirm, { exportConfirm = it }, "Type it again")
    val exportReady = exportPassword.length >= PlanningArchive.MIN_PASSWORD_LENGTH && exportPassword == exportConfirm
    if (exportPassword.isNotEmpty() && !exportReady) {
        Text(
            if (exportPassword.length < PlanningArchive.MIN_PASSWORD_LENGTH) "Too short." else "The two passwords differ.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Button(enabled = exportReady && busy == null, onClick = { createDocument.launch(transfer.suggestedFileName()) }) {
        Text("Export to a file...")
    }

    PasswordField(importPassword, { importPassword = it }, "Password of the export to import")
    OutlinedButton(enabled = importPassword.isNotEmpty() && busy == null, onClick = { openDocument.launch(arrayOf("*/*")) }) {
        Text("Import from a file...")
    }
}

/** Thalia never deletes a document it was given; after a failed export the user decides. */
private const val LEFT_IN_PLACE =
    "The chosen file was left in place and may be empty or hold an incomplete export, which cannot be imported. Delete it yourself."

/** A decrypted, validated export, waiting for the user's decision, and what this phone holds now. */
internal class Preview(
    val content: PlanningExportContent,
    val current: PlanningCounts,
)

@Composable
private fun ImportPreview(
    preview: Preview,
    enabled: Boolean,
    onCancel: () -> Unit,
    onRestore: () -> Unit,
    onReplace: () -> Unit,
) {
    val info = preview.content.info
    val made = info.createdAt.atZone(info.zone).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM))
    Text("Export made $made by Thalia ${info.appVersion}", style = MaterialTheme.typography.labelLarge)
    Text("In the export: ${summary(preview.content.snapshot.counts)}.")
    Text("On this phone now: ${summary(preview.current)}.")
    if (preview.current.total == 0) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = enabled, onClick = onRestore) { Text("Restore this export") }
            TextButton(enabled = enabled, onClick = onCancel) { Text("Cancel") }
        }
        return
    }
    var replacing by remember { mutableStateOf(false) }
    var typed by remember { mutableStateOf("") }
    Text(
        "Not imported: this phone already has planning data, and import never merges.",
        color = MaterialTheme.colorScheme.error,
    )
    if (!replacing) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = enabled, onClick = { replacing = true }) { Text("Replace current planning data...") }
            TextButton(enabled = enabled, onClick = onCancel) { Text("Cancel") }
        }
        return
    }
    Text(
        "Replacing moves the current planning data into a dated folder on this phone, where it is kept, " +
            "and puts the export in its place. Check-ins are not affected. Type REPLACE to confirm.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(typed, { typed = it }, label = { Text("Type REPLACE") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = enabled && typed == "REPLACE", onClick = onReplace) { Text("Replace") }
        TextButton(enabled = enabled, onClick = onCancel) { Text("Cancel") }
    }
}

/**
 * Shown when planning data cannot be opened: "Set aside and start fresh", behind a confirmation. The
 * files are renamed into a dated folder and kept; nothing is deleted. ADR 0009 decision 7.
 */
@Composable
internal fun SetAsideSection(
    access: PlanningAccess,
    problem: PlanningOpenProblem,
    onChanged: (String) -> Unit,
) {
    // Not for a failed migration, which a later Thalia may fix in place, nor when storage itself is unavailable.
    if (problem == PlanningOpenProblem.STORAGE_UNAVAILABLE || problem == PlanningOpenProblem.MIGRATION_FAILED) return
    val scope = rememberCoroutineScope()
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    if (!confirming) {
        OutlinedButton(onClick = { confirming = true }) { Text("Set aside and start fresh...") }
        return
    }
    Text(
        "The planning files on this phone are moved into a dated folder and kept, not deleted, and planning starts empty. " +
            "You can then import an export. Check-ins are not affected.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy, onClick = {
            busy = true
            scope.launch {
                try {
                    val folder = access.setAsideAndStartFresh()
                    onChanged("Set aside: the previous planning files are kept on this phone in ${folder.name}. Planning starts empty.")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A set-aside that failed after its commit point is finished by reopening, so reload either way.
                    onChanged("Set aside stopped: ${e.javaClass.simpleName}. Planning data was reopened as it now is; nothing was deleted.")
                } finally {
                    busy = false
                }
            }
        }) { Text("Set aside and start fresh") }
        TextButton(enabled = !busy, onClick = { confirming = false }) { Text("Cancel") }
    }
}

@Composable
private fun PasswordField(
    value: String,
    onValue: (String) -> Unit,
    label: String,
) = OutlinedTextField(
    value,
    onValue,
    label = { Text(label) },
    singleLine = true,
    visualTransformation = PasswordVisualTransformation(),
    // A password keyboard, without suggestions or learning, so the keyboard never keeps it.
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    modifier = Modifier.fillMaxWidth(),
)

private fun summary(counts: PlanningCounts): String =
    listOf(
        "direction" to counts.direction,
        "settings" to counts.settings,
        "goals" to counts.goals,
        "projects" to counts.projects,
        "actions" to counts.actions,
        "inbox items" to counts.inboxItems,
    ).joinToString(", ") { (name, count) ->
        when (name) {
            "direction", "settings" -> if (count == 0) "no $name" else name
            else -> "$count $name"
        }
    }

internal fun describe(problem: ImportProblem): String =
    when (problem) {
        ImportProblem.TOO_LARGE -> "Not imported: the file is larger than any Thalia export (8 MiB)."
        ImportProblem.NOT_AN_EXPORT -> "Not imported: this is not a Thalia planning export, or its start is damaged."
        ImportProblem.NEWER_VERSION -> "Not imported: the export was made by a newer Thalia."
        ImportProblem.UNSUPPORTED_PARAMETERS -> "Not imported: the file's encryption settings are not ones Thalia accepts."
        ImportProblem.WRONG_PASSWORD_OR_DAMAGED -> "Not imported: wrong password or damaged file."
        ImportProblem.INVALID_CONTENT -> "Not imported: the export's contents are not valid."
        ImportProblem.BROKEN_REFERENCE -> "Not imported: rows in the export refer to rows that are not there."
    } + " Nothing was changed."
