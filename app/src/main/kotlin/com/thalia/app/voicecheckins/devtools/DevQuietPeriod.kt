package com.thalia.app.voicecheckins.devtools

import com.thalia.voicecheckins.domain.QuietPeriod
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * Development only. A quiet period that starts at the current minute and lasts [minutes], switched on.
 *
 * It is what the debug screen sets so a device run can put a confirmed check-in, or one about to be
 * confirmed, inside the quiet period without waiting for the night. A period that crosses midnight
 * wraps, as any other does.
 */
fun quietPeriodFrom(
    now: LocalTime,
    minutes: Long = DEV_QUIET_MINUTES,
): QuietPeriod {
    val start = now.truncatedTo(ChronoUnit.MINUTES)
    return QuietPeriod(start, start.plusMinutes(minutes), enabled = true)
}

/** How long the debug screen's quiet period lasts. Longer than the ten-minute move, so a moved call falls inside it. */
const val DEV_QUIET_MINUTES: Long = 15

/** The quiet period in one plain line, for the debug screen. */
fun QuietPeriod.summaryLine(): String =
    if (enabled) {
        "on, $start to $end" + if (spansMidnight) " (spans midnight)" else ""
    } else {
        "off ($start to $end when on)"
    }
