package com.thalia.app.conversation

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Status colours, light and dark. Meaning is also in words on every card, never in colour alone. */
internal class StatusPalette(
    val container: Color,
    val border: Color,
    val chip: Color,
    val onChip: Color,
    val ink: Color,
)

@Composable
internal fun pendingPalette(): StatusPalette =
    if (isSystemInDarkTheme()) {
        StatusPalette(Color(0xFF332A10), Color(0xFFB8892B), Color(0xFF5C4710), Color(0xFFFBE6A9), Color(0xFFFFF1CC))
    } else {
        StatusPalette(Color(0xFFFFF8E4), Color(0xFFE2B13C), Color(0xFFFBE6A9), Color(0xFF5A3B00), Color(0xFF2A1F00))
    }

@Composable
internal fun savedPalette(): StatusPalette =
    if (isSystemInDarkTheme()) {
        StatusPalette(Color(0xFF12301D), Color(0xFF3F8A5B), Color(0xFF1F5133), Color(0xFFC9ECD5), Color(0xFFE2F5E8))
    } else {
        StatusPalette(Color(0xFFEAF7EE), Color(0xFF5DB57A), Color(0xFFC9ECD5), Color(0xFF0F4A28), Color(0xFF0B2E18))
    }

@Composable
internal fun statusPalette(): StatusPalette =
    if (isSystemInDarkTheme()) {
        StatusPalette(Color(0xFF1B2B29), Color(0xFF3B5C58), Color(0xFF1E3A38), Color(0xFFBFE3DD), Color(0xFFE0F0ED))
    } else {
        StatusPalette(Color(0xFFFFFFFF), Color(0xFFD5DFDD), Color(0xFFE4EFEC), Color(0xFF1B4F4C), Color(0xFF16282C))
    }

/** A dashed rounded outline, used for the model's words beside a decision and for functions that are not built yet. */
internal fun Modifier.dashedOutline(
    color: Color,
    radius: Float = 14f,
): Modifier =
    drawBehind {
        drawRoundRect(
            color,
            cornerRadius = CornerRadius(radius.dp.toPx()),
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 14f))),
        )
    }

@Composable
private fun Chip(
    text: String,
    background: Color,
    content: Color,
) {
    Surface(color = background, shape = RoundedCornerShape(50)) {
        Text(
            text,
            color = content,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}

@Composable
internal fun TranscriptRow(
    row: RowView,
    onUndo: (Int) -> Unit,
    onNextStep: (NextStep) -> Unit,
) {
    when (row.style) {
        RowStyle.YOU ->
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
                Text(row.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp)) {
                    Text(row.lines.joinToString(" "), modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp))
                }
            }
        RowStyle.REPLY -> ReplyCard(row)
        RowStyle.EXPLANATION -> ExplanationCard(row.label, row.lines.joinToString(" "))
        RowStyle.PROPOSED ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(row.lines.joinToString(" "))
                row.explanation?.let { ExplanationCard(Presentation.LABEL_EXPLANATION, it) }
            }
        RowStyle.SAVED -> SavedCard(row, onUndo)
        RowStyle.NOT_APPLIED -> NotAppliedCard(row, onNextStep)
        RowStyle.STATUS, RowStyle.NOTE, RowStyle.DAY_SCREEN ->
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Text(row.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                row.lines.forEach { Text(it) }
                if (row.style == RowStyle.DAY_SCREEN) {
                    TextButton(onClick = { onNextStep(NextStep.PLANNING) }) { Text("Open the day screen") }
                }
            }
    }
}

/** A plain reply from Thalia. Kotlin's status line, when there is one, comes first and is the louder of the two. */
@Composable
private fun ReplyCard(row: RowView) {
    val palette = statusPalette()
    Surface(
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, palette.border),
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            row.status?.let {
                Surface(color = palette.chip, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(
                        it,
                        color = palette.onChip,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(row.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                row.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge) }
            }
        }
    }
}

/** The model's reasons beside a decision: smaller, dashed, labelled, and never the status. */
@Composable
internal fun ExplanationCard(
    label: String,
    text: String,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .dashedOutline(MaterialTheme.colorScheme.outline)
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .semantics(mergeDescendants = true) {},
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun FactBox(
    label: String,
    value: String,
    detail: String?,
    palette: StatusPalette,
    modifier: Modifier = Modifier,
    boxed: Boolean = false,
) {
    val content: @Composable () -> Unit = {
        Column(modifier = Modifier.padding(if (boxed) 12.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = palette.onChip)
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = palette.ink)
            detail?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = palette.ink) }
        }
    }
    if (boxed) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, palette.border),
            shape = RoundedCornerShape(14.dp),
            modifier = modifier,
        ) { content() }
    } else {
        Column(modifier = modifier) { content() }
    }
}

/** The waiting proposal. Everything that would change is inspectable here, and Approve and Dismiss are the only ways on. */
@Composable
internal fun PendingCardView(
    pending: Pending,
    busy: Boolean,
    onApprove: () -> Unit,
    onDismiss: () -> Unit,
) {
    val palette = pendingPalette()
    val card = Presentation.pendingCard(pending)
    Surface(
        color = palette.container,
        border = BorderStroke(2.dp, palette.border),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(
                modifier =
                    Modifier.semantics(mergeDescendants = true) {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = card.spoken
                    },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Chip("Waiting for your approval", palette.chip, palette.onChip)
                    Text("Not set yet", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = palette.ink)
                }
                Text(
                    card.headline,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = palette.ink,
                    modifier = Modifier.semantics { heading() },
                )
                card.about?.let { FactBox(it.label, it.value, it.detail, palette) }
                if (card.when_.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        card.when_.forEach { FactBox(it.label, it.value, it.detail, palette, Modifier.weight(1f), boxed = true) }
                    }
                }
                card.lines.forEach { Text(it, color = palette.ink) }
            }
            val approve: @Composable (Modifier) -> Unit = { modifier ->
                Button(
                    enabled = !busy,
                    onClick = onApprove,
                    shape = RoundedCornerShape(14.dp),
                    modifier = modifier.heightIn(min = 52.dp).semantics { contentDescription = "Approve: ${card.approveLabel}" },
                ) { Text("Approve", style = APPROVAL_LABEL) }
            }
            val dismiss: @Composable (Modifier) -> Unit = { modifier ->
                OutlinedButton(
                    enabled = !busy,
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                    modifier = modifier.heightIn(min = 52.dp).semantics { contentDescription = "Dismiss: apply nothing" },
                ) { Text("Dismiss", style = APPROVAL_LABEL) }
            }
            // Side by side while the narrower button can hold its label on one line, stacked at full width when it cannot
            // (large text), so neither label ever breaks mid-word. Approve stays first either way.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val dismissLabelPx = measurer.measure("Dismiss", APPROVAL_LABEL).size.width
                val dismissNeeds = with(density) { dismissLabelPx.toDp() } + BUTTON_SIDE_PADDING * 2
                if ((maxWidth - APPROVAL_GAP) / (APPROVE_WEIGHT + 1f) >= dismissNeeds) {
                    Row(horizontalArrangement = Arrangement.spacedBy(APPROVAL_GAP)) {
                        approve(Modifier.weight(APPROVE_WEIGHT))
                        dismiss(Modifier.weight(1f))
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(APPROVAL_GAP)) {
                        approve(Modifier.fillMaxWidth())
                        dismiss(Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

private val APPROVAL_LABEL = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold)
private val APPROVAL_GAP = 10.dp
private val BUTTON_SIDE_PADDING = 24.dp
private const val APPROVE_WEIGHT = 1.4f

@Composable
private fun SavedCard(
    row: RowView,
    onUndo: (Int) -> Unit,
) {
    val palette = savedPalette()
    Surface(
        color = palette.container,
        border = BorderStroke(2.dp, palette.border),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(
                modifier = Modifier.semantics(mergeDescendants = true) { row.spoken?.let { contentDescription = "Saved. $it" } },
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Chip("Saved", palette.chip, palette.onChip)
                row.title?.let {
                    Text(
                        it,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = palette.ink,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                if (row.facts.isNotEmpty()) {
                    val about = row.facts.firstOrNull { it.first == "About" }
                    about?.let { FactBox(it.first, it.second, null, palette) }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.facts.filter { it.first != "About" }.forEach {
                            FactBox(
                                it.first,
                                it.second,
                                null,
                                palette,
                                Modifier.weight(1f),
                                boxed = true,
                            )
                        }
                    }
                }
                row.lines.forEach { Text(it, color = palette.ink) }
            }
            row.undoEntryId?.let { id ->
                OutlinedButton(
                    onClick = { onUndo(id) },
                    shape = RoundedCornerShape(14.dp),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(
                                min = 52.dp,
                            ).semantics { row.undoDescription?.let { contentDescription = it } },
                ) { Text("Undo", fontSize = 17.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}

@Composable
private fun NotAppliedCard(
    row: RowView,
    onNextStep: (NextStep) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        color = colors.errorContainer,
        border = BorderStroke(2.dp, colors.error),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(modifier = Modifier.semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("Not applied", colors.error, colors.onError)
                row.lines.forEach { Text(it, color = colors.onErrorContainer) }
                row.nextStepHint?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.onErrorContainer) }
            }
            row.nextStep?.label?.let { label ->
                OutlinedButton(onClick = { onNextStep(row.nextStep) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(label) }
            }
        }
    }
}

/** Shown while a request is out. Nothing has changed yet, and a screen reader is told so. */
@Composable
internal fun WorkingRow() {
    Column(
        modifier =
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) {
                liveRegion = LiveRegionMode.Polite
                contentDescription = "Thalia is working on your message. Nothing has changed yet."
            },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Working on it. Nothing has changed yet.", style = MaterialTheme.typography.bodyMedium)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }
}

/** Spoken-reply controls that do not exist yet. Visibly unbuilt: dashed, disabled, tagged, and described as not built. */
@Composable
internal fun FutureSpeechRow() {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = Modifier.fillMaxWidth().dashedOutline(MaterialTheme.colorScheme.outline, 12f).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Spoken replies", style = MaterialTheme.typography.labelLarge, color = muted)
            Text("FUTURE, NOT BUILT", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = false,
                onClick = {},
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Stop spoken reply. Not built yet." },
            ) { Text("Stop") }
            OutlinedButton(
                enabled = false,
                onClick = {},
                modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Mute spoken replies. Not built yet." },
            ) { Text("Mute") }
        }
    }
}

/** Stop, mute and the reason the last reply was not spoken. Quiet when there is nothing to say. */
@Composable
internal fun SpeechRow(speaker: SpokenReplyController) {
    val ui by speaker.ui.collectAsStateWithLifecycle()
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (ui.speaking) {
                OutlinedButton(
                    onClick = speaker::stop,
                    modifier = Modifier.heightIn(min = 44.dp).semantics { contentDescription = "Stop the spoken reply" },
                ) { Text("Stop") }
            }
            OutlinedButton(
                onClick = { speaker.setMuted(!ui.muted) },
                modifier =
                    Modifier.heightIn(min = 44.dp).semantics {
                        contentDescription = if (ui.muted) "Spoken replies are muted. Unmute them" else "Mute spoken replies"
                    },
            ) { Text(if (ui.muted) "Unmute" else "Mute") }
            if (ui.speaking) Text("Speaking...", style = MaterialTheme.typography.bodySmall)
        }
        ui.note?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}
