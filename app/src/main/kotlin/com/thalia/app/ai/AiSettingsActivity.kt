package com.thalia.app.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.thalia.app.ThaliaApplication
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.ui.ThaliaTheme
import com.thalia.conversation.ContextLimits
import java.time.Instant

/**
 * AI settings, ADR 0013: consent, the device-held key, and the monthly budget. Plain and functional. Nothing here sends a
 * request; opening it reads local files only. The key is typed into a masked field and is never shown again once saved.
 */
class AiSettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ai = (application as ThaliaApplication).aiServices
        setContent {
            ThaliaTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AiSettingsScreen(ai)
                }
            }
        }
    }
}

/** The words about the budget, apart from the screen so they are tested. */
internal fun budgetText(s: BudgetSummary): String =
    buildString {
        append(
            "This month (${s.month}, UTC): ${Usd.format(
                s.spentMicros,
            )} of ${Usd.format(s.budgetMicros)} used by this phone's own estimate.",
        )
        if (s.heldCount > 0) {
            append(
                " ${Usd.format(s.heldMicros)} of that is held for ${s.heldCount} request(s) whose cost is not confirmed, " +
                    "such as one that timed out. It stays counted until the month ends.",
            )
        }
        if (s.unreadableLines >
            0
        ) {
            append(" ${s.unreadableLines} unreadable record(s) are counted at ${Usd.format(BudgetLedger.UNREADABLE_LINE_MICROS)} each.")
        }
    }

internal fun budgetWarning(s: BudgetSummary): String? =
    when {
        s.spentMicros >= s.budgetMicros -> "The budget is used up. No more requests are sent this month unless you raise it."
        s.fraction >= 0.9 -> "90% of the budget is used."
        s.fraction >= 0.7 -> "70% of the budget is used."
        else -> null
    }

/** What is behind the gates in this build, in words that do not overstate it. */
internal fun connectionText(
    connection: AiConnection,
    provider: String = "Google's Gemini",
): String =
    when (connection) {
        AiConnection.NONE -> "This build has no connection to any AI service, so nothing is ever sent."
        AiConnection.FAKE ->
            "CHECK BUILD: requests go to a fake transport inside this app, not to Google. This build has no internet " +
                "permission, so nothing leaves the phone. The wording below describes the real service."
        AiConnection.LIVE -> "This build can send requests over the internet to $provider, an external service, as described below."
    }

/** The ceiling in plain words: the budget can be lowered and raised back, never above the validation cap. */
internal fun ceilingText(): String =
    "The most this budget can be is ${Usd.format(Usd.AI_BUDGET_CEILING_MICROS)} a month. The whole recurring technology cost of " +
        "Thalia, AI and hosting together, is meant to stay within RM 40 to 50 a month, so a higher figure is not a setting: " +
        "it needs a recorded decision and a new version of the app."

@Composable
private fun AiSettingsScreen(ai: AiServices) {
    var version by remember { mutableIntStateOf(0) }
    var message by remember { mutableStateOf<String?>(null) }
    var keyEntry by remember { mutableStateOf("") }
    var budgetEntry by remember { mutableStateOf("") }
    var confirmBudget by remember { mutableStateOf<Long?>(null) }
    // Read again after every action; none of this is a request.
    val accepted = version.let { ai.consent.isAccepted() }
    val keySaved = version.let { ai.keys.hasKey() }
    val summary = version.let { ai.ledger.summary(Instant.now()) }
    Column(
        modifier = Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("AI settings", style = MaterialTheme.typography.headlineSmall)
        Text(
            "AI is ${if (accepted) "ON" else "OFF"}. Key: ${if (keySaved) "saved" else "none"}. " +
                connectionText(ai.connection, ai.providerName),
        )
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        HorizontalDivider()

        Text("What AI means here", style = MaterialTheme.typography.titleMedium)
        Text(ai.consentText, style = MaterialTheme.typography.bodySmall)
        if (accepted) {
            OutlinedButton(onClick = {
                ai.consent.withdraw()
                message = "AI is off. Nothing will be sent."
                version++
            }) { Text("Turn AI off") }
        } else {
            Button(onClick = {
                ai.consent.accept(System.currentTimeMillis())
                message = "AI is on for this wording. Requests still need a key and budget."
                version++
            }) { Text("I understand. Turn AI on") }
        }
        HorizontalDivider()

        Text("Key", style = MaterialTheme.typography.titleMedium)
        Text(
            "The key is encrypted with a key held in this phone's Android Keystore and stored in this app's private folder. " +
                "It is never shown again, logged, exported or included in an export of your planning data. Clearing it deletes it.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            keyEntry,
            { keyEntry = it },
            label = { Text(ai.keyLabel) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = keyEntry.isNotBlank(), onClick = {
                message =
                    when (val r = ai.keys.save(keyEntry)) {
                        ApiKeyStore.SaveResult.Saved -> "Key saved."
                        is ApiKeyStore.SaveResult.Invalid -> r.message
                        ApiKeyStore.SaveResult.EncryptionFailed -> "The key could not be encrypted, so it was not saved."
                    }
                keyEntry = ""
                version++
            }) { Text("Save key") }
            OutlinedButton(enabled = keySaved, onClick = {
                message =
                    when (ai.keys.clear()) {
                        ApiKeyStore.ClearResult.Cleared -> "Key cleared."
                        ApiKeyStore.ClearResult.Failed -> "The key file is deleted, but the Keystore key could not be."
                    }
                version++
            }) { Text("Clear key") }
        }
        HorizontalDivider()

        Text("Budget", style = MaterialTheme.typography.titleMedium)
        Text(budgetText(summary))
        ai.estimateNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        budgetWarning(summary)?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Text(
            "Before each request Thalia holds its worst-case cost (at most ${ContextLimits.OUTPUT_TOKENS} output tokens, " +
                "thinking included, and a bounded input) at the highest ${ai.prices.model} price in force within a day of " +
                "it, prices read ${ai.prices.readOn}. It settles to the usage ${ai.providerName} reports. This is an estimate: it is " +
                "not the provider's invoice or a hard cap, clearing this app's data resets it, and a price change Thalia does not " +
                "know about is not in it. The provider's own billing page and spend limit are the real limit.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(ceilingText(), style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(
            budgetEntry,
            {
                budgetEntry = it
                confirmBudget = null
            },
            label = { Text("New monthly budget in USD") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.fillMaxWidth(),
        )
        val parsed = Usd.parse(budgetEntry)
        if (confirmBudget == null) {
            OutlinedButton(
                enabled = parsed != null && parsed != summary.budgetMicros && parsed <= Usd.AI_BUDGET_CEILING_MICROS,
                onClick = { confirmBudget = parsed },
            ) {
                Text("Change budget")
            }
        } else {
            val new = confirmBudget!!
            Text(
                if (new > summary.budgetMicros) {
                    "Raise the monthly budget from ${Usd.format(
                        summary.budgetMicros,
                    )} to ${Usd.format(new)}? Thalia may then send requests up to that amount."
                } else {
                    "Lower the monthly budget from ${Usd.format(summary.budgetMicros)} to ${Usd.format(new)}?"
                },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    message =
                        when (ai.ledger.setBudget(new, Instant.now())) {
                            BudgetLedger.SetBudget.SET -> "Budget is now ${Usd.format(new)} a month."
                            BudgetLedger.SetBudget.ABOVE_CEILING -> ceilingText()
                            BudgetLedger.SetBudget.UNWRITABLE -> "The budget could not be saved."
                        }
                    confirmBudget = null
                    budgetEntry = ""
                    version++
                }) { Text("Confirm") }
                OutlinedButton(onClick = { confirmBudget = null }) { Text("Cancel") }
            }
        }
        HorizontalDivider()

        SpeechSection(ai, version, onChange = {
            message = it
            version++
        })
        HorizontalDivider()

        Text("ChatGPT plan route", style = MaterialTheme.typography.titleMedium)
        ai.planRoute.settingsLines().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Spoken replies and sending after speaking: their own consent, key and small budget. Nothing here sends a request. */
@Composable
private fun SpeechSection(
    ai: AiServices,
    version: Int,
    onChange: (String) -> Unit,
) {
    var keyEntry by remember { mutableStateOf("") }
    val consented = version.let { ai.speechConsent.isAccepted() }
    val keySaved = version.let { ai.speechKeys.hasKey() }
    val speak = version.let { ai.prefs.speakReplies }
    val autoSend = version.let { ai.prefs.sendAfterSpeaking }
    val summary = version.let { ai.speechUsage.summary(Instant.now()) }
    var capEntry by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Spoken replies and speaking", style = MaterialTheme.typography.titleMedium)
        if (ai.synthesizer == null) {
            Text(SpeechProblem.NOT_BUILT.text, style = MaterialTheme.typography.bodySmall)
        } else {
            Text(SpeechConsentWording.TEXT, style = MaterialTheme.typography.bodySmall)
            if (consented) {
                OutlinedButton(onClick = {
                    ai.speechConsent.withdraw()
                    onChange("Spoken replies are off. Nothing will be sent for speech.")
                }) { Text("Turn spoken replies off") }
            } else {
                Button(onClick = {
                    ai.speechConsent.accept(System.currentTimeMillis())
                    onChange("Spoken replies are allowed. They still need a key.")
                }) { Text("I understand. Allow spoken replies") }
            }
            Text(
                "Speech key: ${if (keySaved) "saved" else "none"}. Use a Cartesia API key (starts with sk_car_). It is encrypted " +
                    "in this phone's Keystore, never shown again, and never logged or exported.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                keyEntry,
                { keyEntry = it },
                label = { Text("Cartesia API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = keyEntry.isNotBlank(), onClick = {
                    val result =
                        when (val r = ai.speechKeys.save(keyEntry)) {
                            ApiKeyStore.SaveResult.Saved -> "Speech key saved."
                            is ApiKeyStore.SaveResult.Invalid -> r.message
                            ApiKeyStore.SaveResult.EncryptionFailed -> "The key could not be encrypted, so it was not saved."
                        }
                    keyEntry = ""
                    onChange(result)
                }) { Text("Save speech key") }
                OutlinedButton(enabled = keySaved, onClick = {
                    ai.speechKeys.clear()
                    onChange("Speech key cleared.")
                }) { Text("Clear speech key") }
            }
            Text(speechMeterText(summary), style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                capEntry,
                { capEntry = it.filter(Char::isDigit).take(9) },
                label = { Text("Monthly voice limit (characters)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(enabled = parseSpeechCap(capEntry) != null, onClick = {
                val cap = parseSpeechCap(capEntry)
                val saved = cap != null && ai.speechUsage.setCap(cap, Instant.now())
                capEntry = ""
                onChange(if (saved) "Monthly voice limit saved." else "The limit could not be saved.")
            }) { Text("Set monthly voice limit") }
            OutlinedButton(enabled = summary.cap != null, onClick = {
                onChange(
                    if (ai.speechUsage.clearCap(Instant.now())) {
                        "Monthly voice limit cleared. Voice is off until you set one. This month's count is kept."
                    } else {
                        "The limit could not be cleared."
                    },
                )
            }) { Text("Clear monthly cap") }
        }
        Text(
            "Read replies aloud: ${if (speak) "ON" else "OFF"}. " +
                "Send a message right after I finish speaking: ${if (autoSend) "ON" else "OFF"}. " +
                "Sending never approves anything; only the Approve tap does.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                ai.prefs.setSpeakReplies(!speak)
                onChange("Read replies aloud is now ${if (!speak) "ON" else "OFF"}.")
            }) { Text(if (speak) "Mute replies" else "Unmute replies") }
            OutlinedButton(onClick = {
                ai.prefs.setSendAfterSpeaking(!autoSend)
                onChange("Send after speaking is now ${if (!autoSend) "ON" else "OFF"}.")
            }) { Text(if (autoSend) "Wait for Send" else "Send after speaking") }
        }
    }
}
