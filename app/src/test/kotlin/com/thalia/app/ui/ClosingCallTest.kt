package com.thalia.app.ui

import com.thalia.app.voicecheckins.schedule.CancelResult
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.ConfirmResult
import com.thalia.app.voicecheckins.schedule.DUE
import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.InMemoryOccurrenceStore
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import com.thalia.voicecheckins.domain.occurrenceOn
import kotlinx.coroutines.test.runTest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The closing-call control is the existing confirmer behind explicit taps. These run it against the real rules and test doubles. */
class ClosingCallTest {
    private val today = LocalDate.of(2026, 9, 20)
    private val clock = TestClock() // a minute before 10:00 on the 20th in Kuala Lumpur
    private val alarms = FakeAlarmScheduler(clock)
    private val store = InMemoryOccurrenceStore()
    private val confirmer = CheckInConfirmer(store, OccurrenceScheduler(store, alarms, clock), clock)

    private suspend fun view(date: LocalDate = today): ClosingCallView {
        val closing = store.plan()[CheckInType.CLOSING]
        return ClosingCallView(
            date,
            closing.localTime,
            closing.enabled,
            store.slot(closing.occurrenceOn(date).slot).lastOrNull(),
            store.quietPeriod(),
        )
    }

    private fun at(text: String) = LocalTime.parse(text)

    @Test
    fun `nothing is confirmed until the person confirms`() =
        runTest {
            val v = view()
            assertEquals(ClosingCallStatus.NOT_CONFIRMED, closingStatus(v))
            assertEquals("Not confirmed for Sun 20 Sep. Thalia rings only for a call you confirm.", closingStatusText(v))
            assertTrue(alarms.armed.isEmpty(), "looking at the control arms nothing")
        }

    @Test
    fun `confirming shows the confirmed time and arms exactly one alarm`() =
        runTest {
            val result = confirmer.reschedule(CheckInType.CLOSING, today, at("15:00"))

            assertEquals("Confirmed: the closing call will ring Sun 20 Sep 15:00.", closingResultText(result))
            val v = view()
            assertEquals(ClosingCallStatus.CONFIRMED, closingStatus(v))
            assertEquals("Confirmed for Sun 20 Sep 15:00.", closingStatusText(v))
            assertEquals(setOf("2026-09-20-closing"), alarms.armed.keys)
        }

    @Test
    fun `changing the time retires the old call, arms the new one, and shows the new time`() =
        runTest {
            confirmer.reschedule(CheckInType.CLOSING, today, at("15:00"))

            val moved = confirmer.reschedule(CheckInType.CLOSING, today, at("16:30"))

            assertEquals("Moved: the closing call will now ring Sun 20 Sep 16:30.", closingResultText(moved))
            assertEquals("Confirmed for Sun 20 Sep 16:30.", closingStatusText(view()))
            assertEquals(1, alarms.armed.size, "only the new revision has an alarm")
            assertEquals(2, store.slot("2026-09-20-closing").size, "the history keeps both revisions")
        }

    @Test
    fun `choosing the same time again changes nothing`() =
        runTest {
            confirmer.reschedule(CheckInType.CLOSING, today, at("15:00"))
            val again = confirmer.reschedule(CheckInType.CLOSING, today, at("15:00"))
            assertEquals("It is already at Sun 20 Sep 15:00. Nothing changed.", closingResultText(again))
            assertEquals(1, store.slot("2026-09-20-closing").size)
        }

    @Test
    fun `cancelling withdraws the call and disarms its alarm, and the day can be confirmed again`() =
        runTest {
            confirmer.reschedule(CheckInType.CLOSING, today, at("15:00"))

            val cancelled = confirmer.cancel(CheckInType.CLOSING, today)

            assertEquals("Cancelled: the closing call for Sun 20 Sep 15:00 will not ring.", closingCancelText(cancelled))
            assertTrue(alarms.armed.isEmpty())
            val v = view()
            assertEquals(ClosingCallStatus.NOT_CONFIRMED, closingStatus(v))
            assertTrue("An earlier one was cancelled." in closingStatusText(v))
            assertIs<ConfirmResult.Confirmed>(confirmer.reschedule(CheckInType.CLOSING, today, at("15:00")))
            assertEquals("There is no scheduled closing call to cancel for that day.", closingCancelText(CancelResult.NothingToCancel))
        }

    @Test
    fun `a time inside the quiet period is refused and nothing is recorded or moved`() =
        runTest {
            store.saveQuietPeriod(QuietPeriod(at("22:00"), at("07:00"), enabled = true))
            val refused = confirmer.reschedule(CheckInType.CLOSING, today, at("23:00"))
            assertTrue("is inside your quiet period" in closingResultText(refused))
            assertTrue(store.slot("2026-09-20-closing").isEmpty())

            confirmer.reschedule(CheckInType.CLOSING, today, at("21:00"))
            val stillRefused = confirmer.reschedule(CheckInType.CLOSING, today, at("23:30"))
            assertIs<ConfirmResult.InQuietPeriod>(stillRefused)
            assertEquals("Confirmed for Sun 20 Sep 21:00.", closingStatusText(view()), "the scheduled call was not moved")
        }

    @Test
    fun `a time that has passed and a missing exact-alarm permission are refused in plain words`() =
        runTest {
            clock.set(DUE.plusSeconds(8 * 3600)) // after 15:00 on the 20th
            val passed = confirmer.reschedule(CheckInType.CLOSING, today, at("15:00"))
            assertTrue("is not in the future" in closingResultText(passed))
            assertTrue(store.slot("2026-09-20-closing").isEmpty().not() || alarms.armed.isEmpty())

            alarms.permitted = false
            clock.set(DUE)
            assertEquals(
                "Android is not allowing exact alarms for Thalia, so nothing was scheduled. Allow Alarms & reminders, then confirm again.",
                closingResultText(confirmer.reschedule(CheckInType.CLOSING, today, at("16:00"))),
            )
            assertTrue(alarms.armed.isEmpty())
        }

    @Test
    fun `a closing call that already rang cannot be confirmed again`() =
        runTest {
            val rang =
                ScheduledOccurrence("2026-09-20-closing", CheckInType.CLOSING, DUE.plusSeconds(18_000), KUALA_LUMPUR)
                    .transitionTo(CheckInStatus.RINGING, DUE.plusSeconds(18_000))
                    .transitionTo(CheckInStatus.ANSWERED, DUE.plusSeconds(18_005))
                    .transitionTo(CheckInStatus.COMPLETED, DUE.plusSeconds(18_100))
            val v = view().copy(latest = rang)
            assertEquals(ClosingCallStatus.USED, closingStatus(v))
            assertEquals("The closing call for Sun 20 Sep already rang (completed). A day gets one call.", closingStatusText(v))
            val answered =
                ScheduledOccurrence("2026-09-20-closing", CheckInType.CLOSING, DUE.plusSeconds(18_000), KUALA_LUMPUR)
                    .transitionTo(CheckInStatus.RINGING, DUE.plusSeconds(18_000))
                    .transitionTo(CheckInStatus.ANSWERED, DUE.plusSeconds(18_005))
            val ringing = view().copy(latest = answered)
            assertEquals(ClosingCallStatus.IN_CALL, closingStatus(ringing))
        }

    @Test
    fun `a closing call switched off in the plan says so`() {
        val v = ClosingCallView(today, at("15:00"), enabled = false, latest = null, quiet = QuietPeriod.DEFAULT)
        assertEquals(ClosingCallStatus.OFF, closingStatus(v))
        assertTrue("switched off" in closingStatusText(v))
    }

    @Test
    fun `the day defaults to today while the closing time is ahead and to tomorrow after it`() {
        assertEquals(today, defaultClosingDate(LocalDateTime.of(2026, 9, 20, 9, 0), at("15:00")))
        assertEquals(today.plusDays(1), defaultClosingDate(LocalDateTime.of(2026, 9, 20, 15, 0), at("15:00")))
        assertEquals(today.plusDays(1), defaultClosingDate(LocalDateTime.of(2026, 9, 20, 23, 30), at("15:00")))
    }

    @Test
    fun `only HH mm to the minute is accepted as a time`() {
        assertEquals(at("21:30"), parseClosingTime("21:30"))
        assertEquals(at("09:05"), parseClosingTime(" 9:05 "))
        listOf(
            "",
            "2130",
            "21:30:15",
            "24:00",
            "21:60",
            "9pm",
            "21.30",
            "2026-09-20T21:30",
        ).forEach { assertNull(parseClosingTime(it), it) }
    }

    @Test
    fun `the quiet period is described by the existing rule, and off is said plainly`() {
        assertEquals("No quiet period is set.", quietText(QuietPeriod.DEFAULT))
        assertTrue("22:00 to 07:00" in quietText(QuietPeriod(at("22:00"), at("07:00"), enabled = true)))
    }

    @Test
    fun `the note says answering never closes the day or applies a change`() {
        assertTrue("never closes your day" in ANSWER_NOTE && "applies a change" in ANSWER_NOTE && "not sent" in ANSWER_NOTE)
        assertFalse("automatically" in ANSWER_NOTE)
    }
}
