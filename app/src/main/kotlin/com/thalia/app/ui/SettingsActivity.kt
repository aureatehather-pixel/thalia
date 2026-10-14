package com.thalia.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thalia.app.ThaliaApplication
import com.thalia.app.ToolsActivity
import com.thalia.app.ai.AiSettingsActivity
import com.thalia.app.planning.ClosingNotesSection
import com.thalia.app.planning.TransferSection

/**
 * Settings, opened from the profile button on every tab. It collects what already exists, each in one place: AI, speech and voice
 * drafts (their own screen), the check-in tools, appearance, and the planning data export and import.
 *
 * Nothing here is a mock: each card opens a real screen or holds a real control. The parts of the design's Settings that are
 * not built are named on a card at the bottom.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as ThaliaApplication
        setContent {
            ThaliaTheme {
                SettingsScreen(
                    app = app,
                    onClose = ::finish,
                    // An export or import ran: the planning screen behind must read everything again. Otherwise it keeps what is on screen.
                    onTransferred = { setResult(RESULT_OK) },
                    onAiSettings = { startActivity(Intent(this, AiSettingsActivity::class.java)) },
                    onTools = { startActivity(Intent(this, ToolsActivity::class.java)) },
                    onManual = { startActivity(Intent(this, ManualActivity::class.java)) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsScreen(
    app: ThaliaApplication,
    onClose: () -> Unit,
    onTransferred: () -> Unit,
    onAiSettings: () -> Unit,
    onTools: () -> Unit,
    onManual: () -> Unit,
) {
    val prefs = AppearancePrefs.of(app)
    var transferNotice by remember { mutableStateOf<String?>(null) }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 44.dp)) { Text("Close") }
            }
            SettingsCard("How to use Thalia") {
                Text(
                    "The daily loop, entering what you know, plans and approval, calls, AI and voice, privacy, and what is not built yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onManual, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open the manual") }
            }
            SettingsCard("AI, speech and voice drafts") {
                Text(
                    "Consent, keys, the monthly budget, spoken replies, and whether a finished voice draft is sent for you.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = onAiSettings, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open AI settings") }
            }
            DefaultTimesCard(app)
            ClosingCallCard(app)
            SettingsCard("Check-ins and calls") {
                Text(
                    "Permissions, the quiet period, and the check-in tools. This is the development screen the app used to start on.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onTools, modifier = Modifier.heightIn(min = 48.dp)) { Text("Open check-in tools") }
            }
            SettingsCard("Appearance") {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeChoice.entries.forEach { choice ->
                        FilterChip(
                            selected = prefs.theme == choice,
                            onClick = { prefs.chooseTheme(choice) },
                            label = { Text(choice.label) },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
                Text("Accent", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Accent.entries.forEach { accent ->
                        FilterChip(
                            selected = prefs.accent == accent,
                            onClick = { prefs.chooseAccent(accent) },
                            label = { Text(accent.label) },
                            modifier = Modifier.heightIn(min = 44.dp),
                        )
                    }
                }
                Text(
                    "System follows the phone. The default accent, teal or Thalia Blue, and the dark palette are still waiting for your decision.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SettingsCard("Privacy and your data") {
                Text(
                    "Everything Thalia keeps is what you entered or approved, on this phone. Each kind has one place to read, change and " +
                        "delete it. Deleting is permanent; archiving is separate and keeps the item.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Direction and goals: Life tab, Direction and Goals. Clear the direction, or delete a goal that no project uses.\n" +
                        "Filed notes and ideas: Plan tab, Inbox, Show filed items. Edit, put back, or delete.\n" +
                        "Closing notes: below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                ClosingNotesSection(app.planningAccess, onChanged = onTransferred)
            }
            SettingsCard("Data") {
                transferNotice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                TransferSection(app.planningAccess, app.planningTransfer) {
                    transferNotice = it
                    onTransferred()
                }
            }
        }
    }
}

@Composable
internal fun SettingsCard(
    title: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            content()
        }
    }
}
