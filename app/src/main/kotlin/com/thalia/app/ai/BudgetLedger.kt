package com.thalia.app.ai

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** USD amounts in micro-dollars. Exact, no floating point. */
internal object Usd {
    /**
     * The initial validation cap, and also the most this app will accept. The product's whole recurring technology cost,
     * AI and hosting together, is bounded at RM 40 to 50 a month (owner, 2026-10-08), which supersedes the earlier V2 and V3
     * allowances. How that is shared between AI and hosting is undecided, so nothing above the validation cap is allowed
     * here: a higher figure needs that decision recorded and this constant changed in code. A confirmation on the settings
     * screen is a person confirming a figure within the ceiling, never authority to pass it.
     */
    const val AI_BUDGET_CEILING_MICROS: Long = 5_000_000
    const val DEFAULT_MONTHLY_BUDGET_MICROS: Long = AI_BUDGET_CEILING_MICROS

    fun format(micros: Long): String {
        val cents = (micros + 5_000) / 10_000
        return "USD ${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }

    /** "5", "5.5" or "12.34" as micro-dollars; null for anything else, including negatives, zero and more than 2 decimals. */
    fun parse(text: String): Long? {
        val t =
            text
                .trim()
                .removePrefix("USD")
                .trim()
                .removePrefix("$")
                .trim()
        if (!Regex("""\d{1,6}(\.\d{1,2})?""").matches(t)) return null
        val (whole, fraction) = t.split('.').let { it[0] to (it.getOrNull(1) ?: "") }
        val micros = whole.toLong() * 1_000_000 + fraction.padEnd(2, '0').toLong() * 10_000
        return micros.takeIf { it > 0 }
    }
}

/** The month a charge belongs to: the calendar month in UTC, written 2026-10. It is Thalia's own accounting month, not Google's billing cycle. */
internal fun budgetMonth(at: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM").format(at.atZone(ZoneOffset.UTC))

/**
 * What the ledger knows for one month. [spentMicros] is settled cost plus every reservation not yet settled or released,
 * plus a flat charge for each line that could not be read. [heldMicros] is the part of it that is reserved and unconfirmed.
 */
internal data class BudgetSummary(
    val month: String,
    val budgetMicros: Long,
    val settledMicros: Long,
    val heldMicros: Long,
    val heldCount: Int,
    val unreadableLines: Int,
    val unreadableMicros: Long,
) {
    val spentMicros: Long get() = settledMicros + heldMicros + unreadableMicros
    val remainingMicros: Long get() = (budgetMicros - spentMicros).coerceAtLeast(0)
    val fraction: Double get() = spentMicros.toDouble() / budgetMicros
}

internal sealed interface Reservation {
    data class Reserved(
        val id: String,
        /** The month the allowance was held in; the settlement or release goes to the same month, whenever it happens. */
        val month: String,
    ) : Reservation

    /** [worstCaseMicros] would not fit in what is left of the month's budget. Nothing was written. */
    data class BudgetExhausted(
        val summary: BudgetSummary,
        val worstCaseMicros: Long,
    ) : Reservation

    /** The reservation could not be made durable, so nothing may be sent. */
    data object Unwritable : Reservation
}

/**
 * The persistent monthly request budget, ADR 0013. It is an append-only file of tab-separated lines, forced to the disk before
 * [reserve] returns, so a request can only be sent after its allowance is on the disk:
 *
 * - `R id month micros at`: an allowance held before a request is sent (the worst case that request can cost);
 * - `S id month micros at`: the request's cost once the service reported its usage;
 * - `X id month at`: the request is known not to have been billed, so its allowance is returned;
 * - `B micros at`: the monthly budget the person set.
 *
 * Nothing rewrites a line. A request with an `R` and neither an `S` nor an `X` stays counted at its full allowance: it
 * is uncertain, whether because the connection timed out, the service gave no usage, the screen was left mid-request or
 * the process died. Restarting changes nothing, and nothing in the app clears one before its month ends. A line that cannot
 * be read is charged [UNREADABLE_LINE_MICROS] in every month, since its month cannot be known, rather than ignored. The budget starts at
 * [Usd.DEFAULT_MONTHLY_BUDGET_MICROS], which is also its ceiling; only [setBudget], called from an explicit confirmation, changes it, and never above that.
 *
 * This is the app's own estimate. It is not Google's invoice or a hard cap: clearing the app's data resets it, a price the
 * table does not know is not in it, and billing may lag. Google's own spend controls are the backstop.
 */
internal class BudgetLedger(
    private val file: File,
    /** What the budget is until the person sets one. Never above the ceiling. The everyday route starts lower than the default. */
    initialBudgetMicros: Long = Usd.DEFAULT_MONTHLY_BUDGET_MICROS,
) {
    private val initialBudget = initialBudgetMicros.coerceIn(1, Usd.AI_BUDGET_CEILING_MICROS)

    private val lock = Any()

    fun summary(at: Instant): BudgetSummary = synchronized(lock) { replay(budgetMonth(at)) }

    fun reserve(
        id: String,
        worstCaseMicros: Long,
        at: Instant,
    ): Reservation =
        synchronized(lock) {
            require(id.none { it.isWhitespace() } && id.isNotEmpty()) { "an id must be one token" }
            val month = budgetMonth(at)
            val now = replay(month)
            if (now.spentMicros + worstCaseMicros > now.budgetMicros) return Reservation.BudgetExhausted(now, worstCaseMicros)
            try {
                append("R\t$id\t$month\t$worstCaseMicros\t${at.toEpochMilli()}")
                Reservation.Reserved(id, month)
            } catch (e: IOException) {
                Reservation.Unwritable
            }
        }

    /** The request's cost is now known. Never lower than zero; a failure to write leaves the full allowance counted. */
    fun settle(
        reservation: Reservation.Reserved,
        actualMicros: Long,
        at: Instant,
    ): Boolean = write("S\t${reservation.id}\t${reservation.month}\t${actualMicros.coerceAtLeast(0)}\t${at.toEpochMilli()}")

    /** Only for a request the service said it did not process. A failure to write leaves the allowance counted. */
    fun release(
        reservation: Reservation.Reserved,
        at: Instant,
    ): Boolean = write("X\t${reservation.id}\t${reservation.month}\t${at.toEpochMilli()}")

    enum class SetBudget { SET, ABOVE_CEILING, UNWRITABLE }

    /** Sets the monthly budget, up to [Usd.AI_BUDGET_CEILING_MICROS]. The caller is an explicit user action; nothing else may raise it. */
    fun setBudget(
        micros: Long,
        at: Instant,
    ): SetBudget {
        require(micros > 0) { "the budget must be positive" }
        if (micros > Usd.AI_BUDGET_CEILING_MICROS) return SetBudget.ABOVE_CEILING
        return if (write("B\t$micros\t${at.toEpochMilli()}")) SetBudget.SET else SetBudget.UNWRITABLE
    }

    private fun write(line: String): Boolean =
        synchronized(lock) {
            try {
                append(line)
                true
            } catch (e: IOException) {
                false
            }
        }

    private fun replay(month: String): BudgetSummary {
        var budget = initialBudget
        val reserved = linkedMapOf<String, Long>()
        val settled = mutableMapOf<String, Long>()
        val released = mutableSetOf<String>()
        var unreadable = 0
        if (file.isFile) {
            for (line in file.readLines().filter { it.isNotBlank() }) {
                val f = line.split('\t')
                when {
                    f[0] == "R" && f.size == 5 && f[3].toLongOrNull() != null && f[4].toLongOrNull() != null ->
                        if (f[2] == month) reserved[f[1]] = f[3].toLong()
                    f[0] == "S" && f.size == 5 && f[3].toLongOrNull() != null && f[4].toLongOrNull() != null ->
                        if (f[2] == month) settled[f[1]] = f[3].toLong()
                    f[0] == "X" && f.size == 4 && f[3].toLongOrNull() != null -> if (f[2] == month) released += f[1]
                    f[0] == "B" && f.size == 3 && f[1].toLongOrNull()?.let { it > 0 } == true && f[2].toLongOrNull() != null ->
                        // A figure above the ceiling (a hand-edited file) is read as the ceiling.
                        budget = minOf(f[1].toLong(), Usd.AI_BUDGET_CEILING_MICROS)
                    else -> unreadable++
                }
            }
        }
        val settledTotal = settled.values.sum()
        val held = reserved.filterKeys { it !in settled && it !in released }
        return BudgetSummary(month, budget, settledTotal, held.values.sum(), held.size, unreadable, unreadable * UNREADABLE_LINE_MICROS)
    }

    private fun append(line: String) {
        val directory = file.parentFile
        directory?.mkdirs()
        val creating = !file.exists()
        // A line torn by a crash keeps its own line, and so its own charge.
        val separator = if (endsTorn()) "\n" else ""
        FileOutputStream(file, true).use { out ->
            out.write((separator + line + "\n").toByteArray())
            // Durable from here. A flush alone would only reach the kernel.
            out.fd.sync()
        }
        if (creating && directory != null) {
            syncDirectory(directory)
            directory.parentFile?.let(::syncDirectory)
        }
    }

    private fun endsTorn(): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() != '\n'.code
        }
    }

    internal companion object {
        /** More than the most one request can be reserved for (about USD 0.11 at the 2027 price and the request limit). */
        const val UNREADABLE_LINE_MICROS: Long = 150_000

        private fun syncDirectory(directory: File) {
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
