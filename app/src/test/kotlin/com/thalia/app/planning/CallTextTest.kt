package com.thalia.app.planning

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.PlanCall
import com.thalia.app.voicecheckins.data.CheckInDatabase
import com.thalia.app.voicecheckins.data.RoomOccurrenceStore
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.planning.ActiveDay
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The words of the call section, and M2's stored opening slot as the link reads it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CallTextTest {
    private val day = ActiveDay(LocalDate.of(2026, 9, 20))
    private val eight = LocalTime.of(8, 0)

    private fun call(
        status: CallStatus,
        detail: CallDetail? = null,
    ) = PlanCall("p", eight, status.name, detail?.name, 0)

    @Test
    fun `every stored status has its own plain line, and only a confirmed one says confirmed`() {
        val lines = CallStatus.entries.map { callStatusText(call(it)) }
        assertEquals(CallStatus.entries.size, lines.toSet().size)
        assertTrue("not linked on this phone" in callStatusText(call(CallStatus.NOT_LINKED)))
        assertTrue("requested, not confirmed" in callStatusText(call(CallStatus.REQUESTED)))
        assertEquals(listOf(CallStatus.CONFIRMED), CallStatus.entries.filter { "confirmed." in callStatusText(call(it)) })
        assertTrue("inside the quiet period" in callStatusText(call(CallStatus.REFUSED, CallDetail.IN_QUIET_PERIOD)))
        assertTrue("already rang" in callStatusText(call(CallStatus.NEEDS_ATTENTION, CallDetail.CALL_ENDED)))
    }

    @Test
    fun `every detail has words`() {
        CallDetail.entries.forEach {
            assertTrue(callStatusText(call(CallStatus.NEEDS_ATTENTION, it)).length > "Opening call 08:00: needs attention, .".length, "$it")
        }
    }

    @Test
    fun `the live preview says what is there and never offers to move or revive on its own`() {
        fun text(slot: SlotState?) = callPreviewText(day, eight, CallPreview(null, slot))
        assertTrue("no call is set" in text(SlotState.Empty))
        assertTrue("already confirmed for this day at 08:00" in text(SlotState.ScheduledAtTime))
        assertTrue("never moved without your tick" in text(SlotState.ScheduledElsewhere(LocalTime.of(10, 0))))
        assertTrue("never revived without your tick" in text(SlotState.EndedUnrung))
        assertTrue("in progress" in text(SlotState.InProgress))
        assertTrue("gets one call" in text(SlotState.Ended))
        assertTrue("not possible, inside the quiet period" in callPreviewText(day, eight, CallPreview(CallDetail.IN_QUIET_PERIOD, null)))
    }

    @Test
    fun `an explicit action reports what happened, and a stale or unticked one says nothing was done`() {
        assertTrue("Nothing was done" in attemptText(CallAttempt.NotTicked))
        assertTrue("Nothing was done" in attemptText(CallAttempt.NotEligible))
        assertTrue("Nothing was done" in attemptText(CallAttempt.Stale(Recovery.NEW_CALL)))
        assertEquals("The opening call is confirmed.", attemptText(CallAttempt.Done(CallVerdict(CallStatus.CONFIRMED))))
        assertTrue(
            "Android is not allowing exact alarms" in
                attemptText(CallAttempt.Done(CallVerdict(CallStatus.NEEDS_ATTENTION, CallDetail.PERMISSION_REFUSED))),
        )
    }

    @Test
    fun `the two recovery offers are worded from the slot`() {
        assertEquals("Move the 10:00 call to 08:00", recoveryOffer(Recovery.MOVE, SlotState.ScheduledElsewhere(LocalTime.of(10, 0)), eight))
        assertEquals("Confirm a new call for this day at 08:00", recoveryOffer(Recovery.NEW_CALL, SlotState.EndedUnrung, eight))
    }

    @Test
    fun `a tick belongs to exactly one target, so a change of plan, time, call, preview, offer or attempt starts unticked`() {
        val base =
            TickTarget(
                "p",
                call(CallStatus.NEEDS_ATTENTION, CallDetail.OTHER_TIME_CONFIRMED),
                CallPreview(null, SlotState.ScheduledElsewhere(LocalTime.of(10, 0))),
                "Move the 10:00 call to 08:00",
                0,
            )
        assertEquals(base, base.copy())
        listOf(
            base.copy(planId = "q"),
            base.copy(call = base.call.copy(openingTime = LocalTime.of(9, 0))),
            base.copy(call = base.call.copy(updatedAtMillis = 1)),
            base.copy(call = base.call.copy(status = CallStatus.REFUSED.name)),
            base.copy(preview = CallPreview(null, SlotState.EndedUnrung)),
            base.copy(preview = CallPreview(null, SlotState.ScheduledElsewhere(LocalTime.of(11, 0)))),
            base.copy(preview = null),
            base.copy(label = "Confirm a new call for this day at 08:00"),
            base.copy(attempts = 1),
        ).forEach { assertNotEquals(base, it) }
    }

    // --- M2's stored opening slot, as the zone source reports it ---

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val ten = java.time.LocalTime.of(10, 0)

    @Test
    fun `the zone source reads the opening slot's switch without creating or changing anything in M2`() =
        runTest {
            val db = Room.inMemoryDatabaseBuilder(context, CheckInDatabase::class.java).allowMainThreadQueries().build()
            try {
                val source = RoomCheckInZoneSource(db)
                assertEquals(CheckInZone.Unusable(CheckInZoneProblem.NO_CHECK_IN_PLAN), source.read())
                assertEquals(0, db.schedules().all().size, "reading created no default plan")

                val store = RoomOccurrenceStore(db) { KUALA_LUMPUR }
                val plan = store.plan()
                assertEquals(CheckInZone.Known(KUALA_LUMPUR, openingEnabled = true, defaultOpening = ten), source.read())

                store.savePlan(plan.withEnabled(CheckInType.OPENING, false))
                assertEquals(CheckInZone.Known(KUALA_LUMPUR, openingEnabled = false, defaultOpening = ten), source.read())
                store.savePlan(plan.withEnabled(CheckInType.OPENING, true).withEnabled(CheckInType.CLOSING, false))
                assertEquals(CheckInZone.Known(KUALA_LUMPUR, openingEnabled = true, defaultOpening = ten), source.read())
            } finally {
                db.close()
            }
        }
}
