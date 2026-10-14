package com.thalia.planning

import java.util.UUID

/** What a reminder is about. NONE is a checkpoint. A target is only a link: a reminder never changes, or depends on, it. ADR 0014. */
enum class ReminderTargetType { NONE, INBOX_ITEM, ACTION }

/**
 * Where a reminder stands. POSTED means only that Thalia handed a notification to Android; what the person did about it is
 * unknown, and stays unknown until they say. Nothing times it out.
 */
enum class ReminderStatus {
    SCHEDULED,
    POSTED,
    ACKNOWLEDGED,
    DISMISSED,
    CANCELLED,
    NOT_POSTED,
    ;

    val legalNext: Set<ReminderStatus>
        get() =
            when (this) {
                SCHEDULED -> setOf(POSTED, NOT_POSTED, CANCELLED)
                POSTED -> setOf(ACKNOWLEDGED, DISMISSED)
                else -> emptySet()
            }

    /** Once the reminder has a resolved time: everything except waiting and posted. */
    val isResolved: Boolean get() = this != SCHEDULED && this != POSTED

    /** Whether a notification was handed to Android at some point. */
    val wasPosted: Boolean get() = this == POSTED || this == ACKNOWLEDGED || this == DISMISSED
}

/** Why a reminder was not posted. Only things Thalia observed: it never infers that the phone was off. */
enum class ReminderReason { NOTIFICATIONS_BLOCKED, QUIET_PERIOD, WINDOW_EXPIRED }

/**
 * One one-shot reminder. [dueAtMillis] is a fixed instant; [zone] is where it was set and is kept for display only.
 * [label] is a snapshot of the target's words, cut for display, and is shown only inside the app. The invariants are checked
 * here so a stored or imported row that contradicts its status is refused rather than trusted.
 */
data class Reminder(
    val id: String,
    val targetType: ReminderTargetType,
    val targetId: String?,
    val label: String,
    val dueAtMillis: Long,
    val zone: String,
    val status: ReminderStatus = ReminderStatus.SCHEDULED,
    val reason: ReminderReason? = null,
    val postedAtMillis: Long? = null,
    val resolvedAtMillis: Long? = null,
    val createdAtMillis: Long,
) {
    init {
        require(id.isNotBlank()) { "a reminder needs an id" }
        require(
            label.isNotBlank() && label.length <= ReminderRules.LABEL_MAX,
        ) { "a reminder label is 1 to ${ReminderRules.LABEL_MAX} characters" }
        require(
            (targetType == ReminderTargetType.NONE) == (targetId == null),
        ) { "a reminder has a target exactly when it is not a checkpoint" }
        require(zone.isNotBlank()) { "a reminder remembers the zone it was set in" }
        require((status == ReminderStatus.NOT_POSTED) == (reason != null)) { "a reason goes with NOT_POSTED and nothing else" }
        require(status.wasPosted == (postedAtMillis != null)) { "$status and its posted time disagree" }
        require(status.isResolved == (resolvedAtMillis != null)) { "$status and its resolved time disagree" }
    }

    val isCheckpoint: Boolean get() = targetType == ReminderTargetType.NONE

    fun transitionTo(
        next: ReminderStatus,
        at: Long,
        reason: ReminderReason? = null,
    ): Reminder {
        require(next in status.legalNext) { "$status cannot become $next" }
        return copy(
            status = next,
            reason = reason,
            postedAtMillis = if (next == ReminderStatus.POSTED) at else postedAtMillis,
            resolvedAtMillis = if (next.isResolved) at else null,
        )
    }
}

enum class ReminderProblem { TOO_SOON, TOO_FAR, QUIET_PERIOD, TOO_MANY }

/** What delivery does when the alarm arrives. */
sealed interface ReminderDelivery {
    data object Post : ReminderDelivery

    data class Skip(
        val reason: ReminderReason,
    ) : ReminderDelivery
}

/** The product defaults of ADR 0014. They are this slice's choices, not the specification's. */
object ReminderRules {
    const val MIN_LEAD_MILLIS = 60_000L
    const val HORIZON_MILLIS = 7 * 24 * 60 * 60_000L
    const val MAX_PENDING = 20
    const val WINDOW_MILLIS = 60 * 60_000L
    const val LABEL_MAX = 120
    const val CHECKPOINT_LABEL = "Checkpoint"

    /** Why a new reminder due at [dueAtMillis] cannot be made now, or null. [pending] counts the SCHEDULED ones. */
    fun problem(
        dueAtMillis: Long,
        nowMillis: Long,
        pending: Int,
        inQuietPeriod: Boolean,
    ): ReminderProblem? =
        when {
            dueAtMillis < nowMillis + MIN_LEAD_MILLIS -> ReminderProblem.TOO_SOON
            dueAtMillis > nowMillis + HORIZON_MILLIS -> ReminderProblem.TOO_FAR
            inQuietPeriod -> ReminderProblem.QUIET_PERIOD
            pending >= MAX_PENDING -> ReminderProblem.TOO_MANY
            else -> null
        }

    /** What to do when the alarm for a reminder due at [dueAtMillis] is delivered at [nowMillis]. The window is checked first. */
    fun delivery(
        dueAtMillis: Long,
        nowMillis: Long,
        inQuietPeriod: Boolean,
        canPost: Boolean,
    ): ReminderDelivery =
        when {
            nowMillis - dueAtMillis > WINDOW_MILLIS -> ReminderDelivery.Skip(ReminderReason.WINDOW_EXPIRED)
            inQuietPeriod -> ReminderDelivery.Skip(ReminderReason.QUIET_PERIOD)
            !canPost -> ReminderDelivery.Skip(ReminderReason.NOTIFICATIONS_BLOCKED)
            else -> ReminderDelivery.Post
        }

    /** The display label for a target's [text]: its first non-blank line, spaces collapsed, cut with an ellipsis. */
    fun labelOf(text: String): String {
        val line =
            text
                .lineSequence()
                .map { it.trim().replace(Regex("\\s+"), " ") }
                .firstOrNull { it.isNotEmpty() }
                ?: return CHECKPOINT_LABEL
        return if (line.length <= LABEL_MAX) line else line.take(LABEL_MAX - 1) + "…"
    }

    fun newId(): String = UUID.randomUUID().toString()
}
