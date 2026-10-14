package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.CommitmentStatus
import com.thalia.planning.ContextField
import com.thalia.planning.FixedCommitment
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.WorkingDayState
import com.thalia.planning.WorkingDays
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** ADR 0011 at the store: working-day transitions, commitments, focus, project context, staleness and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningDayLoopStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "planning-day-loop-test.db"
    private val otherName = "planning-day-loop-restore.db"
    private val opened = mutableListOf<PlanningDatabase>()
    private var now = 1_000L
    private val zone = ZoneId.of("Asia/Kuala_Lumpur")
    private val day = ActiveDay(LocalDate.of(2026, 10, 6))

    private fun store(file: String = name): PlanningStore {
        val db = plainPlanningDatabase(context, context.getDatabasePath(file))
        opened += db
        return PlanningStore(db) { now++ }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
        context.deleteDatabase(otherName)
    }

    private fun at(text: String): Instant = LocalDateTime.parse(text).atZone(zone).toInstant()

    private fun commitment(
        id: String,
        from: String,
        to: String,
    ) = FixedCommitment(id, "Seminar", at(from), at(to), zone)

    private suspend fun PlanningStore.seed(): PlanningStore {
        saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        saveProject(Project("book", "Book", null, ProjectState.ACTIVE))
        saveAction(Action("draft", "Draft chapter", "book", Priority.MUST, 120))
        saveAction(Action("bank", "Call bank", null, Priority.NORMAL, 30))
        return this
    }

    private suspend fun PlanningStore.state(time: String) = WorkingDays.resolve(at(time), zone, LocalTime.of(4, 0), workingDays())

    @Test
    fun `opening, closing at 01 00 and opening at 03 30 follow the documented resolver`() =
        runTest {
            val store = store().seed()
            assertEquals(WorkingDayChange.Opened(day, reopened = false), store.openWorkingDay(at("2026-10-06T13:00"), zone))
            assertIs<WorkingDayChange.AnotherOpen>(store.openWorkingDay(at("2026-10-06T14:00"), zone))
            assertEquals(WorkingDayChange.Closed(day), store.closeWorkingDay(day, at("2026-10-07T01:00"), null))
            assertNull(store.workingDay(day)?.closingNote)

            val next = ActiveDay(LocalDate.of(2026, 10, 7))
            assertEquals(WorkingDayChange.Opened(next, reopened = false), store.openWorkingDay(at("2026-10-07T03:30"), zone))
            assertEquals(WorkingDayState.Current(next), store.state("2026-10-07T03:45"))
        }

    @Test
    fun `reopening on the same date keeps the day and its note, and Start after an absence closes the old day only`() =
        runTest {
            val store = store().seed()
            store.openWorkingDay(at("2026-10-06T09:00"), zone)
            store.closeWorkingDay(day, at("2026-10-06T13:00"), "Draft the intro next")
            assertEquals(WorkingDayChange.Opened(day, reopened = true), store.openWorkingDay(at("2026-10-06T15:00"), zone))
            val reopened = assertNotNull(store.workingDay(day))
            assertEquals("Draft the intro next", reopened.closingNote)
            assertEquals(at("2026-10-06T09:00"), reopened.openedAt)

            val choose = assertIs<WorkingDayState.Choose>(store.state("2026-10-09T14:00"))
            val friday = ActiveDay(LocalDate.of(2026, 10, 9))
            assertEquals(
                WorkingDayChange.Opened(friday, reopened = false),
                store.startWorkingDay(choose.open, at("2026-10-09T14:00"), zone),
            )
            assertEquals(at("2026-10-09T14:00"), store.workingDay(day)?.closedAt)
            assertEquals(WorkingDayState.Current(friday), store.state("2026-10-09T14:01"))
        }

    @Test
    fun `continuing past the rollover is remembered for that clock day only`() =
        runTest {
            val store = store().seed()
            store.openWorkingDay(at("2026-10-06T13:00"), zone)
            val choose = assertIs<WorkingDayState.Choose>(store.state("2026-10-07T05:00"))
            assertEquals(WorkingDayChange.Continued(day), store.continueWorkingDay(choose.open, choose.clockDay))
            assertEquals(WorkingDayState.Current(day), store.state("2026-10-07T05:01"))
            assertIs<WorkingDayState.Choose>(store.state("2026-10-08T05:00"))
        }

    @Test
    fun `a commitment counts once, and moving it or changing focus after a preview makes approval stale`() =
        runTest {
            val store = store().seed()
            assertEquals(CreateCommitmentResult.CREATED, store.createCommitment(commitment("c1", "2026-10-06T13:00", "2026-10-06T14:00")))
            assertEquals(
                CreateCommitmentResult.ALREADY_EXISTS,
                store.createCommitment(commitment("c1", "2026-10-06T13:00", "2026-10-06T14:00")),
            )
            assertEquals(CreateCommitmentResult.DUPLICATE, store.createCommitment(commitment("c2", "2026-10-06T13:00", "2026-10-06T14:00")))

            val shown = assertNotNull(store.propose(day, zone, null, setOf("bank"), emptyMap()))
            assertEquals(60, shown.occupiedMinutes)
            assertEquals(540, shown.freeMinutes)

            // Moved within the window: the same occupied time, so the same free minutes, but a different input.
            store.updateCommitment(commitment("c1", "2026-10-06T16:00", "2026-10-06T17:00"))
            val moved = assertNotNull(store.propose(day, zone, null, setOf("bank"), emptyMap()))
            assertEquals(shown.freeMinutes, moved.freeMinutes)
            assertNotEquals(shown, moved)
            assertEquals(ApprovalRefusal.CHANGED, (store.approve(shown, zone) as ApprovalResult.Refused).reason)

            assertNull(store.setFocus(day, 200))
            assertEquals(ApprovalRefusal.CHANGED, (store.approve(moved, zone) as ApprovalResult.Refused).reason)
            val withFocus = assertNotNull(store.propose(day, zone, null, setOf("bank"), emptyMap()))
            assertEquals(200, withFocus.targetMinutes)
            val approved = assertIs<ApprovalResult.Approved>(store.approve(withFocus, zone))

            // A later capacity change does not rewrite the approved plan.
            assertEquals(200, store.setFocus(day, 60))
            assertEquals(approved.plan, store.planFor(day))
        }

    @Test
    fun `a cancelled commitment or one on another day does not count`() =
        runTest {
            val store = store().seed()
            store.createCommitment(commitment("c1", "2026-10-07T13:00", "2026-10-07T14:00"))
            store.createCommitment(commitment("c2", "2026-10-06T13:00", "2026-10-06T14:00").copy(status = CommitmentStatus.CANCELLED))
            assertEquals(emptyList(), store.commitmentsOf(day, zone, LocalTime.of(4, 0)))
            assertEquals(600, assertNotNull(store.propose(day, zone, null, setOf("bank"), emptyMap())).freeMinutes)
        }

    @Test
    fun `a project context change applies only to the value it was proposed from`() =
        runTest {
            val store = store().seed()
            assertEquals(
                ContextChangeResult.CHANGED,
                store.changeProjectContext("book", ContextField.PURPOSE, null, "A first draft by March"),
            )
            assertEquals(ContextChangeResult.STALE, store.changeProjectContext("book", ContextField.PURPOSE, null, "Something else"))
            assertEquals("A first draft by March", store.project("book")?.purpose)
            assertEquals(ContextChangeResult.PROJECT_NOT_FOUND, store.changeProjectContext("none", ContextField.BLOCKER, null, "x"))
        }

    @Test
    fun `schema 4 rows survive a snapshot and a restore into an empty database`() =
        runTest {
            val store = store().seed()
            store.changeProjectContext("book", ContextField.CONSTRAINTS, null, "Evenings only")
            store.createCommitment(commitment("c1", "2026-10-06T13:00", "2026-10-06T14:00"))
            store.openWorkingDay(at("2026-10-06T09:00"), zone)
            store.setFocus(day, 150)
            store.closeWorkingDay(day, at("2026-10-06T23:00"), "Bank first tomorrow")
            val snapshot = store.snapshot()
            assertEquals(1, snapshot.counts.commitments)
            assertEquals(1, snapshot.counts.workingDays)

            val restored = store(otherName)
            assertEquals(true, restored.restoreIntoEmpty(snapshot))
            assertEquals(snapshot, restored.snapshot())
        }
}
