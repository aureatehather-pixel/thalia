package com.thalia.app.ai

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** What the monthly voice meter knows for one calendar month. [cap] is null until the person sets one. */
internal data class SpeechUsageSummary(
    val month: String,
    val cap: Long?,
    val used: Long,
    val unreadableLines: Int,
) {
    val remaining: Long? get() = cap?.let { (it - used).coerceAtLeast(0) }
}

internal sealed interface SpeechCount {
    /** The characters are now on the disk as submitted; the request may be made. */
    data object Counted : SpeechCount

    /** No cap is set, so voice output is off. Nothing was written. */
    data object NoCap : SpeechCount

    /** The characters would not fit in what is left of the month's cap. Nothing was written. */
    data class CapReached(
        val summary: SpeechUsageSummary,
    ) : SpeechCount

    /** The count could not be made durable, so nothing may be sent. */
    data object Unwritable : SpeechCount
}

/**
 * The app's own monthly count of characters submitted to Cartesia, with the cap the person sets. It is an append-only file of
 * tab-separated lines, forced to the disk before [count] returns, so a request is only made after its characters are counted:
 *
 * - `C chars at`: the monthly cap the person set (the last cap line wins);
 * - `N at`: the person cleared the cap, so it is unset again (counts are kept);
 * - `U month chars at`: characters submitted in a calendar month.
 *
 * The month is the calendar month in the device time zone at the moment of the submission ([zone]); a later change of zone
 * does not move earlier lines. Every submission is counted in full, repeats included, before the request is made, and is never
 * returned, even if the request then fails: the service may have processed it. Unset cap means voice is off. A line that
 * cannot be read is charged [UNREADABLE_LINE_CHARS] in every month, since its month cannot be known.
 *
 * This is the app's own estimate of submitted characters, not Cartesia's billing: clearing the app's data resets it.
 * It is separate from the older `speech-budget.log` (the 2,000-character livecheck test ledger), which this class never reads
 * or writes.
 */
internal class SpeechUsage(
    private val file: File,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {
    private val lock = Any()

    fun summary(at: Instant): SpeechUsageSummary = synchronized(lock) { replay(monthOf(at)) }

    /** Sets the cap in characters. The caller is an explicit user action. Returns false if it could not be saved. */
    fun setCap(
        chars: Long,
        at: Instant,
    ): Boolean {
        require(chars in 1..MAX_CAP) { "the cap must be between 1 and $MAX_CAP" }
        return synchronized(lock) { append("C\t$chars\t${at.toEpochMilli()}") }
    }

    /** Returns the cap to unset, which turns voice off. The month's count and every earlier line are kept. False if not saved. */
    fun clearCap(at: Instant): Boolean = synchronized(lock) { append("N\t${at.toEpochMilli()}") }

    /** Checks the cap and writes the characters in one step under the lock, so concurrent submissions cannot pass it together. */
    fun count(
        chars: Int,
        at: Instant,
    ): SpeechCount =
        synchronized(lock) {
            require(chars > 0) { "nothing to count" }
            val month = monthOf(at)
            val now = replay(month)
            val cap = now.cap ?: return SpeechCount.NoCap
            if (now.used + chars > cap) return SpeechCount.CapReached(now)
            if (append("U\t$month\t$chars\t${at.toEpochMilli()}")) SpeechCount.Counted else SpeechCount.Unwritable
        }

    private fun monthOf(at: Instant): String = MONTH.format(at.atZone(zone()))

    private fun replay(month: String): SpeechUsageSummary {
        var cap: Long? = null
        var used = 0L
        var unreadable = 0
        if (file.isFile) {
            for (line in file.readLines().filter { it.isNotBlank() }) {
                val f = line.split('\t')
                when {
                    f[0] == "C" && f.size == 3 && f[1].toLongOrNull()?.let { it in 1..MAX_CAP } == true && f[2].toLongOrNull() != null ->
                        cap = f[1].toLong()
                    f[0] == "N" && f.size == 2 && f[1].toLongOrNull() != null -> cap = null
                    f[0] == "U" && f.size == 4 && f[2].toLongOrNull()?.let { it > 0 } == true && f[3].toLongOrNull() != null ->
                        if (f[1] == month) used += f[2].toLong()
                    else -> unreadable++
                }
            }
        }
        return SpeechUsageSummary(month, cap, used + unreadable * UNREADABLE_LINE_CHARS, unreadable)
    }

    private fun append(line: String): Boolean =
        try {
            file.parentFile?.mkdirs()
            // A line torn by a crash keeps its own line, and so its own charge.
            val separator = if (file.isFile && file.length() > 0 && !file.readText().endsWith("\n")) "\n" else ""
            FileOutputStream(file, true).use { out ->
                out.write((separator + line + "\n").toByteArray())
                out.fd.sync()
            }
            true
        } catch (e: IOException) {
            false
        }

    internal companion object {
        /** The most one submission can be, so the most a torn or unreadable line can have been. */
        const val UNREADABLE_LINE_CHARS: Long = SPEECH_MAX_CHARS.toLong()

        /** A typing guard, not a product limit. */
        const val MAX_CAP: Long = 100_000_000

        private val MONTH = DateTimeFormatter.ofPattern("yyyy-MM")
    }
}

/** Digits only, 1 to 100,000,000; null for anything else, including zero, negatives, decimals and separators. */
internal fun parseSpeechCap(text: String): Long? =
    text
        .trim()
        .takeIf { Regex("""\d{1,9}""").matches(it) }
        ?.toLong()
        ?.takeIf { it in 1..SpeechUsage.MAX_CAP }

/** The meter as shown in AI settings. It says app-tracked submitted characters, never billing. */
internal fun speechMeterText(s: SpeechUsageSummary): String =
    if (s.cap == null) {
        "Monthly voice limit: not set. Voice output is off until you set one; text replies still work. " +
            "This app has counted ${s.used} characters submitted to Cartesia in ${s.month} (device time zone)."
    } else {
        "Voice this month (${s.month}, device time zone): ${s.used} of ${s.cap} characters submitted to Cartesia, " +
            "counted by this app. This is app-tracked usage, not Cartesia's billing. Repeats count; a new month starts at local midnight."
    }
