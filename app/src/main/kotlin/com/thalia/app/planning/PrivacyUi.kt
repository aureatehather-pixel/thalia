package com.thalia.app.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.GoalDeletion
import com.thalia.app.planning.data.InboxDeletion
import com.thalia.app.planning.data.NoteChange
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanningText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/** The words of the privacy controls, apart from the screens so they can be tested. Deletion here is permanent, and says so. */
internal object PrivacyText {
    const val DIRECTION_HINT =
        "Nothing here yet. Write, in your own words, the direction you want your days to serve. " +
            "Thalia keeps only what you type, and you can change or clear it at any time."
    const val GOALS_HINT =
        "No goals yet. Add one below: a title and what success looks like. Goals are optional; a project can stand without one."
    const val PROJECTS_HINT =
        "No projects yet. A project is a body of work you name yourself. Add one below, then add its actions. " +
            "Actions can also stand alone."

    const val CLEAR_DIRECTION_TITLE = "Clear your direction?"
    const val CLEAR_DIRECTION_BODY =
        "The direction statement is erased for good and cannot be brought back. Goals, projects, actions and plans are not touched."

    fun deleteGoalBody(title: String) =
        "Delete the goal \"$title\" for good? It cannot be brought back. If a project is still linked to it, nothing is deleted " +
            "and you are told which one. To keep the goal without deleting it, set its status to achieved or dropped instead."

    fun deleteItemBody(archived: Boolean) =
        "Delete this ${if (archived) "filed item" else "note or idea"} for good, including the text it was captured as? " +
            "It cannot be brought back. Actions, plans and reminders are not deleted. " +
            "To keep it without deleting, put it back in the Inbox or leave it archived."

    fun deleteNoteBody(day: ActiveDay) =
        "Delete the closing note for ${dayText(day)} for good? Only the note text is erased; the day, its plan, " +
            "outcomes and focus figure are kept. It cannot be brought back."

    fun goalDeletion(result: GoalDeletion): String =
        when (result) {
            GoalDeletion.Deleted -> "Goal deleted."
            GoalDeletion.NotFound -> "That goal is already gone."
            is GoalDeletion.Blocked ->
                "Not deleted: this goal is still linked to ${result.projectTitles.size} " +
                    "${if (result.projectTitles.size == 1) "project" else "projects"}: ${result.projectTitles.joinToString(", ")}. " +
                    "Nothing was changed. Open each of those projects and choose another goal or none, then delete the goal."
        }

    fun itemDeletion(result: InboxDeletion): String =
        when (result) {
            is InboxDeletion.Deleted ->
                "Deleted." +
                    if (result.remindersKept > 0) {
                        " ${result.remindersKept} reminder${if (result.remindersKept == 1) "" else "s"} for it " +
                            "${if (result.remindersKept == 1) "keeps" else "keep"} their own text."
                    } else {
                        ""
                    }
            InboxDeletion.NotFound -> "That item is already gone."
            InboxDeletion.StillOpen -> "Not deleted: it is still in the Inbox. File it first, for example as discarded."
            InboxDeletion.BecameAction ->
                "Not deleted: this capture became an action, and the capture is kept as where that action came from. The action is untouched."
        }

    fun noteChange(
        result: NoteChange,
        verb: String,
    ): String =
        when (result) {
            NoteChange.DONE -> if (verb == "edit") "Note saved." else "Note deleted."
            NoteChange.STALE -> "Not changed: the note changed since you opened it. It is shown again as it is now."
            NoteChange.NOT_FOUND -> "That note is already gone."
        }

    fun dayText(day: ActiveDay): String = DAY.format(day.date)

    private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
}

/** The one confirmation every permanent deletion goes through. Dismissing it changes nothing. */
@Composable
internal fun ConfirmDelete(
    title: String,
    body: String,
    confirmLabel: String = "Delete permanently",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(body) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(confirmLabel, color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("Keep it") } },
    )
}

/**
 * Closing notes, newest day first: read, edit, delete. They are the notes the person wrote when closing a day; this is the only
 * place older ones can be seen. Deleting erases the text and keeps the day.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ClosingNotesSection(
    access: PlanningAccess,
    onChanged: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var notes by remember { mutableStateOf<List<Pair<ActiveDay, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var draft by rememberSaveable { mutableStateOf("") }
    var deleting by remember { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        try {
            notes = access.withStore { it.closingNotes() }
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    fun change(
        day: ActiveDay,
        expected: String,
        verb: String,
        write: suspend (com.thalia.app.planning.data.PlanningStore) -> NoteChange,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val result = access.withStore(write)
                message = PrivacyText.noteChange(result, verb)
                if (result == NoteChange.DONE) onChanged()
                if (result != NoteChange.NOT_FOUND || verb == "delete") editing = null
                reload++
            } catch (e: IllegalArgumentException) {
                message = e.message ?: "Not saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Not changed: planning data could not be written."
            } finally {
                busy = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Closing notes", style = MaterialTheme.typography.titleSmall)
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        val list = notes
        when {
            failed -> Text("Closing notes could not be read. Nothing was changed.", color = MaterialTheme.colorScheme.error)
            list == null -> Text("Loading...")
            list.isEmpty() ->
                Text(
                    "No closing notes yet. A note you write when closing a day appears here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            else -> {
                list.take(shown).forEach { (day, note) ->
                    val key = day.toString()
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(PrivacyText.dayText(day), style = MaterialTheme.typography.labelLarge)
                        if (editing == key) {
                            OutlinedTextField(
                                draft,
                                { draft = it },
                                label = { Text("Closing note") },
                                supportingText = { Text("${draft.trim().length} of ${PlanningText.CONTEXT_MAX}") },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    enabled = !busy && draft.isNotBlank() && PlanningText.clean(draft) != note,
                                    onClick = { change(day, note, "edit") { it.editClosingNote(day, note, PlanningText.clean(draft)) } },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Save note") }
                                OutlinedButton(onClick = { editing = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
                            }
                        } else {
                            Text(note)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = {
                                        editing = key
                                        draft = note
                                    },
                                    modifier = Modifier.heightIn(min = 48.dp),
                                ) { Text("Edit") }
                                OutlinedButton(onClick = { deleting = key }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Delete") }
                            }
                        }
                    }
                    if (deleting == key) {
                        ConfirmDelete(
                            "Delete this closing note?",
                            PrivacyText.deleteNoteBody(day),
                            onConfirm = {
                                deleting = null
                                change(day, note, "delete") { it.deleteClosingNote(day, note) }
                            },
                            onDismiss = { deleting = null },
                        )
                    }
                }
                if (list.size > shown) {
                    TextButton(onClick = { shown += PAGE }) { Text("Show ${minOf(PAGE, list.size - shown)} more") }
                }
            }
        }
    }
}

private const val PAGE = 10
