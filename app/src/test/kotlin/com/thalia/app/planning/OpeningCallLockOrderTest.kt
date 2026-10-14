package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.FakeSealer
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.plainOpener
import com.thalia.app.voicecheckins.data.CheckInDatabase
import com.thalia.app.voicecheckins.data.RoomOccurrenceStore
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.ReconcileTrigger
import com.thalia.app.voicecheckins.schedule.Reconciler
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import kotlin.test.assertTrue

/**
 * The lock order is the [PlanningAccess] mutex, then one planning transaction, then M2 (its Room database and the alarm
 * scheduler), and never the other way round: M2 code never reaches planning. This runs every kind of caller at once, on
 * real threads and with M2 on a real Room database, and requires all of it to finish, with M2 still holding at most one
 * live call for the slot. A reversed order anywhere would deadlock and fail the timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class OpeningCallLockOrderTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val files = PlanningFiles.of(context, "lockorder")
    private val opened = mutableListOf<PlanningDatabase>()
    private lateinit var m2Database: CheckInDatabase
    private val day = ActiveDay(LocalDate.of(2026, 9, 20))
    private val clock = TestClock(Instant.parse("2026-09-20T00:00:00Z").toEpochMilli())

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        if (this::m2Database.isInitialized) m2Database.close()
        (files.live + files.staged + listOf(files.journal)).forEach(File::delete)
        files.setAsideRoot.deleteRecursively()
    }

    @Test
    fun `retry, confirm, approvals, snapshots, Replace and M2's own writers together finish without deadlock`() {
        m2Database = Room.inMemoryDatabaseBuilder(context, CheckInDatabase::class.java).build()
        val m2 = RoomOccurrenceStore(m2Database) { KUALA_LUMPUR }
        val alarms = FakeAlarmScheduler(clock)
        val scheduler = OccurrenceScheduler(m2, alarms, clock)
        val confirmer = CheckInConfirmer(m2, scheduler, clock)
        val link = OpeningCallLink({ CheckInZone.Known(KUALA_LUMPUR) }, m2, scheduler, confirmer, clock)
        val (opener, replacement) = plainOpener(context, files, FakeSealer(), opened)
        val access = PlanningAccess(opener, replacement, Dispatchers.IO)
        val ten = LocalTime.of(10, 0)

        suspend fun approveWithTime(): String? =
            access.withStore { store ->
                val proposal = store.propose(day, KUALA_LUMPUR, null, setOf("a"), emptyMap()) ?: return@withStore null
                (store.approve(proposal, KUALA_LUMPUR, openingTime = ten) as? ApprovalResult.Approved)?.plan?.plan?.id
            }

        runBlocking(Dispatchers.Default) {
            access.withStore { store ->
                store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
                store.saveAction(Action("a", "Write"))
            }
            approveWithTime()
            val failures = mutableListOf<Throwable>()

            suspend fun guarded(block: suspend () -> Unit) =
                try {
                    block()
                } catch (e: Throwable) {
                    synchronized(failures) { failures += e }
                }

            withTimeout(60_000) {
                (0 until 6)
                    .flatMap {
                        listOf(
                            async { guarded { access.withStore { link.retry(it) } } },
                            async { guarded { approveWithTime() } },
                            async {
                                guarded {
                                    access.withStore { store ->
                                        val latest = store.planFor(day)!!
                                        link.confirm(store, latest.plan.id, ticked = true)
                                    }
                                }
                            },
                            async { guarded { access.snapshot() } },
                            async { guarded { link.preview(OpeningCall(day, KUALA_LUMPUR, LocalTime.of(4, 0), ten)) } },
                            async { guarded { confirmer.confirm(CheckInType.OPENING, day.date) } },
                            async { guarded { confirmer.cancel(CheckInType.OPENING, day.date) } },
                            async { guarded { Reconciler(m2, alarms, { null }, clock).reconcile(ReconcileTrigger.MANUAL) } },
                        )
                    }.awaitAll()
            }

            assertEquals(emptyList(), failures)
            val rows = m2.slot("2026-09-20-opening")
            assertTrue(rows.count { it.isEligible } <= 1, "a slot never holds two live calls")
            assertEquals(rows.map { it.revision }, (1..rows.size).toList(), "revisions run 1..n with no gap or duplicate")
        }
    }
}
