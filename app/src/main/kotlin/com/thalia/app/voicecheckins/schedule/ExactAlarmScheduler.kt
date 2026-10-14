package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.ScheduledOccurrence

/** One alarm to arm: which occurrence, what kind of check-in, and when. */
internal data class AlarmRequest(
    val occurrenceId: String,
    val checkInType: CheckInType,
    val triggerAtMillis: Long,
)

/**
 * The three things the platform is asked to do, and nothing else.
 *
 * The scheduling policy lives in [ExactAlarmScheduler] and is tested on the JVM. This is
 * the seam to Android: [AlarmManagerBackend] is the only implementation, and it is a thin
 * translation to `AlarmManager` and `PendingIntent`.
 */
internal interface AlarmBackend {
    /** Whether Android currently allows Thalia to set exact alarms. */
    fun canScheduleExactAlarms(): Boolean

    /**
     * Arm an exact alarm for [request], replacing any alarm already armed for the same
     * occurrence. Throws [SecurityException] if the permission has just been taken away.
     */
    fun setExact(request: AlarmRequest)

    /** Disarm the alarm for [occurrenceId]. Does nothing if none is armed. */
    fun cancel(occurrenceId: String)

    /** Whether the alarm's `PendingIntent` for [occurrenceId] still exists. See [AlarmScheduler.isArmed]. */
    fun isArmed(occurrenceId: String): Boolean
}

/**
 * The scheduling policy: refuse rather than approximate, and never arm a moment that has
 * already passed.
 *
 * Both refusals are deliberate. An exact alarm that Android will not allow is not replaced
 * by an approximate one, because a check-in that arrives at 10:47 is the harm the feature is
 * built to avoid. An occurrence in the past is not armed either, because an alarm set for a
 * past instant fires at once, which would ring a call nobody scheduled for this moment.
 */
internal class ExactAlarmScheduler(
    private val backend: AlarmBackend,
    private val clock: () -> Long,
) : AlarmScheduler {
    override fun schedule(occurrence: ScheduledOccurrence): ScheduleResult {
        // Permission is checked first, so a phone that cannot schedule at all says so
        // whatever the time was.
        if (!backend.canScheduleExactAlarms()) return ScheduleResult.NotPermitted

        val triggerAtMillis = occurrence.scheduledAt.toEpochMilli()
        if (triggerAtMillis <= clock()) return ScheduleResult.NotInTheFuture

        return try {
            backend.setExact(AlarmRequest(occurrence.id, occurrence.type, triggerAtMillis))
            ScheduleResult.Scheduled(triggerAtMillis)
        } catch (e: SecurityException) {
            // The permission was taken away between the check above and the call. Android
            // stops the app when that happens, so this is a narrow race, but it must not crash.
            ScheduleResult.NotPermitted
        }
    }

    override fun canScheduleExactAlarms(): Boolean = backend.canScheduleExactAlarms()

    override fun cancel(occurrenceId: String) = backend.cancel(occurrenceId)

    /**
     * Without the permission no alarm can exist, whatever intent record is left behind. Android
     * removes an app's exact alarms when the permission is revoked but keeps their `PendingIntent`
     * records, as the Pixel 10 showed, so the record alone would report an alarm that is not there.
     */
    override fun isArmed(occurrenceId: String): Boolean = backend.canScheduleExactAlarms() && backend.isArmed(occurrenceId)
}
