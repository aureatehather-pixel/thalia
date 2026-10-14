package com.thalia.app.reminders

import com.thalia.planning.Reminder
import com.thalia.planning.ReminderReason
import com.thalia.planning.ReminderRules
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The words for reminders, kept apart from the screen so they can be tested. ADR 0014. */
internal object ReminderText {
    private val hhmm = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
    private val dayAndTime = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.ENGLISH)

    /** "around 15:00", with the date when it is not today, and the zone it was set in when that is not the current one. */
    fun around(
        reminder: Reminder,
        now: Long,
        zone: ZoneId,
    ): String {
        val due = Instant.ofEpochMilli(reminder.dueAtMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val clock = if (due.toLocalDate() == today) due.format(hhmm) else due.format(dayAndTime)
        val where = if (reminder.zone != zone.id) " (set in ${reminder.zone})" else ""
        return "around $clock$where"
    }

    fun status(reminder: Reminder): String =
        when (reminder.status) {
            ReminderStatus.SCHEDULED -> "Waiting"
            ReminderStatus.POSTED -> "Notification posted; response unknown"
            ReminderStatus.ACKNOWLEDGED -> "You acknowledged this reminder"
            ReminderStatus.DISMISSED -> "You dismissed this reminder"
            ReminderStatus.CANCELLED -> "Cancelled before it was due"
            ReminderStatus.NOT_POSTED ->
                when (reminder.reason) {
                    ReminderReason.NOTIFICATIONS_BLOCKED -> "Not posted: notifications were blocked"
                    ReminderReason.QUIET_PERIOD -> "Not posted: it fell in your quiet period"
                    ReminderReason.WINDOW_EXPIRED ->
                        "Not posted: the ${ReminderRules.WINDOW_MILLIS / 60_000}-minute delivery window had passed"
                    null -> "Not posted"
                }
        }

    /** What the reminder is about. [targetGone] is true when a target-bound reminder's original can no longer be found. */
    fun about(
        reminder: Reminder,
        targetGone: Boolean,
    ): String =
        when (reminder.targetType) {
            ReminderTargetType.NONE -> "Checkpoint. Look at today's plan if you want to."
            ReminderTargetType.INBOX_ITEM ->
                "Inbox: ${reminder.label}" +
                    if (targetGone) " (the original is no longer in the Inbox)" else ""
            ReminderTargetType.ACTION -> "Action: ${reminder.label}" + if (targetGone) " (the original is no longer there)" else ""
        }

    fun refusal(why: ReminderRefusal): String =
        when (why) {
            ReminderRefusal.TOO_SOON -> "Choose a time at least a minute from now. Nothing was scheduled."
            ReminderRefusal.TOO_FAR -> "Choose a time within the next 7 days. Nothing was scheduled."
            ReminderRefusal.QUIET_PERIOD ->
                "That time is in your quiet period, when Thalia shows nothing. Choose another. Nothing was scheduled."
            ReminderRefusal.TOO_MANY ->
                "You already have ${ReminderRules.MAX_PENDING} reminders waiting. Cancel one first. Nothing was scheduled."
            ReminderRefusal.TARGET_NOT_FOUND -> "That item is no longer there. Nothing was scheduled."
            ReminderRefusal.NOTIFICATIONS_BLOCKED ->
                "Notifications for Thalia are off, so a reminder could not be shown. Turn them on in system settings. Nothing was scheduled."
            ReminderRefusal.CONFLICT -> "That reminder id is already used by another reminder. Try again. Nothing was scheduled."
        }
}
