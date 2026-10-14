package com.thalia.app.m3a

/** Everything the start gate looks at, read at one moment. Reading it changes nothing. */
data class GateInputs(
    val consentAccepted: Boolean,
    val keySaved: Boolean,
    val microphoneGranted: Boolean,
    val networkValidated: Boolean,
    val checkInCallInProgress: Boolean,
    val attemptsUsed: Int,
)

/** Why a Gemini session may not start. Each is shown to the user as it is. */
enum class StartRefusal(
    val message: String,
) {
    CONSENT_NOT_GIVEN("Consent to the current wording has not been given."),
    NO_KEY("No API key is saved."),
    MICROPHONE_NOT_GRANTED("Microphone permission is not granted."),
    NO_VALIDATED_NETWORK("There is no validated internet connection."),
    CHECK_IN_CALL_IN_PROGRESS("A check-in call is ringing or active."),
    ALLOWANCE_EXHAUSTED("The experiment allowance cannot cover another connection attempt."),

    /** Found after the gate: the saved key could not be opened. Nothing was charged. */
    KEY_UNREADABLE("The saved API key could not be opened. Clear it and enter it again."),

    /** Found after the gate: the session could not be prepared. Nothing was charged. */
    PREPARATION_FAILED("The session could not be prepared."),

    /** Found after the gate: the charge could not be written, so nothing was opened. */
    LEDGER_UNWRITABLE("The attempt could not be recorded in the reservation ledger, so nothing was started."),
}

sealed interface GateDecision {
    data object Allowed : GateDecision

    data class Refused(
        val reason: StartRefusal,
    ) : GateDecision
}

/**
 * ADR 0008 decision 6's start gate. A pure function: it reads only its inputs, and it neither
 * asks for a permission nor charges the allowance. Asking for the microphone is the screen's job.
 */
object StartGate {
    fun evaluate(inputs: GateInputs): GateDecision {
        val refusal =
            when {
                !inputs.consentAccepted -> StartRefusal.CONSENT_NOT_GIVEN
                !inputs.keySaved -> StartRefusal.NO_KEY
                !inputs.microphoneGranted -> StartRefusal.MICROPHONE_NOT_GRANTED
                !inputs.networkValidated -> StartRefusal.NO_VALIDATED_NETWORK
                inputs.checkInCallInProgress -> StartRefusal.CHECK_IN_CALL_IN_PROGRESS
                inputs.attemptsUsed >= ExperimentAllowance.MAX_ATTEMPTS -> StartRefusal.ALLOWANCE_EXHAUSTED
                else -> null
            }
        return if (refusal == null) GateDecision.Allowed else GateDecision.Refused(refusal)
    }
}
