package com.thalia.app.planning

import com.thalia.app.planning.data.UndoRefusal
import com.thalia.app.planning.data.UndoResult
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Undo of an approved plan is a direct tap on the Plan tab and nothing else. The conversation, the model providers and
 * the call and guided check-in code never reach it, so a model, a typed "yes" or a spoken "yes" cannot trigger it.
 * Checked at the source level, like [com.thalia.app.voicecheckins.call.CallAnswerSafetyTest].
 */
class PlanUndoSafetyTest {
    private fun kotlinFiles(vararg roots: String) =
        roots.flatMap { root ->
            File(root)
                .walkTopDown()
                .filter { it.isFile && it.extension == "kt" }
                .toList()
        }

    @Test
    fun `only the plan section and the store mention undoApproval`() {
        val all = kotlinFiles("src/main", "../core/src/main", "../openai-provider/src", "../chatgpt-provider/src")
        assertTrue(all.size > 100, "the scan must see the sources, or it proves nothing")
        val hits = all.filter { "undoApproval" in it.readText() }.map { it.name }.sorted()
        assertEquals(listOf("PlanDaySection.kt", "PlanningStore.kt", "PlanningWrites.kt"), hits)
    }

    @Test
    fun `no conversation, call or check-in source reaches the undo path`() {
        val sources =
            kotlinFiles(
                "src/main/kotlin/com/thalia/app/conversation",
                "src/main/kotlin/com/thalia/app/ai",
                "src/main/kotlin/com/thalia/app/checkin",
            )
        assertTrue(sources.size > 10, "the scan must see those packages")
        val hits =
            sources
                .filter { file ->
                    listOf("undoApproval", "UndoResult", "restoredRows").any { it in file.readText() }
                }.map { it.name }
        assertEquals(emptyList(), hits)
    }

    @Test
    fun `the unavailable and refusal wording gives the reason and never suggests clearing outcomes`() {
        val refused = undoText(UndoResult.Refused(UndoRefusal.OUTCOMES_RECORDED))
        for (text in listOf(UNDO_LOCKED_NOTE, refused)) {
            assertTrue("outcomes" in text, text)
            assertTrue(listOf("unknown", "clear", "reset", "set every").none { it in text.lowercase() }, text)
        }
    }
}
