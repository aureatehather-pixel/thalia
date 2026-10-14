package com.thalia.app.ai

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpeechUsageTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "speech-usage.log")
    private val kl = ZoneId.of("Asia/Kuala_Lumpur")
    private val oct = Instant.parse("2026-10-13T04:00:00Z")

    private fun usage(zone: ZoneId = kl) = SpeechUsage(file) { zone }

    @Test
    fun `the cap starts unset and voice is refused until one is set`() {
        val u = usage()
        assertNull(u.summary(oct).cap)
        assertEquals(SpeechCount.NoCap, u.count(10, oct))
        assertEquals(0, u.summary(oct).used)
        assertTrue(u.setCap(100, oct))
        assertEquals(SpeechCount.Counted, u.count(10, oct))
    }

    @Test
    fun `every submission counts in full, repeats included, and the cap is checked first`() {
        val u = usage()
        u.setCap(100, oct)
        assertEquals(SpeechCount.Counted, u.count(40, oct))
        assertEquals(SpeechCount.Counted, u.count(40, oct))
        assertEquals(80, u.summary(oct).used)
        val refused = assertIs<SpeechCount.CapReached>(u.count(21, oct))
        assertEquals(80, refused.summary.used)
        assertEquals(80, u.summary(oct).used)
        assertEquals(SpeechCount.Counted, u.count(20, oct))
        assertEquals(0, u.summary(oct).remaining)
    }

    @Test
    fun `concurrent submissions cannot pass the cap together`() {
        val u = usage()
        u.setCap(100, oct)
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        val counted = AtomicInteger()
        val jobs =
            List(20) {
                pool.submit {
                    start.await()
                    if (u.count(10, oct) == SpeechCount.Counted) counted.incrementAndGet()
                }
            }
        start.countDown()
        jobs.forEach { it.get() }
        pool.shutdown()
        assertEquals(10, counted.get())
        assertEquals(100, u.summary(oct).used)
    }

    @Test
    fun `the month turns over at local midnight in the device zone, not at UTC midnight`() {
        val u = usage()
        u.setCap(100, oct)
        // 2026-10-31 23:30 in Kuala Lumpur is 15:30 UTC the same day; 2026-11-01 00:30 there is 16:30 UTC on 31 October.
        val lastLocalMinutes = Instant.parse("2026-10-31T15:30:00Z")
        val firstLocalMinutes = Instant.parse("2026-10-31T16:30:00Z")
        u.count(90, lastLocalMinutes)
        assertEquals("2026-10", u.summary(lastLocalMinutes).month)
        assertEquals(90, u.summary(lastLocalMinutes).used)
        assertEquals("2026-11", u.summary(firstLocalMinutes).month)
        assertEquals(0, u.summary(firstLocalMinutes).used)
        assertEquals(SpeechCount.Counted, u.count(90, firstLocalMinutes))
        // The same instant is still October when counted in UTC, so the zone is what decides.
        assertEquals("2026-10", usage(ZoneId.of("UTC")).summary(firstLocalMinutes).month)
        // The cap carries over; October's characters are kept in the file but do not count in November.
        assertEquals(100L, u.summary(firstLocalMinutes).cap)
    }

    @Test
    fun `cap and counts persist across a restart and a changed cap applies at once`() {
        val first = usage()
        first.setCap(100, oct)
        first.count(60, oct)
        val again = usage()
        assertEquals(100L, again.summary(oct).cap)
        assertEquals(60, again.summary(oct).used)
        again.setCap(50, oct)
        assertIs<SpeechCount.CapReached>(again.count(1, oct))
        again.setCap(500, oct)
        assertEquals(SpeechCount.Counted, again.count(1, oct))
        assertEquals(61, usage().summary(oct).used)
    }

    @Test
    fun `clearing the cap turns voice off, keeps the count, and a new cap carries on from that count`() {
        val u = usage()
        u.setCap(100, oct)
        u.count(40, oct)
        assertTrue(u.clearCap(oct))
        assertNull(u.summary(oct).cap)
        assertEquals(40, u.summary(oct).used)
        assertEquals(SpeechCount.NoCap, u.count(1, oct))
        val again = usage()
        assertNull(again.summary(oct).cap)
        assertEquals(40, again.summary(oct).used)
        again.setCap(50, oct)
        assertIs<SpeechCount.CapReached>(again.count(11, oct))
        assertEquals(SpeechCount.Counted, again.count(10, oct))
        assertEquals(50, usage().summary(oct).used)
        // Clearing when already unset is harmless.
        assertTrue(usage().also { it.clearCap(oct) }.clearCap(oct))
        assertEquals(0, usage().summary(oct).unreadableLines)
    }

    @Test
    fun `a line that cannot be read is charged, a torn one keeps its own line`() {
        val u = usage()
        u.setCap(1000, oct)
        u.count(10, oct)
        file.appendText("U\tgarbage")
        assertEquals(1, u.summary(oct).unreadableLines)
        assertEquals(10 + SpeechUsage.UNREADABLE_LINE_CHARS, u.summary(oct).used)
        u.count(5, oct)
        assertEquals(15 + SpeechUsage.UNREADABLE_LINE_CHARS, usage().summary(oct).used)
    }

    @Test
    fun `an unwritable file refuses the submission`() {
        val blocked = File(dir, "blocker").apply { writeText("a file, not a folder") }
        val u = SpeechUsage(File(blocked, "speech-usage.log")) { kl }
        assertEquals(false, u.setCap(100, oct))
        assertEquals(SpeechCount.NoCap, u.count(1, oct))
    }

    @Test
    fun `the old test ledger is a separate file and is never read or changed`() {
        val old = File(dir, "speech-budget.log")
        val body = "R\ta\t2026-10\t519\t1\nS\ta\t2026-10\t519\t2\n"
        old.writeText(body)
        val u = usage()
        u.setCap(100, oct)
        u.count(10, oct)
        assertEquals(body, old.readText())
        assertEquals(10, u.summary(oct).used)
        assertEquals(519, BudgetLedger(old, SPEECH_TEST_LEDGER_CHARS).summary(Instant.parse("2026-10-13T04:00:00Z")).spentMicros)
    }

    @Test
    fun `the cap field takes whole positive numbers only`() {
        assertEquals(5000L, parseSpeechCap(" 5000 "))
        listOf("", "0", "-5", "1.5", "1,000", "abc", "1000000000").forEach { assertNull(parseSpeechCap(it), it) }
        assertFailsWith<IllegalArgumentException> { usage().setCap(0, oct) }
    }

    @Test
    fun `the meter says app-tracked submitted characters, not billing`() {
        val unset = speechMeterText(usage().summary(oct))
        assertTrue("not set" in unset && "off until you set one" in unset && "text replies still work" in unset)
        val u = usage()
        u.setCap(100, oct)
        u.count(30, oct)
        val text = speechMeterText(u.summary(oct))
        assertTrue("30 of 100 characters submitted" in text && "app-tracked usage, not Cartesia's billing" in text, text)
    }
}
