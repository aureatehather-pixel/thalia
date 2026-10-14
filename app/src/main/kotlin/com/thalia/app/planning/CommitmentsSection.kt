package com.thalia.app.planning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thalia.app.planning.data.CreateCommitmentResult
import com.thalia.planning.CommitmentStatus
import com.thalia.planning.FixedCommitment
import com.thalia.planning.PlanningText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Fixed commitments, entered by hand at real dates and times in the check-in zone. ADR 0011. Adding or cancelling one never
 * changes an approved plan; it counts in new previews and in the working day's notices.
 */
@Composable
internal fun CommitmentsSection(
    access: PlanningAccess,
    zoneSource: CheckInZoneSource,
    writes: PlanningWrites,
    changes: Int,
) {
    val scope = rememberCoroutineScope()
    var zone by remember { mutableStateOf<ZoneId?>(null) }
    var list by remember { mutableStateOf<List<FixedCommitment>?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var cleared by remember { mutableIntStateOf(0) }

    LaunchedEffect(changes) {
        try {
            zone = (zoneSource.read() as? CheckInZone.Known)?.zone
            val since = PlanningClock.now().minus(1, ChronoUnit.DAYS)
            list =
                access.withStore { store -> store.commitments().filter { it.status == CommitmentStatus.ACTIVE && it.end.isAfter(since) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            message = "Fixed commitments could not be read."
        }
    }

    fun write(
        what: suspend (com.thalia.app.planning.data.PlanningStore) -> String,
        succeeded: (String) -> Boolean,
        then: () -> Unit = {},
    ) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val text = writes.run(what, succeeded)
                message = text
                if (succeeded(text)) then()
            } catch (e: IllegalArgumentException) {
                message = e.message ?: "Not saved."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                message = "Not saved: planning data could not be written."
            } finally {
                busy = false
            }
        }
    }

    Text("Fixed commitments", style = MaterialTheme.typography.titleMedium)
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
    val known = zone ?: return Text("Commitments need the check-in time zone, which is not usable yet.")
    val shown = list ?: return Text("Loading...")
    if (shown.isEmpty()) Text("None from yesterday onward.", style = MaterialTheme.typography.bodySmall)
    shown.forEach { commitment ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${commitment.title}, ${CommitmentText.span(commitment, known)}", modifier = Modifier.weight(1f))
            TextButton(enabled = !busy, onClick = {
                write({ store ->
                    if (store.updateCommitment(
                            commitment.copy(status = CommitmentStatus.CANCELLED),
                        )
                    ) {
                        "Cancelled ${commitment.title}."
                    } else {
                        "Not found."
                    }
                }, { it.startsWith("Cancelled") })
            }) { Text("Cancel") }
        }
    }
    var title by remember(cleared) { mutableStateOf("") }
    var date by remember(cleared) { mutableStateOf("") }
    var start by remember(cleared) { mutableStateOf("") }
    var end by remember(cleared) { mutableStateOf("") }
    var endDate by remember(cleared) { mutableStateOf("") }
    Text("New commitment, in ${known.id}", style = MaterialTheme.typography.labelLarge)
    OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-mm-dd)") }, modifier = Modifier.fillMaxWidth())
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(start, { start = it }, label = { Text("Start (HH:mm)") }, modifier = Modifier.weight(1f))
        OutlinedTextField(end, { end = it }, label = { Text("End (HH:mm)") }, modifier = Modifier.weight(1f))
    }
    OutlinedTextField(endDate, { endDate = it }, label = { Text("End date, only if a later day") }, modifier = Modifier.fillMaxWidth())
    Button(enabled = !busy, onClick = {
        write({ store ->
            val day = parse("Dates are yyyy-mm-dd") { LocalDate.parse(date.trim()) }
            val lastDay = if (endDate.isBlank()) day else parse("Dates are yyyy-mm-dd") { LocalDate.parse(endDate.trim()) }
            val from = exact(LocalDateTime.of(day, parse("Times are HH:mm") { LocalTime.parse(start.trim()) }), known)
            val to = exact(LocalDateTime.of(lastDay, parse("Times are HH:mm") { LocalTime.parse(end.trim()) }), known)
            require(to.isAfter(from)) { "It must end after it starts. For one that ends after midnight, give the end date." }
            val commitment = FixedCommitment(UUID.randomUUID().toString(), PlanningText.clean(title), from, to, known)
            when (store.createCommitment(commitment)) {
                CreateCommitmentResult.CREATED -> "Added ${commitment.title}, ${CommitmentText.span(commitment, known)}."
                else -> "Not added: the same commitment is already recorded."
            }
        }, { it.startsWith("Added") }) { cleared++ }
    }) { Text("Add commitment") }
}

private inline fun <T> parse(
    what: String,
    read: () -> T,
): T = runCatching(read).getOrElse { throw IllegalArgumentException(what) }

/** The one instant [local] names in [zone]. A time the clocks skip, or one that happens twice, is refused, not guessed. */
private fun exact(
    local: LocalDateTime,
    zone: ZoneId,
): Instant {
    val offsets = zone.rules.getValidOffsets(local)
    require(offsets.isNotEmpty()) { "$local does not exist in ${zone.id}: the clocks skip it." }
    require(offsets.size == 1) { "$local happens twice in ${zone.id}. Enter the other time, or a time just after." }
    return local.toInstant(offsets.single())
}
