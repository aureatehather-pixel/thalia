package com.thalia.app.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.planning.PlanningActivity

/**
 * The four tabs of the approved V2 design. Today, Plan and Life are three tabs of [PlanningActivity], which holds the planning
 * data. Thalia is the conversation, which is its own activity because it carries a session, a draft and call hand-offs.
 */
enum class ThaliaTab(
    val label: String,
    val iconPath: String,
) {
    TODAY(
        "Today",
        "M12 8a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4",
    ),
    PLAN(
        "Plan",
        "M5 5h14a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7a2 2 0 0 1 2-2zM3 10h18M8 3v4M16 3v4M9 15l2 2 4-4",
    ),
    LIFE("Life", "M12 3a9 9 0 1 0 0 18a9 9 0 1 0 0-18zM15.5 8.5l-2 5-5 2 2-5z"),
    THALIA("Thalia", "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"),
    ;

    companion object {
        const val EXTRA = "com.thalia.app.TAB"

        fun of(name: String?): ThaliaTab? = entries.firstOrNull { it.name == name }

        fun from(intent: Intent?): ThaliaTab? = of(intent?.getStringExtra(EXTRA))
    }
}

/**
 * The intent that shows [tab]. Thalia is the conversation; the other three are the planning host, brought back and told which tab
 * to show if it is still below, started if not.
 */
internal fun tabIntent(
    context: Context,
    tab: ThaliaTab,
): Intent =
    Intent(context, if (tab == ThaliaTab.THALIA) ConversationActivity::class.java else PlanningActivity::class.java)
        .putExtra(ThaliaTab.EXTRA, tab.name)
        .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Goes to a tab from an activity that does not host it. Nothing is written by moving between tabs. */
fun Activity.goToTab(tab: ThaliaTab) = startActivity(tabIntent(this, tab))

private fun strokeIcon(path: String): ImageVector =
    ImageVector
        .Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = PathParser().parsePathString(path).toNodes(),
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ).build()

private const val PROFILE_PATH = "M12 5a4 4 0 1 0 0 8a4 4 0 1 0 0-8zM4.5 21a7.5 7.5 0 0 1 15 0"

/** The screen's frame: the tab's content above, the four tabs below. The tabs step aside while the keyboard is open. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThaliaShell(
    selected: ThaliaTab,
    onSelect: (ThaliaTab) -> Unit,
    content: @Composable () -> Unit,
) {
    val keyboard = WindowInsets.isImeVisible
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // The tab bar already clears the system navigation bar, so the content above it must not pad for it a second time.
        Box(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .then(if (keyboard) Modifier else Modifier.consumeWindowInsets(WindowInsets.navigationBars)),
        ) { content() }
        if (!keyboard) ThaliaNavBar(selected, onSelect, Modifier.navigationBarsPadding())
    }
}

@Composable
fun ThaliaNavBar(
    selected: ThaliaTab,
    onSelect: (ThaliaTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // The labels stop growing at 1.3 times, so four tabs always fit on one row.
    val capped = Density(density.density, fontScale = minOf(density.fontScale, 1.3f))
    CompositionLocalProvider(LocalDensity provides capped) {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, modifier = modifier.fillMaxWidth()) {
            Column {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    ThaliaTab.entries.forEach { tab -> NavItem(tab, tab == selected, onSelect, Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: ThaliaTab,
    selected: Boolean,
    onSelect: (ThaliaTab) -> Unit,
    modifier: Modifier,
) {
    val icon = remember(tab) { strokeIcon(tab.iconPath) }
    val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier =
            modifier
                .heightIn(min = 52.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                .selectable(selected = selected, role = Role.Tab, onClick = { onSelect(tab) })
                .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold,
            color = if (selected) MaterialTheme.colorScheme.onSurface else tint,
        )
    }
}

/** A tab's title, an optional line under it, and the profile button that opens Settings. [trailing] sits beside the button. */
@Composable
fun ThaliaTopBar(
    title: String,
    subtitle: String?,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        trailing()
        ProfileButton(onSettings)
    }
}

@Composable
fun ProfileButton(onClick: () -> Unit) {
    val icon = remember { strokeIcon(PROFILE_PATH) }
    Box(
        modifier =
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .border(1.5.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                .clickable(role = Role.Button, onClick = onClick)
                .semantics { contentDescription = "Open settings" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(22.dp))
    }
}

/** A dashed outline, as the design uses for anything that is not a real, saved thing. */
fun Modifier.dashedBorder(
    color: Color,
    radius: androidx.compose.ui.unit.Dp = 14.dp,
): Modifier =
    drawBehind {
        drawRoundRect(
            color = color,
            cornerRadius = CornerRadius(radius.toPx()),
            style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
        )
    }

/**
 * Says plainly that part of the approved design is not built yet. It holds no data and no sample content: nothing on it can be
 * mistaken for the person's own planning data.
 */
@Composable
fun NotBuiltCard(
    title: String,
    parts: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().dashedBorder(MaterialTheme.colorScheme.outline).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(6.dp)) {
            Text(
                "NOT BUILT YET",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Text(title, style = MaterialTheme.typography.titleSmall)
        parts.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Text(
            "Approved in the V2 design, not built yet. Nothing on this card is your data.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
