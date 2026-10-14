package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalRefusal
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.OutcomeRefusal
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.unfinishedEntries
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The outcomes section as the screen drives it: the same store calls, then the same reads, then the
 * functions that make its rows and words. Behavior through existing interfaces, not source text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanOutcomeTextTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "plan-outcome-text-test.db"
    private var database: PlanningDatabase? = null
    private var now = 1_000L
    private val utc = ZoneId.of("UTC")
    private val day1 = ActiveDay(LocalDate.of(2026, 10, 3))
    private val day2 = ActiveDay(LocalDate.of(2026, 10, 4))
    private val day3 = ActiveDay(LocalDate.of(2026, 10, 5))

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    private suspend fun store(): PlanningStore {
        val db = plainPlanningDatabase(context, context.getDatabasePath(name)).also { database = it }
        return PlanningStore(db) { now++ }.also {
            it.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            it.saveAction(Action("x", "Write chapter"))
            it.saveAction(Action("y", "Call bank"))
        }
    }

    private suspend fun PlanningStore.plan(
        on: ActiveDay,
        vararg ids: String,
    ) = assertIs<ApprovalResult.Approved>(approve(assertNotNull(propose(on, utc, null, ids.toSet(), emptyMap())), utc)).plan

    /** What the screen reads after every save. */
    private class Screen(
        val latest: DayPlanWithItems,
        val outcomes: Map<String, com.thalia.planning.PlanOutcome>,
        val actions: List<Action>,
    ) {
        fun rows(today: ActiveDay) = outcomeRows(latest, outcomes, actions, today)

        fun locked() = adjustLocked(latest, outcomes)
    }

    private suspend fun PlanningStore.screen(on: ActiveDay) =
        Screen(checkNotNull(planFor(on)), outcomes().associateBy { it.planItemId }, actions())

    private suspend fun PlanningStore.record(
        row: OutcomeRow,
        result: OutcomeResult,
        today: ActiveDay,
    ) = assertIs<OutcomeRecording.Recorded>(recordOutcome(row.itemId, result, today)).effect

    @Test
    fun `rows are the planned items of the latest revision, with their approved titles, on a day that has started`() =
        runTest {
            val store = store()
            store.plan(day1, "x")
            val latest = store.plan(day1, "x", "y")

            val screen = store.screen(day1)
            assertEquals(latest.plan.id, screen.latest.plan.id)
            assertEquals(setOf("Write chapter", "Call bank"), screen.rows(day1).map { it.title }.toSet())
            assertEquals(setOf(null), screen.rows(day1).map { it.result }.toSet())
            assertTrue(screen.rows(day1.plusDays(-1)).isEmpty(), "a day that has not started offers none")
        }

    @Test
    fun `a renamed action keeps its approved title in the rows, and a left-out item has no row`() =
        runTest {
            val store = store()
            store.plan(day1, "x")
            store.saveAction(Action("x", "Renamed later"))
            assertEquals(listOf("Write chapter"), store.screen(day1).rows(day1).map { it.title })
        }

    @Test
    fun `recording shows the result and the action's closure, and correcting shows the reopening`() =
        runTest {
            val store = store()
            store.plan(day1, "x")
            val row = store.screen(day1).rows(day1).single()

            store.record(row, OutcomeResult.DONE, day1)
            val closed = store.screen(day1).rows(day1).single()
            assertEquals(OutcomeResult.DONE, closed.result)
            assertEquals("Write chapter: done (action done)", outcomeRowText(closed))

            val effect = store.record(closed, OutcomeResult.PARTIAL, day1)
            val reopened = store.screen(day1).rows(day1).single()
            assertEquals("Write chapter: partial", outcomeRowText(reopened))
            assertEquals("Recorded partial.", recordedText(OutcomeResult.PARTIAL, effect.staysDoneNote))
        }

    @Test
    fun `the stays-done line appears only when a correction leaves the action done`() =
        runTest {
            val store = store()
            store.plan(day1, "x")
            store.plan(day2, "x")
            val first = store.screen(day1).rows(day2).single()
            val second = store.screen(day2).rows(day2).single()
            store.record(first, OutcomeResult.DONE, day2)
            store.record(second, OutcomeResult.DONE, day2) // history

            val effect = store.record(first, OutcomeResult.SKIPPED, day2)
            assertEquals(
                "Recorded skipped. The action stays done. Reopen it in Actions if it is not.",
                recordedText(OutcomeResult.SKIPPED, effect.staysDoneNote),
            )
            assertEquals(ActionStatus.DONE, store.actions().single { it.id == "x" }.status)
        }

    @Test
    fun `an explicit unknown reads as no result recorded`() =
        runTest {
            val store = store()
            store.plan(day1, "x")
            val row = store.screen(day1).rows(day1).single()
            store.record(row, OutcomeResult.BLOCKED, day1)
            store.record(row, OutcomeResult.UNKNOWN, day1)
            assertEquals("Write chapter: no result recorded", outcomeRowText(store.screen(day1).rows(day1).single()))
        }

    @Test
    fun `adjust and a new approval are locked while an outcome is effective, and unlock when all are unknown`() =
        runTest {
            val store = store()
            store.plan(day1, "x", "y")
            val rows = store.screen(day1).rows(day1)
            assertFalse(store.screen(day1).locked())

            store.record(rows[0], OutcomeResult.PARTIAL, day1)
            assertTrue(store.screen(day1).locked())
            assertTrue("Keep original is still available" in ADJUST_LOCKED_NOTE)
            assertTrue("Recorded outcomes are kept as they are" in ADJUST_LOCKED_NOTE)
            assertTrue(listOf("unknown", "clear", "reset", "set every").none { it in ADJUST_LOCKED_NOTE.lowercase() }, ADJUST_LOCKED_NOTE)
            val proposal = assertNotNull(store.propose(day1, utc, null, setOf("x", "y"), emptyMap()))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.OUTCOMES_RECORDED), store.approve(proposal, utc))

            store.record(rows[0], OutcomeResult.UNKNOWN, day1)
            assertFalse(store.screen(day1).locked())
            val again = assertNotNull(store.propose(day1, utc, null, setOf("x", "y"), emptyMap()))
            assertIs<ApprovalResult.Approved>(store.approve(again, utc))
        }

    @Test
    fun `the store refuses what the screen does not offer`() =
        runTest {
            val store = store()
            val plan = store.plan(day2, "x")
            val item = plan.items.single().id
            val refused = assertIs<OutcomeRecording.Refused>(store.recordOutcome(item, OutcomeResult.DONE, day1))
            assertEquals(OutcomeRefusal.NOT_ELIGIBLE, refused.reason)
            assertTrue(outcomeRefusalText(refused.reason).endsWith("Nothing was saved."))
            assertTrue(store.outcomes().isEmpty())
        }

    @Test
    fun `unfinished labels follow status and carry the last result, after saving`() =
        runTest {
            val store = store()
            val first = store.plan(day1, "x", "y")
            val rows = outcomeRows(first, emptyMap(), store.actions(), day1)
            val x = rows.single { it.title == "Write chapter" }
            val y = rows.single { it.title == "Call bank" }

            store.record(x, OutcomeResult.PARTIAL, day2)
            store.record(y, OutcomeResult.DONE, day2)

            suspend fun labels() =
                unfinishedEntries(day3, store.latestPlans(), store.actions(), store.outcomes().associateBy { it.planItemId })
                    .map { unfinishedLabel(it) + it.action.title }

            assertEquals(listOf("Unfinished (last time: partial): Write chapter"), labels()) // y is done, so not offered

            store.record(x, OutcomeResult.UNKNOWN, day2)
            assertEquals(listOf("Unfinished (no result recorded): Write chapter"), labels())

            val actionY = store.actions().single { it.id == "y" }
            store.saveAction(actionY.copy(status = ActionStatus.OPEN)) // reopened by hand: offered again
            assertEquals(
                listOf("Unfinished (no result recorded): Write chapter", "Unfinished (last time: done): Call bank"),
                labels(),
            )
        }
}
