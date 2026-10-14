package com.thalia.app.voicecheckins.devtools

import com.thalia.app.voicecheckins.schedule.CancelResult
import com.thalia.app.voicecheckins.schedule.ConfirmResult
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")

/** One plain line saying what a confirmation or a move did, for the debug screen. */
fun ConfirmResult.describe(): String =
    when (this) {
        is ConfirmResult.Confirmed -> "Confirmed ${occurrence.type.name.lowercase()} for ${TIME.format(occurrence.localDateTime)}."
        is ConfirmResult.Rescheduled ->
            "Moved ${occurrence.type.name.lowercase()} to ${TIME.format(occurrence.localDateTime)} (revision ${occurrence.revision})."
        is ConfirmResult.Unchanged -> "Already at ${TIME.format(occurrence.localDateTime)}. Nothing changed."
        is ConfirmResult.AlreadyConfirmed -> "Already confirmed for that day (${status.name.lowercase()}). Nothing changed."
        is ConfirmResult.InProgress -> "That call is ${status.name.lowercase()}, so it cannot be changed."
        ConfirmResult.SlotOff -> "That check-in is switched off in the plan. Nothing was recorded."
        is ConfirmResult.InQuietPeriod ->
            "${TIME.format(occurrence.localDateTime)} is inside the quiet period. Nothing was recorded or moved."
        is ConfirmResult.TimePassed -> "${TIME.format(occurrence.localDateTime)} is not in the future. Nothing was changed."
        ConfirmResult.NotPermitted -> "Exact alarms are not allowed, so it could not be scheduled."
    }

/** One plain line saying what a cancel did, for the debug screen. */
fun CancelResult.describe(): String =
    when (this) {
        is CancelResult.Cancelled -> "Cancelled ${occurrence.type.name.lowercase()} for ${TIME.format(occurrence.localDateTime)}."
        CancelResult.NothingToCancel -> "Nothing scheduled to cancel for that day."
        is CancelResult.InProgress -> "That call is ${status.name.lowercase()}, so it cannot be cancelled."
    }
