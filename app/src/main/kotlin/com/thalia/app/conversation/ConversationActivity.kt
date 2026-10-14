package com.thalia.app.conversation

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thalia.app.ThaliaApplication
import com.thalia.app.ai.AiConnection
import com.thalia.app.ai.AiSettingsActivity
import com.thalia.app.ui.ProfileButton
import com.thalia.app.ui.SettingsActivity
import com.thalia.app.ui.ThaliaShell
import com.thalia.app.ui.ThaliaTab
import com.thalia.app.ui.ThaliaTheme
import com.thalia.app.ui.goToTab
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Keeps the session across rotation. Leaving the screen for good drops it, and with it any unapproved proposal. */
internal class ConversationViewModel(
    val session: ConversationSession,
    val composer: ConversationComposer = ConversationComposer(null),
    /** Reads Thalia's replies aloud. Null in a build with no speech service. */
    val speaker: SpokenReplyController? = null,
    sendAfterSpeaking: () -> Boolean = { false },
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
) : ViewModel() {
    private val scope = scope
    private var autoSend: kotlinx.coroutines.Job? = null

    init {
        speaker?.let { s -> this.scope.launch { session.state.collect { s.onState(it) } } }
        composer.afterHeard = {
            if (sendAfterSpeaking()) {
                // A moment later, so the heard words are visible and the voice control has finished its own update. Only the
                // newest pending send survives, and it sends only if the draft is still exactly what was heard: an edit, a
                // press of Send or a newer phrase in the meantime means nothing is sent from here (sendDraft ignores an empty draft).
                val heard = composer.draft.value
                autoSend?.cancel()
                autoSend =
                    this.scope.launch {
                        kotlinx.coroutines.delay(SEND_AFTER_SPEAKING_DELAY_MS)
                        if (composer.draft.value == heard) sendDraft()
                    }
            }
        }
    }

    /** Sends the draft, which is cleared. Approving or dismissing a proposal never touches the draft. */
    fun sendDraft() {
        val text = composer.take() ?: return
        scope.launch { session.send(text) }
    }

    fun approve() {
        scope.launch { session.approve() }
    }

    fun dismiss() {
        scope.launch { session.dismiss() }
    }

    fun undo(entryId: Int) {
        scope.launch { session.undo(entryId) }
    }

    override fun onCleared() {
        speaker?.release()
        composer.release()
        scope.cancel()
    }

    companion object {
        const val SEND_AFTER_SPEAKING_DELAY_MS = 400L
    }
}

/**
 * Text conversation, functional and small: what you said, what Thalia saved, what is only proposed and what was not
 * applied are labelled apart. Reached from the planning screen. The conversation is not stored; the planning data is.
 */
class ConversationActivity : ComponentActivity() {
    /** True when the system is recreating this screen (for example after the process died). The call's message is not applied again. */
    private var restored = false

    private val model: ConversationViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = application as ThaliaApplication
                val port =
                    StoreConversationPort(
                        AccessStoreRunner(app.planningAccess),
                        app.checkInZone,
                        com.thalia.app.planning.PlanningClock::now,
                        app.reminderService,
                    )
                val triage = intent.getStringArrayExtra(EXTRA_TRIAGE_IDS)?.toSet()
                val composer = ConversationComposer(OnDeviceSpeechSource(app), initialDraft(intent.getStringExtra(EXTRA_DRAFT), restored))
                val ai = app.aiServices
                val vmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
                val speaker =
                    ai.synthesizer?.let {
                        val playback = StreamingPlayback(app)
                        SpokenReplyController(ai.spokenReplies(playback), playback, ai.prefs, vmScope)
                    }
                return ConversationViewModel(
                    newConversationSession(port, app.conversationProvider, triage),
                    composer,
                    speaker,
                    { ai.prefs.sendAfterSpeaking },
                    vmScope,
                ) as T
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState != null
        val handoff = CallHandoff.typeOf(intent)
        setContent {
            ThaliaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val ai = (application as ThaliaApplication).aiServices
                    ThaliaShell(
                        selected = ThaliaTab.THALIA,
                        onSelect = { tab -> if (tab != ThaliaTab.THALIA) goToTab(tab) },
                    ) {
                        ConversationScreen(
                            model,
                            aiNotice =
                                conversationNotice {
                                    aiNotice(
                                        ai.connection,
                                        ai.consent.isAccepted(),
                                        ai.keys.hasKey(),
                                        ai.providerName,
                                    )
                                },
                            handoffNotice = handoff?.let(CallHandoff::notice),
                            onAiSettings = { startActivity(Intent(this@ConversationActivity, AiSettingsActivity::class.java)) },
                            // The day screen is the Today tab. It is brought back if it is below, otherwise it starts.
                            onDayScreen = { goToTab(ThaliaTab.TODAY) },
                            onSettings = { startActivity(Intent(this@ConversationActivity, SettingsActivity::class.java)) },
                            onGuided = {
                                startActivity(
                                    com.thalia.app.checkin.GuidedCheckInActivity.intent(
                                        this@ConversationActivity,
                                        handoff ?: com.thalia.voicecheckins.domain.CheckInType.OPENING,
                                    ),
                                )
                            },
                        )
                    }
                }
            }
        }
    }

    companion object {
        /** Text put in the message box, never sent until the person presses Send. */
        const val EXTRA_DRAFT = "com.thalia.app.conversation.DRAFT"

        /** The Inbox captures the person chose to go through. Without it, the conversation never sees the Inbox. */
        const val EXTRA_TRIAGE_IDS = "com.thalia.app.conversation.TRIAGE_IDS"

        /** The messages that open the day and close it. Prefilled, never sent until Send is pressed. */
        const val OPENING_DRAFT =
            "Help me start my day. Look at what is open and what is due, tell me what you would put in today's plan and " +
                "why, and ask me one question first if something is missing."
        const val CLOSING_DRAFT =
            "Help me close my day. Go through today's plan and ask me what happened to anything without an outcome, one " +
                "item at a time."
    }
}

@Composable
internal fun ConversationScreen(
    model: ConversationViewModel,
    aiNotice: String? = null,
    handoffNotice: String? = null,
    onAiSettings: (() -> Unit)? = null,
    onDayScreen: (() -> Unit)? = null,
    onSettings: (() -> Unit)? = null,
    onGuided: (() -> Unit)? = null,
) {
    val state by model.session.state.collectAsStateWithLifecycle()
    // Slice 4B: the draft and the voice control live in the ViewModel, in memory only. Spoken text only ever lands in the
    // draft; Send stays the person's explicit act.
    val draft by model.composer.draft.collectAsStateWithLifecycle()
    val voiceController = model.composer.voice
    val voiceUi = voiceController?.ui?.collectAsStateWithLifecycle()
    val voiceOn = voiceUi?.value?.phase in setOf(VoicePhase.READY, VoicePhase.LISTENING)
    val rows = remember(state.entries) { Presentation.rows(state.entries) }
    val scroll = rememberScrollState()
    // The header, notices and transcript scroll as one; the scroll area is a column of its own so it is never narrower than the screen.
    LaunchedEffect(rows.size, state.pending, state.busy) {
        if (rows.isNotEmpty() || state.pending != null || state.busy) scroll.scrollTo(Int.MAX_VALUE)
    }
    val composerScroll = rememberScrollState()
    val onNextStep: (NextStep) -> Unit = { step ->
        when (step) {
            NextStep.AI_SETTINGS -> onAiSettings?.invoke()
            NextStep.PLANNING -> onDayScreen?.invoke()
            else -> Unit
        }
    }
    var sharing by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(model) { sharing = model.session.sharingPreview() }
    BoxWithConstraints(modifier = Modifier.safeDrawingPadding().padding(16.dp)) {
        // The composer keeps its natural height while it fits. When it does not (large text with the keyboard open) it scrolls
        // inside itself, so the strip above it, with the header and AI settings, never shrinks below COMPOSER_TOP_STRIP.
        val composerMax = (maxHeight - COMPOSER_TOP_STRIP).coerceAtLeast(COMPOSER_TOP_STRIP)
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Talk to Thalia",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(50)) {
                            Text(
                                if (voiceOn) "Text and voice draft" else "Text only",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            )
                        }
                        onSettings?.let { ProfileButton(it) }
                    }
                }
                handoffNotice?.let { notice ->
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(notice, style = MaterialTheme.typography.bodySmall)
                            onDayScreen?.let { TextButton(onClick = it) { Text("Open the day screen") } }
                        }
                    }
                }
                aiNotice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                onAiSettings?.let { TextButton(onClick = it) { Text("AI settings") } }
                // Always reachable, so a provider failure or AI being off never leaves the day without a way through.
                onGuided?.let { TextButton(onClick = it) { Text("Guided check-in without AI") } }
                rows.forEach { TranscriptRow(it, model::undo, onNextStep) }
                // Spoken replies: real controls where the build has a speech service, otherwise the visibly unbuilt placeholder.
                val speech = model.speaker
                if (speech != null) {
                    SpeechRow(speech)
                } else if (rows.lastOrNull()?.let { it.style == RowStyle.REPLY && it.status != null } == true) {
                    FutureSpeechRow()
                }
                if (state.busy) WorkingRow()
                state.pending?.let { PendingCardView(it, state.busy, model::approve, model::dismiss) }
            }
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = composerMax).verticalScroll(composerScroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (sharing.isNotEmpty()) {
                    Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                "Pressing Send shares these Inbox captures with the AI service:",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            sharing.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
                val voiceDraft = voiceUi?.value?.note == VOICE_DRAFT_READY_NOTE && draft.isNotBlank()
                if (voiceDraft) {
                    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(10.dp)) {
                        Text(
                            "Draft",
                            style = MaterialTheme.typography.labelLarge,
                            modifier =
                                Modifier.padding(horizontal = 10.dp, vertical = 6.dp).semantics {
                                    contentDescription = "Voice draft, not sent yet. Check it, edit if needed, then press Send."
                                },
                        )
                    }
                }
                OutlinedTextField(draft, model.composer::edit, label = { Text("Message") }, modifier = Modifier.fillMaxWidth())
                val send: @Composable (Modifier) -> Unit = { modifier ->
                    Button(
                        enabled = !state.busy && draft.isNotBlank() && voiceUi?.value?.phase != VoicePhase.LISTENING,
                        onClick = model::sendDraft,
                        shape = RoundedCornerShape(12.dp),
                        modifier = modifier.heightIn(min = 48.dp),
                    ) { Text(if (state.busy) "Working..." else "Send") }
                }
                if (voiceController != null) {
                    VoiceDraftControl(voiceController, send)
                } else {
                    send(Modifier.fillMaxWidth())
                }
            }
        }
    }
}

/** The least height the scrolling header and transcript keep above the composer. */
private val COMPOSER_TOP_STRIP = 96.dp

/** The one line at the top of the conversation about what Send can reach. Checked when the screen opens. */
internal fun aiNotice(
    connection: AiConnection,
    consent: Boolean,
    key: Boolean,
    provider: String = "Google's Gemini",
): String =
    when {
        connection == AiConnection.NONE ->
            "AI: this build has no connection to an AI service, so nothing is ever sent. Everything else works by hand."
        !consent -> "AI is off. Nothing is sent until you turn it on in AI settings."
        !key -> "AI is on but has no key saved, so nothing is sent."
        connection == AiConnection.FAKE ->
            "AI is on in a CHECK BUILD: Send goes to a fake transport inside this app, not to Google. Nothing leaves the phone."
        else ->
            "AI is on. Pressing Send shares a snapshot of your planning data with $provider. " +
                "See AI settings for what and how much."
    }

/**
 * The one place a conversation session gets its provider. The default is the gated Gemini provider; an installed route (the
 * ChatGPT-plan route, from a check build or a test, never from a shipped build) replaces it, and then the failure texts make no
 * claim about a monthly budget.
 */
internal fun newConversationSession(
    port: ConversationPort,
    defaultProvider: com.thalia.conversation.ConversationProvider,
    triageIds: Set<String>?,
): ConversationSession {
    val route = com.thalia.app.ai.ConversationRoutes.installed
    return ConversationSession(port, route?.provider ?: defaultProvider, triageIds, budgetNotes = route == null)
}

/** The notice for the top of the screen: the installed route's own, or [default]. */
internal fun conversationNotice(default: () -> String): String =
    com.thalia.app.ai.ConversationRoutes.installed
        ?.notice
        ?.invoke() ?: default()
