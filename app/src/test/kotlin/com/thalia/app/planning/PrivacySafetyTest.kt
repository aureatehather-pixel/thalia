package com.thalia.app.planning

import com.thalia.app.planning.data.GoalDeletion
import com.thalia.app.planning.data.InboxDeletion
import com.thalia.app.planning.data.NoteChange
import com.thalia.planning.ActiveDay
import java.io.File
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Permanent deletion is a direct tap behind a confirmation. No model, conversation, call or check-in source can reach it. */
class PrivacySafetyTest {
    private fun kotlinFiles(vararg roots: String) =
        roots.flatMap { root -> File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }

    private val deletions = listOf("clearDirection", "deleteGoal", "deleteFiledItem", "deleteClosingNote", "editClosingNote")

    @Test
    fun `only the screens and the store mention the deletions`() {
        val all = kotlinFiles("src/main", "../core/src/main", "../openai-provider/src", "../chatgpt-provider/src")
        assertTrue(all.size > 100, "the scan must see the sources, or it proves nothing")
        val hits = all.filter { f -> deletions.any { it in f.readText() } }.map { it.name }.sorted()
        assertEquals(listOf("InboxSection.kt", "PlanningActivity.kt", "PlanningDatabase.kt", "PlanningStore.kt", "PrivacyUi.kt"), hits)
    }

    @Test
    fun `no conversation, ai, call or check-in source reaches them`() {
        val sources =
            kotlinFiles(
                "src/main/kotlin/com/thalia/app/conversation",
                "src/main/kotlin/com/thalia/app/ai",
                "src/main/kotlin/com/thalia/app/checkin",
                "src/main/kotlin/com/thalia/app/voicecheckins",
                "../core/src/main/kotlin/com/thalia/conversation",
            )
        assertTrue(sources.size > 20)
        assertEquals(emptyList(), sources.filter { f -> deletions.any { it in f.readText() } }.map { it.name })
    }

    @Test
    fun `every deletion call sits behind the confirmation dialog`() {
        val life = File("src/main/kotlin/com/thalia/app/planning/PlanningActivity.kt").readText()
        val inbox = File("src/main/kotlin/com/thalia/app/planning/InboxSection.kt").readText()
        val notes = File("src/main/kotlin/com/thalia/app/planning/PrivacyUi.kt").readText()
        assertEquals(2, life.split("ConfirmDelete(").size - 1, "direction and goal")
        assertTrue("ConfirmDelete(" in inbox)
        assertTrue("ConfirmDelete(" in notes)
    }

    @Test
    fun `wording says permanent, keeps archive separate and names the blocked projects`() {
        val day = ActiveDay(LocalDate.of(2026, 10, 3))
        listOf(
            PrivacyText.CLEAR_DIRECTION_BODY,
            PrivacyText.deleteGoalBody("G"),
            PrivacyText.deleteItemBody(false),
            PrivacyText.deleteNoteBody(day),
        ).forEach { assertTrue("cannot be brought back" in it, it) }
        assertTrue("achieved or dropped" in PrivacyText.deleteGoalBody("G"))
        assertFalse("No project is linked" in PrivacyText.deleteGoalBody("G"), "the dialog comes before the check, so it must not claim it")
        assertTrue("still linked" in PrivacyText.deleteGoalBody("G"))
        assertTrue("Sat 3 Oct 2026" in PrivacyText.deleteNoteBody(day))
        val blocked = PrivacyText.goalDeletion(GoalDeletion.Blocked(listOf("Course", "Practice")))
        assertTrue("2 projects: Course, Practice" in blocked && "Nothing was changed" in blocked, blocked)
        assertTrue("1 project:" in PrivacyText.goalDeletion(GoalDeletion.Blocked(listOf("Course"))))
        assertTrue("became an action" in PrivacyText.itemDeletion(InboxDeletion.BecameAction))
        assertTrue("still in the Inbox" in PrivacyText.itemDeletion(InboxDeletion.StillOpen))
        assertTrue("2 reminders for it keep" in PrivacyText.itemDeletion(InboxDeletion.Deleted(2)))
        assertFalse("reminder" in PrivacyText.itemDeletion(InboxDeletion.Deleted(0)))
        assertTrue("changed since" in PrivacyText.noteChange(NoteChange.STALE, "edit"))
    }

    @Test
    fun `a pending deletion never survives recreation`() {
        // rememberSaveable would bring a half-made deletion back after rotation or a font change, in the wrong place.
        listOf("InboxSection.kt", "PrivacyUi.kt").forEach { name ->
            val text = File("src/main/kotlin/com/thalia/app/planning/$name").readText()
            assertFalse(Regex("""deleting by rememberSaveable""").containsMatchIn(text), name)
        }
        val life = File("src/main/kotlin/com/thalia/app/planning/PlanningActivity.kt").readText()
        assertFalse(Regex("""editing by remember \{[^\n]*\n\s*var confirming by rememberSaveable""").containsMatchIn(life))
    }

    @Test
    fun `empty hints invent nothing and point at manual entry`() {
        listOf(PrivacyText.DIRECTION_HINT, PrivacyText.GOALS_HINT, PrivacyText.PROJECTS_HINT).forEach {
            assertTrue(it.length < 260, "hints stay short")
            assertFalse(it.contains("import", ignoreCase = true) || it.contains("ChatGPT"))
        }
    }
}
