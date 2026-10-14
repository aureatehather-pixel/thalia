package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A wall-clock time that does not exist on its date in its zone, because the clocks skip it. */
class NonexistentLocalTimeException(
    val local: LocalDateTime,
    val zone: ZoneId,
) : RuntimeException("$local does not exist in $zone")

/**
 * An active day: D at the rollover up to, but not including, D + 1 at the rollover. ADR 0009 decision 3.
 *
 * It is its own type, never a bare [LocalDate], so it cannot be passed by mistake where M2 expects the
 * calendar date of a check-in slot. [date] is the calendar date on which the active day starts.
 */
@JvmInline
value class ActiveDay(
    val date: LocalDate,
) : Comparable<ActiveDay> {
    override fun compareTo(other: ActiveDay): Int = date.compareTo(other.date)

    override fun toString(): String = date.toString()

    fun plusDays(days: Long) = ActiveDay(date.plusDays(days))

    /**
     * The wall-clock moment that is [offsetMinutes] after this day's rollover, by wall-clock arithmetic
     * and so independent of any daylight-saving change in between.
     */
    fun localAt(
        rollover: LocalTime,
        offsetMinutes: Int,
    ): LocalDateTime = LocalDateTime.of(date, rollover).plusMinutes(offsetMinutes.toLong())

    /** The local time [time] of this active day: from the rollover (inclusive) round to just before it. */
    fun localOf(
        rollover: LocalTime,
        time: LocalTime,
    ): LocalDateTime = localAt(rollover, minutesAfterRollover(rollover, time))

    companion object {
        const val MINUTES_PER_DAY = 24 * 60

        /** 03:59 on 3 October is active day 2 October when the rollover is 04:00; 04:00 is 3 October. */
        fun containing(
            instant: Instant,
            zone: ZoneId,
            rollover: LocalTime,
        ): ActiveDay {
            val local = instant.atZone(zone).toLocalDateTime()
            return ActiveDay(if (local.toLocalTime() < rollover) local.toLocalDate().minusDays(1) else local.toLocalDate())
        }

        /** Minutes from the rollover to [time] on the wall clock, from 0 to 1439. */
        fun minutesAfterRollover(
            rollover: LocalTime,
            time: LocalTime,
        ): Int = Math.floorMod(minuteOfDay(time) - minuteOfDay(rollover), MINUTES_PER_DAY)

        private fun minuteOfDay(time: LocalTime) = time.hour * 60 + time.minute

        /**
         * The instant of a wall-clock moment in [zone].
         *
         * - A moment the clocks skip (spring forward) is refused with [NonexistentLocalTimeException]; it is never shifted.
         * - A moment that happens twice (fall back) is the **first** one, the earlier instant, always.
         */
        fun resolve(
            local: LocalDateTime,
            zone: ZoneId,
        ): Instant {
            if (zone.rules.getValidOffsets(local).isEmpty()) throw NonexistentLocalTimeException(local, zone)
            return ZonedDateTime.ofLocal(local, zone, null).toInstant()
        }
    }
}
