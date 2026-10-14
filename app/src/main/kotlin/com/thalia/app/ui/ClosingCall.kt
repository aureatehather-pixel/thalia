package com.thalia.app.ui

import com.thalia.app.voicecheckins.schedule.CancelResult
import com.thalia.app.voicecheckins.schedule.ConfirmResult
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * What the closing-call control shows and allows, from the stored check-in plan and the day's occurrence history. Nothing here
 * schedules anything: the control calls the existing confirmer, which applies the quiet period, the exact-alarm permission and
 * the one-call-per-slot rule itself.
 */
internal enum class ClosingCallStatus { OFF, NOT_CONFIRMED, CONFIRMED, IN_CALL, USED }

internal data class ClosingCallView(
    val date: LocalDate,
    val planTime: LocalTime,
    val enabled: Boolean,
    /** The newest revision of this day's closing slot, if any. */
    val latest: ScheduledOccurrence?,
    val quiet: QuietPeriod,
)

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM")
private val STAMP = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

internal fun dayText(date: LocalDate): String = DAY.format(date)

internal fun closingStatus(view: ClosingCallView): ClosingCallStatus {
    val latest = view.latest
    return when {
        !view.enabled -> ClosingCallStatus.OFF
        latest == null || latest.isRecoverable -> ClosingCallStatus.NOT_CONFIRMED
        latest.status == CheckInStatus.SCHEDULED -> ClosingCallStatus.CONFIRMED
        latest.isEligible -> ClosingCallStatus.IN_CALL
        else -> ClosingCallStatus.USED
    }
}

/** The one line that says where the closing call stands for the chosen day. */
internal fun closingStatusText(view: ClosingCallView): String {
    val day = dayText(view.date)
    val latest = view.latest
    return when (closingStatus(view)) {
        ClosingCallStatus.OFF -> "The closing call is switched off in the check-in plan, so nothing will ring."
        ClosingCallStatus.NOT_CONFIRMED ->
            "Not confirmed for $day. Thalia rings only for a call you confirm." +
                if (latest != null) " An earlier one was ${latest.status.name.lowercase()}." else ""
        ClosingCallStatus.CONFIRMED -> "Confirmed for ${STAMP.format(checkNotNull(latest).localDateTime)}."
        ClosingCallStatus.IN_CALL -> "The closing call for $day is ringing or in progress, so it cannot be changed now."
        ClosingCallStatus.USED ->
            "The closing call for $day already rang (${checkNotNull(latest).status.name.lowercase()}). A day gets one call."
    }
}

/** What the quiet period means for the time being chosen, in the words of the existing rule. */
internal fun quietText(quiet: QuietPeriod): String =
    if (quiet.enabled) {
        "Quiet period ${quiet.start} to ${quiet.end}: no call rings, vibrates or wakes the screen inside it, " +
            "and a time inside it is refused."
    } else {
        "No quiet period is set."
    }

/** Today while the plan's closing time is still ahead on the clock, otherwise tomorrow. */
internal fun defaultClosingDate(
    now: LocalDateTime,
    planTime: LocalTime,
): LocalDate = if (now.toLocalTime() < planTime) now.toLocalDate() else now.toLocalDate().plusDays(1)

private val CLOCK = Regex("""\d{1,2}:\d{2}""")

/** A time as HH:mm to the minute, or null. Nothing else is accepted, so a stray second or zone never reaches the confirmer. */
internal fun parseClosingTime(text: String): LocalTime? {
    val trimmed = text.trim()
    if (!CLOCK.matches(trimmed)) return null
    return runCatching { LocalTime.parse(trimmed.padStart(5, '0')) }.getOrNull()
}

private fun stamp(occurrence: ScheduledOccurrence): String = STAMP.format(occurrence.localDateTime)

/** What a confirm or move did, in the product's words. */
internal fun closingResultText(result: ConfirmResult): String =
    when (result) {
        is ConfirmResult.Confirmed -> "Confirmed: the closing call will ring ${stamp(result.occurrence)}."
        is ConfirmResult.Rescheduled -> "Moved: the closing call will now ring ${stamp(result.occurrence)}."
        is ConfirmResult.Unchanged -> "It is already at ${stamp(result.occurrence)}. Nothing changed."
        is ConfirmResult.AlreadyConfirmed ->
            "That day's closing call is already confirmed or used (${result.status.name.lowercase()}). Nothing changed."
        is ConfirmResult.InProgress -> "That call is ${result.status.name.lowercase()}, so it cannot be changed. Nothing changed."
        ConfirmResult.SlotOff -> "The closing call is switched off in the check-in plan. Nothing was recorded."
        is ConfirmResult.InQuietPeriod ->
            "${stamp(result.occurrence)} is inside your quiet period, so nothing was scheduled or moved. Pick a time outside it."
        is ConfirmResult.TimePassed -> "${stamp(result.occurrence)} is not in the future. Nothing rings late, and nothing was changed."
        ConfirmResult.NotPermitted ->
            "Android is not allowing exact alarms for Thalia, so nothing was scheduled. Allow Alarms & reminders, then confirm again."
    }

internal fun closingCancelText(result: CancelResult): String =
    when (result) {
        is CancelResult.Cancelled -> "Cancelled: the closing call for ${stamp(result.occurrence)} will not ring."
        CancelResult.NothingToCancel -> "There is no scheduled closing call to cancel for that day."
        is CancelResult.InProgress -> "That call is ${result.status.name.lowercase()}, so it cannot be cancelled."
    }

internal const val ANSWER_NOTE =
    "Answering opens the conversation with the closing message ready to review and not sent. " +
        "It never closes your day, records an outcome or applies a change; only your own taps do."
