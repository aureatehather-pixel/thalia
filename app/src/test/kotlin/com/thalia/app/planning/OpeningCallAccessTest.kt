package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.CallAuthorization
import com.thalia.app.planning.data.FakeSealer
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.plainOpener
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.InMemoryOccurrenceStore
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The call link against the planning data as production reaches it, through [PlanningAccess], whose lock Import, Replace and
 * set-aside also take. A Replace started while a call is being processed must wait, and a Replace that lands first must leave
 * the old authorization with no effect. Nothing here claims an atomic commit across the two databases.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpeningCallAccessTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val files = PlanningFiles.of(context, "occ").also { }
    private val opened = mutableListOf<PlanningDatabase>()
    private val day = ActiveDay(LocalDate.of(2026, 9, 20))
    private val ten = LocalTime.of(10, 0)
    private val clock = TestClock(Instant.parse("2026-09-20T00:00:00Z").toEpochMilli())
    private val inner = InMemoryOccurrenceStore()
    private val alarms = FakeAlarmScheduler(clock)
    private val writer = CoroutineScope(Dispatchers.Default)

    private class HookedStore(
        private val real: OccurrenceStore,
    ) : OccurrenceStore by real {
        var beforeInsert: suspend () -> Unit = {}

        override suspend fun insert(occurrence: ScheduledOccurrence): Boolean {
            val hook = beforeInsert
            beforeInsert = {}
            hook()
            return real.insert(occurrence)
        }
    }

    private val m2 = HookedStore(inner)
    private val scheduler = OccurrenceScheduler(m2, alarms, clock)
    private val link =
        OpeningCallLink({ CheckInZone.Known(KUALA_LUMPUR) }, m2, scheduler, CheckInConfirmer(m2, scheduler, clock), clock)
    private val access: PlanningAccess

    init {
        val (opener, replacement) = plainOpener(context, files, FakeSealer(), opened)
        access = PlanningAccess(opener, replacement, Dispatchers.Unconfined)
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        (files.live + files.staged + listOf(files.journal)).forEach(File::delete)
        files.setAsideRoot.deleteRecursively()
    }

    /** A REQUESTED opening call at ten o'clock, made through the access, as the screen does. */
    private suspend fun requestedPlan(): String =
        access.withStore { store ->
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            store.saveAction(Action("a", "Write"))
            val proposal = assertNotNull(store.propose(day, KUALA_LUMPUR, null, setOf("a"), emptyMap()))
            val plan = assertIs<ApprovalResult.Approved>(store.approve(proposal, KUALA_LUMPUR, openingTime = ten)).plan
            assertEquals(CallAuthorization.AUTHORIZED, store.authorizeOpeningCall(plan.plan.id, day))
            plan.plan.id
        }

    private suspend fun statuses() = access.withStore { store -> store.calls().map { it.status } }

    @Test
    fun `a Replace started while a call is processed waits, and the imported plan is NOT_LINKED and never retried`() =
        runTest {
            requestedPlan()
            val imported = assertNotNull(access.snapshot())
            var replace: Deferred<File>? = null
            var callExistedWhenReplaceRan: Boolean? = null
            m2.beforeInsert = {
                replace =
                    writer.async {
                        access.replace(imported).also { callExistedWhenReplaceRan = inner.slot("2026-09-20-opening").isNotEmpty() }
                    }
                withContext(Dispatchers.IO) { delay(200) } // time for the Replace to try, if nothing stops it
            }

            val report = access.withStore { link.retry(it) }
            replace!!.await()

            assertEquals(RetryReport(1, 0), report)
            assertEquals(true, callExistedWhenReplaceRan, "the Replace ran only after the call it could have invalidated finished")
            assertEquals(listOf("NOT_LINKED"), statuses(), "restored plans are NOT_LINKED, never the old REQUESTED or CONFIRMED")

            val before = inner.slot("2026-09-20-opening")
            assertEquals(RetryReport(0, 0), access.withStore { link.retry(it) })
            assertEquals(before, inner.slot("2026-09-20-opening"))
            assertEquals(1, before.size)
        }

    @Test
    fun `a Replace that lands before processing leaves the old authorization with no effect`() =
        runTest {
            requestedPlan()
            val imported = assertNotNull(access.snapshot())
            access.replace(imported)
            assertEquals(listOf("NOT_LINKED"), statuses())

            assertEquals(RetryReport(0, 0), access.withStore { link.retry(it) })

            assertEquals(emptyList(), inner.slot("2026-09-20-opening"))
            assertEquals(emptyList(), alarms.armCalls)
        }

    @Test
    fun `an import into an empty database is NOT_LINKED and a retry afterwards schedules nothing`() =
        runTest {
            requestedPlan()
            val snapshot = assertNotNull(access.snapshot())
            access.replace(snapshot) // stands in for any restore: the call status is never carried
            assertTrue(statuses().all { it == "NOT_LINKED" })
            assertEquals(RetryReport(0, 0), access.withStore { link.retry(it) })
            assertEquals(emptyList(), inner.slot("2026-09-20-opening"))
        }
}
