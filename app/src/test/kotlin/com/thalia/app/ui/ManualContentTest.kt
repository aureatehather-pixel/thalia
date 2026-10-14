package com.thalia.app.ui

import androidx.compose.ui.text.font.FontWeight
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The manual says what the app does: every named control exists, the hard rules are stated, and nothing priced or invented slips in. */
class ManualContentTest {
    private val sections = ManualContent.sections

    private fun strings(block: Block): List<String> =
        when (block) {
            is Block.Text -> listOf(block.text)
            is Block.Bullets -> block.items
            is Block.Example -> listOf(block.label) + block.lines
            is Block.Unfinished -> listOf(block.text)
        }

    private val all: List<String> = sections.flatMap { s -> listOf(s.title) + s.blocks.flatMap(::strings) }
    private val joined = all.joinToString("\n")

    private val sources: String by lazy {
        File("src/main")
            .walkTopDown()
            .filter {
                it.isFile && (it.extension == "kt") && it.name != "ManualContent.kt"
            }.joinToString("\n") { it.readText() }
    }

    @Test
    fun `every marked label exists in the app's own strings`() {
        assertTrue(sources.length > 100_000, "the scan must see the sources, or it proves nothing")
        val labels = Regex("""\[\[(.+?)]]""").findAll(joined).map { it.groupValues[1] }.toSet()
        assertTrue(labels.size > 30, "the manual names many controls")
        val missing = labels.filter { "\"$it" !in sources }
        assertEquals(emptyList(), missing, "renamed or removed controls: update the manual")
    }

    @Test
    fun `sections are unique, filled and cover the agreed topics`() {
        assertEquals(sections.size, sections.map { it.id }.toSet().size)
        assertTrue(sections.all { it.title.isNotBlank() && it.blocks.isNotEmpty() })
        assertTrue(sections.all { it.chip.length in 3..14 }, "contents chips stay short")
        listOf("start", "loop", "enter", "plan", "outcomes", "calls", "thalia", "ai", "voice", "tokens", "privacy", "later")
            .forEach { id -> assertTrue(sections.any { it.id == id }, id) }
        assertTrue(sections.flatMap { it.blocks }.any { it is Block.Unfinished }, "unfinished things are marked as such")
        assertTrue(sections.flatMap { it.blocks }.any { it is Block.Example }, "small examples")
    }

    @Test
    fun `the approval rule is stated plainly`() {
        assertTrue("only tapping [[Approve]] applies" in joined)
        assertTrue(Regex("""\"yes\" changes nothing""").containsMatchIn(joined))
        assertTrue("Dismiss" in joined)
    }

    @Test
    fun `no provider price, currency figure, key or em dash is written into the manual`() {
        assertFalse(Regex("""\$|USD|\bRM\s?\d|\bcents?\b|per million|per token""", RegexOption.IGNORE_CASE).containsMatchIn(joined))
        assertFalse("sk_" in joined || "AIza" in joined)
        assertFalse('—' in joined, "no em dashes")
        assertTrue("not the provider's billing" in joined && "not the provider's invoice" in joined)
    }

    @Test
    fun `it does not promise an import, memory, caching or retries as features`() {
        val promises = Regex("""(automatically imports|will remember|remembers your|caches|retries (a|the) )""", RegexOption.IGNORE_CASE)
        assertFalse(promises.containsMatchIn(joined))
        assertTrue("Automatic import from ChatGPT" in joined, "the not-built list names it")
        assertTrue("have not yet been checked against the real speech service" in joined, "live speech is stated as unverified")
        assertTrue("There is no retry and no stored audio" in joined)
    }

    @Test
    fun `labels are drawn bold with the brackets removed`() {
        val text = manualText("Tap [[Approve]] now and [[Dismiss]].")
        assertEquals("Tap Approve now and Dismiss.", text.text)
        assertEquals(2, text.spanStyles.size)
        assertTrue(text.spanStyles.all { it.item.fontWeight == FontWeight.Bold })
        assertEquals("Approve", text.text.substring(text.spanStyles[0].start, text.spanStyles[0].end))
        assertEquals("no marks", manualText("no marks").text)
        assertEquals("open [[ stays", manualText("open [[ stays").text)
    }

    @Test
    fun `settings opens the manual and the manifest registers it`() {
        val settings = File("src/main/kotlin/com/thalia/app/ui/SettingsActivity.kt").readText()
        assertTrue("ManualActivity::class.java" in settings && "How to use Thalia" in settings)
        assertTrue(".ui.ManualActivity" in File("src/main/AndroidManifest.xml").readText())
    }
}
