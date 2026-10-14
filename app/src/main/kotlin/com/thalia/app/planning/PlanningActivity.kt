package com.thalia.app.planning

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.thalia.app.ThaliaApplication
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.planning.data.GoalDeletion
import com.thalia.app.planning.data.PlanningOpenProblem
import com.thalia.app.planning.data.PlanningRefusal
import com.thalia.app.reminders.AndroidReminderNotifier
import com.thalia.app.ui.SettingsActivity
import com.thalia.app.ui.ThaliaShell
import com.thalia.app.ui.ThaliaTab
import com.thalia.app.ui.ThaliaTheme
import com.thalia.app.ui.ThaliaTopBar
import com.thalia.app.ui.goToTab
import com.thalia.conversation.ContextLimits
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.GoalStatus
import com.thalia.planning.InboxState
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningText
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.Reminder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * The host of the Today, Plan and Life tabs, which is where Thalia now starts (the launcher entry hands over to it).
 *
 * It holds the planning data's one set of states (the open store, the write path, the change counter), so the three tabs are
 * one screen of three views: nothing is loaded or saved differently from before, only arranged by tab. Thalia, the fourth tab,
 * is the conversation, a separate screen. The sections themselves are the existing ones, unchanged: ADR 0009 slices 1 to 3, the
 * working day (0011), the Inbox (0012) and the reminders (0014). What the approved V2 design adds and is not built yet is named
 * on a "Not built yet" card at the top of each tab, never drawn with sample content.
 */
class PlanningActivity : ComponentActivity() {
    /** A tab asked for by a new intent while this screen is already open, for example from the conversation's tab bar. */
    private val requestedTab = mutableStateOf<ThaliaTab?>(null)

    private fun conversation(draft: String) =
        Intent(this, ConversationActivity::class.java).putExtra(ConversationActivity.EXTRA_DRAFT, draft)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ThaliaTab.from(intent)?.takeIf { it != ThaliaTab.THALIA }?.let { requestedTab.value = it }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        val access = app.planningAccess
        // Opened from a reminder notification: the tab with the reminders and the plan, and the id is read once. Nothing is changed by arriving.
        val fromReminderId = intent.getStringExtra(AndroidReminderNotifier.EXTRA_REMINDER_ID)
        val startTab =
            ThaliaTab.from(intent)?.takeIf { it != ThaliaTab.THALIA }
                ?: if (fromReminderId != null) ThaliaTab.PLAN else ThaliaTab.TODAY
        setContent {
            ThaliaTheme {
                var state by remember { mutableStateOf<PlanningAccess.State?>(null) }
                var opened by remember { mutableIntStateOf(0) }
                var notice by remember { mutableStateOf<String?>(null) }
                var tabName by rememberSaveable { mutableStateOf(startTab.name) }
                var planBar by remember { mutableStateOf<PlanBarModel?>(null) }
                val tab = ThaliaTab.of(tabName) ?: ThaliaTab.TODAY
                val focus = LocalFocusManager.current
                val requested = requestedTab.value
                LaunchedEffect(requested) {
                    requested?.let {
                        tabName = it.name
                        requestedTab.value = null
                    }
                }
                LaunchedEffect(opened) { state = access.state() }
                val talk =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                        // The conversation may have written: read again, keeping what is on screen (a half-typed form) until it arrives.
                        notice = null
                        opened += 1
                    }
                // Thalia stays open behind the tabs, so coming back to this screen is also the moment to read the data again.
                var seenFirstResume by remember { mutableStateOf(false) }
                LifecycleResumeEffect(Unit) {
                    if (seenFirstResume) opened += 1 else seenFirstResume = true
                    onPauseOrDispose {}
                }
                val settings =
                    rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
                        // Settings reports RESULT_OK only when an export or import ran, which may have replaced the data: start
                        // clean. Otherwise read again but keep what is on screen (a half-typed form, a pending proposal).
                        if (result.resultCode == RESULT_OK) state = null
                        opened += 1
                    }
                val reopen: (String) -> Unit = { what ->
                    notice = what
                    state = null
                    opened += 1
                }
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    ThaliaShell(
                        selected = tab,
                        onSelect = { picked ->
                            focus.clearFocus()
                            if (picked == ThaliaTab.THALIA) {
                                goToTab(ThaliaTab.THALIA)
                            } else {
                                tabName = picked.name
                            }
                        },
                    ) {
                        Column(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                            ThaliaTopBar(
                                title = tab.label,
                                subtitle = null,
                                onSettings = { settings.launch(Intent(this@PlanningActivity, SettingsActivity::class.java)) },
                            )
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (val current = state) {
                                    null -> TabScroll(rememberScrollState()) { Text("Opening planning data...") }
                                    is PlanningAccess.State.Unavailable ->
                                        TabScroll(rememberScrollState()) {
                                            Unavailable(current.problem)
                                            SetAsideSection(access, current.problem, reopen)
                                        }
                                    is PlanningAccess.State.Ready ->
                                        ReadyTabs(
                                            tab = tab,
                                            app = app,
                                            notice = notice,
                                            fromReminderId = fromReminderId,
                                            talk = talk,
                                            conversation = ::conversation,
                                            onPlanTab = { tabName = ThaliaTab.PLAN.name },
                                            onPlanBar = { planBar = it },
                                        )
                                }
                            }
                            // Fixed above the tab bar, on the Plan tab only, while a proposal waits or a plan was just saved.
                            if (tab == ThaliaTab.PLAN) PlanBarSlot(planBar)
                        }
                    }
                }
            }
        }
    }
}

/** One tab's scrolling column. */
@Composable
private fun TabScroll(
    scroll: ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * Keeps a tab composed when it is not the one shown, so a half-typed form and its scroll position survive a look at another tab.
 * A hidden tab is measured but not placed, so it is not drawn, cannot be touched and is not read out by a screen reader.
 */
@Composable
private fun KeepAlive(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .layout { measurable, constraints ->
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { if (visible) placeable.placeRelative(0, 0) }
                }.then(if (visible) Modifier else Modifier.clearAndSetSemantics {}),
    ) { content() }
}

@Composable
private fun ReadyTabs(
    tab: ThaliaTab,
    app: ThaliaApplication,
    notice: String?,
    fromReminderId: String?,
    talk: ManagedActivityResultLauncher<Intent, ActivityResult>,
    conversation: (String) -> Intent,
    onPlanTab: () -> Unit,
    onPlanBar: (PlanBarModel?) -> Unit,
) {
    val access = app.planningAccess
    val scope = rememberCoroutineScope()
    // One signal for the whole screen: any successful write reloads every section on every tab.
    var changes by remember { mutableIntStateOf(0) }
    val writes = remember(access) { PlanningWrites(access) { changes++ } }
    var fromReminder by remember { mutableStateOf<Reminder?>(null) }
    LaunchedEffect(fromReminderId) {
        fromReminderId?.let { id ->
            fromReminder = runCatching { access.withStore { it.reminder(id) } }.getOrNull()
        }
    }
    var todayCardAsking by remember { mutableStateOf(false) }
    val todayScroll = rememberScrollState()
    var focusId by rememberSaveable { mutableStateOf<String?>(null) }
    val planScroll = rememberScrollState()
    val lifeScroll = rememberScrollState()
    var planTop by remember { mutableIntStateOf(-1) }
    var scrolledToPlan by remember { mutableStateOf(false) }
    // A checkpoint opens at the current day's plan. Arriving opens and closes no day.
    LaunchedEffect(fromReminder, planTop) {
        if (!scrolledToPlan && fromReminder?.isCheckpoint == true && planTop >= 0) {
            scrolledToPlan = true
            onPlanTab()
            planScroll.animateScrollTo(planTop)
        }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        KeepAlive(tab == ThaliaTab.TODAY) {
            TabScroll(todayScroll) {
                NoticeLine(notice)
                TodaySection(access, app.checkInZone, writes, changes, onPlanTab, focusId, { focusId = it }) { todayCardAsking = it }
                // The focus screen stands alone: the working day and the closing button are for the day view.
                if (focusId == null) {
                    HorizontalDivider()
                    WorkingDaySection(access, app.checkInZone, writes, changes, PromptRule.mayAskQuestion(todayCardAsking))
                    HorizontalDivider()
                    // ADR 0013: this only fills the message box. Nothing is sent until Send, and it never opens or closes the day.
                    OutlinedButton(onClick = { talk.launch(conversation(ConversationActivity.CLOSING_DRAFT)) }) {
                        Text("Close the day with Thalia")
                    }
                }
            }
        }
        KeepAlive(tab == ThaliaTab.PLAN) {
            TabScroll(planScroll) {
                NoticeLine(notice)
                fromReminder?.let {
                    Text(
                        if (it.isCheckpoint) {
                            "Checkpoint. Today's plan is shown here. Nothing was opened or closed."
                        } else {
                            "Opened from a reminder. Its Acknowledge and Dismiss are under Reminders."
                        },
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(onClick = { talk.launch(conversation(ConversationActivity.OPENING_DRAFT)) }) {
                    Text("Plan the day with Thalia")
                }
                Column(
                    modifier = Modifier.onGloballyPositioned { planTop = it.positionInParent().y.toInt() },
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    PlanDaySection(access, app.checkInZone, app.openingCallLink, writes, changes, showOutcomes = false, onBar = onPlanBar)
                }
                HorizontalDivider()
                CommitmentsSection(access, app.checkInZone, writes, changes)
                HorizontalDivider()
                RemindersSection(access, app.reminderService, changes)
            }
        }
        KeepAlive(tab == ThaliaTab.LIFE) {
            TabScroll(lifeScroll) {
                NoticeLine(notice)
                PlanningEditor(access, app.checkInZone, writes, changes) {
                    InboxCard(access, writes, changes) {
                        // The captures this triage may share are chosen here, by this press: the oldest open few.
                        scope.triage(access, talk, conversation)
                    }
                }
            }
        }
    }
}

@Composable
private fun NoticeLine(notice: String?) {
    notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

/** Starts an Inbox triage: the open captures it may share are chosen by this press, oldest first, and only the first few. */
private fun CoroutineScope.triage(
    access: PlanningAccess,
    talk: ManagedActivityResultLauncher<Intent, ActivityResult>,
    conversation: (String) -> Intent,
) {
    launch {
        val chosen =
            access.withStore { store ->
                store
                    .inbox()
                    .filter { it.value.state == InboxState.OPEN }
                    .sortedWith(compareBy({ it.createdAtMillis }, { it.value.id }))
                    .take(ContextLimits.TRIAGE)
                    .map { it.value.id }
            }
        talk.launch(
            conversation("Let's go through these Inbox captures, one at a time.")
                .putExtra(ConversationActivity.EXTRA_TRIAGE_IDS, chosen.toTypedArray()),
        )
    }
}

@Composable
private fun Unavailable(problem: PlanningOpenProblem) {
    val reason =
        when (problem) {
            PlanningOpenProblem.KEY_UNAVAILABLE -> "Planning data cannot be opened: the key on this phone is not available."
            PlanningOpenProblem.DATABASE_UNREADABLE -> "Planning data cannot be opened: the file is unreadable."
            PlanningOpenProblem.MIGRATION_FAILED -> "Planning data needs an update that failed."
            PlanningOpenProblem.DATABASE_MISSING -> "Planning data is missing."
            PlanningOpenProblem.STORAGE_UNAVAILABLE -> "Planning storage could not be prepared on this phone."
        }
    Text(reason, color = MaterialTheme.colorScheme.error)
    Text("Nothing was deleted or changed. Check-ins are not affected.")
}

/** Everything the editor shows, reloaded after each save. */
private data class Snapshot(
    val direction: Direction?,
    val goals: List<Goal>,
    val projects: List<Project>,
    val actions: List<Action>,
    val settings: PlanningSettings,
)

/**
 * A save as the screen asks for it: [build] makes the value, which may refuse invalid input, and
 * [onSaved] runs only if the save succeeded.
 */
internal typealias Save<T> = (build: () -> T, onSaved: () -> Unit) -> Unit

@Composable
private fun PlanningEditor(
    access: PlanningAccess,
    zoneSource: CheckInZoneSource,
    writes: PlanningWrites,
    changes: Int,
    inbox: @Composable () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<Snapshot?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    var saving by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    // Bumped when a save is refused because the stored copy changed elsewhere, so the lists show the newer copy.
    var reloads by remember { mutableIntStateOf(0) }
    // What Life shows, kept across a configuration change. editId is "new", an action's id, or none.
    var segment by rememberSaveable { mutableStateOf(LifeSegment.PROJECTS.key) }
    var query by rememberSaveable { mutableStateOf("") }
    var statusKey by rememberSaveable { mutableStateOf(StatusFilter.ALL.key) }
    var projectFilter by rememberSaveable { mutableStateOf(ProjectFilter.NONE) }
    var editId by rememberSaveable { mutableStateOf<String?>(null) }
    // Overdue cues compare with today's date in the check-in zone; with no usable zone the phone's own zone is the fallback.
    var zone by remember { mutableStateOf(ZoneId.systemDefault()) }
    LaunchedEffect(changes) {
        (runCatching { zoneSource.read() }.getOrNull() as? CheckInZone.Known)?.let { zone = it.zone }
    }

    LaunchedEffect(changes, reloads) {
        try {
            snapshot = access.withStore { Snapshot(it.direction(), it.goals(), it.projects(), it.actions(), it.settings()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            loadFailed = true
        }
    }

    /**
     * Builds and saves in one place, so invalid input, a refusal or a storage error becomes a message,
     * never a crash. A second tap while a save is running is ignored, so it cannot save twice.
     */
    fun <T> saver(write: suspend (T) -> PlanningRefusal?): Save<T> =
        { build, onSaved ->
            if (!saving) {
                saving = true
                scope.launch {
                    try {
                        val refusal = write(build())
                        message = refusal?.let(::describe) ?: "Saved."
                        if (refusal == null) onSaved()
                        if (refusal == PlanningRefusal.ACTION_CHANGED || refusal == PlanningRefusal.PROJECT_CHANGED) reloads++
                    } catch (e: IllegalArgumentException) {
                        message = e.message ?: "Not saved."
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        message = "Not saved: planning data could not be written."
                    } finally {
                        saving = false
                    }
                }
            }
        }

    /** Runs a direct, confirmed deletion: one at a time, any failure becomes a message and changes nothing. */
    fun guarded(block: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Not deleted: planning data could not be written."
            } finally {
                saving = false
            }
        }
    }

    if (loadFailed) return Text("Planning data could not be read. Nothing was changed.", color = MaterialTheme.colorScheme.error)
    val data = snapshot ?: return Text("Loading...")
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

    val saveAction = saver { (action, expected): Pair<Action, ActionStatus?> -> writes.saveAction(action, expected) }
    val editing = editId
    if (editing != null) {
        val current = if (editing == "new") null else data.actions.firstOrNull { it.id == editing }
        if (editing == "new" || current != null) {
            ActionEditor(
                current,
                data.projects,
                saveAction,
                presetProjectId = projectFilter.takeIf { it != ProjectFilter.NONE && it != ProjectFilter.STANDALONE },
                today = DueText.today(PlanningClock.now(), zone),
            ) { editId = null }
            return
        }
        // The action is gone (for example restored or replaced): leave the editor.
        editId = null
    }

    inbox()
    val chosen = LifeSegment.of(segment)
    LifeSegments(chosen) { segment = it.key }
    when (chosen) {
        LifeSegment.DIRECTION -> {
            DirectionSection(
                data.direction,
                saver { direction: Direction -> writes.run({ it.saveDirection(direction) }) { true }.let { null } },
                onClear = {
                    guarded {
                        message =
                            if (writes.run({ it.clearDirection() }) { had -> had }) "Direction cleared." else "There was no direction."
                    }
                },
            )
            HorizontalDivider()
            SettingsSection(
                data.settings,
                saver { settings: PlanningSettings -> writes.run({ it.saveSettings(settings) }) { true }.let { null } },
            )
        }
        LifeSegment.GOALS ->
            GoalsSection(
                data.goals,
                saver { goal: Goal -> writes.run({ it.saveGoal(goal) }) { it == null } },
                onDelete = { goal ->
                    guarded {
                        val result = writes.run({ it.deleteGoal(goal.id) }) { r -> r == GoalDeletion.Deleted }
                        message = PrivacyText.goalDeletion(result)
                    }
                },
            )
        LifeSegment.PROJECTS -> {
            LifeSearch(query) { query = it }
            ProjectCards(
                projectCards(data.projects, data.actions, query),
                emptyText = if (data.projects.isEmpty() && data.actions.isEmpty()) PrivacyText.PROJECTS_HINT else "No projects match.",
            ) { card ->
                projectFilter = card.filterKey
                segment = LifeSegment.ACTIONS.key
            }
            HorizontalDivider()
            ProjectsSection(
                data.projects,
                data.goals,
                saver { (project, expected): Pair<Project, Project?> -> writes.run({ it.saveProject(project, expected) }) { it == null } },
            )
        }
        LifeSegment.ACTIONS -> {
            LifeSearch(query) { query = it }
            val status = StatusFilter.of(statusKey)
            ActionList(
                groups = groupActions(data.actions, data.projects, query, status, projectFilter),
                totalActions = data.actions.size,
                status = status,
                onStatus = { statusKey = it.key },
                projectFilterText =
                    when (projectFilter) {
                        ProjectFilter.NONE -> null
                        ProjectFilter.STANDALONE -> "Standalone"
                        else -> data.projects.firstOrNull { it.id == projectFilter }?.title ?: "a project that no longer exists"
                    },
                onClearProject = { projectFilter = ProjectFilter.NONE },
                today = DueText.today(PlanningClock.now(), zone),
                onAdd = {
                    message = null
                    editId = "new"
                },
                onOpen = {
                    message = null
                    editId = it.id
                },
            )
        }
    }
}

private fun describe(refusal: PlanningRefusal): String =
    when (refusal) {
        PlanningRefusal.GOAL_NOT_FOUND -> "Not saved: that goal no longer exists."
        PlanningRefusal.PROJECT_NOT_FOUND -> "Not saved: that project no longer exists."
        PlanningRefusal.PROJECT_CLOSED -> "Not saved: a completed, abandoned or archived project takes no new actions."
        PlanningRefusal.ACTION_CHANGED ->
            "Not saved: this action changed since you opened it, for example by an outcome. Pick it again from the list."
        PlanningRefusal.PROJECT_CHANGED ->
            "Not saved: this project changed since you opened it, for example in the conversation. " +
                "Your draft is still shown. Pick the project again from the list to load the newer version, then make your change there."
    }

@Composable
private fun DirectionSection(
    direction: Direction?,
    save: Save<Direction>,
    onClear: () -> Unit,
) {
    Text("Direction", style = MaterialTheme.typography.titleMedium)
    var text by remember(direction) { mutableStateOf(direction?.text ?: "") }
    var confirming by rememberSaveable { mutableStateOf(false) }
    if (direction == null) {
        Text(PrivacyText.DIRECTION_HINT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    OutlinedTextField(text, { text = it }, label = { Text("Direction statement") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { save({ Direction(PlanningText.clean(text)) }) {} }) { Text("Save direction") }
        if (direction != null) OutlinedButton(onClick = { confirming = true }) { Text("Clear direction") }
    }
    if (confirming) {
        ConfirmDelete(
            PrivacyText.CLEAR_DIRECTION_TITLE,
            PrivacyText.CLEAR_DIRECTION_BODY,
            confirmLabel = "Clear permanently",
            onConfirm = {
                confirming = false
                onClear()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun SettingsSection(
    settings: PlanningSettings,
    save: Save<PlanningSettings>,
) {
    Text("Planning settings", style = MaterialTheme.typography.titleMedium)
    var rollover by remember(settings) { mutableStateOf(settings.rollover.toString()) }
    var start by remember(settings) { mutableStateOf(settings.window?.start?.toString() ?: "") }
    var end by remember(settings) { mutableStateOf(settings.window?.end?.toString() ?: "") }
    Text(
        settings.window?.let { "Window ${it.start} to ${it.end}." } ?: "No planning window chosen yet.",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(rollover, { rollover = it }, label = { Text("Day turns over at (HH:mm)") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(start, { start = it }, label = { Text("Window start") }, modifier = Modifier.weight(1f))
        OutlinedTextField(end, { end = it }, label = { Text("Window end") }, modifier = Modifier.weight(1f))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = {
            start = PlanningSettings.SUGGESTED_WINDOW.start.toString()
            end = PlanningSettings.SUGGESTED_WINDOW.end.toString()
        }) { Text("Use 12:00 to 22:00") }
        Button(onClick = {
            save({
                val window = if (start.isBlank() && end.isBlank()) null else PlanningWindow(time(start), time(end))
                PlanningSettings(time(rollover), window)
            }) {}
        }) { Text("Save settings") }
    }
}

@Composable
private fun GoalsSection(
    goals: List<Goal>,
    save: Save<Goal>,
    onDelete: (Goal) -> Unit,
) {
    Text("Goals", style = MaterialTheme.typography.titleMedium)
    var editing by remember { mutableStateOf<Goal?>(null) }
    var confirming by remember { mutableStateOf(false) }
    if (goals.isEmpty()) {
        Text(PrivacyText.GOALS_HINT, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    var cleared by remember { mutableIntStateOf(0) }
    goals.forEach { goal ->
        TextButton(onClick = { editing = goal }) { Text("${goal.title} (${goal.status.name.lowercase()})") }
    }
    val current = editing
    var title by remember(current, cleared) { mutableStateOf(current?.title ?: "") }
    var success by remember(current, cleared) { mutableStateOf(current?.successCondition ?: "") }
    var horizon by remember(current, cleared) { mutableStateOf(current?.horizon?.toString() ?: "") }
    var status by remember(current, cleared) { mutableStateOf(current?.status ?: GoalStatus.ACTIVE) }
    Text(if (current == null) "New goal" else "Editing goal", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(success, { success = it }, label = { Text("Success looks like") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(horizon, { horizon = it }, label = { Text("Horizon, optional (yyyy-mm-dd)") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { status = status.next() }) { Text("Status: ${status.name.lowercase()}") }
        Button(onClick = {
            val id = current?.id ?: newId()
            save({ Goal(id, PlanningText.clean(title), PlanningText.clean(success), date(horizon), status) }) {
                editing = null
                cleared++
            }
        }) { Text("Save goal") }
        if (current != null) TextButton(onClick = { editing = null }) { Text("New") }
    }
    if (current != null) {
        OutlinedButton(onClick = { confirming = true }) { Text("Delete this goal") }
        if (confirming) {
            ConfirmDelete(
                "Delete this goal?",
                PrivacyText.deleteGoalBody(current.title),
                onConfirm = {
                    confirming = false
                    onDelete(current)
                    editing = null
                    cleared++
                },
                onDismiss = { confirming = false },
            )
        }
    }
}

@Composable
private fun ProjectsSection(
    projects: List<Project>,
    goals: List<Goal>,
    save: Save<Pair<Project, Project?>>,
) {
    Text("Add or edit a project", style = MaterialTheme.typography.titleMedium)
    var editing by remember { mutableStateOf<Project?>(null) }
    var cleared by remember { mutableIntStateOf(0) }
    projects.forEach { project ->
        val goal = goals.firstOrNull { it.id == project.goalId }?.title ?: "no goal"
        TextButton(onClick = { editing = project }) { Text("${project.title} (${project.state.name.lowercase()}, $goal)") }
    }
    val current = editing
    var title by remember(current, cleared) { mutableStateOf(current?.title ?: "") }
    var goalId by remember(current, cleared) { mutableStateOf(current?.goalId) }
    var state by remember(current, cleared) { mutableStateOf(current?.state ?: ProjectState.PLANNED) }
    // ADR 0011: optional context, each left empty (unknown) until the person has something to say.
    var purpose by remember(current, cleared) { mutableStateOf(current?.purpose ?: "") }
    var doneWhen by remember(current, cleared) { mutableStateOf(current?.doneWhen ?: "") }
    var constraints by remember(current, cleared) { mutableStateOf(current?.constraints ?: "") }
    var blocker by remember(current, cleared) { mutableStateOf(current?.blocker ?: "") }
    Text(if (current == null) "New project" else "Editing project", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(purpose, { purpose = it }, label = { Text("Why it matters, optional") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(doneWhen, { doneWhen = it }, label = { Text("Done when, optional") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(constraints, { constraints = it }, label = { Text("Constraints, optional") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(blocker, { blocker = it }, label = { Text("Current blocker, optional") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { goalId = nextLink(goalId, goals.map { it.id }) }) {
            Text("Goal: ${goals.firstOrNull { it.id == goalId }?.title ?: "none"}")
        }
        OutlinedButton(onClick = { state = state.next() }) { Text("State: ${state.name.lowercase()}") }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val id = current?.id ?: newId()
            save({
                Project(
                    id,
                    PlanningText.clean(title),
                    goalId,
                    state,
                    optional(purpose),
                    optional(doneWhen),
                    optional(constraints),
                    optional(blocker),
                ) to current
            }) {
                editing = null
                cleared++
            }
        }) { Text("Save project") }
        if (current != null) TextButton(onClick = { editing = null }) { Text("New") }
    }
}

internal fun newId(): String = UUID.randomUUID().toString()

/** Empty means unknown, stored as null. */
private fun optional(text: String): String? = PlanningText.clean(text).ifEmpty { null }

private inline fun <reified T : Enum<T>> T.next(): T = enumValues<T>().let { it[(ordinal + 1) % it.size] }

/** None, then each id in turn, then none again. */
private fun nextLink(
    current: String?,
    ids: List<String>,
): String? {
    val index = if (current == null) -1 else ids.indexOf(current)
    return ids.getOrNull(index + 1)
}

internal fun minutes(text: String): Int = text.trim().toIntOrNull() ?: throw IllegalArgumentException("Minutes must be a whole number")

private fun time(text: String): LocalTime =
    runCatching { LocalTime.parse(text.trim()) }.getOrElse { throw IllegalArgumentException("Times are HH:mm, got \"$text\"") }

internal fun date(text: String): LocalDate? =
    text.trim().takeIf { it.isNotEmpty() }?.let {
        runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("Dates are yyyy-mm-dd, got \"$text\"") }
    }
