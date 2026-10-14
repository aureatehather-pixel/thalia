package com.thalia.app.voicecheckins.call

import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.OccurrenceReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Where the call reports what happened to the occurrence it rang for.
 *
 * The coordinator calls these at the moments that decide an outcome. It does not know what is done
 * with them, and it never waits: recording a result must not be able to slow or break a call.
 * A call with no stored occurrence, such as one started by a button, reports as normal and the
 * report simply finds nothing to update.
 */
interface CallOutcomeRecorder {
    /** The user answered. */
    fun answered(occurrenceId: String)

    /** The user declined. */
    fun declined(occurrenceId: String)

    /** Ringing ended without an answer, for [reason]. */
    fun missed(
        occurrenceId: String,
        reason: OccurrenceReason,
    )

    /** An answered call ended as [status], either completed or failed. A failure carries its [reason]. */
    fun finished(
        occurrenceId: String,
        status: CheckInStatus,
        reason: OccurrenceReason?,
    )

    companion object {
        /** Records nothing. For a coordinator with no ledger behind it. */
        val None: CallOutcomeRecorder =
            object : CallOutcomeRecorder {
                override fun answered(occurrenceId: String) = Unit

                override fun declined(occurrenceId: String) = Unit

                override fun missed(
                    occurrenceId: String,
                    reason: OccurrenceReason,
                ) = Unit

                override fun finished(
                    occurrenceId: String,
                    status: CheckInStatus,
                    reason: OccurrenceReason?,
                ) = Unit
            }
    }
}

/**
 * Writes the call's outcomes to the [OccurrenceStore], one at a time, in the order they happened.
 *
 * Order matters and a bare launch per call would lose it: an unpaced call is answered and finished
 * in the same instant, and if the finish were written first it would find the occurrence still
 * ringing and be refused. Every report is queued on one channel and applied by one worker, so the
 * store sees them exactly as the call made them. The moment of each is read when it is reported,
 * not when it is written.
 *
 * A failed write is passed to [onError] and does not stop the ones behind it.
 */
class LedgerCallOutcomeRecorder(
    scope: CoroutineScope,
    private val store: OccurrenceStore,
    private val clock: () -> Long,
    private val onError: (Throwable) -> Unit = {},
) : CallOutcomeRecorder {
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    init {
        scope.launch {
            for (write in queue) {
                try {
                    write()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    onError(e)
                }
            }
        }
    }

    override fun answered(occurrenceId: String) = move(occurrenceId, CheckInStatus.RINGING, CheckInStatus.ANSWERED, null)

    override fun declined(occurrenceId: String) = move(occurrenceId, CheckInStatus.RINGING, CheckInStatus.DECLINED, null)

    override fun missed(
        occurrenceId: String,
        reason: OccurrenceReason,
    ) = move(occurrenceId, CheckInStatus.RINGING, CheckInStatus.MISSED, reason)

    override fun finished(
        occurrenceId: String,
        status: CheckInStatus,
        reason: OccurrenceReason?,
    ) = move(occurrenceId, CheckInStatus.ANSWERED, status, reason)

    private fun move(
        occurrenceId: String,
        from: CheckInStatus,
        to: CheckInStatus,
        reason: OccurrenceReason?,
    ) {
        val at = Instant.ofEpochMilli(clock())
        queue.trySend { store.transition(occurrenceId, from, to, at, reason) }
    }
}
