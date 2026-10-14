package com.thalia.app.planning

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.Priority
import com.thalia.planning.Project
import java.time.LocalDate

/** The four parts of Life. The key is what is kept across a configuration change. */
internal enum class LifeSegment(
    val key: String,
    val label: String,
) {
    DIRECTION("direction", "Direction"),
    GOALS("goals", "Goals"),
    PROJECTS("projects", "Projects"),
    ACTIONS("actions", "Actions"),
    ;

    companion object {
        fun of(key: String?): LifeSegment = entries.firstOrNull { it.key == key } ?: PROJECTS
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LifeSegments(
    selected: LifeSegment,
    onSelect: (LifeSegment) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LifeSegment.entries.forEach { segment ->
            FilterChip(
                selected = segment == selected,
                onClick = { onSelect(segment) },
                label = { Text(segment.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
}

@Composable
internal fun LifeSearch(
    query: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        query,
        onChange,
        label = { Text("Search") },
        placeholder = { Text("Actions and projects") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
internal fun LifeCard(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
internal fun ProjectCards(
    cards: List<ProjectCard>,
    emptyText: String = "No projects match.",
    onOpen: (ProjectCard) -> Unit,
) {
    if (cards.isEmpty()) {
        Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    cards.forEach { card ->
        Surface(
            onClick = { onOpen(card) },
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(16.dp),
            modifier =
                Modifier.fillMaxWidth().semantics {
                    contentDescription =
                        "${card.title}, ${card.stateText}, ${card.summary}. Show its actions."
                },
        ) {
            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(card.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f, fill = false))
                    Text(card.stateText, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(card.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionList(
    groups: List<ActionGroup>,
    totalActions: Int,
    status: StatusFilter,
    onStatus: (StatusFilter) -> Unit,
    projectFilterText: String?,
    onClearProject: () -> Unit,
    today: LocalDate,
    onAdd: () -> Unit,
    onOpen: (Action) -> Unit,
) {
    if (totalActions == 0) {
        LifeCard {
            Text("No actions yet", style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            Text(
                "Add one finite thing you want to do. It can sit in a project or stand alone, and you can group it later.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add an action") }
        }
        return
    }
    Button(onClick = onAdd, modifier = Modifier.heightIn(min = 48.dp)) { Text("Add an action") }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusFilter.entries.forEach { f ->
            FilterChip(
                selected = f == status,
                onClick = { onStatus(f) },
                label = { Text(f.label) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    projectFilterText?.let {
        OutlinedButton(onClick = onClearProject, modifier = Modifier.heightIn(min = 48.dp)) { Text("Project: $it (clear)") }
    }
    if (groups.isEmpty()) {
        Text(
            "No actions match. Try another word, or add it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    groups.forEach { group ->
        LifeCard {
            Text(
                "${group.name} · ${group.actions.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
            group.actions.forEach { action -> ActionRow(action, today, onOpen) }
        }
    }
}

@Composable
private fun ActionRow(
    action: Action,
    today: LocalDate,
    onOpen: (Action) -> Unit,
) {
    Surface(
        onClick = { onOpen(action) },
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Edit ${action.title}" },
    ) {
        Column(modifier = Modifier.padding(10.dp).heightIn(min = 48.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(action.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                Text(
                    action.status.name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "${action.priority.name.lowercase().replaceFirstChar { it.uppercase() }} · ${action.estimateMinutes} min" +
                    (DueText.line(action, today)?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The action editor: title, priority, estimate, project, due and (for an existing action) status, as the store already
 * validates them. Nothing is saved until Save; the store's own checks and refusals report in the message above. The draft
 * survives a configuration change and is dropped when the editor is closed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionEditor(
    current: Action?,
    projects: List<Project>,
    save: Save<Pair<Action, ActionStatus?>>,
    presetProjectId: String?,
    today: LocalDate,
    onClose: () -> Unit,
) {
    val key = current?.id ?: "new"
    var title by rememberSaveable(key) { mutableStateOf(current?.title ?: "") }
    var priority by rememberSaveable(key) { mutableStateOf((current?.priority ?: Priority.NORMAL).name) }
    var estimate by rememberSaveable(key) { mutableStateOf((current?.estimateMinutes ?: Action.DEFAULT_ESTIMATE_MINUTES).toString()) }
    var due by rememberSaveable(key) { mutableStateOf(current?.dueDate?.toString() ?: "") }
    var status by rememberSaveable(key) { mutableStateOf((current?.status ?: ActionStatus.OPEN).name) }
    var projectId by rememberSaveable(key) { mutableStateOf(current?.projectId ?: presetProjectId.orEmpty()) }
    BackHandler(onBack = onClose)

    Row(
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            if (current == null) "New action" else "Edit action",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
    }
    OutlinedTextField(
        title,
        { title = it },
        label = { Text("Title") },
        placeholder = { Text("What is the one finite thing?") },
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Priority", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Priority.entries.forEach { p ->
            FilterChip(
                selected = priority == p.name,
                onClick = { priority = p.name },
                label = { Text(p.name.lowercase().replaceFirstChar { it.uppercase() }) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    Text("Estimate", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedButton(
            onClick = { estimate = stepEstimate(estimate, -ESTIMATE_STEP) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Five minutes less" },
        ) { Text("-") }
        OutlinedTextField(estimate, { estimate = it }, label = { Text("Minutes") }, singleLine = true, modifier = Modifier.weight(1f))
        OutlinedButton(
            onClick = { estimate = stepEstimate(estimate, ESTIMATE_STEP) },
            modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Five minutes more" },
        ) { Text("+") }
    }
    Text("Project", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = projectId.isEmpty(),
            onClick = { projectId = "" },
            label = { Text("Standalone") },
            modifier = Modifier.heightIn(min = 48.dp),
        )
        projects.forEach { p ->
            FilterChip(
                selected = projectId == p.id,
                onClick = { projectId = p.id },
                label = { Text(p.title) },
                modifier = Modifier.heightIn(min = 48.dp),
            )
        }
    }
    OutlinedTextField(
        due,
        { due = it },
        label = { Text("Due, optional (yyyy-mm-dd)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    // The cue reads the date as typed right now, for an action that is still open; it changes nothing until Save.
    val typed = runCatching { date(due) }.getOrNull()
    val late = DueText.overdue(typed, ActionStatus.valueOf(status), today)
    val soon = DueText.dueSoon(typed, ActionStatus.valueOf(status), today)
    late?.let { Text(it, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error) }
    soon?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
    var picking by rememberSaveable(key) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { picking = true }, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (typed == null) "Pick a date" else "Change date")
        }
        if (due.isNotBlank()) {
            OutlinedButton(onClick = { due = "" }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear date") }
        }
    }
    if (picking) DueDatePicker(typed ?: today, onPick = { due = it.toString() }, onDismiss = { picking = false })
    if (current != null) {
        Text("Status", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ActionStatus.entries.forEach { s ->
                FilterChip(
                    selected = status == s.name,
                    onClick = { status = s.name },
                    label = { Text(s.name.lowercase().replaceFirstChar { it.uppercase() }) },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    Button(
        enabled = title.isNotBlank(),
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        onClick = {
            val id = current?.id ?: newId()
            // The status the editor was opened with travels along, so a status changed meanwhile is not silently reversed.
            save({
                Action(
                    id,
                    com.thalia.planning.PlanningText
                        .clean(title),
                    projectId.ifEmpty { null },
                    Priority.valueOf(priority),
                    minutes(estimate),
                    date(due),
                    ActionStatus.valueOf(status),
                ) to current?.status
            }) { onClose() }
        },
    ) { Text(if (current == null) "Add action" else "Save changes") }
}

/** The system-style calendar for a due date. Choosing fills the Due field; nothing is saved until Save changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DueDatePicker(
    initial: LocalDate,
    onPick: (LocalDate) -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberDatePickerState(initialSelectedDateMillis = DueText.toPickerMillis(initial))
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = state.selectedDateMillis != null,
                onClick = {
                    state.selectedDateMillis?.let { onPick(DueText.fromPickerMillis(it)) }
                    onDismiss()
                },
            ) { Text("Use this date") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    ) { DatePicker(state = state) }
}

private const val ESTIMATE_STEP = 5

/** The estimate moved by [delta], kept inside the store's own range; text that is not a number starts from the default. */
internal fun stepEstimate(
    text: String,
    delta: Int,
): String {
    val base = text.trim().toIntOrNull() ?: Action.DEFAULT_ESTIMATE_MINUTES
    return (base + delta).coerceIn(Action.ESTIMATE_RANGE).toString()
}
