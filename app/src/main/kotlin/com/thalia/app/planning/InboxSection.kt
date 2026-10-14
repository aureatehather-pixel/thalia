package com.thalia.app.planning

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.CaptureResult
import com.thalia.app.planning.data.ConvertInboxResult
import com.thalia.app.planning.data.EditBodyResult
import com.thalia.app.planning.data.InboxDeletion
import com.thalia.app.planning.data.ReopenInboxResult
import com.thalia.app.planning.data.ResolveInboxResult
import com.thalia.planning.Action
import com.thalia.planning.InboxConversion
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.InboxText
import com.thalia.planning.PlanningText
import com.thalia.planning.ReminderHint
import com.thalia.planning.Stamped
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** How many open items the Inbox shows before "Show more", and how many the closing offers. ADR 0012. */
internal const val INBOX_PAGE = 5

/**
 * The Inbox as it sits in Life: one row that reads "Inbox · N items" and opens or closes the whole Inbox below it. Collapsed
 * is the default. The count is the open captures, read again after every write. Expanding shows [InboxSection] unchanged, so
 * capture, the open list, filing, filed items and Triage with Thalia are the same controls with the same rules.
 */
@Composable
internal fun InboxCard(
    access: PlanningAccess,
    writes: PlanningWrites,
    changes: Int,
    triage: () -> Unit,
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var open by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(changes) {
        try {
            open = access.withStore { store -> store.inbox().count { it.value.state == InboxState.OPEN } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            open = null
        }
    }
    val header = inboxHeader(open)
    LifeCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(onClickLabel = if (expanded) "Hide the Inbox" else "Show the Inbox") { expanded = !expanded }
                    .semantics {
                        heading()
                        contentDescription = "$header. ${if (expanded) "Expanded" else "Collapsed"}."
                    },
        ) {
            Text(header, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text(if (expanded) "Hide" else "Show", style = MaterialTheme.typography.labelLarge)
        }
        if (expanded) InboxSection(access, writes, changes, showTitle = false, triage = triage)
    }
}

/**
 * The Inbox: a capture box, the open items, and a way back to what was filed. ADR 0012. A capture needs only text. It is
 * not an action and not a commitment, and nothing here puts it in a plan. [triage] opens the conversation, which is
 * optional: every choice on this screen works without it.
 */
@Composable
internal fun InboxSection(
    access: PlanningAccess,
    writes: PlanningWrites,
    changes: Int,
    showTitle: Boolean = true,
    triage: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var attempt by remember { mutableStateOf<Pair<String, String>?>(null) }
    var captured by remember { mutableStateOf<InboxItem?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    if (showTitle) Text("Inbox", style = MaterialTheme.typography.titleMedium)
    Text(
        "Capture anything. It is not an action or a commitment, and it never goes into a plan on its own.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(draft, { draft = it }, label = { Text("Capture") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
    val problem = if (draft.isBlank()) null else InboxText.problem(draft)
    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && draft.isNotBlank() && problem == null, onClick = {
            // A retry of the same text after an unknown failure keeps its id, so it cannot make two items.
            val id = attempt?.takeIf { it.second == draft }?.first ?: UUID.randomUUID().toString()
            attempt = id to draft
            busy = true
            scope.launch {
                try {
                    val item = InboxItem(id, draft)
                    val result =
                        writes.run(
                            { it.captureItem(item) },
                        ) { it == CaptureResult.CAPTURED || it == CaptureResult.ALREADY_CAPTURED }
                    when (result) {
                        CaptureResult.CAPTURED, CaptureResult.ALREADY_CAPTURED -> {
                            captured = item
                            message = "Captured."
                            draft = ""
                            attempt = null
                        }
                        CaptureResult.CONFLICT -> message = "Not captured: that capture id is taken. Save again."
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    message = "Not captured: planning data could not be written. Your text is still here; save again."
                } finally {
                    busy = false
                }
            }
        }) { Text("Save") }
        captured?.let { item ->
            TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try {
                        val removed = writes.run({ it.undoCapture(item.id, item.text) }) { it }
                        message = if (removed) "Capture undone." else "It was already filed or changed, so it stays."
                        captured = null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Could not undo: planning data could not be written."
                    } finally {
                        busy = false
                    }
                }
            }) { Text("Undo") }
        }
    }
    OutlinedButton(onClick = triage) { Text("Triage with Thalia") }
    InboxOpenList(access, writes, changes, limit = null)
    FiledItems(access, writes, changes)
}

/**
 * The open items, oldest first, as rows that open their choices when tapped. [limit] null shows [INBOX_PAGE] and a Show more;
 * a number shows at most that many, which is how the closing offers a small batch.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InboxOpenList(
    access: PlanningAccess,
    writes: PlanningWrites,
    changes: Int,
    limit: Int?,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<Stamped<InboxItem>>?>(null) }
    var open by remember { mutableStateOf<String?>(null) }
    var making by remember { mutableStateOf<String?>(null) }
    var titleDraft by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var undo by remember { mutableStateOf<(suspend () -> String)?>(null) }
    var pages by remember { mutableStateOf(1) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(changes) {
        try {
            items = access.withStore { it.inbox() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "The Inbox could not be read."
        }
    }

    fun act(
        work: suspend () -> String,
        undoWith: (suspend () -> String)? = null,
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                message = work()
                undo = undoWith
            } catch (e: IllegalArgumentException) {
                message = e.message ?: "Not saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Not saved: planning data could not be written."
            } finally {
                busy = false
            }
        }
    }

    val all = items?.map { it.value }?.filter { it.state == InboxState.OPEN }.orEmpty()
    val shown = all.take(limit ?: (INBOX_PAGE * pages))
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    undo?.let { back ->
        TextButton(enabled = !busy, onClick = {
            undo = null
            act({ back() })
        }) { Text("Undo") }
    }
    if (items == null) {
        Text("Loading...")
        return
    }
    if (all.isEmpty()) {
        Text("Nothing waiting. Anything you capture will appear here.", style = MaterialTheme.typography.bodySmall)
        return
    }
    Text(
        if (limit == null) "${all.size} waiting, oldest first" else "${all.size} waiting. You can look at up to $limit now, or leave them.",
        style = MaterialTheme.typography.labelLarge,
    )
    shown.forEach { item ->
        Column(
            modifier = Modifier.fillMaxWidth().clickable { open = if (open == item.id) null else item.id }.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(rowText(item.text), maxLines = if (open == item.id) Int.MAX_VALUE else 2)
            if (open == item.id) {
                if (ReminderHint.mayBeReminder(item.text)) {
                    Text(ReminderHint.TEXT, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
                }
                if (making == item.id) {
                    MakeAction(item, titleDraft, { titleDraft = it }, busy, onCancel = { making = null }) { title ->
                        act({
                            val action = Action(InboxConversion.actionIdFor(item.id), title)
                            when (writes.run({ it.convertToAction(item.id, action) }) { r -> r == ConvertInboxResult.CONVERTED }) {
                                ConvertInboxResult.CONVERTED -> {
                                    making = null
                                    open = null
                                    "Added \"${action.title}\" (${action.priority.name.lowercase()}, " +
                                        "${action.estimateMinutes} min, no project). " +
                                        "It is not in today's plan; change its details under Actions."
                                }
                                ConvertInboxResult.ALREADY_CONVERTED -> "That item was already turned into this action."
                                ConvertInboxResult.DUPLICATE_TITLE -> "Not added: an open action called \"${action.title}\" already exists."
                                ConvertInboxResult.CONFLICT -> "Not added: that item already became a different action."
                                else -> "Not added: that item is no longer in the Inbox."
                            }
                        })
                    }
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(enabled = !busy, onClick = {
                            titleDraft = item.text.takeIf { it.length <= PlanningText.TITLE_MAX && '\n' !in it } ?: ""
                            making = item.id
                        }) { Text("Make an action") }
                        listOf(
                            InboxState.NOTE to "Note",
                            InboxState.IDEA to "Idea",
                            InboxState.ARCHIVED to "Archive",
                            InboxState.DISCARDED to "Discard",
                        ).forEach { (to, label) ->
                            OutlinedButton(enabled = !busy, onClick = {
                                act({
                                    when (writes.run({ it.resolveItem(item.id, to) }) { r -> r == ResolveInboxResult.RESOLVED }) {
                                        ResolveInboxResult.RESOLVED -> {
                                            open = null
                                            filedMessage(to)
                                        }
                                        ResolveInboxResult.ALREADY_RESOLVED -> "That item was already filed that way."
                                        else -> "That item is no longer in the Inbox."
                                    }
                                }, {
                                    undoText(
                                        writes.run({ it.reopenItem(item.id, to) }) { r -> r == ReopenInboxResult.REOPENED } ==
                                            ReopenInboxResult.REOPENED,
                                    )
                                })
                            }) { Text(label) }
                        }
                    }
                }
            }
        }
    }
    if (limit == null && all.size > shown.size) {
        TextButton(onClick = { pages++ }) { Text("Show ${minOf(INBOX_PAGE, all.size - shown.size)} more") }
    }
}

@Composable
private fun MakeAction(
    item: InboxItem,
    title: String,
    onTitle: (String) -> Unit,
    busy: Boolean,
    onCancel: () -> Unit,
    onAdd: (String) -> Unit,
) {
    Text("New action from this item", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(
        title,
        onTitle,
        label = { Text("Action title") },
        supportingText = {
            if (item.text.length > PlanningText.TITLE_MAX || '\n' in item.text) {
                Text("The capture is too long or too many lines for a title. Write the action in your own words; the capture is kept.")
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Review before adding", style = MaterialTheme.typography.labelMedium)
    Text("Title: ${PlanningText.clean(title).ifEmpty { "(none yet)" }} (yours)", style = MaterialTheme.typography.bodySmall)
    Text("Priority: normal (default)", style = MaterialTheme.typography.bodySmall)
    Text("Estimate: ${Action.DEFAULT_ESTIMATE_MINUTES} min (default)", style = MaterialTheme.typography.bodySmall)
    Text("Project: none (default). Due: none (default).", style = MaterialTheme.typography.bodySmall)
    Text(
        "It leaves the Inbox and is not placed in any plan. Change the defaults under Actions.",
        style = MaterialTheme.typography.bodySmall,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = !busy && title.isNotBlank(), onClick = { onAdd(PlanningText.clean(title)) }) { Text("Add action") }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

/** Filed items, kept and retrievable: notes and ideas (editable), archives and discards (can be put back). Collapsed by default. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FiledItems(
    access: PlanningAccess,
    writes: PlanningWrites,
    changes: Int,
) {
    val scope = rememberCoroutineScope()
    var items by remember { mutableStateOf<List<InboxItem>>(emptyList()) }
    var expanded by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(InboxState.NOTE) }
    var open by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<String?>(null) }
    var edit by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(changes) {
        try {
            items = access.withStore { store -> store.inbox().map { it.value }.filter { it.state.canReopen } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Filed items could not be read."
        }
    }
    val kinds =
        listOf(
            InboxState.NOTE to "Notes",
            InboxState.IDEA to "Ideas",
            InboxState.ARCHIVED to "Archived",
            InboxState.DISCARDED to "Discarded",
        )
    TextButton(onClick = { expanded = !expanded }) { Text("${if (expanded) "Hide" else "Show"} filed items (${items.size})") }
    if (!expanded) return
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        kinds.forEach { (state, label) ->
            val count = items.count { it.state == state }
            if (filter == state) {
                Button(onClick = {}) { Text("$label $count") }
            } else {
                OutlinedButton(onClick = {
                    filter = state
                    open = null
                }) { Text("$label $count") }
            }
        }
    }
    val shown = items.filter { it.state == filter }.reversed()
    if (shown.isEmpty()) Text("None.", style = MaterialTheme.typography.bodySmall)
    shown.forEach { item ->
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        open = if (open == item.id) null else item.id
                        edit = item.body.orEmpty()
                    }.padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(rowText(item.body ?: item.text), maxLines = if (open == item.id) Int.MAX_VALUE else 2)
            if (open == item.id) {
                if (item.state.hasBody) {
                    OutlinedTextField(edit, { edit = it }, label = { Text("Edit") }, modifier = Modifier.fillMaxWidth())
                    Text("Captured as: ${item.text}", style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy && edit.isNotBlank() && edit != item.body, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val result = writes.run({ it.editBody(item.id, item.body, edit) }) { it == EditBodyResult.EDITED }
                                message =
                                    when (result) {
                                        EditBodyResult.EDITED -> "Saved."
                                        EditBodyResult.STALE -> "Not saved: it changed since you opened it. Open it again."
                                        else -> "Not saved: that item can no longer be edited."
                                    }
                            } catch (e: IllegalArgumentException) {
                                message = e.message ?: "Not saved."
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                message = "Not saved: planning data could not be written."
                            } finally {
                                busy = false
                            }
                        }
                    }) { Text("Save changes") }
                } else {
                    Text("Captured as: ${item.text}", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            val result = writes.run({ it.reopenItem(item.id, item.state) }) { it == ReopenInboxResult.REOPENED }
                            message = if (result == ReopenInboxResult.REOPENED) "Back in the Inbox." else "It changed since you opened it."
                            open = null
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            message = "Not saved: planning data could not be written."
                        } finally {
                            busy = false
                        }
                    }
                }) { Text("Put back in Inbox") }
                OutlinedButton(enabled = !busy, onClick = { deleting = item.id }) { Text("Delete permanently") }
                if (deleting == item.id) {
                    ConfirmDelete(
                        "Delete this for good?",
                        PrivacyText.deleteItemBody(!item.state.hasBody),
                        onConfirm = {
                            deleting = null
                            busy = true
                            scope.launch {
                                try {
                                    val result = writes.run({ it.deleteFiledItem(item.id) }) { it is InboxDeletion.Deleted }
                                    message = PrivacyText.itemDeletion(result)
                                    open = null
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    message = "Not deleted: planning data could not be written."
                                } finally {
                                    busy = false
                                }
                            }
                        },
                        onDismiss = { deleting = null },
                    )
                }
            }
        }
    }
}

private fun rowText(text: String) = text.trim()

private fun filedMessage(to: InboxState) =
    when (to) {
        InboxState.NOTE -> "Saved as a note."
        InboxState.IDEA -> "Kept as an idea."
        InboxState.ARCHIVED -> "Archived. It is kept under filed items."
        InboxState.DISCARDED -> "Discarded. It is kept under filed items, Discarded, in case you want it back."
        else -> "Filed."
    }

private fun undoText(reopened: Boolean) = if (reopened) "Back in the Inbox." else "It changed since, so it was left as it is."
