package com.thalia.app.m3a

import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * The M3A experiment allowance: USD 2.00 in total, USD 0.25 charged for each connection attempt.
 *
 * ADR 0008 decision 7. Amounts are integer micro-dollars. Nothing in the app raises, resets or
 * bypasses the allowance.
 */
object ExperimentAllowance {
    const val ALLOWANCE_MICROS: Long = 2_000_000
    const val ATTEMPT_MICROS: Long = 250_000
    const val MAX_ATTEMPTS: Int = (ALLOWANCE_MICROS / ATTEMPT_MICROS).toInt()

    fun remainingMicros(attemptsUsed: Int): Long = (ALLOWANCE_MICROS - attemptsUsed * ATTEMPT_MICROS).coerceAtLeast(0)

    /** Exact, without floating point: 1_750_000 is "USD 1.75". */
    fun formatUsd(micros: Long): String {
        val cents = micros / 10_000
        return "USD ${cents / 100}.${(cents % 100).toString().padStart(2, '0')}"
    }
}

/**
 * One JSON line per connection attempt, appended to an app-private file and forced to the disk
 * before the attempt may open its socket: [reserve] returns only after `FileDescriptor.sync()` on
 * the appended bytes has returned and, when the append created the file, after the directories
 * holding it have been synced too, so a new file cannot vanish with a crash. Only then does the
 * caller begin collecting the session, which is what opens the socket. If any of that throws, no
 * session is started, and whatever reached the disk still counts.
 *
 * There is no refund: nothing here removes or rewrites a line. Every non-blank line counts, readable
 * or not, so a record a crash left half-written still counts as one attempt. A half-written record
 * has no line break after it, so the next record starts with one, keeping the damaged record on a
 * line of its own: it is counted once however often the file is read again, and every earlier
 * record is left byte for byte as it was. Reservations are serialised in the process,
 * and every reservation re-reads the file, so concurrent or repeated starts can never pass
 * [ExperimentAllowance.MAX_ATTEMPTS]. Clearing the app's data resets the file, which ADR 0008
 * accepts.
 */
class ReservationLedger(
    private val file: File,
) {
    private val lock = Any()

    sealed interface Reservation {
        data class Reserved(
            val attempt: Int,
        ) : Reservation

        data object AllowanceExhausted : Reservation
    }

    /** How many attempts have been charged. Reads only. */
    fun attemptsUsed(): Int = synchronized(lock) { count() }

    /** Charge one attempt, durably, unless the allowance is spent. */
    fun reserve(
        sessionId: String,
        nowMillis: Long,
    ): Reservation =
        synchronized(lock) {
            val used = count()
            if (used >= ExperimentAllowance.MAX_ATTEMPTS) return Reservation.AllowanceExhausted
            val attempt = used + 1
            val line =
                "{\"attempt\":$attempt,\"chargedMicros\":${ExperimentAllowance.ATTEMPT_MICROS}," +
                    "\"reservedAtMillis\":$nowMillis,\"sessionId\":\"${sessionId.filter { it.isLetterOrDigit() || it == '-' }}\"}\n"
            val directory = file.parentFile
            directory?.mkdirs()
            val creating = !file.exists()
            // A line torn by a crash keeps its own line, and so its own charge.
            val separator = if (endsTorn()) "\n" else ""
            FileOutputStream(file, true).use { out ->
                out.write((separator + line).toByteArray())
                // Durable from here. A flush alone would only reach the kernel.
                out.fd.sync()
            }
            if (creating && directory != null) {
                syncDirectory(directory)
                directory.parentFile?.let(::syncDirectory)
            }
            Reservation.Reserved(attempt)
        }

    private fun count(): Int = if (file.isFile) file.readLines().count { it.isNotBlank() } else 0

    private fun endsTorn(): Boolean {
        if (!file.isFile || file.length() == 0L) return false
        return RandomAccessFile(file, "r").use { raf ->
            raf.seek(raf.length() - 1)
            raf.read() != '\n'.code
        }
    }

    private companion object {
        /**
         * Force a directory's entries to the disk, so a file just created in it survives a crash.
         * Android is Linux, where this works and a failure is an error. A Windows host, where the
         * unit tests run, cannot open a directory at all, so there it is skipped.
         */
        fun syncDirectory(directory: File) {
            if (System.getProperty("os.name").orEmpty().startsWith("Windows")) return
            FileChannel.open(directory.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }
}
