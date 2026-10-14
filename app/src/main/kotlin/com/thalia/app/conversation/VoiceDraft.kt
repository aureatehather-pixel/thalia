package com.thalia.app.conversation

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/** The note shown while a spoken draft is in the message box and has not been sent. Compared by the screen to label the draft. */
internal const val VOICE_DRAFT_READY_NOTE = "Check the draft, edit it if needed, then press Send."

/**
 * Slice 4B: speech becomes text in the message box and nothing else. The draft is edited and sent by the person; this file
 * never reaches [ConversationSession]. No audio and no transcript is kept: the text goes into the draft and nowhere else.
 *
 * A [SpeechSource] is explicitly on-device. A source that cannot guarantee that must report itself unavailable instead of
 * listening, so there is no quiet fall back to a cloud recognizer. Only the isolated conversationCheck build supplies one.
 */
internal interface SpeechSource {
    /** The phone's own locale as a language tag, such as en-MY. It is the default only when it is installed. */
    val deviceTag: String

    /** What the on-device recognizer reports. Opens no microphone and downloads nothing. */
    suspend fun readiness(): SpeechReadiness

    /**
     * Listens for one phrase in exactly [languageTag]. The source checks that this tag is installed before it opens the
     * microphone, and reports a failure if it is not. [onDone] is called once, on the main thread.
     */
    fun listen(
        languageTag: String,
        onDone: (SpeechOutcome) -> Unit,
    )

    /** Ends a listen in progress; [listen]'s callback then gets [SpeechOutcome.Cancelled]. Safe at any time. */
    fun cancel()

    /** Frees the recognizer. */
    fun release()
}

internal sealed interface SpeechReadiness {
    data class Unavailable(
        val reason: String,
    ) : SpeechReadiness

    /** The on-device languages the recognizer says are installed, as reported. */
    data class Available(
        val installed: List<String>,
    ) : SpeechReadiness
}

internal sealed interface SpeechOutcome {
    data class Heard(
        val text: String,
    ) : SpeechOutcome

    data class Failed(
        val message: String,
    ) : SpeechOutcome

    data object Cancelled : SpeechOutcome
}

internal enum class VoicePhase { CHECKING, READY, LISTENING, UNAVAILABLE }

internal data class VoiceUi(
    val phase: VoicePhase,
    val note: String? = null,
    /** Installed languages that may be used: the phone's own, or installed variants of its base language. */
    val languages: List<String> = emptyList(),
    /** The language Speak will use. Null until chosen when the phone's own locale is not installed. */
    val selected: String? = null,
    val deviceTag: String = "",
)

internal class VoiceDraftController(
    private val source: SpeechSource,
    /** Receives recognised text. The screen puts it in the message box; it is never sent from here. */
    private val onHeard: (String) -> Unit,
) {
    private val state = MutableStateFlow(VoiceUi(VoicePhase.CHECKING))
    val ui: StateFlow<VoiceUi> = state

    /** Bumped whenever listening is stopped or restarted, so a callback from an earlier listen can change nothing. */
    private var generation = 0

    /** Reads what is installed. Runs once: a later call, such as after rotation, keeps the language already chosen. */
    suspend fun check() {
        if (state.value.phase != VoicePhase.CHECKING) return
        val device = source.deviceTag
        state.value =
            when (val readiness = source.readiness()) {
                is SpeechReadiness.Unavailable -> VoiceUi(VoicePhase.UNAVAILABLE, readiness.reason, deviceTag = device)
                is SpeechReadiness.Available -> {
                    val options = languageOptions(device, readiness.installed)
                    if (options.languages.isEmpty()) {
                        VoiceUi(VoicePhase.UNAVAILABLE, noLanguageMessage(device, readiness.installed), deviceTag = device)
                    } else {
                        VoiceUi(VoicePhase.READY, null, options.languages, options.defaultTag, device)
                    }
                }
            }
    }

    /** The person's explicit choice among [VoiceUi.languages]. Anything else is ignored. */
    fun select(tag: String) {
        val current = state.value
        if (current.phase != VoicePhase.READY || tag !in current.languages) return
        state.value = current.copy(selected = tag, note = null)
    }

    /** Call only once the microphone permission is held. Does nothing until a language is selected. */
    fun start() {
        val current = state.value
        val language = current.selected
        if (current.phase != VoicePhase.READY || language == null) return
        state.value = current.copy(phase = VoicePhase.LISTENING, note = null)
        val mine = ++generation
        source.listen(language) { outcome ->
            if (mine != generation) return@listen
            val ready = state.value.copy(phase = VoicePhase.READY)
            state.value =
                when (outcome) {
                    is SpeechOutcome.Heard -> {
                        onHeard(outcome.text)
                        ready.copy(note = VOICE_DRAFT_READY_NOTE)
                    }
                    is SpeechOutcome.Failed -> ready.copy(note = outcome.message)
                    SpeechOutcome.Cancelled -> ready.copy(note = null)
                }
        }
    }

    /** Ends listening now. Anything the recognizer reports afterwards is ignored. Safe at any time. */
    fun stop() {
        generation++
        val current = state.value
        if (current.phase == VoicePhase.LISTENING) state.value = current.copy(phase = VoicePhase.READY, note = null)
        source.cancel()
    }

    /** Drops the note once the draft it spoke about has been sent. */
    fun clearNote() {
        state.value = state.value.copy(note = null)
    }

    fun permissionDenied() {
        state.value = state.value.copy(phase = VoicePhase.READY, note = "The microphone permission was not granted. Typing still works.")
    }

    fun release() = source.release()
}

internal data class LanguageOptions(
    val languages: List<String>,
    /** Set only when the phone's own locale is installed. Otherwise the person must choose. */
    val defaultTag: String?,
)

/**
 * The phone's own locale when it is installed, otherwise the installed variants of its base language, each under its real
 * tag. Nothing is chosen for the person in the second case.
 */
internal fun languageOptions(
    deviceTag: String,
    installed: List<String>,
): LanguageOptions {
    installed.firstOrNull { it.equals(deviceTag, ignoreCase = true) }?.let { return LanguageOptions(listOf(it), it) }
    val base = Locale.forLanguageTag(deviceTag).language
    val variants =
        installed
            .filter { base.isNotEmpty() && Locale.forLanguageTag(it).language == base }
            .distinct()
            .sorted()
    return LanguageOptions(variants, null)
}

/** True only for the exact requested tag. A base-language match is not enough. */
internal fun languageInstalled(
    installed: List<String>?,
    tag: String,
): Boolean = installed?.any { it.equals(tag, ignoreCase = true) } == true

internal fun noLanguageMessage(
    deviceTag: String,
    installed: List<String>,
): String =
    "No on-device speech language for $deviceTag is installed (installed: " +
        (if (installed.isEmpty()) "none reported" else installed.take(12).joinToString(", ")) +
        "). Thalia does not download it; you can install it in system settings. Typing still works."

/** The one recognizer request: on-device preferred, for exactly [tag]. */
internal fun speechIntent(tag: String): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
        .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)

/**
 * The message box and its voice control, held in memory by the conversation's ViewModel. It survives rotation and is gone
 * when the conversation is left. Nothing here is saved, logged or put in saved state.
 */
internal class ConversationComposer(
    source: SpeechSource?,
    initial: String = "",
) {
    private val text = MutableStateFlow(initial)
    val draft: StateFlow<String> = text

    /** Null when this build has no speech source. Heard text joins the draft and goes nowhere else. */
    val voice: VoiceDraftController? =
        source?.let {
            VoiceDraftController(it) { spoken ->
                text.value = mergeDraft(text.value, spoken)
                afterHeard?.invoke()
            }
        }

    /** Set by the ViewModel when finished speech should be sent at once. Never approves anything; it only does what Send does. */
    @Volatile
    var afterHeard: (() -> Unit)? = null

    fun edit(value: String) {
        text.value = value
    }

    /**
     * The submitted draft, which is cleared. Listening stops first, so a late result cannot refill the box. Anything typed
     * or spoken afterwards is a newer draft and is not touched by what happens to the proposal.
     */
    fun take(): String? {
        voice?.stop()
        val submitted = text.value
        if (submitted.isBlank()) return null
        text.value = ""
        voice?.clearNote()
        return submitted
    }

    fun release() {
        voice?.stop()
        voice?.release()
    }
}

/** Spoken text joins what is already typed rather than replacing it. */
internal fun mergeDraft(
    draft: String,
    spoken: String,
): String = if (draft.isBlank()) spoken.trim() else draft.trimEnd() + " " + spoken.trim()

/**
 * Why on-device recognition cannot be offered at all, or null when the installed languages should be read.
 * [onDeviceAvailable] is the platform's answer. Which language is a separate step, [languageOptions].
 */
internal fun voiceUnavailableReason(
    sdk: Int,
    onDeviceAvailable: Boolean,
    installedLanguages: List<String>?,
): String? =
    when {
        sdk < 33 ->
            "Voice input needs Android 13 or newer, to confirm the on-device language is installed. Typing still works."
        !onDeviceAvailable ->
            "This phone has no on-device speech recognizer, and Thalia does not use a cloud one. Typing still works."
        installedLanguages == null ->
            "Could not confirm the on-device speech language, so voice input is off. Typing still works."
        else -> null
    }

internal fun speechErrorMessage(code: Int): String =
    when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Nothing was recognised. Try again, or type."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "The microphone permission is missing. Typing still works."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy. Try again in a moment."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "The on-device speech language is not available. Thalia does not download it. Typing still works."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
            "The recognizer reported a network or server error. Thalia asked for on-device recognition only. Typing still works."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be used. Typing still works."
        else -> "Speech recognition failed (code $code). Typing still works."
    }

/**
 * The voice control under the message box. Asking for the microphone happens only when the button is pressed, and a refusal
 * leaves everything else as it was. The control exists only where the build supplies a [SpeechSource].
 */
@Composable
internal fun VoiceDraftControl(
    controller: VoiceDraftController,
    sendButton: @Composable (Modifier) -> Unit,
) {
    val ui by controller.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) controller.start() else controller.permissionDenied()
        }
    LaunchedEffect(controller) { controller.check() }
    // Backgrounding ends listening. Leaving the conversation for good releases the recognizer in the ViewModel.
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner, controller) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) controller.stop() }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (ui.phase) {
            VoicePhase.CHECKING -> Text("Checking on-device speech recognition...", style = MaterialTheme.typography.bodySmall)
            VoicePhase.UNAVAILABLE -> Unit
            VoicePhase.LISTENING -> Unit
            VoicePhase.READY -> {
                if (ui.languages.none { it.equals(ui.deviceTag, ignoreCase = true) }) {
                    Text(
                        "Your phone is set to ${ui.deviceTag}, which has no on-device speech installed. Choose an installed " +
                            "variant. Nothing is chosen for you.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ui.languages.forEach { tag ->
                        TextButton(onClick = { controller.select(tag) }) {
                            Text(if (tag == ui.selected) "Using $tag" else "Use $tag")
                        }
                    }
                }
            }
        }
        ui.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            val language = ui.selected
            when {
                ui.phase == VoicePhase.LISTENING ->
                    OutlinedButton(
                        onClick = controller::stop,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp).semantics { contentDescription = "Stop listening" },
                    ) { Text("Listening... tap to stop") }
                ui.phase == VoicePhase.READY && language != null ->
                    OutlinedButton(
                        onClick = {
                            val held =
                                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                                    PackageManager.PERMISSION_GRANTED
                            if (held) controller.start() else permission.launch(Manifest.permission.RECORD_AUDIO)
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier =
                            Modifier.weight(1f).heightIn(min = 48.dp).semantics {
                                contentDescription = "Speak a draft, language $language. Nothing is sent until you press Send."
                            },
                    ) { Text("Speak") }
            }
            sendButton(Modifier.weight(1f))
        }
    }
}
