package com.thalia.voicecheckins.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The daily stretch of wall-clock time in which no check-in may ring, such as 22:00 to 07:00.
 *
 * It runs from [start], included, up to [end], not included, every day. When [end] is earlier than
 * [start] the period spans midnight, so 22:00 to 07:00 covers 23:30 and 06:59 and not 07:00. The two
 * cannot be equal, because that would leave it ambiguous whether the period is empty or the whole day.
 *
 * It is a user setting, off until the user turns it on. A disabled period covers nothing and keeps its
 * times, so switching it back on restores what was set.
 */
data class QuietPeriod(
    val start: LocalTime,
    val end: LocalTime,
    val enabled: Boolean = false,
) {
    init {
        require(start.second == 0 && start.nano == 0 && end.second == 0 && end.nano == 0) {
            "Quiet period times are minute precision, got $start to $end"
        }
        require(start != end) { "A quiet period must start and end at different times, got $start to $end" }
    }

    /** Whether the period wraps past midnight, ending on the next day. */
    val spansMidnight: Boolean get() = end < start

    /** Whether [time] falls inside the period. Always false while the period is disabled. */
    fun covers(time: LocalTime): Boolean =
        enabled &&
            if (spansMidnight) {
                time >= start || time < end
            } else {
                time >= start && time < end
            }

    /** Whether [instant], read on the wall clock in [zone], falls inside the period. */
    fun covers(
        instant: Instant,
        zone: ZoneId,
    ): Boolean = covers(instant.atZone(zone).toLocalTime())

    companion object {
        /** What a new install starts with: a typical night, switched off until the user turns it on. */
        val DEFAULT: QuietPeriod = QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = false)
    }
}
