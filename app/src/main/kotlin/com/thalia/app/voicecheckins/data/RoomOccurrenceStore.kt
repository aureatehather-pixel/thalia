package com.thalia.app.voicecheckins.data

import com.thalia.app.voicecheckins.schedule.ClaimResult
import com.thalia.app.voicecheckins.schedule.OccurrenceStore
import com.thalia.app.voicecheckins.schedule.requireLegalMove
import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInSchedule
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.ScheduledOccurrence
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/**
 * The [OccurrenceStore] on Room.
 *
 * All the atomicity comes from SQL: every move is a single conditional `UPDATE` statement, and the
 * claim and the supersede are each one transaction, so this class holds no lock of its own and needs none.
 *
 * @param defaultZone the zone the first plan is created in, read once, when there is no plan yet.
 */
internal class RoomOccurrenceStore(
    private val database: CheckInDatabase,
    private val defaultZone: () -> ZoneId = ZoneId::systemDefault,
) : OccurrenceStore {
    private val occurrences get() = database.occurrences()
    private val schedules get() = database.schedules()
    private val quiet get() = database.quietPeriod()

    override suspend fun insert(occurrence: ScheduledOccurrence): Boolean = occurrences.insert(occurrence.toEntity()) != NOT_INSERTED

    override suspend fun get(id: String): ScheduledOccurrence? = occurrences.get(id)?.toDomain()

    override suspend fun open(): List<ScheduledOccurrence> = occurrences.open().map { it.toDomain() }

    override fun observeRecent(limit: Int): Flow<List<ScheduledOccurrence>> =
        occurrences.observeRecent(limit).map { rows ->
            rows.map {
                it.toDomain()
            }
        }

    override suspend fun claimForRinging(
        id: String,
        at: Instant,
    ): ClaimResult =
        when (occurrences.claimOrSuppress(id, at.toEpochMilli())) {
            OccurrenceDao.CLAIMED -> ClaimResult.CLAIMED
            OccurrenceDao.SUPPRESSED -> ClaimResult.SUPPRESSED
            else -> ClaimResult.NOT_CLAIMED
        }

    override suspend fun transition(
        id: String,
        from: CheckInStatus,
        to: CheckInStatus,
        at: Instant,
        reason: OccurrenceReason?,
    ): Boolean {
        requireLegalMove(from, to, reason)
        val resolvedAt = if (to.isTerminal) at.toEpochMilli() else null
        return occurrences.transition(id, from.name, to.name, reason?.name, resolvedAt) == 1
    }

    override suspend fun slot(slot: String): List<ScheduledOccurrence> = occurrences.slot(slot).map { it.toDomain() }

    override suspend fun supersede(
        oldId: String,
        replacement: ScheduledOccurrence,
        at: Instant,
    ): Boolean {
        require(replacement.status == CheckInStatus.SCHEDULED) { "a replacement must be scheduled, got ${replacement.status}" }
        return try {
            occurrences.supersede(oldId, replacement.toEntity(), at.toEpochMilli())
        } catch (_: SupersedeAbort) {
            false
        }
    }

    override suspend fun plan(): CheckInPlan {
        var rows = schedules.all()
        if (CheckInType.entries.any { type -> rows.none { it.type == type.name } }) {
            // First use, or a row went missing. Add only what is absent, so a slot the user has
            // already changed is never overwritten by the default.
            schedules.insertMissing(CheckInPlan.default(defaultZone()).toEntities())
            rows = schedules.all()
        }
        return rows.toPlan()
    }

    override suspend fun savePlan(plan: CheckInPlan) = schedules.replace(plan.toEntities())

    override suspend fun quietPeriod(): QuietPeriod =
        quiet.get()?.let { QuietPeriod(LocalTime.parse(it.startTime), LocalTime.parse(it.endTime), it.enabled) }
            ?: QuietPeriod.DEFAULT

    override suspend fun saveQuietPeriod(quietPeriod: QuietPeriod) =
        quiet.save(
            QuietPeriodEntity(
                startTime = quietPeriod.start.toString(),
                endTime = quietPeriod.end.toString(),
                enabled = quietPeriod.enabled,
            ),
        )

    private companion object {
        const val NOT_INSERTED = -1L
    }
}

internal fun ScheduledOccurrence.toEntity() =
    OccurrenceEntity(
        id = id,
        type = type.name,
        scheduledAtMillis = scheduledAt.toEpochMilli(),
        zoneId = zone.id,
        status = status.name,
        reason = reason?.name,
        firedAtMillis = firedAt?.toEpochMilli(),
        resolvedAtMillis = resolvedAt?.toEpochMilli(),
        slot = slot,
        revision = revision,
    )

internal fun OccurrenceEntity.toDomain() =
    ScheduledOccurrence(
        id = id,
        type = CheckInType.valueOf(type),
        scheduledAt = Instant.ofEpochMilli(scheduledAtMillis),
        zone = ZoneId.of(zoneId),
        status = CheckInStatus.valueOf(status),
        reason = reason?.let { OccurrenceReason.valueOf(it) },
        firedAt = firedAtMillis?.let { Instant.ofEpochMilli(it) },
        resolvedAt = resolvedAtMillis?.let { Instant.ofEpochMilli(it) },
        slot = slot,
        revision = revision,
    )

private fun CheckInPlan.toEntities(): List<ScheduleEntity> =
    listOf(opening, closing).map {
        ScheduleEntity(type = it.type.name, localTime = it.localTime.toString(), zoneId = it.zone.id, enabled = it.enabled)
    }

private fun List<ScheduleEntity>.toPlan(): CheckInPlan {
    fun slot(type: CheckInType): CheckInSchedule {
        val row = first { it.type == type.name }
        return CheckInSchedule(type, LocalTime.parse(row.localTime), ZoneId.of(row.zoneId), row.enabled)
    }
    return CheckInPlan(opening = slot(CheckInType.OPENING), closing = slot(CheckInType.CLOSING))
}
