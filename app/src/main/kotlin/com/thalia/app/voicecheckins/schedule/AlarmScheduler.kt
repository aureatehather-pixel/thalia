package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.ScheduledOccurrence

/**
 * Arms and disarms the one exact alarm that starts a check-in call.
 *
 * One alarm per occurrence, identified by the occurrence's id. Arming an occurrence that is
 * already armed replaces its alarm rather than adding a second one, so calling [schedule]
 * again is safe and never doubles a call.
 *
 * There is no approximate fallback. When Android will not allow an exact alarm the answer
 * is [ScheduleResult.NotPermitted], and nothing is armed. See ADR 0005.
 */
interface AlarmScheduler {
    /** Whether Android allows exact alarms right now. It can change at any time, so ask again before relying on it. */
    fun canScheduleExactAlarms(): Boolean

    /** Arm the alarm for [occurrence] at its `scheduledAt` instant. */
    fun schedule(occurrence: ScheduledOccurrence): ScheduleResult

    /** Disarm the alarm for [occurrenceId]. Does nothing if none is armed. */
    fun cancel(occurrenceId: String)

    /**
     * Whether Android still holds an alarm for [occurrenceId].
     *
     * Android offers no way to ask this directly, so it is answered by whether the alarm's
     * `PendingIntent` still exists, and by whether exact alarms are allowed at all. Android cancels
     * the intent when the app is force-stopped and drops it when the phone restarts, as the device
     * runs showed. When exact alarms are revoked it removes the alarms but keeps the intent records
     * (Pixel 10, 2026-09-30), so without the permission this is always false. It remains an
     * approximation, and a `true` is weaker than a `false`: nothing else rules out Android
     * removing an alarm while keeping its intent. Callers treat it as a hint and re-arm regardless.
     */
    fun isArmed(occurrenceId: String): Boolean
}

/** What became of a request to arm an alarm. */
sealed interface ScheduleResult {
    /** An exact alarm is armed and will fire at [fireAtMillis], epoch milliseconds. */
    data class Scheduled(
        val fireAtMillis: Long,
    ) : ScheduleResult

    /** Android is not allowing Thalia to set exact alarms. Nothing was armed. */
    data object NotPermitted : ScheduleResult

    /** The occurrence is due now or already past. Nothing was armed, so nothing rings late. */
    data object NotInTheFuture : ScheduleResult

    /** That occurrence id is already recorded, so it was not scheduled again. Only [OccurrenceScheduler] gives this. */
    data object AlreadyRecorded : ScheduleResult
}
