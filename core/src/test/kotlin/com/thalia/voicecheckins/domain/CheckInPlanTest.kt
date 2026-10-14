package com.thalia.voicecheckins.domain

import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CheckInPlanTest {
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")

    @Test
    fun `default plan is 10am and 3pm in Kuala Lumpur`() {
        val plan = CheckInPlan.default()

        assertEquals(LocalTime.of(10, 0), plan.opening.localTime)
        assertEquals(LocalTime.of(15, 0), plan.closing.localTime)
        assertEquals(kl, plan.zone)
        assertTrue(plan.opening.enabled)
        assertTrue(plan.closing.enabled)
    }

    @Test
    fun `indexing by type returns the matching schedule`() {
        val plan = CheckInPlan.default()

        assertEquals(plan.opening, plan[CheckInType.OPENING])
        assertEquals(plan.closing, plan[CheckInType.CLOSING])
    }

    @Test
    fun `a slot can be rescheduled`() {
        val plan = CheckInPlan.default().withTime(CheckInType.CLOSING, LocalTime.of(17, 30))

        assertEquals(LocalTime.of(17, 30), plan.closing.localTime)
        assertEquals(LocalTime.of(10, 0), plan.opening.localTime)
    }

    @Test
    fun `rescheduling morning past afternoon is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CheckInPlan.default().withTime(CheckInType.OPENING, LocalTime.of(16, 0))
        }
    }

    @Test
    fun `identical morning and afternoon times are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CheckInPlan.default().withTime(CheckInType.CLOSING, LocalTime.of(10, 0))
        }
    }

    @Test
    fun `the two slots must share a zone`() {
        assertFailsWith<IllegalArgumentException> {
            CheckInPlan(
                opening = CheckInSchedule(CheckInType.OPENING, LocalTime.of(10, 0), kl),
                closing =
                    CheckInSchedule(
                        CheckInType.CLOSING,
                        LocalTime.of(15, 0),
                        ZoneId.of("Europe/London"),
                    ),
            )
        }
    }

    @Test
    fun `slots must hold their own check-in type`() {
        assertFailsWith<IllegalArgumentException> {
            CheckInPlan(
                opening = CheckInSchedule(CheckInType.CLOSING, LocalTime.of(10, 0), kl),
                closing = CheckInSchedule(CheckInType.CLOSING, LocalTime.of(15, 0), kl),
            )
        }
    }

    @Test
    fun `disabling a slot keeps its time`() {
        val plan = CheckInPlan.default().withEnabled(CheckInType.OPENING, enabled = false)

        assertFalse(plan.opening.enabled)
        assertEquals(LocalTime.of(10, 0), plan.opening.localTime)
    }

    @Test
    fun `sub-minute precision is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            CheckInSchedule(CheckInType.OPENING, LocalTime.of(10, 0, 30), kl)
        }
    }
}
