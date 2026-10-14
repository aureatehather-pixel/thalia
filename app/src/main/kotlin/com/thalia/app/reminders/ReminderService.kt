package com.thalia.app.reminders

import com.thalia.app.planning.data.CreateReminderResult
import com.thalia.app.planning.data.PlanningStore
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderDelivery
import com.thalia.planning.ReminderProblem
import com.thalia.planning.ReminderReason
import com.thalia.planning.ReminderRules
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import com.thalia.voicecheckins.domain.QuietPeriod
import java.time.Instant
import java.time.ZoneId

/** The alarm half of a reminder. Inexact, one per reminder id. [armedIds] is a registry kept so a stale alarm can be found and cancelled. */
internal interface ReminderAlarms {
    fun arm(
        id: String,
        atMillis: Long,
    )

    fun cancel(id: String)

    fun armedIds(): Set<String>
}

/** The notification half. [post] shows only generic text; [postedIds] lists what is currently in the shade. */
internal interface ReminderNotifier {
    fun canPost(): Boolean

    fun post(id: String)

    fun withdraw(id: String)

    fun postedIds(): Set<String>
}

internal enum class ReminderRefusal {
    TOO_SOON,
    TOO_FAR,
    QUIET_PERIOD,
    TOO_MANY,
    TARGET_NOT_FOUND,
    NOTIFICATIONS_BLOCKED,
    CONFLICT,
}

internal sealed interface CreateOutcome {
    data class Created(
        val reminder: Reminder,
    ) : CreateOutcome

    data class Refused(
        val why: ReminderRefusal,
    ) : CreateOutcome
}

/** What a delivered alarm did. Only [Posted] and [NotPosted] changed a row. */
internal sealed interface FireOutcome {
    data object Posted : FireOutcome

    data class NotPosted(
        val reason: ReminderReason,
    ) : FireOutcome

    /** The reminder is gone, or is no longer waiting: a duplicate, stale or cancelled delivery. Nothing was posted. */
    data object Ignored : FireOutcome

    /** Delivered before its time, for example after the clock moved. It was armed again and nothing was posted. */
    data object Early : FireOutcome
}

internal data class ReminderReconcileReport(
    val armed: Int,
    val expired: Int,
    val staleAlarmsCancelled: Int,
    val notificationsWithdrawn: Int,
)

/**
 * Reminders, ADR 0014. The table is the truth and alarms and notifications are derived from it: every decision reads the row,
 * and every change is a compare-and-set on its status, so a duplicate or stale callback cannot post twice or revive a
 * resolved reminder. Nothing here reads or writes a plan, an action, an Inbox item or an outcome.
 */
internal class ReminderService(
    private val alarms: ReminderAlarms,
    private val notifier: ReminderNotifier,
    private val quietPeriod: suspend () -> QuietPeriod,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private suspend fun quietAt(millis: Long): Boolean = quietPeriod().covers(Instant.ofEpochMilli(millis), zone())

    /** Makes a reminder due at [dueAtMillis]. [labelText] is the target's words, or blank for a checkpoint. Nothing is stored on a refusal. */
    suspend fun create(
        store: PlanningStore,
        targetType: ReminderTargetType,
        targetId: String?,
        labelText: String,
        dueAtMillis: Long,
        id: String = ReminderRules.newId(),
    ): CreateOutcome {
        val now = clock()
        val pending = store.reminders().count { it.status == ReminderStatus.SCHEDULED }
        ReminderRules.problem(dueAtMillis, now, pending, quietAt(dueAtMillis))?.let {
            return CreateOutcome.Refused(
                when (it) {
                    ReminderProblem.TOO_SOON -> ReminderRefusal.TOO_SOON
                    ReminderProblem.TOO_FAR -> ReminderRefusal.TOO_FAR
                    ReminderProblem.QUIET_PERIOD -> ReminderRefusal.QUIET_PERIOD
                    ReminderProblem.TOO_MANY -> ReminderRefusal.TOO_MANY
                },
            )
        }
        if (!notifier.canPost()) return CreateOutcome.Refused(ReminderRefusal.NOTIFICATIONS_BLOCKED)
        val reminder =
            Reminder(
                id = id,
                targetType = targetType,
                targetId = targetId,
                label = if (targetType == ReminderTargetType.NONE) ReminderRules.CHECKPOINT_LABEL else ReminderRules.labelOf(labelText),
                dueAtMillis = dueAtMillis,
                zone = zone().id,
                createdAtMillis = now,
            )
        return when (store.createReminder(reminder)) {
            CreateReminderResult.CREATED, CreateReminderResult.ALREADY_CREATED -> {
                alarms.arm(reminder.id, reminder.dueAtMillis)
                CreateOutcome.Created(reminder)
            }
            CreateReminderResult.CONFLICT -> CreateOutcome.Refused(ReminderRefusal.CONFLICT)
            CreateReminderResult.TARGET_NOT_FOUND -> CreateOutcome.Refused(ReminderRefusal.TARGET_NOT_FOUND)
            CreateReminderResult.TOO_MANY -> CreateOutcome.Refused(ReminderRefusal.TOO_MANY)
        }
    }

    /** Cancels a waiting reminder: its alarm first, then the row. False if it was no longer waiting. */
    suspend fun cancel(
        store: PlanningStore,
        id: String,
    ): Boolean {
        alarms.cancel(id)
        return store.moveReminder(id, ReminderStatus.SCHEDULED, ReminderStatus.CANCELLED, clock())
    }

    /** The person acknowledged a posted reminder. That is all it records. */
    suspend fun acknowledge(
        store: PlanningStore,
        id: String,
    ): Boolean = resolvePosted(store, id, ReminderStatus.ACKNOWLEDGED)

    suspend fun dismiss(
        store: PlanningStore,
        id: String,
    ): Boolean = resolvePosted(store, id, ReminderStatus.DISMISSED)

    private suspend fun resolvePosted(
        store: PlanningStore,
        id: String,
        to: ReminderStatus,
    ): Boolean {
        val moved = store.moveReminder(id, ReminderStatus.POSTED, to, clock())
        if (moved) notifier.withdraw(id)
        return moved
    }

    /** An alarm for [id] was delivered. Acts only on a row that is still SCHEDULED. */
    suspend fun onAlarm(
        store: PlanningStore,
        id: String,
    ): FireOutcome {
        val reminder = store.reminder(id)
        if (reminder == null || reminder.status != ReminderStatus.SCHEDULED) return FireOutcome.Ignored
        val now = clock()
        if (now < reminder.dueAtMillis) {
            alarms.arm(id, reminder.dueAtMillis)
            return FireOutcome.Early
        }
        when (val delivery = ReminderRules.delivery(reminder.dueAtMillis, now, quietAt(now), notifier.canPost())) {
            is ReminderDelivery.Skip -> {
                store.moveReminder(id, ReminderStatus.SCHEDULED, ReminderStatus.NOT_POSTED, now, delivery.reason)
                return FireOutcome.NotPosted(delivery.reason)
            }
            ReminderDelivery.Post -> Unit
        }
        try {
            notifier.post(id)
        } catch (e: RuntimeException) {
            store.moveReminder(id, ReminderStatus.SCHEDULED, ReminderStatus.NOT_POSTED, now, ReminderReason.NOTIFICATIONS_BLOCKED)
            return FireOutcome.NotPosted(ReminderReason.NOTIFICATIONS_BLOCKED)
        }
        // Posting is idempotent for one id, so the claim follows it: if someone resolved the row meanwhile, the post is withdrawn.
        if (!store.moveReminder(id, ReminderStatus.SCHEDULED, ReminderStatus.POSTED, now)) {
            notifier.withdraw(id)
            return FireOutcome.Ignored
        }
        return FireOutcome.Posted
    }

    /**
     * Brings alarms and notifications back into line with the table, from any trigger and any number of times: after a restart,
     * a clock or zone change, an update, a launch, and an import or replacement of planning data. It posts nothing, and never
     * touches a row that is not SCHEDULED, so it cannot revive a resolved reminder.
     */
    suspend fun reconcile(store: PlanningStore): ReminderReconcileReport {
        val now = clock()
        val rows = store.reminders()
        var armed = 0
        var expired = 0
        rows.filter { it.status == ReminderStatus.SCHEDULED }.forEach {
            if (now - it.dueAtMillis > ReminderRules.WINDOW_MILLIS) {
                if (store.moveReminder(it.id, ReminderStatus.SCHEDULED, ReminderStatus.NOT_POSTED, now, ReminderReason.WINDOW_EXPIRED)) {
                    alarms.cancel(it.id)
                    expired++
                }
            } else {
                alarms.arm(it.id, it.dueAtMillis)
                armed++
            }
        }
        val waiting =
            store
                .reminders()
                .filter { it.status == ReminderStatus.SCHEDULED }
                .map { it.id }
                .toSet()
        val stale = alarms.armedIds() - waiting
        stale.forEach { alarms.cancel(it) }
        val posted =
            store
                .reminders()
                .filter { it.status == ReminderStatus.POSTED }
                .map { it.id }
                .toSet()
        val orphans = notifier.postedIds() - posted
        orphans.forEach { notifier.withdraw(it) }
        return ReminderReconcileReport(armed, expired, stale.size, orphans.size)
    }
}
