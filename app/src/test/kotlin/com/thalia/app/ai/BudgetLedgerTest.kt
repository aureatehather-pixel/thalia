package com.thalia.app.ai

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0013's budget accounting: the persistent monthly allowance, with no network and no Android. */
class BudgetLedgerTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "ai/budget.log")
    private val ledger get() = BudgetLedger(file)
    private val oct = Instant.parse("2026-10-08T10:00:00Z")

    private fun reserve(
        l: BudgetLedger,
        micros: Long,
        id: String = "r${System.nanoTime()}",
        at: Instant = oct,
    ) = assertIs<Reservation.Reserved>(l.reserve(id, micros, at))

    @Test
    fun `the budget starts at USD 5 and an empty ledger has spent nothing`() {
        val s = ledger.summary(oct)
        assertEquals(5_000_000, s.budgetMicros)
        assertEquals(0, s.spentMicros)
        assertEquals("2026-10", s.month)
    }

    @Test
    fun `a reservation is on the disk before it returns and counts at once`() {
        val l = ledger
        reserve(l, 40_000, "a")
        assertTrue(file.readText().startsWith("R\ta\t2026-10\t40000\t"))
        assertEquals(40_000, BudgetLedger(file).summary(oct).heldMicros)
    }

    @Test
    fun `a request that would pass the budget is refused and writes nothing`() {
        val l = ledger
        reserve(l, 4_990_000, "big")
        val before = file.readText()
        val r = l.reserve("late", 20_000, oct)
        assertIs<Reservation.BudgetExhausted>(r)
        assertEquals(before, file.readText())
        // Exactly filling the budget is allowed.
        reserve(l, 10_000, "exact")
        assertEquals(0, l.summary(oct).remainingMicros)
    }

    @Test
    fun `settling replaces the allowance with the reported cost and a release returns it`() {
        val l = ledger
        val a = reserve(l, 50_000, "a")
        val b = reserve(l, 50_000, "b")
        assertTrue(l.settle(a, 3_000, oct))
        assertTrue(l.release(b, oct))
        val s = l.summary(oct)
        assertEquals(3_000, s.spentMicros)
        assertEquals(0, s.heldCount)
    }

    @Test
    fun `an unconfirmed reservation stays counted across a restart and is never cleared by the app`() {
        reserve(ledger, 60_000, "timed-out")
        // A new process reads the same file: the held allowance is still there, and nothing in the ledger can drop it.
        val restarted = BudgetLedger(file)
        val s = restarted.summary(oct.plusSeconds(3 * 3600))
        assertEquals(1, s.heldCount)
        assertEquals(60_000, s.spentMicros)
        assertEquals(60_000, restarted.summary(Instant.parse("2026-10-31T23:00:00Z")).spentMicros)
    }

    @Test
    fun `a new month starts clean, and a settlement after midnight still belongs to the month it was reserved in`() {
        val l = ledger
        val late = Instant.parse("2026-10-31T23:59:59Z")
        val r = reserve(l, 80_000, "late", late)
        assertTrue(l.settle(r, 5_000, Instant.parse("2026-11-01T00:00:03Z")))
        assertEquals(5_000, l.summary(late).spentMicros)
        assertEquals(0, l.summary(Instant.parse("2026-11-01T00:00:10Z")).spentMicros)
    }

    @Test
    fun `a line torn by a crash is counted, keeps its own line and is not read as a neighbour`() {
        val l = ledger
        reserve(l, 10_000, "ok")
        file.appendText("R\ttorn\t2026-10\t99") // no line break: the process died mid-write
        val s = l.summary(oct)
        assertEquals(1, s.unreadableLines)
        assertEquals(10_000 + BudgetLedger.UNREADABLE_LINE_MICROS, s.spentMicros)
        reserve(l, 20_000, "after")
        val again = BudgetLedger(file).summary(oct)
        assertEquals(1, again.unreadableLines)
        assertEquals(10_000 + 20_000 + BudgetLedger.UNREADABLE_LINE_MICROS, again.spentMicros)
    }

    @Test
    fun `the budget can be lowered and raised back but never above the USD 5 ceiling`() {
        val l = ledger
        reserve(l, 1_000_000, "a")
        assertEquals(BudgetLedger.SetBudget.SET, l.setBudget(2_000_000, oct))
        assertEquals(2_000_000, BudgetLedger(file).summary(oct).budgetMicros)
        assertEquals(1_000_000, l.summary(oct).spentMicros)
        assertIs<Reservation.BudgetExhausted>(l.reserve("b", 1_500_000, oct))
        assertEquals(BudgetLedger.SetBudget.SET, l.setBudget(5_000_000, oct))
        // An ordinary confirmation is never authority to pass the ceiling, and nothing is written for it.
        val before = file.readText()
        assertEquals(BudgetLedger.SetBudget.ABOVE_CEILING, l.setBudget(5_000_001, oct))
        assertEquals(BudgetLedger.SetBudget.ABOVE_CEILING, l.setBudget(40_000_000, oct))
        assertEquals(before, file.readText())
        assertEquals(5_000_000, l.summary(oct).budgetMicros)
    }

    @Test
    fun `a budget line above the ceiling in the file is read as the ceiling`() {
        file.parentFile!!.mkdirs()
        file.writeText("B\t50000000\t1\n")
        assertEquals(5_000_000, ledger.summary(oct).budgetMicros)
        assertIs<Reservation.BudgetExhausted>(ledger.reserve("big", 5_000_001, oct))
    }

    @Test
    fun `an unwritable ledger refuses the reservation`() {
        File(dir, "ai").writeText("a file where the folder should be")
        assertIs<Reservation.Unwritable>(BudgetLedger(File(dir, "ai/budget.log")).reserve("x", 1, oct))
    }

    @Test
    fun `dollar amounts are exact, rounded to the cent for display and strict on input`() {
        assertEquals("USD 5.00", Usd.format(5_000_000))
        assertEquals("USD 0.04", Usd.format(40_000))
        assertEquals(5_500_000, Usd.parse("5.5"))
        assertEquals(12_340_000, Usd.parse("$12.34"))
        assertEquals(7_000_000, Usd.parse(" USD 7 "))
        listOf("", "0", "0.00", "-1", "1.234", "abc", "1e3", "1,5", "9999999").forEach { assertNull(Usd.parse(it), it) }
    }
}
