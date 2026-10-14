package com.thalia.app.ui

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The user manual, opened from Settings. Its words are in [ManualContent]; this file only draws them. */
class ManualActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ThaliaTheme { ManualScreen(onClose = ::finish) } }
    }
}

/** The text with every `[[Label]]` drawn bold and the brackets removed. */
internal fun manualText(raw: String): AnnotatedString =
    buildAnnotatedString {
        var rest = raw
        while (true) {
            val open = rest.indexOf("[[")
            val close = if (open >= 0) rest.indexOf("]]", open) else -1
            if (open < 0 || close < 0) {
                append(rest)
                break
            }
            append(rest.substring(0, open))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(rest.substring(open + 2, close)) }
            rest = rest.substring(close + 2)
        }
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ManualScreen(onClose: () -> Unit) {
    val sections = ManualContent.sections
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        LazyColumn(
            state = list,
            modifier = Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "How to use Thalia",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    OutlinedButton(onClick = onClose, modifier = Modifier.heightIn(min = 44.dp)) { Text("Close") }
                }
            }
            item {
                Text("Jump to a section:", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    sections.forEachIndexed { index, section ->
                        AssistChip(
                            onClick = { scope.launch { list.animateScrollToItem(index + FIXED_ITEMS) } },
                            label = { Text(section.chip) },
                            modifier = Modifier.heightIn(min = 48.dp),
                        )
                    }
                }
            }
            itemsIndexed(sections, key = { _, section -> section.id }) { _, section -> SectionCard(section) }
            item { Text("", modifier = Modifier.padding(bottom = 16.dp)) }
        }
    }
}

/** The header row and the contents chips come before the first section. */
private const val FIXED_ITEMS = 2

@Composable
private fun SectionCard(section: ManualSection) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
            section.blocks.forEach { block ->
                when (block) {
                    is Block.Text -> Text(manualText(block.text), style = MaterialTheme.typography.bodyMedium)
                    is Block.Bullets ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            block.items.forEach { Text(manualText("· $it"), style = MaterialTheme.typography.bodyMedium) }
                        }
                    is Block.Example ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(block.label, style = MaterialTheme.typography.labelLarge)
                                block.lines.forEach { Text(manualText(it), style = MaterialTheme.typography.bodyMedium) }
                            }
                        }
                    is Block.Unfinished ->
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "NOT FINISHED",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                                Text(
                                    manualText(block.text),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                                )
                            }
                        }
                }
            }
        }
    }
}
