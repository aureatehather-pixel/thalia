package com.thalia.app.voicecheckins.call.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.thalia.app.ui.ThaliaTheme
import com.thalia.app.voicecheckins.call.CallUiState
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.voice.Speaker
import com.thalia.voicecheckins.voice.TranscriptLine
import com.thalia.voicecheckins.voice.VoiceSessionState

/**
 * The whole call surface, one composable per phase.
 *
 * M1's interface is deliberately plain. Its job is to be unmistakably a call while the
 * phone is locked, and to be honest about what is happening; it is not the finished
 * design, and nothing here should be treated as one.
 */
@Composable
fun CallSurface(
    state: CallUiState,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
    onEndCall: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Box(Modifier.safeDrawingPadding().padding(24.dp)) {
            when (state) {
                is CallUiState.Ringing -> IncomingCall(state, onAnswer, onDecline)
                is CallUiState.Active -> ActiveCall(state, onEndCall)
                is CallUiState.Ended -> EndedCall(state, onDismiss)
                CallUiState.Idle -> EmptyCall(onDismiss)
            }
        }
    }
}

/**
 * A call phase that fits any screen: it fills the height when there is room, with its top group at the top and its bottom
 * group (the buttons) at the bottom, and scrolls instead of clipping in landscape or at large text.
 */
private val COMPACT_BELOW = 480.dp

@Composable
private fun CallColumn(
    top: @Composable ColumnScope.(compact: Boolean) -> Unit,
    bottom: @Composable ColumnScope.(compact: Boolean) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // A short window (landscape) trims the spacing and the badge so the buttons show without scrolling.
        val compact = maxHeight < COMPACT_BELOW
        Column(
            modifier = Modifier.fillMaxWidth().heightIn(min = maxHeight).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) { top(compact) }
            Column(horizontalAlignment = Alignment.CenterHorizontally) { bottom(compact) }
        }
    }
}

@Composable
private fun IncomingCall(
    state: CallUiState.Ringing,
    onAnswer: () -> Unit,
    onDecline: () -> Unit,
) {
    CallColumn(
        top = { compact ->
            Spacer(Modifier.height(if (compact) 4.dp else 48.dp))
            CallerBadge(state.callerName, if (compact) 64.dp else 112.dp)
            Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
            Text(
                text = state.callerName,
                style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = state.checkInType.label(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(4.dp))
            // Truthfulness rule from the PRD: never imply this is a phone call.
            Text(
                text = "Incoming internet call",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        bottom = { compact ->
            Spacer(Modifier.height(if (compact) 8.dp else 24.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                CallAction(
                    label = "Decline",
                    container = MaterialTheme.colorScheme.errorContainer,
                    content = MaterialTheme.colorScheme.onErrorContainer,
                    onClick = onDecline,
                    modifier = Modifier.weight(1f),
                )
                CallAction(
                    label = "Answer",
                    container = MaterialTheme.colorScheme.primary,
                    content = MaterialTheme.colorScheme.onPrimary,
                    onClick = onAnswer,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(if (compact) 4.dp else 32.dp))
        },
    )
}

@Composable
private fun ActiveCall(
    state: CallUiState.Active,
    onEndCall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = state.callerName,
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = state.sessionState.label(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))

        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) {
            Transcript(
                lines = state.transcript,
                modifier = Modifier.fillMaxSize().padding(14.dp),
            )
        }

        Spacer(Modifier.height(16.dp))
        // Stated plainly rather than hidden, because in M1 it is the truth.
        Text(
            text = "Simulated conversation. No microphone, no audio, no AI provider.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        CallAction(
            label = "End Call",
            container = MaterialTheme.colorScheme.errorContainer,
            content = MaterialTheme.colorScheme.onErrorContainer,
            onClick = onEndCall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Transcript(
    lines: List<TranscriptLine>,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(
        modifier = modifier,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(lines) { line ->
            Column(Modifier.fillMaxWidth()) {
                Text(
                    text = if (line.speaker == Speaker.THALIA) "Thalia" else "You",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(text = line.text, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun EndedCall(
    state: CallUiState.Ended,
    onDismiss: () -> Unit,
) {
    CallColumn(
        top = { compact ->
            Spacer(Modifier.height(if (compact) 8.dp else 48.dp))
            Text(
                text = state.summary,
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${state.checkInType.label()} · recorded as ${state.status.name.lowercase()}",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        },
        bottom = { compact ->
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)) {
                Text("Done", style = MaterialTheme.typography.titleMedium)
            }
            Spacer(Modifier.height(if (compact) 4.dp else 32.dp))
        },
    )
}

@Composable
private fun EmptyCall(onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("No check-in in progress", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDismiss) { Text("Close") }
    }
}

@Composable
private fun CallerBadge(
    name: String,
    size: Dp = 112.dp,
) {
    Box(
        modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.take(1),
            style = if (size < 100.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun CallAction(
    label: String,
    container: Color,
    content: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        colors =
            ButtonDefaults.buttonColors(
                containerColor = container,
                contentColor = content,
            ),
        modifier = modifier.heightIn(min = 64.dp),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

private fun CheckInType.label(): String =
    when (this) {
        CheckInType.OPENING -> "Opening check-in"
        CheckInType.CLOSING -> "Closing check-in"
    }

private fun VoiceSessionState.label(): String =
    when (this) {
        VoiceSessionState.IDLE -> "Starting"
        VoiceSessionState.CONNECTING -> "Connecting"
        VoiceSessionState.ACTIVE -> "Connected"
        VoiceSessionState.ENDING -> "Ending"
        VoiceSessionState.ENDED -> "Ended"
    }

// Previews are driven by the same fake the real surface uses, so what is drawn here is
// what appears on the device.

@Preview(name = "Incoming", showBackground = true)
@Composable
private fun IncomingPreview() {
    ThaliaTheme {
        CallSurface(
            state = CallUiState.Ringing(CheckInType.OPENING),
            onAnswer = {},
            onDecline = {},
            onEndCall = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Active", showBackground = true)
@Composable
private fun ActivePreview() {
    ThaliaTheme {
        CallSurface(
            state =
                CallUiState.Active(
                    checkInType = CheckInType.OPENING,
                    sessionState = VoiceSessionState.ACTIVE,
                    transcript =
                        com.thalia.voicecheckins.voice.fake
                            .defaultScriptFor(CheckInType.OPENING)
                            .take(3),
                ),
            onAnswer = {},
            onDecline = {},
            onEndCall = {},
            onDismiss = {},
        )
    }
}

@Preview(name = "Ended", showBackground = true)
@Composable
private fun EndedPreview() {
    ThaliaTheme {
        CallSurface(
            state =
                CallUiState.Ended(
                    checkInType = CheckInType.CLOSING,
                    status = CheckInStatus.COMPLETED,
                    summary = "Check-in complete",
                    transcript = emptyList(),
                ),
            onAnswer = {},
            onDecline = {},
            onEndCall = {},
            onDismiss = {},
        )
    }
}
