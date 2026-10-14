package com.thalia.app.m3a

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thalia.voicecheckins.gemini.LiveUsage
import com.thalia.voicecheckins.gemini.ModalityTokenCount
import com.thalia.voicecheckins.gemini.UsageReport
import com.thalia.voicecheckins.voice.VoiceSessionResult
import java.time.Instant

/**
 * The M3A screen. Diagnostic, not a design. It shows no secret: the key field is masked, is held
 * only in memory, is emptied on Save, and the saved key is never shown.
 */
@Composable
internal fun LiveVoiceTestScreen(
    state: LiveTestState,
    consent: LiveConsent?,
    keySaved: Boolean,
    microphoneGranted: Boolean,
    keyMessage: String?,
    onConsentChange: (Boolean) -> Unit,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
    onRequestMicrophone: () -> Unit,
    onStart: () -> Unit,
    onEnd: () -> Unit,
) {
    // The screen stays on only while a session runs.
    val view = LocalView.current
    DisposableEffect(view, state.running) {
        view.keepScreenOn = state.running
        onDispose { view.keepScreenOn = false }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Live voice test", style = MaterialTheme.typography.headlineMedium)
            Text(
                text =
                    "M3A spike, development build only. A manual Gemini Live session on the speaker. " +
                        "Scheduled check-ins are not aware of this test: do not run it while a check-in " +
                        "is scheduled. Leaving this screen ends the session.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section("Consent") {
                Text(LiveConsentWording.TEXT, style = MaterialTheme.typography.bodySmall)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("I consent to this test sending this data to Google", modifier = Modifier.weight(1f))
                    Switch(checked = consent != null, onCheckedChange = onConsentChange)
                }
                Text(
                    text =
                        consent?.let { "Accepted ${Instant.ofEpochMilli(it.acceptedAtMillis)}, wording ${it.wordingVersion}." }
                            ?: "Not given. Wording ${LiveConsentWording.VERSION}.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("API key") {
                KeyEntry(keySaved = keySaved, enabled = !state.running, onSaveKey = onSaveKey, onClearKey = onClearKey)
                if (keyMessage != null) Text(keyMessage, style = MaterialTheme.typography.bodySmall)
            }

            Section("Microphone") {
                Text(if (microphoneGranted) "Permission granted." else "Permission not granted.")
                if (!microphoneGranted) {
                    OutlinedButton(onClick = onRequestMicrophone, modifier = Modifier.fillMaxWidth()) { Text("Allow microphone") }
                }
            }

            Section("Session") {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = onStart, enabled = !state.running) { Text("Start") }
                    OutlinedButton(onClick = onEnd, enabled = state.running) { Text("End") }
                }
                Text("State: ${state.sessionState?.name ?: if (state.running) "STARTING" else "IDLE"}")
                state.refusal?.let { Text("Not started: ${it.message}", color = MaterialTheme.colorScheme.error) }
                state.result?.let { Text(it.describe()) }
                if (state.endedWithoutResult && !state.running) Text("The session stopped without a result.")
                state.audio?.let {
                    Text(
                        "Audio: speaker ${if (it.speakerRouted) "routed" else "not routed"}, " +
                            "echo canceller ${if (it.echoCancelerAttached) "attached" else "unavailable"}.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            Section("Experiment allowance") {
                val used = state.attemptsUsed
                Text("Attempts charged: $used of ${ExperimentAllowance.MAX_ATTEMPTS}.")
                Text(
                    "Remaining: ${ExperimentAllowance.formatUsd(ExperimentAllowance.remainingMicros(used))} of " +
                        "${ExperimentAllowance.formatUsd(ExperimentAllowance.ALLOWANCE_MICROS)}, " +
                        "${ExperimentAllowance.formatUsd(ExperimentAllowance.ATTEMPT_MICROS)} per attempt, never refunded.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Section("Transcript (memory only)") {
                if (state.transcript.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
                for (line in state.transcript) Text("${line.speaker.name.lowercase()}: ${line.text}")
            }

            Section("Usage, raw, in arrival order") {
                for (line in state.usage.summaryLines()) {
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

@Composable
private fun KeyEntry(
    keySaved: Boolean,
    enabled: Boolean,
    onSaveKey: (String) -> Unit,
    onClearKey: () -> Unit,
) {
    // remember, not rememberSaveable: the entry must never reach saved instance state.
    var entry by remember { mutableStateOf("") }
    Text(if (keySaved) "A key is saved. It is never shown." else "No key is saved.")
    OutlinedTextField(
        value = entry,
        onValueChange = { entry = it },
        label = { Text("Dedicated M3A Gemini API key") },
        singleLine = true,
        enabled = enabled,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        modifier = Modifier.fillMaxWidth(),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = {
                onSaveKey(entry)
                entry = ""
            },
            enabled = enabled && entry.isNotBlank(),
        ) { Text("Save") }
        OutlinedButton(onClick = onClearKey, enabled = enabled && keySaved) { Text("Clear") }
    }
}

@Composable
private fun Section(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

private fun VoiceSessionResult.describe(): String =
    when (this) {
        is VoiceSessionResult.Completed -> "Finished: completed."
        is VoiceSessionResult.Cancelled -> "Ended: $reason."
        is VoiceSessionResult.Failed -> "Failed: $reason. $message"
    }

/** One line per report, in the order received. Nothing is summed and nothing is turned into money. */
internal fun LiveUsage.summaryLines(): List<String> =
    buildList {
        if (reports.isEmpty()) add("No usage reports.")
        reports.forEachIndexed { index, report -> add("#${index + 1} ${report.describe()}") }
        add("Unreadable reports: $unreadableReports")
        add("Audio bytes sent: $audioBytesSent, received: $audioBytesReceived (diagnostic, not a cost)")
    }

private fun UsageReport.describe(): String =
    listOfNotNull(
        promptTokenCount?.let { "prompt=$it" },
        cachedContentTokenCount?.let { "cached=$it" },
        responseTokenCount?.let { "response=$it" },
        toolUsePromptTokenCount?.let { "toolUsePrompt=$it" },
        thoughtsTokenCount?.let { "thoughts=$it" },
        totalTokenCount?.let { "total=$it" },
        promptTokensDetails.describe("promptDetails"),
        cacheTokensDetails.describe("cacheDetails"),
        responseTokensDetails.describe("responseDetails"),
        toolUsePromptTokensDetails.describe("toolUsePromptDetails"),
        invalidFields.takeIf { it.isNotEmpty() }?.let { "invalid=$it" },
    ).joinToString(" ").ifEmpty { "(empty)" }

private fun List<ModalityTokenCount>.describe(name: String): String? =
    takeIf { it.isNotEmpty() }?.joinToString(prefix = "$name=[", postfix = "]") { "${it.modality ?: "?"}:${it.tokenCount ?: "?"}" }
