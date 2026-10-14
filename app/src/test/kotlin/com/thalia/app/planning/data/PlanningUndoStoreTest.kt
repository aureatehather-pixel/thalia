package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Undo of an approved plan: the next revision restores the one before it, nothing is deleted or updated, outcomes are
 * never touched (a recorded outcome refuses the Undo), the first plan has nothing to restore, and a stale or repeated
 * tap is refused. Robolectric's plain SQLite, as [PlanningOutcomesStoreTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningUndoStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<PlanningDatabase>()
    private var now = 1_000L
    private val utc = ZoneId.of("UTC")
    private val day1 = ActiveDay(LocalDate.of(2026, 10, 3))
    private val day2 = ActiveDay(LocalDate.of(2026, 10, 4))
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private suspend fun newStore(): PlanningStore {
        val name = "planning-undo-test-${names.size}.db"
        names += name
        val store = PlanningStore(plainPlanningDatabase(context, context.getDatabasePath(name)).also { opened += it }) { now++ }
        store.saveSettings(PlanningSettings(LocalTime.of(4, 0), window))
        listOf("a", "b", "c").forEach { store.saveAction(Action(it, "Action $it")) }
        return store
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        names.forEach { context.deleteDatabase(it) }
    }

    private suspend fun PlanningStore.plan(
        on: ActiveDay,
        vararg ids: String,
        pins: Map<String, LocalTime> = emptyMap(),
        openingTime: LocalTime? = null,
    ): DayPlanWithItems {
        val proposal = assertNotNull(propose(on, utc, null, ids.toSet(), pins))
        return assertIs<ApprovalResult.Approved>(approve(proposal, utc, openingTime = openingTime)).plan
    }

    private fun shape(plan: DayPlanWithItems) =
        plan.items.map { listOf(it.actionId, it.kind, it.start, it.minutes, it.position, it.placement, it.reason, it.title, it.priority) }

    @Test
    fun `undo writes the next revision as an exact copy of the earlier one and deletes nothing`() =
        runTest {
            val store = newStore()
            val first = store.plan(day1, "a", "b", pins = mapOf("a" to LocalTime.of(13, 0)))
            val second = store.plan(day1, "c")
            val before = store.revisionsOf(day1)

            val restored = assertIs<UndoResult.Restored>(store.undoApproval(second.plan.id)).plan

            assertEquals(3, restored.plan.revision)
            assertEquals(shape(first), shape(restored))
            assertEquals(
                first.plan.copy(id = restored.plan.id, revision = 3, approvedAtMillis = restored.plan.approvedAtMillis),
                restored.plan,
            )
            assertTrue(
                restored.items
                    .map { it.id }
                    .intersect((first.items + second.items).map { it.id }.toSet())
                    .isEmpty(),
            )
            // The earlier revisions are exactly as they were; the undone one is kept.
            assertEquals(before, store.revisionsOf(day1).take(2))
            assertEquals(restored.plan.id, store.planFor(day1)?.plan?.id)
        }

    @Test
    fun `the first plan has nothing to restore`() =
        runTest {
            val store = newStore()
            val first = store.plan(day1, "a")
            assertEquals(UndoResult.Refused(UndoRefusal.NO_PREVIOUS), store.undoApproval(first.plan.id))
            assertEquals(1, store.revisionsOf(day1).size)
        }

    @Test
    fun `a repeated or stale undo is refused and writes nothing`() =
        runTest {
            val store = newStore()
            store.plan(day1, "a")
            val second = store.plan(day1, "b")
            assertIs<UndoResult.Restored>(store.undoApproval(second.plan.id))
            val after = store.revisionsOf(day1)

            assertEquals(UndoResult.Refused(UndoRefusal.STALE), store.undoApproval(second.plan.id))
            assertEquals(UndoResult.Refused(UndoRefusal.STALE), store.undoApproval("no-such-plan"))
            assertEquals(after, store.revisionsOf(day1))

            // A newer approval also makes an earlier offer stale.
            val fourth = store.plan(day1, "c")
            assertEquals(UndoResult.Refused(UndoRefusal.STALE), store.undoApproval(second.plan.id))
            assertEquals(4, fourth.plan.revision)
        }

    @Test
    fun `a recorded outcome refuses undo and every outcome stays as it was`() =
        runTest {
            val store = newStore()
            store.plan(day1, "a")
            val second = store.plan(day1, "b")
            val item = second.items.single().id
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome(item, OutcomeResult.DONE, day2))
            val outcomes = store.outcomes()
            val revisions = store.revisionsOf(day1)

            assertEquals(UndoResult.Refused(UndoRefusal.OUTCOMES_RECORDED), store.undoApproval(second.plan.id))

            assertEquals(outcomes, store.outcomes())
            assertEquals(revisions, store.revisionsOf(day1))
            // Setting it back to Unknown lifts the refusal, and the outcome row is still there afterwards.
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome(item, OutcomeResult.UNKNOWN, day2))
            assertIs<UndoResult.Restored>(store.undoApproval(second.plan.id))
            assertEquals(1, store.outcomes().size)
            assertEquals(OutcomeResult.UNKNOWN, store.outcomes().single().result)
        }

    @Test
    fun `a kept opening time is copied as not linked and no call is requested`() =
        runTest {
            val store = newStore()
            store.plan(day1, "a", openingTime = LocalTime.of(12, 30))
            val second = store.plan(day1, "b")
            val restored = assertIs<UndoResult.Restored>(store.undoApproval(second.plan.id)).plan
            val call = store.calls().single { it.planId == restored.plan.id }
            assertEquals(LocalTime.of(12, 30), call.openingTime)
            assertEquals(PlanCallEntity.NOT_LINKED, call.status)
            assertEquals(1, store.calls().count { it.planId == restored.plan.id })
        }
}
