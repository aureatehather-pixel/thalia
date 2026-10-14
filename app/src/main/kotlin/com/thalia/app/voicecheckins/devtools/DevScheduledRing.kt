package com.thalia.app.voicecheckins.devtools

import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.ScheduleResult
import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import java.time.Instant
import java.time.ZoneId
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/** One stored occurrence as the debug screen shows it, with whether Android still holds its alarm. */
data class OccurrenceRow(
    val occurrence: ScheduledOccurrence,
    /** Whether the alarm's intent still exists. Null when the question does not apply, once it is no longer scheduled. */
    val armed: Boolean?,
)

/**
 * A development-only way to arm one real alarm a couple of minutes ahead.
 *
 * It exists so the scheduled path can be tried on the phone without waiting for a real
 * check-in time. It goes through the real [OccurrenceScheduler], which records the occurrence in
 * the store and arms its alarm, then the real receiver, the real claim and the real call. Nothing
 * here keeps the process alive: the alarm belongs to Android.
 *
 * What it is not:
 *
 * - **Not the schedule.** There is no plan-driven recurrence and no 10:00 or 15:00. Each press makes
 *   one occurrence, or two for [schedulePair], and replaces any earlier ones that have not yet rung.
 * - **Not remembered in memory.** What was armed is read back from the store, so it is right after
 *   the app has been swiped away, restarted or the phone rebooted.
 *
 * Every occurrence it makes has an id starting `dev-`, which no real occurrence id can have, and is
 * stamped in the device's current time zone at the moment of arming.
 *
 * @param zone the device's current zone. Read at arming time, so a zone change is honoured.
 */
class DevScheduledRing(
    private val scheduler: OccurrenceScheduler,
    private val store: OccurrenceStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val delay: Duration = DEFAULT_DELAY,
) {
    /** Arm one check-in of [type] (opening unless told otherwise) [delay] from now, replacing any earlier one that is still scheduled. */
    suspend fun schedule(type: CheckInType = CheckInType.OPENING): ScheduleResult {
        cancelScheduled()
        val at = clock() + delay.inWholeMilliseconds
        return scheduler.schedule(
            ScheduledOccurrence(
                id = "$ID_PREFIX$at",
                type = type,
                scheduledAt = Instant.ofEpochMilli(at),
                zone = zone(),
            ),
        )
    }

    /**
     * Arm two opening check-ins for the same instant, [delay] from now, replacing any earlier debug
     * ones that are still scheduled.
     *
     * Android delivers both alarms together, so this is how a device run makes two calls race for the
     * phone: exactly one should ring, and the other should be suppressed as a call in progress. The
     * second id ends `-b`. Both results are returned, first then second.
     */
    suspend fun schedulePair(): List<ScheduleResult> {
        cancelScheduled()
        val at = clock() + delay.inWholeMilliseconds
        return listOf("$ID_PREFIX$at", "$ID_PREFIX$at$PAIR_SUFFIX").map { id ->
            scheduler.schedule(
                ScheduledOccurrence(id = id, type = CheckInType.OPENING, scheduledAt = Instant.ofEpochMilli(at), zone = zone()),
            )
        }
    }

    /** Disarm every debug occurrence that is still scheduled. Safe when there are none. */
    suspend fun cancel() = cancelScheduled()

    private suspend fun cancelScheduled() {
        store
            .open()
            .filter { it.id.startsWith(ID_PREFIX) && it.status == CheckInStatus.SCHEDULED }
            .forEach { scheduler.cancel(it.id) }
    }

    companion object {
        /** Long enough to lock the phone or swipe the app away, short enough to wait for. */
        val DEFAULT_DELAY: Duration = 2.minutes

        /** What every debug occurrence id starts with. Real ids are a date and a type. */
        const val ID_PREFIX: String = "dev-"

        /** What the second of a racing pair ends with. */
        const val PAIR_SUFFIX: String = "-b"
    }
}

/** The stored plan in one plain line, for the debug screen. */
fun CheckInPlan.summaryLine(): String {
    fun slot(
        name: String,
        schedule: com.thalia.voicecheckins.domain.CheckInSchedule,
    ) = "$name ${schedule.localTime} ${if (schedule.enabled) "on" else "off"}"
    return "${slot("Opening", opening)}, ${slot("Closing", closing)}, zone ${zone.id}"
}
