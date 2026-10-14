package com.thalia.app.planning

import com.thalia.planning.ActiveDay
import com.thalia.planning.WorkingDayRecord
import com.thalia.planning.WorkingDayState
import com.thalia.planning.WorkingDays
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The clock the planning screens and the conversation read. ADR 0011. Production never moves it. The isolated
 * conversationCheck build shifts it, so a check can cross the rollover or midnight without waiting; M2's check-ins,
 * alarms and calls never read it.
 */
internal object PlanningClock {
    @Volatile var offsetMillis: Long = 0

    fun now(): Instant = Instant.now().plusMillis(offsetMillis)
}

/** The day planning shows by default: the current working day, or the clock's active day when none is current. */
internal fun planningDay(
    now: Instant,
    zone: ZoneId,
    rollover: LocalTime,
    rows: List<WorkingDayRecord>,
): ActiveDay =
    (WorkingDays.resolve(now, zone, rollover, rows) as? WorkingDayState.Current)?.day ?: ActiveDay.containing(now, zone, rollover)

/** The day outcomes may be recorded up to, ADR 0011: the later of the clock's active day and the current working day. */
internal fun outcomeToday(
    now: Instant,
    zone: ZoneId,
    rollover: LocalTime,
    rows: List<WorkingDayRecord>,
): ActiveDay = WorkingDays.outcomeDay(WorkingDays.resolve(now, zone, rollover, rows), ActiveDay.containing(now, zone, rollover))
