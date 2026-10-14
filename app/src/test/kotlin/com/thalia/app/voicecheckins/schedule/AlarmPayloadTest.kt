package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AlarmPayloadTest {
    @Test
    fun `a complete payload is rebuilt exactly`() {
        val payload = AlarmPayload.parse("2026-09-19-closing", "CLOSING", 1_789_000_000_000L)

        assertEquals(AlarmPayload("2026-09-19-closing", CheckInType.CLOSING, 1_789_000_000_000L), payload)
    }

    @Test
    fun `every check-in type round-trips through its name`() {
        CheckInType.entries.forEach { type ->
            assertEquals(type, AlarmPayload.parse("id", type.name, 1L)?.checkInType)
        }
    }

    @Test
    fun `an alarm armed before the rename is still understood`() {
        assertEquals(CheckInType.OPENING, AlarmPayload.parse("id", "MORNING", 1L)?.checkInType)
        assertEquals(CheckInType.CLOSING, AlarmPayload.parse("id", "AFTERNOON", 1L)?.checkInType)
    }

    @Test
    fun `an alarm that cannot say what it is for is rejected`() {
        assertNull(AlarmPayload.parse(null, "OPENING", 1L), "no occurrence id")
        assertNull(AlarmPayload.parse("", "OPENING", 1L), "empty occurrence id")
        assertNull(AlarmPayload.parse("   ", "OPENING", 1L), "blank occurrence id")
        assertNull(AlarmPayload.parse("id", null, 1L), "no type")
        assertNull(AlarmPayload.parse("id", "EVENING", 1L), "unknown type")
        assertNull(AlarmPayload.parse("id", "morning", 1L), "type is matched exactly, not loosely")
        assertNull(AlarmPayload.parse("id", "OPENING", null), "no scheduled instant")
    }
}
