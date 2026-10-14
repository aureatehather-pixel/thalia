package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlanWithItems
import com.thalia.planning.ItemKind
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningExportContent
import com.thalia.planning.PlanningExportFormat
import com.thalia.planning.PlanningExportInfo
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.outcomeInvariantViolation
import com.thalia.planning.unfinishedEntries
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
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0009 slice 3, storage batch: outcomes are recorded and corrected with their status effect in one
 * transaction, eligibility and the adjustment lock hold at the store, a manual status change clears the
 * marker, and export and import carry outcomes and opening times but never authorize a call.
 * Robolectric's plain SQLite, as [PlanningApprovalTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningOutcomesStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val names = mutableListOf<String>()
    private val opened = mutableListOf<PlanningDatabase>()
    private var now = 1_000L
    private val utc = ZoneId.of("UTC")
    private val day1 = ActiveDay(LocalDate.of(2026, 10, 3))
    private val day2 = ActiveDay(LocalDate.of(2026, 10, 4))
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private fun newDatabase(name: String): PlanningDatabase {
        names += name
        return plainPlanningDatabase(context, context.getDatabasePath(name)).also { opened += it }
    }

    private suspend fun newStore(name: String = "planning-outcomes-test-${names.size}.db"): PlanningStore {
        val store = PlanningStore(newDatabase(name)) { now++ }
        store.saveSettings(PlanningSettings(LocalTime.of(4, 0), window))
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
        openingTime: LocalTime? = null,
    ): DayPlanWithItems {
        val proposal = assertNotNull(propose(on, utc, null, ids.toSet(), emptyMap()))
        return assertIs<ApprovalResult.Approved>(approve(proposal, utc, openingTime = openingTime)).plan
    }

    private fun DayPlanWithItems.item(actionId: String) = items.single { it.actionId == actionId }.id

    private suspend fun PlanningStore.record(
        itemId: String,
        result: OutcomeResult,
        today: ActiveDay = day2,
    ) = assertIs<OutcomeRecording.Recorded>(recordOutcome(itemId, result, today)).effect

    private suspend fun PlanningStore.status(id: String) = actions().single { it.id == id }.status

    private suspend fun PlanningStore.marker(itemId: String) = outcomes().single { it.planItemId == itemId }.closedAction

    private suspend fun PlanningStore.manual(
        id: String,
        status: ActionStatus,
    ) = assertNull(saveAction(actions().single { it.id == id }.copy(status = status)))

    private suspend fun PlanningStore.withActions(vararg ids: String) = apply { ids.forEach { saveAction(Action(it, "Action $it")) } }

    // --- the ADR's transition table, through the store ---

    @Test
    fun `done closes the action and correcting it to partial reopens it`() =
        runTest {
            val store = newStore().withActions("x")
            val item = store.plan(day1, "x").item("x")

            store.record(item, OutcomeResult.DONE)
            assertEquals(ActionStatus.DONE, store.status("x"))
            assertTrue(store.marker(item))

            store.record(item, OutcomeResult.PARTIAL)
            assertEquals(ActionStatus.OPEN, store.status("x"))
            assertFalse(store.marker(item))
        }

    @Test
    fun `a closure made by hand survives a done and its correction`() =
        runTest {
            val store = newStore().withActions("x")
            val item = store.plan(day1, "x").item("x")
            store.manual("x", ActionStatus.DONE)

            store.record(item, OutcomeResult.DONE)
            assertFalse(store.marker(item))
            val effect = store.record(item, OutcomeResult.SKIPPED)
            assertEquals(ActionStatus.DONE, store.status("x"))
            assertTrue(effect.staysDoneNote)
        }

    @Test
    fun `a manual drop clears the marker and a later correction leaves it dropped`() =
        runTest {
            val store = newStore().withActions("x")
            val item = store.plan(day1, "x").item("x")
            store.record(item, OutcomeResult.DONE)
            store.manual("x", ActionStatus.DROPPED)
            assertFalse(store.marker(item))
            store.record(item, OutcomeResult.PARTIAL)
            assertEquals(ActionStatus.DROPPED, store.status("x"))
        }

    @Test
    fun `reopening by hand then a new done may close it again, and the new done owns the closure`() =
        runTest {
            val store = newStore().withActions("x")
            val first = store.plan(day1, "x").item("x")
            val second = store.plan(day2, "x").item("x")
            store.record(first, OutcomeResult.DONE)
            store.manual("x", ActionStatus.OPEN)
            assertFalse(store.marker(first))

            store.record(second, OutcomeResult.DONE)
            assertEquals(ActionStatus.DONE, store.status("x"))
            assertTrue(store.marker(second))
            assertFalse(store.marker(first))
        }

    @Test
    fun `repeated done across days, correcting the closer then the remaining done`() =
        runTest {
            val store = newStore().withActions("x")
            val first = store.plan(day1, "x").item("x")
            val second = store.plan(day2, "x").item("x")

            store.record(first, OutcomeResult.DONE)
            store.record(second, OutcomeResult.DONE) // history, status stays, no marker moves
            assertTrue(store.marker(first))
            assertFalse(store.marker(second))

            store.record(first, OutcomeResult.PARTIAL)
            assertEquals(ActionStatus.DONE, store.status("x"))
            assertFalse(store.marker(first))

            val effect = store.record(second, OutcomeResult.PARTIAL)
            assertEquals(ActionStatus.DONE, store.status("x"))
            assertTrue(effect.staysDoneNote)

            store.manual("x", ActionStatus.OPEN) // the line says the user reopens it in Actions
            assertEquals(ActionStatus.OPEN, store.status("x"))
        }

    @Test
    fun `done on a dropped action is history and a done to unknown reopens and counts as no outcome`() =
        runTest {
            val store = newStore().withActions("x", "y")
            val plan = store.plan(day1, "x", "y")
            store.manual("y", ActionStatus.DROPPED)
            store.record(plan.item("y"), OutcomeResult.DONE)
            assertEquals(ActionStatus.DROPPED, store.status("y"))
            assertFalse(store.marker(plan.item("y")))

            store.record(plan.item("x"), OutcomeResult.DONE)
            store.record(plan.item("x"), OutcomeResult.UNKNOWN)
            assertEquals(ActionStatus.OPEN, store.status("x"))
        }

    @Test
    fun `a save that does not change the status keeps the marker, one that does clears it`() =
        runTest {
            val store = newStore().withActions("x")
            val item = store.plan(day1, "x").item("x")
            store.record(item, OutcomeResult.DONE)

            assertNull(store.saveAction(store.actions().single().copy(title = "Renamed", priority = Priority.MUST)))
            assertTrue(store.marker(item))
            store.manual("x", ActionStatus.OPEN)
            assertFalse(store.marker(item))
        }

    // --- eligibility and the adjustment lock, at the store ---

    @Test
    fun `an outcome is refused for an unknown item, a left-out item, an earlier revision and a future day, writing nothing`() =
        runTest {
            val store = newStore()
            listOf("pa", "pb", "pc").forEach { store.saveProject(Project(it, "Project $it", null, ProjectState.ACTIVE)) }
            listOf("a" to "pa", "b" to "pb", "c" to "pc").forEach { (id, project) -> store.saveAction(Action(id, "Action $id", project)) }
            val rev1 = store.plan(day1, "a", "b", "c")
            val leftOut = rev1.items.single { it.kind == ItemKind.LEFT_OUT }
            val rev2 = store.plan(day1, "a", "b")
            val future = store.plan(day2, "a")
            val before = store.snapshot()

            fun refusal(
                id: String,
                today: ActiveDay,
            ) = runTest { assertIs<OutcomeRecording.Refused>(store.recordOutcome(id, OutcomeResult.DONE, today)).reason }

            assertEquals(
                OutcomeRefusal.ITEM_NOT_FOUND,
                assertIs<OutcomeRecording.Refused>(store.recordOutcome("nope", OutcomeResult.DONE, day2)).reason,
            )
            assertEquals(
                OutcomeRefusal.NOT_ELIGIBLE,
                assertIs<OutcomeRecording.Refused>(store.recordOutcome(leftOut.id, OutcomeResult.DONE, day2)).reason,
            )
            assertEquals(
                OutcomeRefusal.NOT_ELIGIBLE,
                assertIs<OutcomeRecording.Refused>(store.recordOutcome(rev1.item("a"), OutcomeResult.DONE, day2)).reason,
            )
            assertEquals(
                OutcomeRefusal.NOT_ELIGIBLE,
                assertIs<OutcomeRecording.Refused>(store.recordOutcome(future.item("a"), OutcomeResult.DONE, day1)).reason,
            )
            assertEquals(before, store.snapshot())
            // The latest revision of a day that is today is eligible, and so is an earlier day's.
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome(rev2.item("a"), OutcomeResult.PARTIAL, day2))
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome(future.item("a"), OutcomeResult.PARTIAL, day2))
        }

    @Test
    fun `a new revision is refused while any outcome is recorded, lifted when all are unknown, and keep original is unaffected`() =
        runTest {
            val store = newStore().withActions("x", "y")
            val plan = store.plan(day1, "x", "y")
            val proposal = assertNotNull(store.propose(day1, utc, null, setOf("x", "y"), emptyMap()))
            assertIs<ApprovalResult.Approved>(store.approve(proposal, utc)) // no outcome yet: allowed (rev 2)
            val current = checkNotNull(store.planFor(day1))
            val item = current.item("x")

            store.record(item, OutcomeResult.PARTIAL)
            val locked = assertNotNull(store.propose(day1, utc, null, setOf("x", "y"), emptyMap()))
            val before = store.snapshot()
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.OUTCOMES_RECORDED), store.approve(locked, utc))
            assertEquals(before, store.snapshot())

            store.record(item, OutcomeResult.UNKNOWN)
            val again = assertNotNull(store.propose(day1, utc, null, setOf("x", "y"), emptyMap()))
            val result = assertIs<ApprovalResult.Approved>(store.approve(again, utc))
            assertEquals(3, result.plan.plan.revision)
            assertEquals(
                plan.plan.id,
                store
                    .revisionsOf(day1)
                    .first()
                    .plan.id,
            ) // earlier revisions untouched
        }

    @Test
    fun `an outcome on the previous revision of a day does not lock the new one, only the current revision's does`() =
        runTest {
            val store = newStore().withActions("x")
            val rev1 = store.plan(day1, "x")
            store.record(rev1.item("x"), OutcomeResult.PARTIAL)
            // rev 1 has an outcome, so it is locked: that is the current revision.
            val proposal = assertNotNull(store.propose(day1, utc, null, setOf("x"), emptyMap()))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.OUTCOMES_RECORDED), store.approve(proposal, utc))
        }

    // --- atomicity ---

    @Test
    fun `recording rolls back the outcome when the status write fails`() =
        runTest {
            val store = newStore().withActions("x")
            val item = store.plan(day1, "x").item("x")
            val before = store.snapshot()
            opened.last().openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_action_update BEFORE UPDATE ON actions BEGIN SELECT RAISE(ABORT, 'no'); END",
            )

            assertFails { store.recordOutcome(item, OutcomeResult.DONE, day2) }

            assertEquals(before, store.snapshot())
            assertEquals(emptyList(), store.outcomes())
            assertEquals(ActionStatus.OPEN, store.status("x"))
        }

    @Test
    fun `a manual save rolls back its status when clearing the marker fails`() =
        runTest {
            val store = newStore().withActions("x")
            val item = store.plan(day1, "x").item("x")
            store.record(item, OutcomeResult.DONE)
            val before = store.snapshot()
            opened.last().openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_outcome_write BEFORE INSERT ON plan_outcomes BEGIN SELECT RAISE(ABORT, 'no'); END",
            )

            assertFails { store.saveAction(store.actions().single().copy(status = ActionStatus.OPEN)) }

            assertEquals(before, store.snapshot())
            assertEquals(ActionStatus.DONE, store.status("x"))
            assertTrue(store.marker(item))
        }

    @Test
    fun `a snapshot that breaks an outcome invariant is refused whole`() =
        runTest {
            val source = newStore().withActions("x")
            val item = source.plan(day1, "x").item("x")
            source.record(item, OutcomeResult.DONE)
            val good = source.snapshot()
            val bad =
                good.copy(
                    actions = good.actions.map { it.copy(value = it.value.copy(status = ActionStatus.OPEN)) }, // a marker on an open action
                )
            val target = newStore()
            target.saveSettings(PlanningSettings()) // not empty
            val empty = PlanningStore(newDatabase("planning-outcomes-empty.db")) { now++ }

            assertFails { empty.restoreIntoEmpty(bad) }

            assertEquals(0, empty.snapshot().counts.total)
            assertTrue(empty.restoreIntoEmpty(good))
            assertEquals(good, empty.snapshot())
        }

    // --- random sequences against the real store ---

    @Test
    fun `random sequences through the store keep the invariants and never reopen a hand-made closure`() =
        runTest {
            val ids = (0 until 8).map { "r$it" }
            val store = newStore().withActions(*ids.toTypedArray())
            val days = listOf(day1, ActiveDay(LocalDate.of(2026, 10, 4)), ActiveDay(LocalDate.of(2026, 10, 5)))
            val today = days.last()
            val itemsOf = days.map { store.plan(it, *ids.toTypedArray()) }
            ids.forEachIndexed { seed, id ->
                val random = Random(seed)
                val itemIds = itemsOf.map { it.item(id) }
                var handClosed = false
                repeat(25) {
                    val before = store.status(id)
                    if (random.nextInt(5) < 3) {
                        store.record(itemIds.random(random), OutcomeResult.entries.random(random), today)
                        if (handClosed) assertEquals(before, store.status(id), "seed $seed: an outcome moved a hand-made status")
                        if (before == ActionStatus.DROPPED) assertEquals(ActionStatus.DROPPED, store.status(id))
                    } else {
                        val saved = ActionStatus.entries.random(random)
                        store.manual(id, saved)
                        handClosed = saved == ActionStatus.DONE
                    }
                    val snapshot = store.snapshot()
                    assertNull(
                        outcomeInvariantViolation(
                            snapshot.outcomes,
                            snapshot.planItems,
                            snapshot.actions.associate {
                                it.value.id to
                                    it.value.status
                            },
                        ),
                        "seed $seed",
                    )
                }
            }
        }

    // --- Unfinished, from stored data ---

    @Test
    fun `unfinished entries from the store follow status and annotate from the latest earlier day`() =
        runTest {
            val store = newStore().withActions("x", "y", "z")
            val first = store.plan(day1, "x", "y", "z")
            val second = store.plan(day2, "x", "y")
            store.record(first.item("x"), OutcomeResult.DONE, day2)
            store.record(first.item("z"), OutcomeResult.DONE, day2)
            store.record(second.item("y"), OutcomeResult.BLOCKED, day2)
            store.manual("x", ActionStatus.OPEN) // reopened by hand: offered again though its last result is done
            val next = ActiveDay(LocalDate.of(2026, 10, 5))

            val entries =
                unfinishedEntries(next, store.latestPlans(), store.actions(), store.outcomes().associateBy { it.planItemId })

            assertEquals(listOf("x", "y"), entries.map { it.action.id }) // z is done, so not offered
            assertEquals(listOf(null, OutcomeResult.BLOCKED), entries.map { it.lastResult })
        }

    // --- export, import, and calls ---

    private fun info() = PlanningExportInfo(PlanningDatabase.SCHEMA_VERSION, "test", Instant.EPOCH, utc)

    private val ux = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a003"
    private val uy = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a004"

    /** Exports need UUID ids, so this store uses them. */
    private suspend fun fullStore(): PlanningStore {
        val store = newStore().withActions(ux, uy)
        val first = store.plan(day1, ux, uy, openingTime = LocalTime.of(8, 0))
        assertEquals(CallAuthorization.AUTHORIZED, store.authorizeOpeningCall(first.plan.id, day1)) // the user's explicit confirmation
        store.plan(day2, ux)
        store.record(first.item(ux), OutcomeResult.DONE, day2)
        store.record(first.item(uy), OutcomeResult.PARTIAL, day2)
        return store
    }

    // --- the call-authorization boundary ---

    @Test
    fun `an opening time given at approval is kept but requests nothing, so a retry has nothing to act on`() =
        runTest {
            val store = newStore().withActions(ux)
            val plan = store.plan(day1, ux, openingTime = LocalTime.of(8, 0))

            val call = store.calls().single()
            assertEquals(plan.plan.id, call.planId)
            assertEquals(LocalTime.of(8, 0), call.openingTime)
            assertEquals("NOT_LINKED", call.status)
            assertNull(call.detail)
            assertTrue(store.calls().none { it.status == "REQUESTED" }) // what a launch retry would select
            assertEquals(mapOf(plan.plan.id to LocalTime.of(8, 0)), store.snapshot().openingTimes)
        }

    @Test
    fun `a plan approved without a time has no call row, and cannot be authorized`() =
        runTest {
            val store = newStore().withActions(ux)
            val plan = store.plan(day1, ux)
            assertEquals(emptyList(), store.calls())
            assertEquals(CallAuthorization.NO_OPENING_TIME, store.authorizeOpeningCall(plan.plan.id, day1))
            assertEquals(CallAuthorization.NO_OPENING_TIME, store.authorizeOpeningCall("missing", day1))
            assertEquals(emptyList(), store.calls())
        }

    @Test
    fun `explicit authorization turns NOT_LINKED into REQUESTED, keeping the time, once`() =
        runTest {
            val store = newStore().withActions(ux)
            val plan = store.plan(day2, ux, openingTime = LocalTime.of(8, 30))

            assertEquals(CallAuthorization.AUTHORIZED, store.authorizeOpeningCall(plan.plan.id, day1)) // an earlier day than the plan's
            val call = store.calls().single()
            assertEquals("REQUESTED", call.status)
            assertEquals(LocalTime.of(8, 30), call.openingTime)

            val after = store.snapshot()
            assertEquals(CallAuthorization.NOT_UNLINKED, store.authorizeOpeningCall(plan.plan.id, day1))
            assertEquals(after, store.snapshot())
            assertEquals(call, store.calls().single())
        }

    @Test
    fun `authorization is refused for a past day and for an earlier revision, writing nothing`() =
        runTest {
            val store = newStore().withActions(ux)
            val rev1 = store.plan(day1, ux, openingTime = LocalTime.of(8, 0))
            val rev2 = store.plan(day1, ux, openingTime = LocalTime.of(9, 0))
            val before = store.snapshot()
            val callsBefore = store.calls()

            assertEquals(CallAuthorization.NOT_ELIGIBLE, store.authorizeOpeningCall(rev1.plan.id, day1)) // not the latest revision
            assertEquals(CallAuthorization.NOT_ELIGIBLE, store.authorizeOpeningCall(rev2.plan.id, day2)) // the day has passed
            assertEquals(before, store.snapshot())
            assertEquals(callsBefore, store.calls())
            assertEquals(setOf("NOT_LINKED"), store.calls().map { it.status }.toSet())

            assertEquals(CallAuthorization.AUTHORIZED, store.authorizeOpeningCall(rev2.plan.id, day1))
            assertEquals(listOf("NOT_LINKED", "REQUESTED"), store.calls().sortedBy { it.openingTime }.map { it.status })
        }

    @Test
    fun `authorization is one transaction, so a failed write leaves the call NOT_LINKED`() =
        runTest {
            val store = newStore().withActions(ux)
            val plan = store.plan(day1, ux, openingTime = LocalTime.of(8, 0))
            val before = store.calls()
            opened.last().openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_call_update BEFORE UPDATE ON plan_calls BEGIN SELECT RAISE(ABORT, 'no'); END",
            )

            assertFails { store.authorizeOpeningCall(plan.plan.id, day1) }

            assertEquals(before, store.calls())
        }

    @Test
    fun `a REQUESTED call is never exported, and import and replace give NOT_LINKED, never REQUESTED`() =
        runTest {
            val source = fullStore()
            assertEquals(listOf("REQUESTED"), source.calls().map { it.status })
            val payload = PlanningExportFormat.encode(PlanningExportContent(info(), source.snapshot()))
            val decoded = PlanningExportFormat.decode(payload, PlanningDatabase.SCHEMA_VERSION).snapshot

            val imported = PlanningStore(newDatabase("planning-outcomes-import.db")) { 1L }
            assertTrue(imported.restoreIntoEmpty(decoded))
            assertEquals(listOf("NOT_LINKED"), imported.calls().map { it.status })

            // Replace restores the same way, into a staged database, then swaps it in.
            val staged = PlanningStore(newDatabase("planning-outcomes-replace.db")) { 1L }
            assertTrue(staged.restoreIntoEmpty(decoded))
            assertTrue(staged.calls().none { it.status == "REQUESTED" })

            // Only the explicit operation can make a restored call REQUESTED, and a past day cannot.
            val restoredPlan = imported.snapshot().plans.single { imported.snapshot().openingTimes.containsKey(it.id) }
            assertEquals(CallAuthorization.NOT_ELIGIBLE, imported.authorizeOpeningCall(restoredPlan.id, day2))
            assertEquals(listOf("NOT_LINKED"), imported.calls().map { it.status })
            assertEquals(CallAuthorization.AUTHORIZED, imported.authorizeOpeningCall(restoredPlan.id, day1))
        }

    @Test
    fun `an export carries outcomes and opening times but no call status, and the restore is NOT_LINKED, never REQUESTED`() =
        runTest {
            val source = fullStore()
            val snapshot = source.snapshot()
            assertEquals(2, snapshot.outcomes.size)
            assertEquals(1, snapshot.openingTimes.size)
            val payload = PlanningExportFormat.encode(PlanningExportContent(info(), snapshot))
            val text = String(payload)
            assertFalse("REQUESTED" in text || "NOT_LINKED" in text || "status\":\"CONFIRMED" in text)

            val decoded = PlanningExportFormat.decode(payload, PlanningDatabase.SCHEMA_VERSION).snapshot
            val target = PlanningStore(newDatabase("planning-outcomes-restore.db")) { 9_000L }
            assertTrue(target.restoreIntoEmpty(decoded))

            assertEquals(snapshot, target.snapshot())
            val restored = target.calls()
            assertEquals(listOf("NOT_LINKED"), restored.map { it.status })
            assertEquals(LocalTime.of(8, 0), restored.single().openingTime)
            assertTrue(restored.none { it.status == "REQUESTED" })
            // The source keeps its own REQUESTED log; the snapshot never needed it.
            assertEquals(listOf("REQUESTED"), source.calls().map { it.status })
        }

    @Test
    fun `a plan without an opening time restores with no call row`() =
        runTest {
            val source = newStore().withActions("x")
            source.plan(day1, "x")
            val target = PlanningStore(newDatabase("planning-outcomes-nocall.db")) { 1L }
            assertTrue(target.restoreIntoEmpty(source.snapshot()))
            assertEquals(emptyList(), target.calls())
        }

    @Test
    fun `a schema 2 export restores with no outcomes and no calls, and a schema 1 one too`() =
        runTest {
            val source = fullStore()
            val v4 = String(PlanningExportFormat.encode(PlanningExportContent(info().copy(schemaVersion = 4), source.snapshot())))
            // Down to schema 2's shape: no schema 4 fields, then no schema 3 ones.
            val v2 =
                v4
                    .replace("\"schemaVersion\":4", "\"schemaVersion\":2")
                    .replace(Regex(",\"commitments\":\\[.*?],\"workingDays\":\\[.*?]}$"), "}")
                    .replace(Regex(",\"commitments\":\\d+,\"workingDays\":\\d+"), "")
                    .replace(Regex(",\"purpose\":null,\"doneWhen\":null,\"constraints\":null,\"blocker\":null"), "")
                    .replace(Regex(",\"outcomes\":\\[.*]}$"), "}")
                    .replace(Regex(",\"outcomes\":\\d+"), "")
                    .replace(Regex(",\"openingTime\":(null|\"[^\"]*\")"), "")
            val decoded = PlanningExportFormat.decode(v2.toByteArray(), PlanningDatabase.SCHEMA_VERSION)
            assertEquals(2, decoded.info.schemaVersion)

            val target = PlanningStore(newDatabase("planning-outcomes-v2.db")) { 1L }
            assertTrue(target.restoreIntoEmpty(decoded.snapshot))
            assertEquals(emptyList(), target.outcomes())
            assertEquals(emptyList(), target.calls())
            assertEquals(source.snapshot().plans, target.snapshot().plans)
        }

    @Test
    fun `restoring never touches the check-in database, and a restored past day offers no call`() =
        runTest {
            // Planning storage has no handle on checkins.db or an alarm: the restore path is PlanningStore alone,
            // and the only call rows it writes are NOT_LINKED. This asserts the rows, which is all it can write.
            val source = fullStore()
            val target = PlanningStore(newDatabase("planning-outcomes-links.db")) { 1L }
            assertTrue(target.restoreIntoEmpty(source.snapshot()))
            assertEquals(setOf("NOT_LINKED"), target.calls().map { it.status }.toSet())
            assertEquals(target.snapshot().plans.size, source.snapshot().plans.size)
        }
}
