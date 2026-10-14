package com.thalia.app.voicecheckins.call

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The diagnostics logic, which decides what the app tells the user to expect.
 *
 * Reading the values from Android needs a device; deciding what they mean does not, and
 * getting that wrong would make M1's on-device answer untrustworthy.
 */
class CallCapabilitiesTest {
    private fun capabilities(
        sdkInt: Int = 36,
        postNotificationsGranted: Boolean = true,
        notificationsEnabled: Boolean = true,
        channelImportance: Int = 4,
        canUseFullScreenIntent: Boolean = true,
        canScheduleExactAlarms: Boolean = true,
    ) = CallCapabilities(
        sdkInt = sdkInt,
        postNotificationsGranted = postNotificationsGranted,
        notificationsEnabled = notificationsEnabled,
        channelImportance = channelImportance,
        canUseFullScreenIntent = canUseFullScreenIntent,
        canScheduleExactAlarms = canScheduleExactAlarms,
    )

    @Test
    fun `everything granted means a full-screen call`() {
        val c = capabilities()
        assertTrue(c.fullScreenCallExpected)
        assertTrue(c.expectedBehaviour.contains("full-screen incoming-call screen"))
    }

    @Test
    fun `a withheld full-screen intent degrades to a heads-up, honestly`() {
        val c = capabilities(canUseFullScreenIntent = false)
        assertFalse(c.fullScreenCallExpected)
        assertTrue(c.expectedBehaviour.contains("A notification with Answer and Decline remains available"))
        // The fallback is a worse experience, not a bug, and is described as such.
        assertTrue(c.expectedBehaviour.contains("honest fallback"))
    }

    @Test
    fun `no notification permission means nothing happens at all`() {
        val c = capabilities(postNotificationsGranted = false)
        assertFalse(c.fullScreenCallExpected)
        assertTrue(c.expectedBehaviour.startsWith("Nothing will happen"))
    }

    @Test
    fun `notifications switched off means nothing happens at all`() {
        val c = capabilities(notificationsEnabled = false)
        assertFalse(c.fullScreenCallExpected)
        assertTrue(c.expectedBehaviour.startsWith("Nothing will happen"))
    }

    @Test
    fun `a channel turned below high importance will not ring`() {
        val c = capabilities(channelImportance = 3)
        assertFalse(c.fullScreenCallExpected)
        assertTrue(c.expectedBehaviour.contains("below High importance"))
    }

    @Test
    fun `missing permission is reported ahead of a quieter problem`() {
        // Both are wrong here. The user is told the one that blocks everything.
        val c = capabilities(postNotificationsGranted = false, canUseFullScreenIntent = false)
        assertTrue(c.expectedBehaviour.startsWith("Nothing will happen"))
    }

    @Test
    fun `the full-screen intent permission only exists from Android 14`() {
        assertFalse(capabilities(sdkInt = 33).fullScreenIntentIsGovernedByPermission)
        assertTrue(capabilities(sdkInt = 34).fullScreenIntentIsGovernedByPermission)
        assertTrue(capabilities(sdkInt = 36).fullScreenIntentIsGovernedByPermission)
    }

    @Test
    fun `exact alarms allowed means scheduled check-ins can start at their time`() {
        val c = capabilities(canScheduleExactAlarms = true)
        assertTrue(c.exactAlarmBehaviour.startsWith("Scheduled check-ins can start"))
    }

    @Test
    fun `exact alarms not allowed says scheduled check-ins are off and that there is no approximate fallback`() {
        val c = capabilities(canScheduleExactAlarms = false)
        assertTrue(c.exactAlarmBehaviour.startsWith("Scheduled check-ins are off"))
        assertTrue(c.exactAlarmBehaviour.contains("does not fall back to approximate times"))
        // And it tells the user where to fix it.
        assertTrue(c.exactAlarmBehaviour.contains("Alarms & reminders"))
    }

    @Test
    fun `the exact-alarm answer does not change what the call itself will look like`() {
        // Two separate questions. Being unable to schedule must not repaint how a call presents.
        assertEquals(
            capabilities(canScheduleExactAlarms = true).expectedBehaviour,
            capabilities(canScheduleExactAlarms = false).expectedBehaviour,
        )
        assertEquals(
            capabilities(canUseFullScreenIntent = false).exactAlarmBehaviour,
            capabilities(canUseFullScreenIntent = true).exactAlarmBehaviour,
        )
    }

    @Test
    fun `every failure mode has a distinct explanation`() {
        val explanations =
            listOf(
                capabilities(),
                capabilities(canUseFullScreenIntent = false),
                capabilities(channelImportance = 3),
                capabilities(notificationsEnabled = false),
                capabilities(postNotificationsGranted = false),
            ).map { it.expectedBehaviour }

        assertEquals(explanations.size, explanations.distinct().size)
    }
}
