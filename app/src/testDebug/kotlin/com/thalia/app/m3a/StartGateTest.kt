package com.thalia.app.m3a

import kotlin.test.Test
import kotlin.test.assertEquals

class StartGateTest {
    private val allowed =
        GateInputs(
            consentAccepted = true,
            keySaved = true,
            microphoneGranted = true,
            networkValidated = true,
            checkInCallInProgress = false,
            attemptsUsed = 0,
        )

    private fun refusal(inputs: GateInputs): StartRefusal? = (StartGate.evaluate(inputs) as? GateDecision.Refused)?.reason

    @Test
    fun `everything in place allows a start`() {
        assertEquals(GateDecision.Allowed, StartGate.evaluate(allowed))
        assertEquals(GateDecision.Allowed, StartGate.evaluate(allowed.copy(attemptsUsed = ExperimentAllowance.MAX_ATTEMPTS - 1)))
    }

    @Test
    fun `consent not given is refused`() {
        assertEquals(StartRefusal.CONSENT_NOT_GIVEN, refusal(allowed.copy(consentAccepted = false)))
    }

    @Test
    fun `no saved key is refused`() {
        assertEquals(StartRefusal.NO_KEY, refusal(allowed.copy(keySaved = false)))
    }

    @Test
    fun `microphone permission not granted is refused`() {
        assertEquals(StartRefusal.MICROPHONE_NOT_GRANTED, refusal(allowed.copy(microphoneGranted = false)))
    }

    @Test
    fun `no validated network is refused`() {
        assertEquals(StartRefusal.NO_VALIDATED_NETWORK, refusal(allowed.copy(networkValidated = false)))
    }

    @Test
    fun `a ringing or active check-in call is refused`() {
        assertEquals(StartRefusal.CHECK_IN_CALL_IN_PROGRESS, refusal(allowed.copy(checkInCallInProgress = true)))
    }

    @Test
    fun `a spent allowance is refused`() {
        assertEquals(StartRefusal.ALLOWANCE_EXHAUSTED, refusal(allowed.copy(attemptsUsed = ExperimentAllowance.MAX_ATTEMPTS)))
        assertEquals(StartRefusal.ALLOWANCE_EXHAUSTED, refusal(allowed.copy(attemptsUsed = ExperimentAllowance.MAX_ATTEMPTS + 3)))
    }

    @Test
    fun `consent is checked first, so nothing else is looked at without it`() {
        val nothing = GateInputs(false, false, false, false, true, ExperimentAllowance.MAX_ATTEMPTS)
        assertEquals(StartRefusal.CONSENT_NOT_GIVEN, refusal(nothing))
    }

    @Test
    fun `the gate is a pure function of its inputs`() {
        val inputs = allowed.copy(networkValidated = false)
        val snapshot = inputs.copy()
        repeat(3) { assertEquals(StartRefusal.NO_VALIDATED_NETWORK, refusal(inputs)) }
        assertEquals(snapshot, inputs)
    }
}
