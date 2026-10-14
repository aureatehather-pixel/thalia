package com.thalia.app.checkin

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.OutcomeRecording
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The structured check-in over the real planning store: reading writes nothing, and each change is its own explicit call. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class GuidedCheckInTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "guided-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")
    private val at = Instant.parse("2026-10-12T20:00:00Z")
    private val rollover = LocalTime.of(4, 0)
    private val oct12 = ActiveDay(LocalDate.of(2026, 10, 12))
    private val oct13 = ActiveDay(LocalDate.of(2026, 10, 13))
    private var clock = 1_000L

    private val write = Action("id-write", "Write intro", null, Priority.MUST, 60)
    private val read = Action("id-read", "Read paper", null, Priority.NORMAL, 30)
    private val email = Action("id-email", "Email tutor", null, Priority.IMPORTANT, 15)
    private lateinit var store: PlanningStore

    private suspend fun setUp(plan: Boolean = true) {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        store = PlanningStore(db) { clock++ }
        store.saveSettings(PlanningSettings(rollover, PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        listOf(write, read, email).forEach { store.saveAction(it) }
        if (plan) approve(oct12, setOf(write.id, read.id))
    }

    private suspend fun approve(
        day: ActiveDay,
        selected: Set<String>,
    ) = assertIs<ApprovalResult.Approved>(store.approve(checkNotNull(store.propose(day, utc, null, selected, emptyMap())), utc))

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `reading the flow, as answering a call does, writes nothing`() =
        runTest {
            setUp()
            store.openWorkingDay(at, utc)
            val before = store.snapshot()

            val state = loadGuided(store, utc, at)
            carryProposal(store, state)
            loadGuided(store, utc, at)

            assertEquals(before, store.snapshot(), "no outcome, estimate, plan, working-day or action row changed")
            assertTrue(store.outcomes().isEmpty())
            assertNull(store.planFor(oct13))
        }

    @Test
    fun `the opening shows today's plan, and without one offers manual planning only`() =
        runTest {
            setUp()
            val withPlan = loadGuided(store, utc, at)
            assertEquals(oct12, withPlan.day)
            assertEquals(2, assertNotNull(withPlan.plan).items.size)
            database?.close()
            context.deleteDatabase(dbName)
            setUp(plan = false)
            assertNull(loadGuided(store, utc, at).plan)
        }

    @Test
    fun `an outcome is saved only by an explicit validated write, and a partial one exposes its remaining estimate`() =
        runTest {
            setUp()
            val state = loadGuided(store, utc, at)
            assertEquals(2, state.rows.size)
            assertTrue(state.partial.isEmpty())

            val itemId = state.rows.first { it.title == "Write intro" }.itemId
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome(itemId, OutcomeResult.PARTIAL, state.outcomeDay))

            val after = loadGuided(store, utc, at)
            assertEquals(listOf("Write intro"), after.partial.map { it.title })
            assertEquals(OutcomeResult.PARTIAL, after.rows.first { it.title == "Write intro" }.result)
            assertNull(after.rows.first { it.title == "Read paper" }.result, "nothing was recorded for the other item")
        }

    @Test
    fun `a remaining estimate goes through the validated action save and is refused outside the range`() =
        runTest {
            setUp()
            assertNull(store.saveAction(write.copy(estimateMinutes = 25), ActionStatus.OPEN))
            assertEquals(25, store.action("id-write")?.estimateMinutes)
            val refused = runCatching { write.copy(estimateMinutes = 2) }
            assertTrue(refused.isFailure, "the Action itself refuses an estimate outside 5 to 720")
        }

    @Test
    fun `the next day is the day after the planning day, and the proposal carries the unfinished work`() =
        runTest {
            setUp()
            val state = loadGuided(store, utc, at)
            assertEquals(oct13, state.nextDay)
            assertEquals(listOf("Read paper", "Write intro"), state.carry.map { it.title }.sorted())

            val proposal = assertNotNull(carryProposal(store, state))
            assertEquals(oct13, proposal.inputs.day)
            assertEquals(setOf("id-write", "id-read"), proposal.inputs.selected)
            assertNull(store.planFor(oct13), "a proposal saves nothing")
        }

    @Test
    fun `a finished item is not carried, and only Approve saves the next day, once`() =
        runTest {
            setUp()
            val state = loadGuided(store, utc, at)
            store.recordOutcome(state.rows.first { it.title == "Write intro" }.itemId, OutcomeResult.DONE, state.outcomeDay)

            val after = loadGuided(store, utc, at)
            assertEquals(listOf("Read paper"), after.carry.map { it.title })
            val proposal = assertNotNull(carryProposal(store, after))
            assertNull(store.planFor(oct13))

            assertIs<ApprovalResult.Approved>(store.approve(proposal, utc))
            val saved = assertNotNull(store.planFor(oct13))
            assertEquals(1, saved.plan.revision)
            assertEquals(setOf("id-read"), saved.selected)
            assertEquals(2, checkNotNull(store.planFor(oct12)).items.size, "today's plan is unchanged")
        }

    @Test
    fun `nothing unfinished means no proposal and a manual route`() =
        runTest {
            setUp()
            val state = loadGuided(store, utc, at)
            state.rows.forEach { store.recordOutcome(it.itemId, OutcomeResult.DONE, state.outcomeDay) }
            val after = loadGuided(store, utc, at)
            assertTrue(after.carry.isEmpty())
            assertNull(carryProposal(store, after))
        }

    @Test
    fun `closing needs an open working day, saves the note, and leaves unrecorded outcomes unknown`() =
        runTest {
            setUp()
            val notOpen = loadGuided(store, utc, at)
            assertTrue(!notOpen.canClose)
            assertEquals("No open working day to close. Nothing was changed.", closeDay(store, notOpen, "note", at))
            assertNull(store.workingDay(oct12)?.closingNote)

            store.openWorkingDay(at, utc)
            val open = loadGuided(store, utc, at)
            assertTrue(open.canClose)
            assertEquals("Closed 2026-10-12. Anything you did not record stays unknown.", closeDay(store, open, "beta half done", at))
            assertEquals("beta half done", store.workingDay(oct12)?.closingNote)
            assertTrue(store.outcomes().isEmpty(), "closing records no outcome")
            assertEquals("beta half done", loadGuided(store, utc, at).lastClosingNote)
        }

    @Test
    fun `while an earlier working day awaits a choice there is no next day to propose`() =
        runTest {
            setUp()
            store.openWorkingDay(Instant.parse("2026-10-10T12:00:00Z"), utc)
            val state = loadGuided(store, utc, at)
            assertNull(state.nextDay)
            assertNull(carryProposal(store, state))
        }

    @Test
    fun `the separate unknown question lists only planned items without an outcome, and answering it writes nothing`() =
        runTest {
            setUp()
            val state = loadGuided(store, utc, at)
            assertEquals(listOf("Read paper", "Write intro"), unrecorded(state.rows).map { it.title }.sorted())

            val itemId = state.rows.first { it.title == "Write intro" }.itemId
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome(itemId, OutcomeResult.DONE, state.outcomeDay))
            val after = loadGuided(store, utc, at)
            assertEquals(listOf("Read paper"), unrecorded(after.rows).map { it.title })

            // Reading the flow, and the words of the question, change nothing: "Leave as unknown" is not a write.
            val before = store.snapshot()
            UnknownQuestion.question(unrecorded(after.rows).map { it.title })
            UnknownQuestion.left(unrecorded(after.rows).map { it.title })
            loadGuided(store, utc, at)
            assertEquals(before, store.snapshot())
            assertNull(store.outcomes().firstOrNull { it.result == OutcomeResult.UNKNOWN }, "no Unknown row is ever written")

            store.recordOutcome(after.rows.single { it.title == "Read paper" }.itemId, OutcomeResult.SKIPPED, after.outcomeDay)
            assertTrue(unrecorded(loadGuided(store, utc, at).rows).isEmpty(), "nothing left to ask about")
        }

    @Test
    fun `the question wording counts and names the items`() {
        assertEquals(
            "1 planned item has no outcome yet: Read paper. Unknown is fine, and you are asked this once. " +
                "To record one, use its buttons in step 1.",
            UnknownQuestion.question(listOf("Read paper")),
        )
        assertTrue(UnknownQuestion.question(listOf("A", "B")).startsWith("2 planned items have no outcome yet: A, B."))
        assertTrue(UnknownQuestion.left(listOf("A")).contains("Nothing was recorded or assumed"))
    }

    @Test
    fun `a fixed commitment is never an outcome row, never unknown and never needs a result to close the day`() =
        runTest {
            setUp()
            val start = Instant.parse("2026-10-12T14:00:00Z")
            store.createCommitment(
                com.thalia.planning.FixedCommitment("c1", "Team call", start, start.plusSeconds(1800), utc),
            )
            store.openWorkingDay(at, utc)
            val state = loadGuided(store, utc, at)
            assertEquals(listOf("Team call"), state.commitments.map { it.title })
            assertTrue(state.rows.none { it.title == "Team call" })
            assertTrue(unrecorded(state.rows).none { it.title == "Team call" })

            // Closing needs no outcome at all: the two planned items stay unknown and the commitment is untouched.
            assertTrue(closeDay(store, state, null).startsWith("Closed"))
            assertTrue(store.outcomes().isEmpty())
            assertEquals(listOf("Team call"), store.commitments().map { it.title })
        }
}
