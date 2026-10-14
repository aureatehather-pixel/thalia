package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * The due-date words an action row shows. Only an open action can be overdue: a done or dropped one is not waiting on its date.
 * Due soon (agreed 2026-10-13): an open action due today through today + [SOON_DAYS] calendar days, inclusive.
 * The cue is words, never colour alone, and it is a per-action line, not a count or a badge (ADR 0009 section 4: no overdue count or red badge).
 */
internal object DueText {
    /** The date as typed, plus the overdue words when the action is open and the date is before [today]. Null with no date. */
    fun line(
        action: Action,
        today: LocalDate,
    ): String? {
        val due = action.dueDate ?: return null
        val cue = cue(due, action.status, today)
        return if (cue == null) "due $due" else "due $due · $cue"
    }

    /** The overdue words, else the due-soon words, else null. */
    fun cue(
        due: LocalDate?,
        status: ActionStatus,
        today: LocalDate,
    ): String? = overdue(due, status, today) ?: dueSoon(due, status, today)

    /** "Due today", "Due tomorrow" or "Due in N days" for an open action due from today through today + 3; else null. */
    fun dueSoon(
        due: LocalDate?,
        status: ActionStatus,
        today: LocalDate,
    ): String? {
        if (due == null || status != ActionStatus.OPEN || due.isBefore(today)) return null
        return when (val days = ChronoUnit.DAYS.between(today, due)) {
            0L -> "Due today"
            1L -> "Due tomorrow"
            in 2..SOON_DAYS -> "Due in $days days"
            else -> null
        }
    }

    const val SOON_DAYS = 3L

    /** "Overdue by 1 day" / "Overdue by N days", or null when not overdue (no date, today or later, or not open). */
    fun overdue(
        due: LocalDate?,
        status: ActionStatus,
        today: LocalDate,
    ): String? {
        if (due == null || status != ActionStatus.OPEN || !due.isBefore(today)) return null
        val days = ChronoUnit.DAYS.between(due, today)
        return "Overdue by $days ${if (days == 1L) "day" else "days"}"
    }

    /** Today's calendar date from the planning clock in the check-in zone (ADR 0011: due dates are calendar values). */
    fun today(
        now: Instant,
        zone: ZoneId,
    ): LocalDate = now.atZone(zone).toLocalDate()

    /** A date as the picker's UTC-midnight milliseconds, and back. The picker works in UTC days. */
    fun toPickerMillis(date: LocalDate): Long = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

    fun fromPickerMillis(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()
}

/** The collapsed Inbox header: "Inbox · 3 items", "Inbox · 1 item". Null count (still reading) shows just "Inbox". */
internal fun inboxHeader(open: Int?): String =
    when (open) {
        null -> "Inbox"
        1 -> "Inbox · 1 item"
        else -> "Inbox · $open items"
    }
