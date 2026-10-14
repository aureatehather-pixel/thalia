package com.thalia.app.planning

import com.thalia.app.planning.data.PlanCall
import com.thalia.planning.ActiveDay
import java.time.LocalTime

/**
 * The words of the opening-call section, apart from the screen so they are tested. The stored status is a log of what
 * the link last did; the preview line is M2's live state, read now. They are shown side by side and never merged.
 * ADR 0009 slice 3 amendment.
 */
internal fun callStatusText(
    call: PlanCall,
    dayPassed: Boolean = false,
): String {
    val status = CallStatus.valueOf(call.status)
    val detail = call.detail?.let { CallDetail.valueOf(it) }
    return "Opening call ${call.openingTime}: " +
        when (status) {
            CallStatus.NOT_LINKED -> "not linked on this phone. Nothing is scheduled."
            CallStatus.REQUESTED ->
                if (dayPassed) {
                    "requested, but its day passed before it was confirmed. No call was scheduled."
                } else {
                    "requested, not confirmed yet. It is created when Thalia next runs."
                }
            CallStatus.CONFIRMED -> "confirmed."
            CallStatus.REFUSED -> "not confirmed, ${detail.words()}."
            CallStatus.NEEDS_ATTENTION -> "needs attention, ${detail.words()}."
        }
}

private fun CallDetail?.words(): String =
    when (this) {
        CallDetail.SLOT_OFF -> "the opening check-in is switched off"
        CallDetail.ZONE_CHANGED -> "the check-in plan's time zone is not this plan's"
        CallDetail.TIME_NOT_ALLOWED -> "that time is before the day starts or does not exist"
        CallDetail.IN_QUIET_PERIOD -> "inside the quiet period"
        CallDetail.TIME_PASSED -> "the time has passed"
        CallDetail.PERMISSION_REFUSED -> "Android is not allowing exact alarms"
        CallDetail.OTHER_TIME_CONFIRMED -> "a call is already confirmed for this day at another time"
        CallDetail.CALL_CANCELLED -> "this day's call was cancelled or never rang"
        CallDetail.CALL_ENDED -> "a call already rang for this day"
        CallDetail.CALL_IN_PROGRESS -> "a call is in progress"
        CallDetail.M2_UNAVAILABLE -> "the check-in plan could not be read"
        null -> "no reason recorded"
    }

/** The live effect, from [preview], for [time] on [day]. Nothing in it is written anywhere. */
internal fun callPreviewText(
    day: ActiveDay,
    time: LocalTime,
    preview: CallPreview,
): String {
    preview.refusal?.let { return "Right now: not possible, ${it.words()}." }
    return "Right now: " +
        when (val slot = preview.slot) {
            SlotState.Empty, null -> "no call is set for this day. Confirming would create one at $time on $day."
            SlotState.ScheduledAtTime -> "a call is already confirmed for this day at $time."
            is SlotState.ScheduledElsewhere ->
                "a call is already confirmed for this day at ${slot.time}. It is never moved without your tick."
            SlotState.EndedUnrung -> "this day's call was cancelled or never rang. It is never revived without your tick."
            SlotState.InProgress -> "a call is ringing or in progress."
            SlotState.Ended -> "a call already rang for this day. A day gets one call."
        }
}

/** The offer, from a fresh preview, when the slot is not as planned. Null when nothing may be offered. */
internal fun recoveryOffer(
    recovery: Recovery,
    slot: SlotState?,
    time: LocalTime,
): String =
    when (recovery) {
        Recovery.MOVE -> "Move the ${(slot as? SlotState.ScheduledElsewhere)?.time ?: "existing"} call to $time"
        Recovery.NEW_CALL -> "Confirm a new call for this day at $time"
    }

/**
 * Everything a tick on the opening-call section refers to: the plan, its stored call, the live preview, the action offered and
 * how many actions have run. A tick belongs to exactly one value of this, and a different one starts unticked, so a box ticked
 * for one target can never authorize another. ADR 0009 slice 3.
 */
internal data class TickTarget(
    val planId: String,
    val call: PlanCall,
    val preview: CallPreview?,
    val label: String?,
    val attempts: Int,
)

internal fun attemptText(attempt: CallAttempt): String =
    when (attempt) {
        CallAttempt.NotTicked -> "Tick the box first. Nothing was done."
        CallAttempt.NotEligible ->
            "This call can no longer be changed from here: the plan was replaced, its day has passed, or it already changed. Nothing was done."
        is CallAttempt.Stale -> "The check-in changed since you looked. Nothing was done; look again."
        is CallAttempt.Done ->
            when (attempt.verdict.status) {
                CallStatus.CONFIRMED -> "The opening call is confirmed."
                CallStatus.REFUSED -> "The call was not confirmed: ${attempt.verdict.detail.words()}."
                CallStatus.NEEDS_ATTENTION -> "The call needs attention: ${attempt.verdict.detail.words()}."
                else -> "Done."
            }
    }
