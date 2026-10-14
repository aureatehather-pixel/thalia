package com.thalia.app.planning

import androidx.compose.runtime.saveable.Saver
import com.thalia.planning.ActiveDay
import java.time.LocalDate

/**
 * How the Plan tab's choices survive a configuration change (rotation, font size) or the process being recreated.
 *
 * Only the person's own choices are kept: which actions are selected, their pin texts, the day, the window and the
 * opening-call text, and whether a preview was open. The proposal itself is never stored: it is made again from these
 * choices and the stored data, so a restored screen shows what the data says now. Nothing here approves or saves a plan;
 * only a tap on Approve does.
 */
internal val StringSetSaver: Saver<Set<String>, ArrayList<String>> =
    Saver(save = { ArrayList(it.sorted()) }, restore = { it.toSet() })

internal val StringMapSaver: Saver<Map<String, String>, ArrayList<String>> =
    Saver(
        save = { map -> ArrayList(map.entries.sortedBy { it.key }.flatMap { listOf(it.key, it.value) }) },
        restore = { list -> list.chunked(2).associate { it[0] to it[1] } },
    )

/** The day being planned, or none yet. */
internal val ActiveDaySaver: Saver<ActiveDay?, String> =
    Saver(
        save = { it?.toString().orEmpty() },
        restore = { text -> if (text.isEmpty()) null else ActiveDay(LocalDate.parse(text)) },
    )
