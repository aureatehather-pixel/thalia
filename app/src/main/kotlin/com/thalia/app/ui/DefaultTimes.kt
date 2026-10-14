package com.thalia.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.thalia.app.ThaliaApplication
import com.thalia.voicecheckins.domain.CheckInPlan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.time.LocalTime

/** What saving the typed default times would do to the stored check-in plan. */
internal sealed interface DefaultTimesChange {
    data class Valid(
        val plan: CheckInPlan,
        val changed: Boolean,
    ) : DefaultTimesChange

    data class Invalid(
        val message: String,
    ) : DefaultTimesChange
}

internal const val DEFAULT_TIMES_NOTE =
    "These are suggestions for future plans and closing calls. Saving them changes no approved plan, no confirmed call and no " +
        "alarm, and it schedules nothing. An approved plan keeps its own times."

/**
 * The stored plan with the typed times, or why not. Only the two times change: each slot keeps its on or off state and the zone
 * is kept. Opening must be before closing, as the pair rule of the check-in plan says.
 */
internal fun planWithDefaults(
    current: CheckInPlan,
    openingText: String,
    closingText: String,
): DefaultTimesChange {
    val opening = parseClosingTime(openingText)
    val closing = parseClosingTime(closingText)
    if (opening == null || closing == null) return DefaultTimesChange.Invalid("Times are HH:mm, for example 09:30. Nothing was changed.")
    if (!opening.isBefore(closing)) {
        return DefaultTimesChange.Invalid("The opening time must be before the closing time. Nothing was changed.")
    }
    val next =
        CheckInPlan(
            opening = current.opening.copy(localTime = opening),
            closing = current.closing.copy(localTime = closing),
        )
    return DefaultTimesChange.Valid(next, changed = next != current)
}

internal fun savedDefaultsText(plan: CheckInPlan) =
    "Saved. Opening ${plan.opening.localTime}, closing ${plan.closing.localTime}, ${plan.zone}. No call was scheduled, moved or cancelled."

/** The default opening and closing times. Reads and writes the stored check-in plan; the reconciler never reads its times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DefaultTimesCard(app: ThaliaApplication) {
    val scope = rememberCoroutineScope()
    var stored by remember { mutableStateOf<CheckInPlan?>(null) }
    var failed by remember { mutableStateOf(false) }
    var reload by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        try {
            stored = app.occurrenceStore.plan()
            failed = false
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
        }
    }

    SettingsCard("Default opening and closing times") {
        val plan = stored
        if (failed || plan == null) {
            Text(if (failed) "The check-in plan could not be read. Nothing was changed." else "Loading...")
            return@SettingsCard
        }
        var opening by rememberSaveable(plan) { mutableStateOf(plan.opening.localTime.toString()) }
        var closing by rememberSaveable(plan) { mutableStateOf(plan.closing.localTime.toString()) }
        Text(DEFAULT_TIMES_NOTE, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            opening,
            { opening = it },
            label = { Text("Opening time (HH:mm)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            closing,
            { closing = it },
            label = { Text("Closing time (HH:mm)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Time zone ${plan.zone}, kept as it is." +
                listOf(plan.opening to "opening", plan.closing to "closing")
                    .filter { !it.first.enabled }
                    .joinToString("") { " The ${it.second} call is switched off in the check-in plan; its time is still kept here." },
            style = MaterialTheme.typography.bodySmall,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy,
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    when (val change = planWithDefaults(plan, opening, closing)) {
                        is DefaultTimesChange.Invalid -> message = change.message
                        is DefaultTimesChange.Valid ->
                            if (!change.changed) {
                                message = "Those are already the default times. Nothing changed."
                            } else {
                                busy = true
                                scope.launch {
                                    try {
                                        app.occurrenceStore.savePlan(change.plan)
                                        message = savedDefaultsText(change.plan)
                                        reload++
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        message = "Not saved: the check-in plan could not be written. Nothing was changed."
                                    } finally {
                                        busy = false
                                    }
                                }
                            }
                    }
                },
            ) { Text("Save default times") }
            OutlinedButton(
                modifier = Modifier.heightIn(min = 48.dp),
                onClick = {
                    opening = LocalTime.of(10, 0).toString()
                    closing = LocalTime.of(15, 0).toString()
                },
            ) { Text("Fill in 10:00 and 15:00") }
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        Text(
            "Opening: offered as a suggestion on the Plan tab's opening call time. Closing: the starting time of the closing call " +
                "control below. Neither is used for anything else.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
