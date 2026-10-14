package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.ItemKind
import com.thalia.planning.Placement
import com.thalia.planning.PlanErrorKind
import com.thalia.planning.PlanProposal
import com.thalia.planning.PlanValidator
import com.thalia.planning.PlanViolationKind
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
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
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0009 slice 2 criteria 5 to 8 at the store: approval validates independently, rechecks every
 * dependency inside its transaction, writes atomically, never overwrites an earlier revision, and the
 * approved history outlives later edits. Robolectric's plain SQLite, as [PlanningStoreTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningApprovalTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "planning-approval-test.db"
    private var database: PlanningDatabase? = null
    private var now = 1_000L
    private val utc = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 3))
    private val defaultWindow = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private fun store(): PlanningStore {
        database?.close()
        val db = plainPlanningDatabase(context, context.getDatabasePath(name))
        database = db
        return PlanningStore(db) { now++ }
    }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    private suspend fun PlanningStore.seed(): PlanningStore {
        saveSettings(PlanningSettings(LocalTime.of(4, 0), defaultWindow))
        saveProject(Project("book", "Book", null, ProjectState.ACTIVE))
        saveProject(Project("garden", "Garden", null, ProjectState.ACTIVE))
        saveAction(Action("draft", "Draft chapter", "book", Priority.MUST, 180))
        saveAction(Action("review", "Review PR", "garden", Priority.IMPORTANT, 90))
        saveAction(Action("bank", "Call bank", null, Priority.NORMAL, 30))
        saveAction(Action("dentist", "Dentist", null, Priority.NORMAL, 60))
        return this
    }

    private suspend fun PlanningStore.propose(
        vararg selected: String,
        pins: Map<String, LocalTime> = emptyMap(),
        window: PlanningWindow? = null,
        on: ActiveDay = day,
        zone: ZoneId = utc,
    ): PlanProposal = assertNotNull(propose(on, zone, window, selected.toSet(), pins))

    private suspend fun PlanningStore.counts() = snapshot().counts

    @Test
    fun `without a saved window there is nothing to propose, so setup comes first`() =
        runTest {
            val store = store()
            assertNull(store.propose(day, utc, null, setOf("x"), emptyMap()))
            assertTrue(store.propose(day, utc, defaultWindow, setOf("x"), emptyMap()) != null) // an explicit window is enough
        }

    @Test
    fun `leaving a proposal writes nothing`() =
        runTest {
            val store = store().seed()
            val before = store.snapshot()
            val proposal = store.propose("draft", "review", "bank")
            assertTrue(proposal.canApprove)
            store.propose("draft", pins = mapOf("dentist" to LocalTime.of(14, 0)))
            assertEquals(before, store.snapshot())
            assertNull(store.planFor(day))
        }

    @Test
    fun `approving writes revision 1 with its items and the window it was made with`() =
        runTest {
            val store = store().seed()
            val proposal = store.propose("draft", "review", "bank", "dentist", pins = mapOf("dentist" to LocalTime.of(14, 0)))
            val approved = assertIs<ApprovalResult.Approved>(store.approve(proposal, utc)).plan

            assertEquals(approved, store.planFor(day))
            assertEquals(1, approved.plan.revision)
            assertEquals(defaultWindow, approved.plan.window)
            assertEquals(utc, approved.plan.zone)
            assertEquals(listOf("dentist", "draft", "review", "bank"), approved.items.map { it.actionId })
            assertEquals(ItemKind.PINNED, approved.items[0].kind)
            assertEquals(Placement.WITHIN_TARGET, approved.items[1].placement)
            assertEquals(PlanCounts(plans = 1, items = 4), store.planCounts())
        }

    private data class PlanCounts(
        val plans: Int,
        val items: Int,
    )

    private suspend fun PlanningStore.planCounts() = snapshot().counts.let { PlanCounts(it.plans, it.planItems) }

    @Test
    fun `adjusting writes a new revision and the latest is found by query, with no earlier row touched`() =
        runTest {
            val store = store().seed()
            val first = assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft", "bank"), utc)).plan
            assertEquals(first, store.revisionsOf(day).single())

            val second = assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft", "review"), utc)).plan

            assertEquals(2, second.plan.revision)
            val history = store.revisionsOf(day)
            assertEquals(listOf(1, 2), history.map { it.plan.revision })
            assertEquals(first, history[0]) // revision 1 is exactly what was approved, nothing marks it superseded
            assertEquals(second, store.planFor(day)) // only the latest is shown
            assertEquals(listOf(second), store.latestPlans())
        }

    @Test
    fun `earlier plan and item rows stay byte for byte the same after another approval and after later action and project edits`() =
        runTest {
            val store = store().seed()
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft", "bank", "review"), utc))
            val dao = database!!.planning()
            val planRowsBefore = dao.plans()
            val itemRowsBefore = dao.planItems()
            assertEquals(1, planRowsBefore.size)

            // Another approval for the same day, then for another day.
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("review"), utc))
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft", on = day.plusDays(1)), utc))
            // Later edits and closures of what the plans refer to.
            store.saveAction(Action("draft", "Renamed", "garden", Priority.OPTIONAL, 10, status = ActionStatus.DONE))
            store.saveAction(Action("bank", "Also renamed", null, Priority.MUST, 99))
            store.saveProject(Project("book", "Renamed book", null, ProjectState.ARCHIVED))
            store.saveProject(Project("garden", "Renamed garden", null, ProjectState.ABANDONED))

            val firstPlanId = planRowsBefore.single().id
            assertEquals(planRowsBefore, dao.plans().filter { it.id == firstPlanId }) // every column of revision 1
            assertEquals(itemRowsBefore, dao.planItems().filter { it.planId == firstPlanId }) // every item of revision 1
            assertEquals(3, dao.plans().size) // revision 2 of the day, and the next day
            assertEquals(listOf(1, 2), dao.plansOfDay(day.toString()).map { it.revision })
        }

    @Test
    fun `no plan table has an update path, so nothing can mark or change an earlier revision`() {
        val sql =
            sequenceOf("PlanningDatabase.kt", "PlanningStore.kt")
                .map { java.io.File("src/main/kotlin/com/thalia/app/planning/data/$it").readText() }
                .joinToString("\n")
        val updates = Regex("UPDATE\\s+(\\w+)", RegexOption.IGNORE_CASE).findAll(sql).map { it.groupValues[1] }.toSet()
        assertTrue(updates.none { it == "day_plans" || it == "plan_items" }, "an UPDATE of a plan table exists: $updates")
        assertNull(
            Regex("@Update\\s+suspend fun \\w+\\(\\w+: (DayPlanEntity|PlanItemEntity)").find(sql),
            "an @Update of a plan entity exists",
        )
    }

    @Test
    fun `approved history keeps the action and project as they were when they are edited or closed later`() =
        runTest {
            val store = store().seed()
            val approved = assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft", "bank"), utc)).plan

            store.saveAction(Action("draft", "Renamed chapter", "garden", Priority.OPTIONAL, 10, status = ActionStatus.DONE))
            store.saveProject(Project("book", "Renamed book", null, ProjectState.ARCHIVED))

            assertEquals(approved, store.planFor(day))
            val item = store.planFor(day)!!.items.single { it.actionId == "draft" }
            assertEquals(Triple("Draft chapter", Priority.MUST, 180), Triple(item.title, item.priority, item.minutes))
            assertEquals(Triple("book", "Book", ProjectState.ACTIVE), Triple(item.projectId, item.projectTitle, item.projectState))
        }

    @Test
    fun `every changed dependency makes the preview stale, and nothing is written`() =
        runTest {
            val store = store().seed()
            val shown = store.propose("draft", "review", "bank")
            val before = store.snapshot()

            suspend fun refusedAfter(
                change: suspend () -> Unit,
                zone: ZoneId = utc,
            ) {
                change()
                val withChange = store.snapshot()
                assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(shown, zone))
                assertEquals(withChange.plans, store.snapshot().plans)
                assertEquals(withChange.planItems, store.snapshot().planItems)
            }

            refusedAfter({ store.saveAction(Action("bank", "Call bank now", null, Priority.NORMAL, 30)) }) // title
            refusedAfter({ store.saveAction(Action("bank", "Call bank now", null, Priority.NORMAL, 45)) }) // estimate
            refusedAfter({ store.saveAction(Action("bank", "Call bank now", null, Priority.MUST, 45)) }) // priority
            val closed = Action("bank", "Call bank now", null, Priority.MUST, 45, status = ActionStatus.DONE)
            refusedAfter({ store.saveAction(closed) })
            refusedAfter({ store.saveProject(Project("garden", "Garden", null, ProjectState.PAUSED)) }) // eligibility
            refusedAfter({ store.saveProject(Project("garden", "Garden, renamed", null, ProjectState.PAUSED)) }) // display
            refusedAfter({ store.saveSettings(PlanningSettings(LocalTime.of(5, 0), defaultWindow)) }) // rollover
            val zone = ZoneId.of("Asia/Kuala_Lumpur")
            refusedAfter({ store.saveSettings(PlanningSettings(LocalTime.of(5, 0), defaultWindow)) }, zone = zone)
            assertEquals(before.plans, store.snapshot().plans)
        }

    @Test
    fun `the default window going stale matters only to a proposal that used it`() =
        runTest {
            val store = store().seed()
            val usedDefault = store.propose("draft")
            val ownWindow = store.propose("draft", window = PlanningWindow(LocalTime.of(9, 0), LocalTime.of(17, 0)))
            assertTrue(usedDefault.inputs.windowIsDefault)
            assertTrue(!ownWindow.inputs.windowIsDefault)

            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(10, 0), LocalTime.of(20, 0))))

            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(usedDefault, utc))
            assertIs<ApprovalResult.Approved>(store.approve(ownWindow, utc))
        }

    @Test
    fun `an approved plan keeps its window when the default changes afterwards`() =
        runTest {
            val store = store().seed()
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft"), utc))
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(10, 0), LocalTime.of(20, 0))))
            assertEquals(defaultWindow, store.planFor(day)!!.plan.window)
        }

    @Test
    fun `a revision made since the proposal makes it stale`() =
        runTest {
            val store = store().seed()
            val older = store.propose("draft")
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft", "bank"), utc))

            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(older, utc))
            assertEquals(listOf(1), store.revisionsOf(day).map { it.plan.revision })
        }

    @Test
    fun `a proposal the planner did not make is refused, as malformed if it breaks the rules and as changed if it is merely not current`() =
        runTest {
            val store = store().seed()
            val real = store.propose("draft", "bank")
            assertTrue(PlanValidator.validate(real).isEmpty())
            val malformed =
                listOf(
                    real.copy(freeMinutes = real.freeMinutes + 100),
                    real.copy(items = real.items.reversed()),
                    real.copy(overloadMinutes = 0, warnings = emptyList(), items = real.items.drop(1)),
                    real.copy(errors = emptyList(), targetMinutes = 599),
                )
            malformed.forEach { assertEquals(ApprovalResult.Refused(ApprovalRefusal.MALFORMED), store.approve(it, utc)) }
            // Consistent in itself, but based on a revision that does not exist.
            val notCurrent = real.copy(inputs = real.inputs.copy(basedOnRevision = 7))
            assertTrue(PlanValidator.validate(notCurrent).isEmpty())
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(notCurrent, utc))
            assertEquals(0, store.counts().plans)
        }

    @Test
    fun `a proposal that breaks the rules is refused as malformed even with its errors cleared`() =
        runTest {
            val store = store().seed()
            val overlapping =
                store.propose("dentist", "bank", pins = mapOf("dentist" to LocalTime.of(14, 0), "bank" to LocalTime.of(14, 30)))
            assertEquals(PlanErrorKind.PIN_OVERLAP, overlapping.errors.single().kind)
            val cleared = overlapping.copy(errors = emptyList())
            assertTrue(PlanViolationKind.PINS in PlanValidator.validate(cleared).map { it.kind })
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.MALFORMED), store.approve(cleared, utc))

            // Must work that does not fit, presented as fitting, so that no acknowledgement would be asked for.
            store.saveAction(Action("m2", "Big must", null, Priority.MUST, 300))
            store.saveAction(Action("m3", "Another must", null, Priority.MUST, 300))
            val shown = store.propose("draft", "m2", "m3")
            assertEquals(180, shown.overloadMinutes)
            val hidden = shown.copy(overloadMinutes = 0, warnings = emptyList())
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.MALFORMED), store.approve(hidden, utc))
            assertEquals(0, store.counts().plans)
        }

    @Test
    fun `a changed title is stale even when every scheduling result is unchanged`() =
        runTest {
            val store = store().seed()
            val shown = store.propose("draft", "review", "bank")

            store.saveAction(Action("bank", "Call the other bank", null, Priority.NORMAL, 30))
            store.saveProject(Project("garden", "Garden and yard", null, ProjectState.ACTIVE))

            val again = store.propose("draft", "review", "bank")
            assertEquals(shown.items, again.items) // placements, minutes and order are identical
            assertEquals(shown.freeMinutes, again.freeMinutes)
            assertEquals(shown.targetMinutes, again.targetMinutes)
            assertTrue(shown != again) // but what is displayed differs
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(shown, utc))
            assertEquals(0, store.counts().plans)

            val approved = assertIs<ApprovalResult.Approved>(store.approve(again, utc)).plan
            assertEquals("Call the other bank", approved.items.single { it.actionId == "bank" }.title)
            assertEquals("Garden and yard", approved.items.single { it.actionId == "review" }.projectTitle)
        }

    @Test
    fun `a renamed project alone is stale, and so is a changed due date the preview does not show`() =
        runTest {
            val store = store().seed()
            val shown = store.propose("draft", "review")
            store.saveProject(Project("book", "Book, second edition", null, ProjectState.ACTIVE))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(shown, utc))

            val again = store.propose("draft", "review")
            store.saveAction(Action("review", "Review PR", "garden", Priority.IMPORTANT, 90, LocalDate.of(2026, 12, 1)))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(again, utc))
        }

    @Test
    fun `settings, zone and the prior revision are each checked on their own`() =
        runTest {
            val store = store().seed()
            val kl = ZoneId.of("Asia/Kuala_Lumpur")

            // Zone only: nothing stored changed, the zone read from M2 just before the approval differs.
            val shown = store.propose("draft", "bank")
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(shown, kl))
            assertEquals(0, store.counts().plans)

            // Rollover only.
            val beforeRollover = store.propose("draft", "bank")
            store.saveSettings(PlanningSettings(LocalTime.of(5, 0), defaultWindow))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(beforeRollover, utc))
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), defaultWindow))

            // Default window only, for a proposal that used the default.
            val beforeWindow = store.propose("draft", "bank")
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(11, 0), LocalTime.of(22, 0))))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(beforeWindow, utc))
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), defaultWindow))

            // Prior revision only.
            val stale = store.propose("draft")
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("bank"), utc))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(stale, utc))
            assertEquals(1, store.counts().plans)
        }

    @Test
    fun `a rest day is approved only explicitly, and previewing or discarding it writes nothing`() =
        runTest {
            val store = store().seed()
            val rest = store.propose()
            assertTrue(rest.isRestDay && rest.canApprove)
            assertTrue(rest.items.isEmpty())
            val before = store.snapshot()
            assertNull(store.planFor(day)) // the preview was only in memory; Keep original and Discard do nothing more

            assertEquals(ApprovalResult.Refused(ApprovalRefusal.REST_DAY_NOT_ACKNOWLEDGED), store.approve(rest, utc))
            assertEquals(before, store.snapshot())

            val approved = assertIs<ApprovalResult.Approved>(store.approve(rest, utc, acknowledgeRestDay = true)).plan
            assertEquals(emptyList(), approved.items)
            assertEquals(1, approved.plan.revision)
            assertEquals(approved, store.planFor(day))
            assertEquals(PlanCounts(plans = 1, items = 0), store.planCounts())
        }

    @Test
    fun `a rest day can be adjusted into a planned day and back, keeping every revision, and an abandoned adjust writes nothing`() =
        runTest {
            val store = store().seed()
            val rest = assertIs<ApprovalResult.Approved>(store.approve(store.propose(), utc, acknowledgeRestDay = true)).plan
            val kept = store.snapshot()
            store.propose("draft") // an adjustment started and then abandoned: Keep original
            assertEquals(kept, store.snapshot())
            assertEquals(rest, store.planFor(day))

            val planned = assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft"), utc)).plan
            val backToRest = assertIs<ApprovalResult.Approved>(store.approve(store.propose(), utc, acknowledgeRestDay = true)).plan
            assertEquals(listOf(rest, planned, backToRest), store.revisionsOf(day))
            assertEquals(listOf(1, 2, 3), store.revisionsOf(day).map { it.plan.revision })
        }

    @Test
    fun `a proposal with blocking errors is refused even though it is current`() =
        runTest {
            val store = store().seed()
            val overlapping =
                store.propose("dentist", "bank", pins = mapOf("dentist" to LocalTime.of(14, 0), "bank" to LocalTime.of(14, 30)))
            assertEquals(PlanErrorKind.PIN_OVERLAP, overlapping.errors.single().kind)
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.INVALID), store.approve(overlapping, utc))
            assertEquals(0, store.counts().plans)
        }

    @Test
    fun `must work that does not fit needs the separate acknowledgement and the plan keeps the unresolved minutes`() =
        runTest {
            val store = store().seed()
            store.saveAction(Action("m2", "Big must", null, Priority.MUST, 300))
            store.saveAction(Action("m3", "Another must", null, Priority.MUST, 300))
            val shown = store.propose("draft", "m2", "m3") // 780 of Must work in 600 minutes: 180 do not fit
            assertEquals(180, shown.overloadMinutes)

            assertEquals(ApprovalResult.Refused(ApprovalRefusal.UNRESOLVED_NOT_ACKNOWLEDGED), store.approve(shown, utc))
            assertEquals(0, store.counts().plans)

            val approved = assertIs<ApprovalResult.Approved>(store.approve(shown, utc, acknowledgeUnresolved = true)).plan
            assertEquals(180, approved.plan.overloadMinutes)
            assertEquals(1, approved.items.count { it.placement == Placement.DOES_NOT_FIT })
            assertEquals(approved, store.planFor(day))
        }

    @Test
    fun `a write that fails midway leaves the previous state, never part of the new one`() =
        runTest {
            val store = store().seed()
            val first = assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft"), utc)).plan
            val before = store.snapshot()

            var ids = 0
            val failing = {
                if (++ids == 3) error("the disk fills up")
                "id-$ids"
            }
            assertFailsWith<IllegalStateException> { store.approve(store.propose("draft", "review", "bank"), utc, newId = failing) }

            assertEquals(before, store.snapshot())
            assertEquals(first, store.planFor(day))
            assertEquals(listOf(first), store.revisionsOf(day))
        }

    @Test
    fun `concurrent approvals never overwrite each other, one wins and the other is stale`() {
        repeat(15) { round ->
            val store = store()
            runBlocking {
                store.seed()
                val onDay = day.plusDays(round.toLong())
                val a = store.propose("draft", on = onDay)
                val b = store.propose("bank", on = onDay)
                val results = listOf(a, b).map { async(Dispatchers.IO) { store.approve(it, utc) } }.awaitAll()

                assertEquals(1, results.count { it is ApprovalResult.Approved }, "round $round")
                assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), results.single { it !is ApprovalResult.Approved })
                assertEquals(listOf(1), store.revisionsOf(onDay).map { it.plan.revision })
                assertEquals(1, store.snapshot().plans.count { it.activeDay == onDay }, "round $round")
            }
        }
    }

    @Test
    fun `the database itself refuses a second plan with the same day and revision`() =
        runTest {
            val store = store().seed()
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft"), utc))
            val existing = database!!.planning().plansOfDay(day.toString()).single()
            assertFailsWith<SQLiteConstraintException> { database!!.planning().insertPlan(existing.copy(id = "another-id")) }
        }

    @Test
    fun `an item for an action that does not exist is refused by the database`() =
        runTest {
            val store = store().seed()
            assertIs<ApprovalResult.Approved>(store.approve(store.propose("draft"), utc))
            val item = database!!.planning().planItems().single()
            assertFailsWith<SQLiteConstraintException> {
                database!!.planning().insertPlanItems(
                    listOf(item.copy(id = "i2", actionId = "ghost")),
                )
            }
        }
}
