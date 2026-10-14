package com.thalia.app.m3a

import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReservationLedgerTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "m3a/reservations.jsonl")

    @Test
    fun `the allowance is two dollars at a quarter per attempt, so eight attempts, in exact integers`() {
        assertEquals(2_000_000L, ExperimentAllowance.ALLOWANCE_MICROS)
        assertEquals(250_000L, ExperimentAllowance.ATTEMPT_MICROS)
        assertEquals(8, ExperimentAllowance.MAX_ATTEMPTS)
        assertEquals("USD 2.00", ExperimentAllowance.formatUsd(ExperimentAllowance.remainingMicros(0)))
        assertEquals("USD 1.75", ExperimentAllowance.formatUsd(ExperimentAllowance.remainingMicros(1)))
        assertEquals("USD 0.00", ExperimentAllowance.formatUsd(ExperimentAllowance.remainingMicros(8)))
        assertEquals("USD 0.00", ExperimentAllowance.formatUsd(ExperimentAllowance.remainingMicros(9)))
    }

    @Test
    fun `counting attempts reads only`() {
        val ledger = ReservationLedger(file)

        assertEquals(0, ledger.attemptsUsed())
        assertFalse(file.exists())
    }

    @Test
    fun `eight attempts are charged and the ninth is refused`() {
        val ledger = ReservationLedger(file)

        (1..8).forEach { n -> assertEquals(ReservationLedger.Reservation.Reserved(n), ledger.reserve("m3a-manual-$n", n.toLong())) }
        assertEquals(ReservationLedger.Reservation.AllowanceExhausted, ledger.reserve("m3a-manual-9", 9))
        assertEquals(8, ledger.attemptsUsed())
        assertEquals(8, file.readLines().size)
    }

    @Test
    fun `each line records the exact charge and the session`() {
        ReservationLedger(file).reserve("m3a-manual-1790000000000", 1_790_000_000_000)

        assertEquals(
            "{\"attempt\":1,\"chargedMicros\":250000,\"reservedAtMillis\":1790000000000,\"sessionId\":\"m3a-manual-1790000000000\"}",
            file.readLines().single(),
        )
    }

    @Test
    fun `charges survive a process restart`() {
        ReservationLedger(file).apply {
            reserve("a", 1)
            reserve("b", 2)
        }

        val reloaded = ReservationLedger(file)
        assertEquals(2, reloaded.attemptsUsed())
        assertEquals(ReservationLedger.Reservation.Reserved(3), reloaded.reserve("c", 3))
    }

    @Test
    fun `a final record torn by a crash counts once, leaves earlier records intact, and the ledger still appends`() {
        ReservationLedger(file).apply {
            reserve("m3a-manual-1", 1)
            reserve("m3a-manual-2", 2)
        }
        val valid = file.readLines()
        // The process died partway through writing the third record: no line break, no closing brace.
        file.appendText("{\"attempt\":3,\"chargedMicros\":250")

        // Read again and again, as after several restarts: the damaged tail is one attempt, every time.
        repeat(3) { assertEquals(3, ReservationLedger(file).attemptsUsed()) }

        assertEquals(ReservationLedger.Reservation.Reserved(4), ReservationLedger(file).reserve("m3a-manual-4", 4))

        val lines = file.readLines()
        assertEquals(valid, lines.take(2), "earlier records are untouched")
        assertEquals("{\"attempt\":3,\"chargedMicros\":250", lines[2], "the damaged record keeps a line of its own")
        assertTrue(lines[3].startsWith("{\"attempt\":4,"))
        repeat(3) { assertEquals(4, ReservationLedger(file).attemptsUsed()) }
        assertEquals(ReservationLedger.Reservation.Reserved(5), ReservationLedger(file).reserve("m3a-manual-5", 5))
        assertEquals(5, file.readLines().size)
    }

    @Test
    fun `a torn first record counts as one attempt`() {
        file.parentFile!!.mkdirs()
        file.writeText("{\"att")

        assertEquals(1, ReservationLedger(file).attemptsUsed())
        assertEquals(ReservationLedger.Reservation.Reserved(2), ReservationLedger(file).reserve("b", 2))
        assertEquals(2, ReservationLedger(file).attemptsUsed())
    }

    @Test
    fun `a tail of zero bytes left by a crash counts as one attempt`() {
        ReservationLedger(file).reserve("a", 1)
        // A file system may extend the file before the data lands, leaving zeros.
        file.appendBytes(ByteArray(16))

        assertEquals(2, ReservationLedger(file).attemptsUsed())
        assertEquals(ReservationLedger.Reservation.Reserved(3), ReservationLedger(file).reserve("c", 3))
        assertEquals(3, ReservationLedger(file).attemptsUsed())
    }

    @Test
    fun `a torn record is never refunded, even at the end of the allowance`() {
        val ledger = ReservationLedger(file)
        repeat(ExperimentAllowance.MAX_ATTEMPTS - 1) { n -> ledger.reserve("s$n", n.toLong()) }
        file.appendText("{\"attempt\":8,")

        assertEquals(ExperimentAllowance.MAX_ATTEMPTS, ReservationLedger(file).attemptsUsed())
        assertEquals(ReservationLedger.Reservation.AllowanceExhausted, ReservationLedger(file).reserve("ninth", 9))
    }

    @Test
    fun `concurrent reservations never pass the allowance`() {
        val ledger = ReservationLedger(file)
        val threads = 32
        val pool = Executors.newFixedThreadPool(threads)
        val go = CountDownLatch(1)
        try {
            val results =
                (1..threads)
                    .map { n ->
                        pool.submit<ReservationLedger.Reservation> {
                            go.await()
                            ledger.reserve("s$n", n.toLong())
                        }
                    }.also { go.countDown() }
                    .map { it.get(10, TimeUnit.SECONDS) }

            assertEquals(8, results.count { it is ReservationLedger.Reservation.Reserved })
            assertEquals((1..8).toSet(), results.filterIsInstance<ReservationLedger.Reservation.Reserved>().map { it.attempt }.toSet())
            assertEquals(8, ReservationLedger(file).attemptsUsed())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `a ledger that cannot be written throws rather than reporting a charge`() {
        file.mkdirs()

        assertFailsWith<IOException> { ReservationLedger(file).reserve("a", 1) }
    }
}
