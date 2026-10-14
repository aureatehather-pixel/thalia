package com.thalia.app.planning

import com.thalia.planning.FixedCommitment
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** How a fixed commitment's real instants read in a zone: the date, and the end's date only when it differs. ADR 0011. */
internal object CommitmentText {
    private val date = DateTimeFormatter.ofPattern("EEE d MMM")
    private val time = DateTimeFormatter.ofPattern("HH:mm")

    fun span(
        commitment: FixedCommitment,
        zone: ZoneId,
    ): String {
        val start = commitment.start.atZone(zone)
        val end = commitment.end.atZone(zone)
        val endText = if (end.toLocalDate() == start.toLocalDate()) end.format(time) else "${end.format(date)} ${end.format(time)}"
        return "${start.format(date)} ${start.format(time)} to $endText"
    }
}
