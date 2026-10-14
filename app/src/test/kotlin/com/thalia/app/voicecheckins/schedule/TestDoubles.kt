package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** The zone every test occurrence is in, so a wall-clock time is the same wherever the test runs. */
internal val KUALA_LUMPUR: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

/** 10:00 in Kuala Lumpur on 20 September 2026, the moment most tests treat as an occurrence's due time. */
internal val DUE: Instant = Instant.parse("2026-09-20T02:00:00Z")

internal fun occurrence(
    id: String = "2026-09-20-morning",
    type: CheckInType = CheckInType.OPENING,
    at: Instant = DUE,
) = ScheduledOccurrence(id = id, type = type, scheduledAt = at, zone = KUALA_LUMPUR)

/** A clock a test moves by hand. */
internal class TestClock(
    var millis: Long = DUE.toEpochMilli() - 60_000,
) : () -> Long {
    override fun invoke(): Long = millis

    fun set(instant: Instant) {
        millis = instant.toEpochMilli()
    }

    fun advanceMillis(by: Long) {
        millis += by
    }
}

/**
 * An [OccurrenceStore] in memory that behaves exactly like the real one.
 *
 * The same contract tests run against this and against Room, so a test that passes on this double
 * is a statement about the real store. Every change happens under one lock, which is what the
 * conditional `UPDATE` statements give the real store.
 */
internal class InMemoryOccurrenceStore(
    plan: CheckInPlan = CheckInPlan.default(KUALA_LUMPUR),
) : OccurrenceStore {
    private val lock = Mutex()
    private val rows = LinkedHashMap<String, ScheduledOccurrence>()
    private val snapshot = MutableStateFlow<List<ScheduledOccurrence>>(emptyList())
    private var storedPlan: CheckInPlan? = plan
    private var storedQuietPeriod: QuietPeriod = QuietPeriod.DEFAULT

    override suspend fun insert(occurrence: ScheduledOccurrence): Boolean =
        lock.withLock {
            if (occurrence.id in rows) {
                false
            } else {
                rows[occurrence.id] = occurrence
                publish()
                true
            }
        }

    override suspend fun get(id: String): ScheduledOccurrence? = lock.withLock { rows[id] }

    override suspend fun open(): List<ScheduledOccurrence> =
        lock.withLock {
            rows.values.filterNot { it.status.isTerminal }.sortedWith(compareBy({ it.scheduledAt }, { it.id }))
        }

    override fun observeRecent(limit: Int): Flow<List<ScheduledOccurrence>> =
        snapshot.map { all ->
            all.sortedWith(compareByDescending<ScheduledOccurrence> { it.scheduledAt }.thenByDescending { it.id }).take(limit)
        }

    override suspend fun claimForRinging(
        id: String,
        at: Instant,
    ): ClaimResult =
        lock.withLock {
            val current = rows[id]
            when {
                current == null || current.status != CheckInStatus.SCHEDULED -> ClaimResult.NOT_CLAIMED
                rows.values.any { it.id != id && it.status in ACTIVE } -> {
                    rows[id] = current.transitionTo(CheckInStatus.SUPPRESSED, at, OccurrenceReason.CALL_IN_PROGRESS)
                    publish()
                    ClaimResult.SUPPRESSED
                }
                else -> {
                    rows[id] = current.transitionTo(CheckInStatus.RINGING, at)
                    publish()
                    ClaimResult.CLAIMED
                }
            }
        }

    override suspend fun transition(
        id: String,
        from: CheckInStatus,
        to: CheckInStatus,
        at: Instant,
        reason: OccurrenceReason?,
    ): Boolean {
        requireLegalMove(from, to, reason)
        return lock.withLock {
            val current = rows[id]
            if (current == null || current.status != from) {
                false
            } else {
                rows[id] = current.transitionTo(to, at, reason)
                publish()
                true
            }
        }
    }

    override suspend fun slot(slot: String): List<ScheduledOccurrence> =
        lock.withLock { rows.values.filter { it.slot == slot }.sortedBy { it.revision } }

    override suspend fun supersede(
        oldId: String,
        replacement: ScheduledOccurrence,
        at: Instant,
    ): Boolean {
        require(replacement.status == CheckInStatus.SCHEDULED) { "a replacement must be scheduled, got ${replacement.status}" }
        return lock.withLock {
            val old = rows[oldId] ?: return@withLock false
            require(old.slot == replacement.slot && replacement.revision == old.revision + 1) {
                "${replacement.id} is not the next revision of $oldId"
            }
            if (old.status != CheckInStatus.SCHEDULED || replacement.id in rows) {
                false
            } else {
                rows[oldId] = old.transitionTo(CheckInStatus.CANCELLED, at, OccurrenceReason.SUPERSEDED)
                rows[replacement.id] = replacement
                publish()
                true
            }
        }
    }

    override suspend fun plan(): CheckInPlan = lock.withLock { storedPlan ?: CheckInPlan.default(KUALA_LUMPUR).also { storedPlan = it } }

    override suspend fun savePlan(plan: CheckInPlan) = lock.withLock { storedPlan = plan }

    override suspend fun quietPeriod(): QuietPeriod = lock.withLock { storedQuietPeriod }

    override suspend fun saveQuietPeriod(quietPeriod: QuietPeriod) = lock.withLock { storedQuietPeriod = quietPeriod }

    private fun publish() {
        snapshot.value = rows.values.toList()
    }

    private companion object {
        val ACTIVE = setOf(CheckInStatus.RINGING, CheckInStatus.ANSWERED)
    }
}

/**
 * An [AlarmScheduler] that keeps its alarms in a map, and can lose them the way a restart or a
 * force-stop does.
 *
 * It follows the real scheduler's rules: no alarm without the permission, none for a moment that
 * is not in the future, and arming an occurrence again replaces its alarm rather than adding one.
 */
internal class FakeAlarmScheduler(
    private val clock: () -> Long,
    var permitted: Boolean = true,
) : AlarmScheduler {
    /** Occurrence id to the moment its alarm will fire. */
    val armed = mutableMapOf<String, Long>()

    /** Every occurrence id an alarm was armed for, in order, including repeats. */
    val armCalls = mutableListOf<String>()

    /** Runs just before an alarm is armed. A test hooks in here to look at the world at that instant. */
    var beforeArm: (String) -> Unit = {}

    /** Turns the permission off just after it has been asked about once, as a settings change can. */
    var revokeAfterNextCheck = false

    override fun canScheduleExactAlarms(): Boolean {
        val answer = permitted
        if (revokeAfterNextCheck) {
            revokeAfterNextCheck = false
            permitted = false
        }
        return answer
    }

    override fun schedule(occurrence: ScheduledOccurrence): ScheduleResult {
        if (!permitted) return ScheduleResult.NotPermitted
        val at = occurrence.scheduledAt.toEpochMilli()
        if (at <= clock()) return ScheduleResult.NotInTheFuture
        beforeArm(occurrence.id)
        armCalls += occurrence.id
        armed[occurrence.id] = at
        return ScheduleResult.Scheduled(at)
    }

    override fun cancel(occurrenceId: String) {
        armed.remove(occurrenceId)
    }

    override fun isArmed(occurrenceId: String): Boolean = occurrenceId in armed

    /** What a restart of the phone, or a force-stop of the app, does to every alarm at once. */
    fun loseEverything() = armed.clear()
}
