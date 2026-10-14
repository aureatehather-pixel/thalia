package com.thalia.planning

import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlanningModelTest {
    @Test
    fun `text is accepted only trimmed, not blank and within its limit`() {
        Direction("Build a calm, useful life")
        Direction("x".repeat(PlanningText.DIRECTION_MAX))

        assertFailsWith<IllegalArgumentException> { Direction("") }
        assertFailsWith<IllegalArgumentException> { Direction("   ") }
        assertFailsWith<IllegalArgumentException> { Direction(" padded") }
        assertFailsWith<IllegalArgumentException> { Direction("x".repeat(PlanningText.DIRECTION_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { Goal("g", "x".repeat(PlanningText.TITLE_MAX + 1), "done") }
        assertFailsWith<IllegalArgumentException> { Goal("g", "Ship", "x".repeat(PlanningText.SUCCESS_CONDITION_MAX + 1)) }
        assertFailsWith<IllegalArgumentException> { Goal("g", "Ship", " ") }
        assertFailsWith<IllegalArgumentException> { Project("p", "") }
        assertFailsWith<IllegalArgumentException> { Action("a", "\t") }
    }

    @Test
    fun `clean trims what the user typed`() {
        assertEquals("Read a chapter", PlanningText.clean("  Read a chapter \n"))
    }

    @Test
    fun `an action needs only a title, and is then standalone, normal, thirty minutes and open`() {
        val action = Action("a", "Book flights")

        assertNull(action.projectId)
        assertEquals(Priority.NORMAL, action.priority)
        assertEquals(30, action.estimateMinutes)
        assertNull(action.dueDate)
        assertEquals(ActionStatus.OPEN, action.status)
    }

    @Test
    fun `an estimate must be from five minutes to twelve hours`() {
        Action("a", "Short", estimateMinutes = 5)
        Action("a", "Long", estimateMinutes = 720)

        assertFailsWith<IllegalArgumentException> { Action("a", "Too short", estimateMinutes = 4) }
        assertFailsWith<IllegalArgumentException> { Action("a", "Too long", estimateMinutes = 721) }
        assertFailsWith<IllegalArgumentException> { Action("a", "Negative", estimateMinutes = -30) }
    }

    @Test
    fun `ids and links must not be blank`() {
        assertFailsWith<IllegalArgumentException> { Goal(" ", "Ship", "Done") }
        assertFailsWith<IllegalArgumentException> { Project("p", "Book", goalId = "") }
        assertFailsWith<IllegalArgumentException> { Action("a", "Draft", projectId = " ") }
    }

    @Test
    fun `a project defaults to planned with no goal`() {
        val project = Project("p", "Write the book")

        assertNull(project.goalId)
        assertEquals(ProjectState.PLANNED, project.state)
    }

    @Test
    fun `only a completed, abandoned or archived project refuses actions`() {
        val closed = setOf(ProjectState.COMPLETED, ProjectState.ABANDONED, ProjectState.ARCHIVED)
        for (state in ProjectState.entries) {
            assertEquals(state !in closed, state.acceptsActions, "$state")
        }
    }

    @Test
    fun `settings default to a 4am rollover and no window until the user chooses one`() {
        val settings = PlanningSettings()

        assertEquals(LocalTime.of(4, 0), settings.rollover)
        assertNull(settings.window)
        assertEquals(PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)), PlanningSettings.SUGGESTED_WINDOW)
    }

    @Test
    fun `a window must lie inside the active day, and may cross midnight but not the rollover`() {
        fun ok(
            start: Int,
            end: Int,
            rollover: Int = 4,
        ) = runCatching {
            PlanningSettings(LocalTime.of(rollover, 0), PlanningWindow(LocalTime.of(start, 0), LocalTime.of(end, 0)))
        }.isSuccess

        assertTrue(ok(12, 22))
        assertTrue(ok(18, 1), "crossing midnight is inside the day")
        assertTrue(ok(4, 3), "from the rollover to the hour before the next one")
        assertTrue(ok(9, 4), "ending exactly at the next rollover")
        assertFalse(ok(3, 5), "crossing the rollover leaves the day")
        assertFalse(ok(22, 12), "ending before it starts")
        assertFalse(ok(12, 12), "empty")
        assertFalse(ok(1, 3, rollover = 2), "starting before the rollover and ending after it")
        assertTrue(ok(1, 3, rollover = 0))
    }

    @Test
    fun `times are whole minutes`() {
        assertFailsWith<IllegalArgumentException> { PlanningSettings(LocalTime.of(4, 0, 30)) }
        assertFailsWith<IllegalArgumentException> { PlanningWindow(LocalTime.of(12, 0, 1), LocalTime.of(22, 0)) }
    }
}
