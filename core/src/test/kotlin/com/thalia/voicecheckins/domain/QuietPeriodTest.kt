package com.thalia.voicecheckins.domain

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class QuietPeriodTest {
    private fun at(text: String) = LocalTime.parse(text)

    private val night = QuietPeriod(at("22:00"), at("07:00"), enabled = true)
    private val afternoon = QuietPeriod(at("13:00"), at("14:30"), enabled = true)

    @Test
    fun `a new install starts with the period switched off`() {
        assertFalse(QuietPeriod.DEFAULT.enabled)
        assertFalse(QuietPeriod.DEFAULT.covers(at("23:00")), "off covers nothing, even inside its times")
    }

    @Test
    fun `a period within one day covers from its start up to but not including its end`() {
        assertFalse(afternoon.spansMidnight)
        assertFalse(afternoon.covers(at("12:59")))
        assertTrue(afternoon.covers(at("13:00")))
        assertTrue(afternoon.covers(at("14:29:59")))
        assertFalse(afternoon.covers(at("14:30")))
        assertFalse(afternoon.covers(at("23:00")))
    }

    @Test
    fun `a period spanning midnight covers the late evening and the early morning, and not the day`() {
        assertTrue(night.spansMidnight)
        assertFalse(night.covers(at("21:59")))
        assertTrue(night.covers(at("22:00")))
        assertTrue(night.covers(at("23:59:59")))
        assertTrue(night.covers(at("00:00")))
        assertTrue(night.covers(at("06:59")))
        assertFalse(night.covers(at("07:00")))
        assertFalse(night.covers(at("10:00")))
        assertFalse(night.covers(at("15:00")))
    }

    @Test
    fun `a period ending at midnight covers up to the last moment of the day`() {
        val lateEvening = QuietPeriod(at("20:00"), at("00:00"), enabled = true)
        assertTrue(lateEvening.spansMidnight)
        assertTrue(lateEvening.covers(at("23:59")))
        assertFalse(lateEvening.covers(at("00:00")))
        assertFalse(lateEvening.covers(at("19:59")))
    }

    @Test
    fun `a disabled period keeps its times and covers nothing`() {
        val off = night.copy(enabled = false)
        assertFalse(off.covers(at("23:00")))
        assertTrue(off.copy(enabled = true).covers(at("23:00")))
    }

    @Test
    fun `an instant is read on the wall clock of the zone given`() {
        val kl = ZoneId.of("Asia/Kuala_Lumpur")
        // 23:30 in Kuala Lumpur is 15:30 UTC.
        val lateNight = Instant.parse("2026-09-30T15:30:00Z")
        assertTrue(night.covers(lateNight, kl))
        assertFalse(night.covers(lateNight, ZoneId.of("UTC")))
    }

    @Test
    fun `equal start and end, and times below a minute, are refused`() {
        assertFailsWith<IllegalArgumentException> { QuietPeriod(at("22:00"), at("22:00")) }
        assertFailsWith<IllegalArgumentException> { QuietPeriod(at("22:00:30"), at("07:00")) }
    }
}
