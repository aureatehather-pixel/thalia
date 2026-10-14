package com.thalia.app.ui

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.RoomCheckInZoneSource
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.app.voicecheckins.data.CheckInDatabase
import com.thalia.app.voicecheckins.data.RoomOccurrenceStore
import com.thalia.app.voicecheckins.schedule.DUE
import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.InMemoryOccurrenceStore
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.ReconcileTrigger
import com.thalia.app.voicecheckins.schedule.Reconciler
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.app.voicecheckins.schedule.occurrence
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Default opening and closing times: validation, the real reconciler's behaviour after a change, persistence in Room with a
 * non-default zone, and that an approved plan's own opening time is untouched. Robolectric's plain SQLite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DefaultTimesTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val base = CheckInPlan.default(KUALA_LUMPUR)

    private fun valid(
        plan: CheckInPlan,
        o: String,
        c: String,
    ) = assertIs<DefaultTimesChange.Valid>(planWithDefaults(plan, o, c))

    private fun invalid(
        plan: CheckInPlan,
        o: String,
        c: String,
    ) = assertIs<DefaultTimesChange.Invalid>(planWithDefaults(plan, o, c)).message

    @Test
    fun `valid times change only the two times and keep the zone and each switch`() {
        val off = base.withEnabled(CheckInType.CLOSING, false)
        val v = valid(off, "08:30", "19:45")
        assertEquals(LocalTime.of(8, 30), v.plan.opening.localTime)
        assertEquals(LocalTime.of(19, 45), v.plan.closing.localTime)
        assertEquals(KUALA_LUMPUR, v.plan.zone)
        assertTrue(v.plan.opening.enabled)
        assertFalse(v.plan.closing.enabled, "a switched-off closing slot stays off")
        assertTrue(v.changed)
    }

    @Test
    fun `typing the stored times is not a change`() {
        assertFalse(valid(base, "10:00", "15:00").changed)
        assertTrue(valid(base, " 9:00 ", "15:00").changed, "a one-digit hour is padded, not refused")
    }

    @Test
    fun `bad format, equal times and an opening after the closing are refused with the plan untouched`() {
        listOf("", "25:00", "10:00:30", "ten", "10.00").forEach { assertTrue("HH:mm" in invalid(base, it, "15:00"), it) }
        assertTrue("HH:mm" in invalid(base, "10:00", ""))
        assertTrue("before" in invalid(base, "15:00", "15:00"))
        assertTrue("before" in invalid(base, "16:00", "15:00"))
        assertTrue("before" in invalid(base, "00:00", "00:00"))
    }

    @Test
    fun `saving new defaults leaves every occurrence, every alarm and the report unchanged`() =
        runTest {
            val clock = TestClock(DUE.toEpochMilli() - 3_600_000)
            val store = InMemoryOccurrenceStore()
            val alarms = FakeAlarmScheduler(clock)
            val reconciler = Reconciler(store, alarms, activeOccurrenceId = { null }, clock = clock)
            val future = occurrence("2026-09-20-closing", CheckInType.CLOSING, DUE.plusSeconds(18_000))
            val opening = occurrence("2026-09-20-opening", CheckInType.OPENING, DUE)
            val past = occurrence("2026-09-19-closing", CheckInType.CLOSING, DUE.minusSeconds(86_400))
            store.insert(future)
            store.insert(opening)
            store.insert(past)
            store.transition(
                past.id,
                CheckInStatus.SCHEDULED,
                CheckInStatus.MISSED,
                DUE,
                com.thalia.voicecheckins.domain.OccurrenceReason.ALARM_MISSING,
            )
            reconciler.reconcile(ReconcileTrigger.MANUAL)
            val rowsBefore = listOf(future.id, opening.id, past.id).map { store.get(it) }
            val alarmsBefore = alarms.armed.toMap()
            val armCallsBefore = alarms.armCalls.size

            store.savePlan((valid(store.plan(), "07:00", "21:00")).plan)
            val report = reconciler.reconcile(ReconcileTrigger.MANUAL)

            assertEquals(rowsBefore, listOf(future.id, opening.id, past.id).map { store.get(it) }, "no occurrence moved or changed")
            assertEquals(alarmsBefore, alarms.armed.toMap(), "no alarm added, moved or removed")
            assertEquals(armCallsBefore + 2, alarms.armCalls.size, "the pass re-arms the same two, as it does every time")
            assertTrue(report.changes.isEmpty())
            assertEquals(3, store.open().size + 1, "two scheduled stay open, one missed stays ended")
            assertEquals(LocalTime.of(7, 0), store.plan().opening.localTime)
        }

    @Test
    fun `a default change never creates an occurrence for any day`() =
        runTest {
            val clock = TestClock(DUE.toEpochMilli() - 3_600_000)
            val store = InMemoryOccurrenceStore()
            val alarms = FakeAlarmScheduler(clock)
            store.savePlan(valid(store.plan(), "07:00", "21:00").plan)
            Reconciler(store, alarms, { null }, clock).reconcile(ReconcileTrigger.APP_LAUNCH)
            assertTrue(store.open().isEmpty())
            assertTrue(alarms.armed.isEmpty())
        }

    @Test
    fun `defaults persist in Room with a non-default zone and the zone and switches survive`() =
        runTest {
            val newYork = ZoneId.of("America/New_York")
            val file = "default-times-test.db"
            val first = Room.databaseBuilder(context, CheckInDatabase::class.java, file).build()
            val before = RoomOccurrenceStore(first) { newYork }
            val seeded = before.plan().withEnabled(CheckInType.OPENING, false)
            before.savePlan(seeded)
            before.savePlan(valid(before.plan(), "06:45", "20:15").plan)
            first.close()
            val second = Room.databaseBuilder(context, CheckInDatabase::class.java, file).build()
            try {
                val after = RoomOccurrenceStore(second) { KUALA_LUMPUR }.plan()
                assertEquals(LocalTime.of(6, 45), after.opening.localTime)
                assertEquals(LocalTime.of(20, 15), after.closing.localTime)
                assertEquals(newYork, after.zone, "the stored zone is kept, not the phone's")
                assertFalse(after.opening.enabled)
                assertTrue(after.closing.enabled)
                val zone = assertIs<CheckInZone.Known>(RoomCheckInZoneSource(second).read())
                assertEquals(LocalTime.of(6, 45), zone.defaultOpening, "the plan form's suggestion reads the stored opening")
                assertFalse(zone.openingEnabled)
            } finally {
                second.close()
                context.deleteDatabase(file)
            }
        }

    @Test
    fun `an empty check-in database has no suggestion and reading it creates nothing`() =
        runTest {
            val db = Room.inMemoryDatabaseBuilder(context, CheckInDatabase::class.java).build()
            try {
                assertIs<CheckInZone.Unusable>(RoomCheckInZoneSource(db).read())
                assertTrue(db.schedules().all().isEmpty())
            } finally {
                db.close()
            }
        }

    @Test
    fun `an approved plan keeps its own opening time and no call is requested when the defaults change`() =
        runTest {
            val name = "default-times-planning.db"
            val planning = plainPlanningDatabase(context, context.getDatabasePath(name))
            val checkIns = Room.inMemoryDatabaseBuilder(context, CheckInDatabase::class.java).build()
            try {
                var now = 1_000L
                val store = PlanningStore(planning) { now++ }
                store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
                store.saveAction(Action("a", "Action a"))
                val day = ActiveDay(LocalDate.of(2026, 10, 3))
                val proposal = assertNotNull(store.propose(day, ZoneId.of("UTC"), null, setOf("a"), emptyMap()))
                val plan =
                    assertIs<ApprovalResult.Approved>(
                        store.approve(proposal, ZoneId.of("UTC"), openingTime = LocalTime.of(9, 5)),
                    ).plan
                val callsBefore = store.calls()
                assertEquals(1, callsBefore.size)

                val m2 = RoomOccurrenceStore(checkIns) { ZoneId.of("UTC") }
                m2.savePlan(valid(m2.plan(), "07:00", "21:00").plan)

                assertEquals(callsBefore, store.calls(), "the plan's opening call row is byte for byte what it was")
                assertEquals(LocalTime.of(9, 5), store.calls().single().openingTime)
                assertTrue(store.calls().none { it.status == "REQUESTED" })
                assertEquals(plan, store.planFor(day))
            } finally {
                planning.close()
                checkIns.close()
                context.deleteDatabase(name)
            }
        }

    @Test
    fun `only the defaults card writes the check-in plan, and it reaches no scheduling or planning code`() {
        fun files(root: String) = File(root).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        val all = files("src/main") + files("../core/src/main") + files("../openai-provider/src") + files("../chatgpt-provider/src")
        assertTrue(all.size > 100)
        assertEquals(
            listOf("DefaultTimes.kt", "OccurrenceStore.kt", "RoomOccurrenceStore.kt"),
            all.filter { "savePlan(" in it.readText() && !it.path.contains("/test/") }.map { it.name }.sorted(),
        )
        val card = File("src/main/kotlin/com/thalia/app/ui/DefaultTimes.kt").readText()
        listOf(
            "PlanningStore",
            "authorizeOpeningCall",
            "checkInConfirmer",
            "AlarmScheduler",
            "Reconciler",
            "ReminderService",
            "conversation",
        ).forEach { assertFalse(it in card, it) }
    }
}
