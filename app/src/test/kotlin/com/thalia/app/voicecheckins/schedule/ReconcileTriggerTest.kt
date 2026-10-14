package com.thalia.app.voicecheckins.schedule

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Which system broadcast means which trigger. */
class ReconcileTriggerTest {
    @Test
    fun `each system broadcast maps to its own trigger`() {
        assertEquals(ReconcileTrigger.BOOT, ReconcileTrigger.fromAction("android.intent.action.BOOT_COMPLETED"))
        assertEquals(ReconcileTrigger.PACKAGE_REPLACED, ReconcileTrigger.fromAction("android.intent.action.MY_PACKAGE_REPLACED"))
        assertEquals(ReconcileTrigger.TIME_CHANGED, ReconcileTrigger.fromAction("android.intent.action.TIME_SET"))
        assertEquals(ReconcileTrigger.TIMEZONE_CHANGED, ReconcileTrigger.fromAction("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(
            ReconcileTrigger.EXACT_ALARM_PERMISSION_GRANTED,
            ReconcileTrigger.fromAction("android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED"),
        )
    }

    @Test
    fun `anything else is not a trigger`() {
        assertNull(ReconcileTrigger.fromAction(null))
        assertNull(ReconcileTrigger.fromAction(""))
        assertNull(ReconcileTrigger.fromAction("android.intent.action.LOCKED_BOOT_COMPLETED"))
        assertNull(ReconcileTrigger.fromAction("android.intent.action.BOOT_COMPLETED "))
        assertNull(ReconcileTrigger.fromAction("com.thalia.app.action.CHECK_IN_ALARM"))
    }

    @Test
    fun `every trigger except the two started from inside the app arrives as a system broadcast`() {
        val fromSystem = ReconcileTrigger.SYSTEM_ACTIONS.values.toSet()
        val fromApp = setOf(ReconcileTrigger.APP_LAUNCH, ReconcileTrigger.MANUAL)

        assertEquals(ReconcileTrigger.entries.toSet(), fromSystem + fromApp)
        assertEquals(emptySet(), fromSystem.intersect(fromApp))
    }
}
