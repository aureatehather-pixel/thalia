package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The scheduling policy, tested without Android.
 *
 * What this cannot prove is whether a real `PendingIntent` replaces an existing one, or
 * whether the alarm fires from deep sleep. Those are `AlarmManager`'s behaviour and are
 * left to the device. What it does prove is that the policy asks the platform for the right
 * thing, and refuses to ask for the wrong one.
 */
class ExactAlarmSchedulerTest {
    private class FakeBackend(
        var exactAllowed: Boolean = true,
        var revokedDuringSet: Boolean = false,
    ) : AlarmBackend {
        val armed = mutableListOf<AlarmRequest>()
        val cancelled = mutableListOf<String>()

        override fun canScheduleExactAlarms() = exactAllowed

        override fun setExact(request: AlarmRequest) {
            if (revokedDuringSet) throw SecurityException("exact alarm permission revoked")
            armed += request
        }

        override fun cancel(occurrenceId: String) {
            cancelled += occurrenceId
        }

        override fun isArmed(occurrenceId: String) = armed.any { it.occurrenceId == occurrenceId }
    }

    private val now = 1_000_000L

    private fun occurrenceAt(
        millis: Long,
        id: String = "2026-09-19-morning",
        type: CheckInType = CheckInType.OPENING,
    ) = ScheduledOccurrence(
        id = id,
        type = type,
        scheduledAt = Instant.ofEpochMilli(millis),
        zone = ZoneId.of("Asia/Kuala_Lumpur"),
    )

    private fun scheduler(backend: FakeBackend) = ExactAlarmScheduler(backend, clock = { now })

    @Test
    fun `arms an exact alarm at the occurrence's instant`() {
        val backend = FakeBackend()

        val result = scheduler(backend).schedule(occurrenceAt(now + 120_000, type = CheckInType.CLOSING))

        assertEquals(ScheduleResult.Scheduled(now + 120_000), result)
        assertEquals(
            listOf(AlarmRequest("2026-09-19-morning", CheckInType.CLOSING, now + 120_000)),
            backend.armed,
        )
    }

    @Test
    fun `refuses without the permission and arms nothing, with no approximate fallback`() {
        val backend = FakeBackend(exactAllowed = false)

        val result = scheduler(backend).schedule(occurrenceAt(now + 120_000))

        assertEquals(ScheduleResult.NotPermitted, result)
        assertTrue(backend.armed.isEmpty(), "nothing at all may be armed without the permission")
    }

    @Test
    fun `a permission taken away between the check and the call is a refusal, not a crash`() {
        val backend = FakeBackend(revokedDuringSet = true)

        val result = scheduler(backend).schedule(occurrenceAt(now + 120_000))

        assertEquals(ScheduleResult.NotPermitted, result)
        assertTrue(backend.armed.isEmpty())
    }

    @Test
    fun `refuses a moment that is now or already past, so nothing rings that nobody scheduled for now`() {
        val backend = FakeBackend()
        val scheduler = scheduler(backend)

        assertEquals(ScheduleResult.NotInTheFuture, scheduler.schedule(occurrenceAt(now)))
        assertEquals(ScheduleResult.NotInTheFuture, scheduler.schedule(occurrenceAt(now - 1)))
        assertTrue(backend.armed.isEmpty())
    }

    @Test
    fun `one millisecond ahead is the earliest moment that can be armed`() {
        val backend = FakeBackend()

        assertIs<ScheduleResult.Scheduled>(scheduler(backend).schedule(occurrenceAt(now + 1)))
        assertEquals(1, backend.armed.size)
    }

    @Test
    fun `a missing permission is reported ahead of a moment that has passed`() {
        val backend = FakeBackend(exactAllowed = false)

        assertEquals(ScheduleResult.NotPermitted, scheduler(backend).schedule(occurrenceAt(now - 1)))
    }

    @Test
    fun `arming the same occurrence twice asks for the same alarm identity both times`() {
        val backend = FakeBackend()
        val scheduler = scheduler(backend)

        scheduler.schedule(occurrenceAt(now + 120_000))
        scheduler.schedule(occurrenceAt(now + 180_000))

        // The backend replaces by occurrence id, so a repeat is a replacement and never a
        // second alarm. That replacement is AlarmManager's, and is checked on the device.
        assertEquals(listOf("2026-09-19-morning", "2026-09-19-morning"), backend.armed.map { it.occurrenceId })
        assertEquals(listOf(now + 120_000, now + 180_000), backend.armed.map { it.triggerAtMillis })
    }

    @Test
    fun `different occurrences get different alarm identities`() {
        val backend = FakeBackend()
        val scheduler = scheduler(backend)

        scheduler.schedule(occurrenceAt(now + 120_000, id = "2026-09-19-morning"))
        scheduler.schedule(occurrenceAt(now + 240_000, id = "2026-09-19-afternoon", type = CheckInType.CLOSING))

        val ids = backend.armed.map { it.occurrenceId }
        assertEquals(2, ids.toSet().size)
    }

    @Test
    fun `whether an alarm is armed is answered by the platform`() {
        val backend = FakeBackend()
        val scheduler = scheduler(backend)
        assertFalse(scheduler.isArmed("2026-09-19-morning"))

        scheduler.schedule(occurrenceAt(now + 120_000))

        assertTrue(scheduler.isArmed("2026-09-19-morning"))
    }

    @Test
    fun `cancelling passes the occurrence id to the platform`() {
        val backend = FakeBackend()

        scheduler(backend).cancel("2026-09-19-morning")

        assertEquals(listOf("2026-09-19-morning"), backend.cancelled)
    }

    @Test
    fun `cancelling needs no permission, so a revoked permission never traps an armed alarm`() {
        val backend = FakeBackend(exactAllowed = false)

        scheduler(backend).cancel("2026-09-19-morning")

        assertEquals(listOf("2026-09-19-morning"), backend.cancelled)
    }
}
