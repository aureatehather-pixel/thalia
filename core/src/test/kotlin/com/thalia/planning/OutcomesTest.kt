package com.thalia.planning

import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Outcome transitions, the manual-status rule, eligibility, the adjustment lock and Unfinished membership. */
class OutcomesTest {
    /** What storage does around the pure functions, for one action with several plan items. */
    private class World(
        var status: ActionStatus = ActionStatus.OPEN,
        itemCount: Int = 3,
    ) {
        val items = (1..itemCount).map { "i$it" }
        val outcomes = mutableMapOf<String, PlanOutcome>()
        var lastNote = false
        private var clock = 0L

        fun record(
            item: String,
            result: OutcomeResult,
        ) {
            val effect =
                applyOutcome(item, result, ++clock, status, outcomes[item], outcomes.values.filter { it.planItemId != item })
            outcomes[item] = effect.outcome
            status = effect.status
            lastNote = effect.staysDoneNote
        }

        fun manualSave(saved: ActionStatus) {
            outcomesAfterManualSave(status, saved, outcomes.values.toList()).forEach { outcomes[it.planItemId] = it }
            status = saved
        }

        fun marker(item: String) = outcomes[item]?.closedAction
    }

    private fun world(
        status: ActionStatus = ActionStatus.OPEN,
        block: World.() -> Unit,
    ) = World(status).apply(block)

    @Test
    fun `done on an open action closes it and takes the marker`() {
        val w = world { record("i1", OutcomeResult.DONE) }
        assertEquals(ActionStatus.DONE, w.status)
        assertEquals(true, w.marker("i1"))
    }

    @Test
    fun `results other than done change no status, and unknown does not either`() {
        for (result in OutcomeResult.entries.filter { it != OutcomeResult.DONE }) {
            for (start in ActionStatus.entries) {
                val w = world(start) { record("i1", result) }
                assertEquals(start, w.status, "$result on $start")
                assertEquals(false, w.marker("i1"))
            }
        }
    }

    @Test
    fun `done on a done or dropped action is history, never refused, and keeps the status`() {
        for (start in listOf(ActionStatus.DONE, ActionStatus.DROPPED)) {
            val w = world(start) { record("i1", OutcomeResult.DONE) }
            assertEquals(start, w.status)
            assertEquals(false, w.marker("i1"))
            assertEquals(OutcomeResult.DONE, w.outcomes.getValue("i1").result)
        }
    }

    @Test
    fun `record done then correct to partial reopens the action`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                record("i1", OutcomeResult.PARTIAL)
            }
        assertEquals(ActionStatus.OPEN, w.status)
        assertEquals(false, w.marker("i1"))
        assertFalse(w.lastNote)
    }

    @Test
    fun `a closure made by hand survives a done then a correction away`() {
        val w =
            world {
                manualSave(ActionStatus.DONE)
                record("i1", OutcomeResult.DONE)
                record("i1", OutcomeResult.SKIPPED)
            }
        assertEquals(ActionStatus.DONE, w.status)
        assertEquals(false, w.marker("i1"))
        assertTrue(w.lastNote)
    }

    @Test
    fun `done then a manual drop then a correction leaves it dropped, and drop clears the marker`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                manualSave(ActionStatus.DROPPED)
            }
        assertEquals(false, w.marker("i1"))
        w.record("i1", OutcomeResult.PARTIAL)
        assertEquals(ActionStatus.DROPPED, w.status)
    }

    @Test
    fun `reopening and closing by hand clears the marker so a later correction cannot reopen`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                manualSave(ActionStatus.OPEN)
                manualSave(ActionStatus.DONE)
            }
        assertEquals(false, w.marker("i1"))
        w.record("i1", OutcomeResult.PARTIAL)
        assertEquals(ActionStatus.DONE, w.status)
    }

    @Test
    fun `a save that keeps the status touches no marker`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                manualSave(ActionStatus.DONE)
            }
        assertEquals(true, w.marker("i1"))
    }

    @Test
    fun `repeated done, correcting the closing result keeps the action done because another done remains`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                record("i2", OutcomeResult.DONE)
            }
        assertEquals(true, w.marker("i1"))
        assertEquals(false, w.marker("i2"))
        w.record("i1", OutcomeResult.PARTIAL)
        assertEquals(ActionStatus.DONE, w.status)
        assertEquals(false, w.marker("i1"))
        assertEquals(false, w.marker("i2"))
        assertTrue(w.lastNote, "a correction away from done that leaves the action done shows the note")
    }

    @Test
    fun `then correcting the remaining done leaves it done with the note`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                record("i2", OutcomeResult.DONE)
                record("i1", OutcomeResult.PARTIAL)
                record("i2", OutcomeResult.PARTIAL)
            }
        assertEquals(ActionStatus.DONE, w.status)
        assertTrue(w.lastNote)
    }

    @Test
    fun `done corrected to unknown reopens, and every outcome unknown lifts the adjustment lock`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                record("i2", OutcomeResult.BLOCKED)
            }
        assertTrue(adjustmentLocked(w.outcomes.values))
        w.record("i1", OutcomeResult.UNKNOWN)
        assertEquals(ActionStatus.OPEN, w.status)
        assertTrue(adjustmentLocked(w.outcomes.values))
        w.record("i2", OutcomeResult.UNKNOWN)
        assertFalse(adjustmentLocked(w.outcomes.values))
        assertFalse(adjustmentLocked(emptyList()))
    }

    @Test
    fun `re-recording done on the marked item keeps the marker and the status`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                record("i1", OutcomeResult.DONE)
            }
        assertEquals(true, w.marker("i1"))
        assertEquals(ActionStatus.DONE, w.status)
    }

    @Test
    fun `correcting an unmarked item to done on an action reopened by hand closes it again with the marker`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                manualSave(ActionStatus.OPEN)
                record("i1", OutcomeResult.DONE)
            }
        assertEquals(ActionStatus.DONE, w.status)
        assertEquals(true, w.marker("i1"))
    }

    @Test
    fun `after a manual reopen a new done on another item may close it, and then owns the closure`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                manualSave(ActionStatus.OPEN)
                record("i2", OutcomeResult.DONE)
            }
        assertEquals(ActionStatus.DONE, w.status)
        assertEquals(false, w.marker("i1"))
        assertEquals(true, w.marker("i2"))
        w.record("i1", OutcomeResult.PARTIAL)
        w.record("i2", OutcomeResult.PARTIAL)
        assertEquals(ActionStatus.OPEN, w.status, "the new done made the closure, so correcting it reopens once no done remains")
    }

    @Test
    fun `a correction never overrides a manual status change made after the outcome`() {
        val w =
            world {
                record("i1", OutcomeResult.DONE)
                manualSave(ActionStatus.OPEN)
                manualSave(ActionStatus.DONE)
                record("i1", OutcomeResult.PARTIAL)
            }
        assertEquals(ActionStatus.DONE, w.status)
    }

    @Test
    fun `a marker is refused on anything but done`() {
        assertFailsWith<IllegalArgumentException> { PlanOutcome("i1", OutcomeResult.PARTIAL, 0, closedAction = true) }
    }

    @Test
    fun `random sequences keep the invariants and never let an outcome reopen a hand-made closure`() {
        repeat(300) { seed ->
            val random = Random(seed)
            val w = World()
            var handClosed = false
            repeat(40) {
                val before = w.status
                when (random.nextInt(5)) {
                    0, 1, 2 -> {
                        w.record(w.items.random(random), OutcomeResult.entries.random(random))
                        if (handClosed) assertEquals(before, w.status, "seed $seed: an outcome moved a hand-made status")
                        if (before == ActionStatus.DROPPED) assertEquals(ActionStatus.DROPPED, w.status)
                    }
                    else -> {
                        val saved = ActionStatus.entries.random(random)
                        w.manualSave(saved)
                        handClosed = saved == ActionStatus.DONE && before != ActionStatus.DONE || handClosed && saved == ActionStatus.DONE
                        if (saved != ActionStatus.DONE) handClosed = false
                    }
                }
                val marked = w.outcomes.values.filter { it.closedAction }
                assertTrue(marked.all { it.result == OutcomeResult.DONE }, "seed $seed: marker off DONE")
                assertTrue(marked.size <= 1, "seed $seed: two markers")
                assertTrue(marked.isEmpty() || w.status == ActionStatus.DONE, "seed $seed: marker while not done")
            }
        }
    }

    // --- eligibility and Unfinished ---

    private val day = ActiveDay(LocalDate.of(2026, 10, 5))

    private fun plan(
        id: String,
        on: ActiveDay,
        revision: Int = 1,
    ) = DayPlan(
        id,
        on,
        revision,
        ZoneId.of("UTC"),
        LocalTime.of(4, 0),
        PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)),
        600,
        480,
        0,
        0,
    )

    private fun item(
        id: String,
        planId: String,
        actionId: String,
        kind: ItemKind,
    ) = PlanItem(
        id,
        planId,
        actionId,
        kind,
        if (kind == ItemKind.PINNED) LocalTime.of(14, 0) else null,
        30,
        0,
        if (kind == ItemKind.QUEUED) Placement.WITHIN_TARGET else null,
        if (kind == ItemKind.LEFT_OUT) LeftOutReason.OVER_TARGET else null,
        actionId,
        Priority.NORMAL,
        null,
        null,
        null,
    )

    @Test
    fun `only pinned or queued items of the latest revision of a day that is today or earlier are eligible`() {
        val p = plan("p", day, revision = 2)
        assertTrue(canRecordOutcome(item("a", "p", "x", ItemKind.PINNED), p, 2, day))
        assertTrue(canRecordOutcome(item("a", "p", "x", ItemKind.QUEUED), p, 2, day.plusDays(3)))
        assertFalse(canRecordOutcome(item("a", "p", "x", ItemKind.LEFT_OUT), p, 2, day))
        assertFalse(canRecordOutcome(item("a", "p", "x", ItemKind.QUEUED), p, 3, day), "earlier revision")
        assertFalse(canRecordOutcome(item("a", "p", "x", ItemKind.QUEUED), p, 2, day.plusDays(-1)), "future day")
        assertFalse(canRecordOutcome(item("a", "other", "x", ItemKind.QUEUED), p, 2, day), "item of another plan")
    }

    private fun o(
        id: String,
        r: OutcomeResult,
    ) = id to PlanOutcome(id, r, 0)

    @Test
    fun `unfinished is by status, outcomes only annotate, and one action appears once from its latest day`() {
        val d1 = plan("p1", day.plusDays(-2))
        val d2 = plan("p2", day.plusDays(-1))
        val plans =
            listOf(
                DayPlanWithItems(
                    d1,
                    listOf(
                        item("a-d1", "p1", "a", ItemKind.QUEUED),
                        item("b-d1", "p1", "b", ItemKind.QUEUED),
                        item("c-d1", "p1", "c", ItemKind.PINNED),
                        item("e-d1", "p1", "e", ItemKind.QUEUED),
                    ),
                ),
                DayPlanWithItems(
                    d2,
                    listOf(
                        item("a-d2", "p2", "a", ItemKind.QUEUED),
                        item("b-d2", "p2", "b", ItemKind.LEFT_OUT),
                        item("e-d2", "p2", "e", ItemKind.QUEUED),
                    ),
                ),
            )
        val actions =
            listOf(
                Action("a", "a"),
                Action("b", "b"),
                Action("c", "c", status = ActionStatus.DONE),
                Action("e", "e"),
            )
        val outcomes =
            mapOf(
                o("a-d1", OutcomeResult.DONE),
                o("a-d2", OutcomeResult.BLOCKED),
                o("b-d1", OutcomeResult.PARTIAL),
                o("c-d1", OutcomeResult.DONE),
                o("e-d2", OutcomeResult.UNKNOWN),
            )

        val entries = unfinishedEntries(day, plans, actions, outcomes)

        // a: latest day's item says BLOCKED (older DONE is ignored). b: only d1 counts, a left-out item does not move it.
        // c is done, so not offered. e: UNKNOWN is a reset, so no result recorded.
        assertEquals(listOf("a", "b", "e"), entries.map { it.action.id })
        assertEquals(listOf(OutcomeResult.BLOCKED, OutcomeResult.PARTIAL, null), entries.map { it.lastResult })
        assertEquals(listOf("a", "b", "e"), unfinishedFor(day, plans, actions).map { it.id })
    }

    @Test
    fun `an open action whose last outcome is done is offered, because status decides`() {
        val p = plan("p1", day.plusDays(-1))
        val plans = listOf(DayPlanWithItems(p, listOf(item("a-1", "p1", "a", ItemKind.QUEUED))))
        val outcomes = mapOf("a-1" to PlanOutcome("a-1", OutcomeResult.DONE, 0))

        val entries = unfinishedEntries(day, plans, listOf(Action("a", "a")), outcomes)

        assertEquals(listOf(OutcomeResult.DONE), entries.map { it.lastResult })
        assertNull(unfinishedEntries(day, plans, listOf(Action("a", "a", status = ActionStatus.DONE)), outcomes).firstOrNull())
    }
}
