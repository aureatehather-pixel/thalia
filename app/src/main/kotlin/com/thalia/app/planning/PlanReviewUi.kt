package com.thalia.app.planning

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.thalia.planning.Placement
import com.thalia.planning.Priority

/** From this font scale the bar keeps only Approve and the second choice moves into the page, so nothing is cut off. */
internal const val LARGE_TEXT_SCALE = 1.5f

@Composable
internal fun isLargeText(): Boolean = LocalDensity.current.fontScale >= LARGE_TEXT_SCALE

internal enum class PlanBarKind { PENDING, SAVED }

/** What the bar's buttons do, set by the section that owns the proposal. The model itself holds only comparable values. */
internal class PlanBarActions {
    var approve: () -> Unit = {}
    var secondary: () -> Unit = {}
}

/** The fixed bar above the tab bar: Approve with its second choice, or the saved line. Nothing here is a sample. */
internal data class PlanBarModel(
    val kind: PlanBarKind,
    val approveLabel: String,
    val approveEnabled: Boolean,
    val secondaryLabel: String,
    val savedText: String,
    val actions: PlanBarActions,
)

/** The bar, hidden while the keyboard is up so the field being typed in keeps its room. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanBarSlot(model: PlanBarModel?) {
    if (model != null && !WindowInsets.isImeVisible) PlanApproveBar(model)
}

@Composable
internal fun PlanApproveBar(
    model: PlanBarModel,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            when (model.kind) {
                PlanBarKind.PENDING -> {
                    Button(
                        enabled = model.approveEnabled,
                        onClick = { model.actions.approve() },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) { Text(model.approveLabel) }
                    if (!isLargeText()) {
                        OutlinedButton(
                            onClick = { model.actions.secondary() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) { Text(model.secondaryLabel) }
                    }
                }
                PlanBarKind.SAVED ->
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    ) {
                        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.padding(horizontal = 12.dp)) {
                            Text(model.savedText, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanBanner(
    replace: Boolean,
    dayText: String,
    revisionText: String,
    counts: ChangeCounts?,
) {
    Surface(
        color = if (replace) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        val ink = if (replace) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                if (replace) "Replaces your current plan" else "First plan for $dayText",
                style = MaterialTheme.typography.titleSmall,
                color = ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                if (replace) "$revisionText stays until you approve." else "Nothing is scheduled yet. Approve to save it.",
                style = MaterialTheme.typography.bodyMedium,
                color = ink,
            )
            if (replace && counts != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Chip("Unchanged ${counts.unchanged}")
                    Chip("Moved ${counts.moved}")
                    if (counts.length > 0) Chip("Length changed ${counts.length}")
                    Chip("New ${counts.added}")
                    Chip("Removed ${counts.removed}")
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(8.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/**
 * The capacity bar, on the planner's own basis: the target is for the queue only. Pins and fixed commitments are taken out of the
 * window first, which leaves the free time, and the queue's minutes are shown against the target inside that free time.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CapacityCard(
    review: PlanReview,
    captions: List<String>,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val fill = if (review.overTarget > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val marker = MaterialTheme.colorScheme.onSurface
    val free = review.free.coerceAtLeast(1)
    val fillFraction = (review.queued.toFloat() / free).coerceIn(0f, 1f)
    val targetFraction = (review.target.toFloat() / free).coerceIn(0f, 1f)
    ReviewSurface {
        Text("Capacity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.semantics { heading() })
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 12.dp)
                    .semantics {
                        contentDescription =
                            "${review.queued} minutes in the queue, target ${review.target} minutes, ${review.free} free minutes"
                    }.drawBehind { drawCapacity(track, fill, marker, fillFraction, targetFraction) },
        ) { Box(modifier = Modifier.heightIn(min = 12.dp)) }
        FlowRow(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text("${review.queued} min in the queue", style = MaterialTheme.typography.bodyMedium)
            Text("target ${review.target} · free ${review.free}", style = MaterialTheme.typography.bodyMedium)
        }
        if (review.overTarget > 0) {
            Text(
                "Must work puts the queue ${review.overTarget} min over the target, into the buffer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        capacityLines(review).forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        captions.forEach {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The figures behind the capacity bar, in plain words: what the window loses to pins and fixed commitments, and what the target covers. */
internal fun capacityLines(review: PlanReview): List<String> {
    val taken =
        if (review.occupied == 0) {
            "Nothing is pinned or fixed, so all ${review.window} min of the window are free."
        } else {
            val parts =
                listOfNotNull(
                    review.pinnedMinutes.takeIf { it > 0 }?.let { "pinned items take $it min" },
                    review.fixedMinutes.takeIf { it > 0 }?.let { "fixed commitments take $it min" },
                )
            val together = if (parts.size > 1) " Together they use ${review.occupied} min, overlaps counted once." else ""
            "Of the ${review.window} min window, ${parts.joinToString(" and ")}.$together That leaves ${review.free} min free."
        }
    return listOf(taken, "The target covers the queue only. Pinned and fixed time is not counted against it.")
}

private fun DrawScope.drawCapacity(
    track: Color,
    fill: Color,
    marker: Color,
    fillFraction: Float,
    targetFraction: Float,
) {
    val radius = CornerRadius(size.height / 2, size.height / 2)
    drawRoundRect(track, size = size, cornerRadius = radius)
    if (fillFraction > 0f) drawRoundRect(fill, size = Size(size.width * fillFraction, size.height), cornerRadius = radius)
    val x = size.width * targetFraction
    drawLine(marker, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
}

/**
 * The plan's blocks: timed ones (a pinned item, a fixed commitment) in time order, then the queue, which has an order and
 * no times. [removedLine] says what a replacement drops; [leftOutLines] are the planner's own words.
 */
@Composable
internal fun ReviewCard(
    header: @Composable () -> Unit,
    review: PlanReview,
    leftOutLines: List<String>,
    removedLine: String?,
    /** Set while a proposal is being adjusted: leaves an item out of it. Null shows the plan as it is, without controls. */
    onLeaveOut: ((String) -> Unit)? = null,
    /** Items left out in this adjustment, as (action id, title), each with a control to include it again. */
    leftOut: List<Pair<String, String>> = emptyList(),
    onInclude: (String) -> Unit = {},
) {
    ReviewSurface {
        header()
        if (review.timed.isEmpty() && review.queue.isEmpty()) {
            Text("Nothing is planned.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        review.timed.forEach { TimedRow(it, onLeaveOut) }
        if (review.queue.isNotEmpty()) {
            Text(
                if (review.timed.isEmpty()) "Queue, in order, no times" else "Then the queue, in order, no times",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.semantics { heading() },
            )
            review.queue.forEach { QueueRow(it, onLeaveOut) }
        }
        if (leftOut.isNotEmpty()) {
            Text("Left out of this proposal", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            leftOut.forEach { (id, title) -> LeftOutRow(title) { onInclude(id) } }
        }
        if (leftOutLines.isNotEmpty()) {
            Text("Left out", style = MaterialTheme.typography.labelLarge, modifier = Modifier.semantics { heading() })
            leftOutLines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        removedLine?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun TimedRow(
    block: ReviewBlock,
    onLeaveOut: ((String) -> Unit)?,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, line),
        modifier =
            Modifier.fillMaxWidth().then(
                if (block.fixed) Modifier.drawBehind { dashedFrame(line) } else Modifier,
            ),
    ) {
        Row(modifier = Modifier.padding(10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(block.timeText, style = MaterialTheme.typography.titleSmall)
                block.endText?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(block.title, style = MaterialTheme.typography.bodyLarge)
                if (!block.fixed) onLeaveOut?.let { LeaveOutChip(block.title) { it(block.key.removePrefix("item:")) } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (block.fixed) {
                        Tag("Fixed", MaterialTheme.colorScheme.surfaceContainerHigh)
                    } else {
                        block.priority?.let { Tag(it.words(), MaterialTheme.colorScheme.surfaceContainerHigh) }
                    }
                    Tag("${block.minutes} min", MaterialTheme.colorScheme.surfaceContainerHigh)
                    ChangeTag(block.change)
                    block.before?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun DrawScope.dashedFrame(color: Color) {
    drawRoundRect(
        color,
        style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
    )
}

@Composable
private fun QueueRow(
    item: ReviewQueued,
    onLeaveOut: ((String) -> Unit)?,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${item.position + 1}. ${item.title}", style = MaterialTheme.typography.bodyLarge)
            onLeaveOut?.let { LeaveOutChip(item.title) { it(item.key.removePrefix("item:")) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Tag(item.priority.words(), MaterialTheme.colorScheme.surfaceContainerHigh)
                Tag("${item.minutes} min", MaterialTheme.colorScheme.surfaceContainerHigh)
                when (item.placement) {
                    Placement.IN_BUFFER -> Tag("In buffer", MaterialTheme.colorScheme.tertiaryContainer)
                    Placement.DOES_NOT_FIT -> Tag("Does not fit", MaterialTheme.colorScheme.errorContainer)
                    else -> Unit
                }
                ChangeTag(item.change)
                item.before?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChangeTag(change: Change?) {
    when (change) {
        Change.NEW -> Tag("New", MaterialTheme.colorScheme.primaryContainer)
        Change.MOVED -> Tag("Moved", MaterialTheme.colorScheme.secondaryContainer)
        Change.LENGTH -> Tag("Length changed", MaterialTheme.colorScheme.secondaryContainer)
        Change.UNCHANGED -> Tag("Unchanged", MaterialTheme.colorScheme.surfaceContainerHigh)
        null -> Unit
    }
}

@Composable
private fun Tag(
    text: String,
    container: Color,
) {
    Surface(color = container, shape = RoundedCornerShape(6.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
    }
}

@Composable
internal fun ReviewSurface(content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

private fun Priority.words(): String = name.lowercase().replaceFirstChar { it.uppercase() }

/** Shown on a proposed block: it is Included, and tapping it leaves it out of this proposal only. */
@Composable
private fun LeaveOutChip(
    title: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.FilterChip(
        selected = true,
        onClick = onClick,
        label = { Text("Included") },
        modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Included. Leave out $title" },
    )
}

/** An item left out of this proposal, kept in view so it can be included again. Nothing here is saved. */
@Composable
private fun LeftOutRow(
    title: String,
    onInclude: () -> Unit,
) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().drawBehind { dashedFrame(line) },
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            androidx.compose.material3.FilterChip(
                selected = false,
                onClick = onInclude,
                label = { Text("Left out") },
                modifier = Modifier.heightIn(min = 48.dp).semantics { contentDescription = "Left out. Include $title" },
            )
        }
    }
}

/** "Why this plan": Kotlin's own explanation of the proposal on screen. Reading it changes and saves nothing. */
@Composable
internal fun ExplanationCard(lines: List<String>) {
    ReviewSurface {
        Text(
            "Why this plan",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        Text(
            "Written by Thalia's rules from the figures above. Nothing here is saved or approved.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
