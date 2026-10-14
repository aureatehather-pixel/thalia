package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a reconciliation ran. It changes what is logged and nothing else: the work is always the same. */
enum class ReconcileTrigger {
    APP_LAUNCH,
    BOOT,
    PACKAGE_REPLACED,
    TIME_CHANGED,
    TIMEZONE_CHANGED,
    EXACT_ALARM_PERMISSION_GRANTED,

    /** The debug screen's Reconcile now button. */
    MANUAL,
    ;

    companion object {
        /**
         * The broadcast action each system trigger arrives as.
         *
         * These are the public values of `Intent.ACTION_BOOT_COMPLETED`, `ACTION_MY_PACKAGE_REPLACED`,
         * `ACTION_TIME_CHANGED`, `ACTION_TIMEZONE_CHANGED` and
         * `AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED`. They are written out
         * here so that mapping them is ordinary Kotlin, and a device-free test checks each against
         * the platform constant.
         */
        val SYSTEM_ACTIONS: Map<String, ReconcileTrigger> =
            mapOf(
                "android.intent.action.BOOT_COMPLETED" to BOOT,
                "android.intent.action.MY_PACKAGE_REPLACED" to PACKAGE_REPLACED,
                "android.intent.action.TIME_SET" to TIME_CHANGED,
                "android.intent.action.TIMEZONE_CHANGED" to TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" to EXACT_ALARM_PERMISSION_GRANTED,
            )

        fun fromAction(action: String?): ReconcileTrigger? = SYSTEM_ACTIONS[action]
    }
}

/** What one reconciliation found and did. */
data class ReconcileReport(
    val trigger: ReconcileTrigger,
    val at: Instant,
    /** Every occurrence this pass moved, in the order it moved them. */
    val changes: List<Change>,
    /** Future scheduled occurrences that have an armed alarm after this pass. */
    val armed: Int,
    /** Of those, how many had no alarm registered before this pass, so this pass restored them. */
    val restored: Int,
    /** Future scheduled occurrences that could not be armed, because Android is not allowing exact alarms. */
    val unarmed: Int,
) {
    data class Change(
        val occurrenceId: String,
        val from: CheckInStatus,
        val to: CheckInStatus,
        val reason: OccurrenceReason,
    )

    /** One plain line, for the log and the debug screen. */
    fun summary(): String =
        buildString {
            append("$trigger: armed=$armed restored=$restored unarmed=$unarmed changes=${changes.size}")
            changes.forEach { append(" [${it.occurrenceId} ${it.from}->${it.to} ${it.reason}]") }
        }
}

/**
 * The one path that brings the stored occurrences and Android's alarms back into agreement.
 *
 * It runs for every trigger in [ReconcileTrigger], and does the same thing each time, so it can
 * run twice, or from two triggers at once, and land in the same place. It never rings a call.
 * Whatever is overdue is recorded as missed and left, silently.
 *
 * For each stored occurrence that has not ended:
 *
 * - **Scheduled, its slot switched off:** cancelled, and its alarm disarmed.
 * - **Scheduled, in the future:** its alarm is armed again. Arming replaces, so an alarm that
 *   was already there is not doubled, and one that a restart or a force-stop removed comes back.
 *   Without the exact-alarm permission it cannot be armed and is left scheduled, and counted.
 * - **Scheduled, its moment has come, no alarm registered:** missed, [OccurrenceReason.ALARM_MISSING].
 *   The cause is not established and is not guessed.
 * - **Scheduled, its moment has come, more than the lateness limit ago, an alarm still registered:**
 *   missed, [OccurrenceReason.OVERDUE], and the alarm is disarmed so it cannot ring late.
 * - **Scheduled, its moment has come, within the lateness limit, an alarm registered:** left alone.
 *   The alarm is about to be delivered, and delivery is the path that rings.
 * - **Ringing or answered, and nothing in this process is carrying it:** ended. A ringing one is
 *   missed and an answered one failed, both [OccurrenceReason.INTERRUPTED]. Something that was
 *   claimed a moment ago is left alone, because the call may not have started yet.
 *
 * Every move is a conditional update in the store, so if the alarm receiver or the call gets to an
 * occurrence first, this pass loses that move and records nothing for it.
 */
class Reconciler(
    private val store: OccurrenceStore,
    private val alarms: AlarmScheduler,
    /** The occurrence this process is ringing or talking for right now, or null. */
    private val activeOccurrenceId: () -> String?,
    private val clock: () -> Long,
    private val maxLateness: Duration = CheckInFireHandler.MAX_LATENESS,
    private val inProgressGrace: Duration = IN_PROGRESS_GRACE,
) {
    suspend fun reconcile(trigger: ReconcileTrigger): ReconcileReport {
        val nowMillis = clock()
        val now = Instant.ofEpochMilli(nowMillis)
        val plan = store.plan()

        val changes = mutableListOf<ReconcileReport.Change>()
        var armed = 0
        var restored = 0
        var unarmed = 0

        suspend fun move(
            occurrence: ScheduledOccurrence,
            to: CheckInStatus,
            reason: OccurrenceReason,
        ) {
            if (store.transition(occurrence.id, occurrence.status, to, now, reason)) {
                changes += ReconcileReport.Change(occurrence.id, occurrence.status, to, reason)
            }
        }

        for (occurrence in store.open()) {
            when (occurrence.status) {
                CheckInStatus.SCHEDULED -> {
                    val dueMillis = occurrence.scheduledAt.toEpochMilli()
                    val hasAlarm = alarms.isArmed(occurrence.id)
                    when {
                        !plan[occurrence.type].enabled -> {
                            alarms.cancel(occurrence.id)
                            move(occurrence, CheckInStatus.CANCELLED, OccurrenceReason.PLAN_DISABLED)
                        }

                        dueMillis > nowMillis ->
                            when (alarms.schedule(occurrence)) {
                                is ScheduleResult.Scheduled -> {
                                    armed++
                                    if (!hasAlarm) restored++
                                }

                                // No alarm can exist without the permission, so an intent record left by the
                                // revoke is dropped. Otherwise it would look like a live alarm after the
                                // permission returns, or make a missed one read as overdue.
                                ScheduleResult.NotPermitted -> {
                                    alarms.cancel(occurrence.id)
                                    unarmed++
                                }

                                // Its moment arrived between the check above and the arming. The next
                                // pass sees it as due, and an alarm for it is not wanted now.
                                ScheduleResult.NotInTheFuture, ScheduleResult.AlreadyRecorded -> Unit
                            }

                        !hasAlarm -> move(occurrence, CheckInStatus.MISSED, OccurrenceReason.ALARM_MISSING)

                        nowMillis - dueMillis > maxLateness.inWholeMilliseconds -> {
                            alarms.cancel(occurrence.id)
                            move(occurrence, CheckInStatus.MISSED, OccurrenceReason.OVERDUE)
                        }

                        else -> Unit
                    }
                }

                CheckInStatus.RINGING, CheckInStatus.ANSWERED -> {
                    if (occurrence.isLostCall(activeOccurrenceId(), nowMillis, inProgressGrace)) {
                        move(occurrence, occurrence.lostCallEnding(), OccurrenceReason.INTERRUPTED)
                    }
                }

                // Not open, so the store does not return them. Listed so a new status must be decided here.
                // A suppressed one never rang and never will, so there is nothing to recover.
                CheckInStatus.COMPLETED,
                CheckInStatus.DECLINED,
                CheckInStatus.MISSED,
                CheckInStatus.FAILED,
                CheckInStatus.CANCELLED,
                CheckInStatus.SUPPRESSED,
                -> Unit
            }
        }

        return ReconcileReport(trigger, now, changes, armed, restored, unarmed)
    }

    companion object {
        /**
         * How long a ringing or answered occurrence is left alone before it is treated as lost.
         * The window in which a claim has been made and the call has not yet started is
         * milliseconds, so this is generous, and it must stay short enough that a call lost with
         * its process is cleared the next time anything reconciles.
         */
        val IN_PROGRESS_GRACE: Duration = 15.seconds
    }
}

/**
 * Whether this ringing or answered occurrence was lost with its process: nothing in this process is
 * carrying it, because [carriedHere] is another id or none, and it was claimed more than [grace] before
 * [nowMillis], so it is not a call that was claimed a moment ago and has yet to start.
 *
 * The reconciliation and the alarm's claim both apply this, so a call lost with its process can never
 * block the next one.
 */
internal fun ScheduledOccurrence.isLostCall(
    carriedHere: String?,
    nowMillis: Long,
    grace: Duration,
): Boolean {
    if (status != CheckInStatus.RINGING && status != CheckInStatus.ANSWERED) return false
    val firedMillis = checkNotNull(firedAt) { "$status without a fired time" }.toEpochMilli()
    return id != carriedHere && nowMillis - firedMillis > grace.inWholeMilliseconds
}

/** How a lost call ends: a ringing one was missed and an answered one failed, both [OccurrenceReason.INTERRUPTED]. */
internal fun ScheduledOccurrence.lostCallEnding(): CheckInStatus =
    if (status == CheckInStatus.RINGING) CheckInStatus.MISSED else CheckInStatus.FAILED
