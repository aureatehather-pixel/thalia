package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalRefusal
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanErrorKind
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

/** Adjust starts from the approved plan's choices, but only those that can still be planned. Real store, same calls as the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class AdjustPrefillTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "adjust-prefill-test.db"
    private var database: PlanningDatabase? = null
    private var now = 1_000L
    private val utc = ZoneId.of("UTC")
    private val day = ActiveDay(LocalDate.of(2026, 10, 4))
    private val pin = LocalTime.of(13, 0)

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    private suspend fun store(): PlanningStore {
        val db = plainPlanningDatabase(context, context.getDatabasePath(name)).also { database = it }
        return PlanningStore(db) { now++ }.also {
            it.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            listOf("x" to "Write chapter", "y" to "Call bank", "z" to "File form", "w" to "Water plants").forEach { (id, title) ->
                it.saveAction(Action(id, title))
            }
        }
    }

    private suspend fun PlanningStore.approveAll() =
        assertIs<ApprovalResult.Approved>(
            approve(assertNotNull(propose(day, utc, null, setOf("x", "y", "z", "w"), mapOf("y" to pin, "z" to LocalTime.of(15, 0)))), utc),
        ).plan

    private suspend fun PlanningStore.setStatus(
        id: String,
        status: ActionStatus,
    ) {
        val action = actions().single { it.id == id }
        saveAction(action.copy(status = status))
    }

    @Test
    fun `closed and dropped actions leave the selection and their pins, and the prefill is approvable`() =
        runTest {
            val store = store()
            store.approveAll()
            store.setStatus("z", ActionStatus.DONE)
            store.setStatus("w", ActionStatus.DROPPED)

            val prefill = adjustPrefill(checkNotNull(store.planFor(day)), store.actions())
            assertEquals(setOf("x", "y"), prefill.selected)
            assertEquals(mapOf("y" to pin), prefill.pins)
            assertEquals(listOf("File form", "Water plants"), prefill.excludedTitles)

            val proposal = assertNotNull(store.propose(day, utc, null, prefill.selected, prefill.pins))
            assertTrue(proposal.canApprove, "errors: ${proposal.errors}")
            assertEquals(listOf("x", "y"), proposal.items.map { it.actionId }.sorted())
        }

    @Test
    fun `an action that cannot be found is excluded too, and nothing is excluded when all are open`() =
        runTest {
            val store = store()
            store.approveAll()
            val approved = checkNotNull(store.planFor(day))

            val all = adjustPrefill(approved, store.actions())
            assertEquals(setOf("x", "y", "z", "w"), all.selected)
            assertEquals(emptyList(), all.excludedTitles)
            assertEquals(
                "Adjusting revision 1. The approved plan stays in force until you approve a new one.",
                adjustMessage(1, all),
            )

            val missing = adjustPrefill(approved, store.actions().filterNot { it.id == "y" })
            assertEquals(setOf("x", "z", "w"), missing.selected)
            assertEquals(mapOf("z" to LocalTime.of(15, 0)), missing.pins)
            assertEquals(listOf("Call bank"), missing.excludedTitles)
            assertEquals(
                "Adjusting revision 1. The approved plan stays in force until you approve a new one. " +
                    "Left out because they are no longer open: Call bank.",
                adjustMessage(1, missing),
            )
        }

    @Test
    fun `reading the prefill changes nothing, and the original plan and its snapshots stay as approved`() =
        runTest {
            val store = store()
            val original = store.approveAll()
            store.setStatus("z", ActionStatus.DONE)

            adjustPrefill(checkNotNull(store.planFor(day)), store.actions())
            val after = checkNotNull(store.planFor(day))
            assertEquals(original, after)
            assertEquals(1, after.plan.revision)
            assertTrue(after.items.any { it.title == "File form" }, "the approved snapshot keeps the closed action")
        }

    @Test
    fun `an action that closes after the prefill still blocks the proposal and refuses a stale approval`() =
        runTest {
            val store = store()
            store.approveAll()
            val prefill = adjustPrefill(checkNotNull(store.planFor(day)), store.actions())
            assertEquals(setOf("x", "y", "z", "w"), prefill.selected)
            val shown = assertNotNull(store.propose(day, utc, null, prefill.selected, prefill.pins))
            assertTrue(shown.canApprove)

            store.setStatus("w", ActionStatus.DONE)

            val stale = assertNotNull(store.propose(day, utc, null, prefill.selected, prefill.pins))
            assertTrue(!stale.canApprove)
            assertEquals(PlanErrorKind.ACTION_UNAVAILABLE, stale.errors.single().kind)
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.CHANGED), store.approve(shown, utc))
            assertEquals(ApprovalResult.Refused(ApprovalRefusal.INVALID), store.approve(stale, utc))
            assertEquals(1, checkNotNull(store.planFor(day)).plan.revision)
        }
}
