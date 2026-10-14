package com.thalia.app.planning

import com.thalia.app.voicecheckins.data.CheckInDatabase
import com.thalia.voicecheckins.domain.CheckInType
import java.time.DateTimeException
import java.time.LocalTime
import java.time.ZoneId

/** Why M2's stored zone cannot be used for planning. Planning never guesses one. ADR 0009 decision 3. */
enum class CheckInZoneProblem {
    /** M2 has no stored check-in plan yet, so there is no zone to read. */
    NO_CHECK_IN_PLAN,

    /** M2's stored slots are incomplete or name different zones. */
    INCONSISTENT,

    /** M2 stored a zone this phone does not know. */
    UNKNOWN_ZONE,
}

sealed interface CheckInZone {
    data class Known(
        val zone: ZoneId,
        /** Whether M2's stored opening slot is switched on. Read, never changed, by the call link. */
        val openingEnabled: Boolean = true,
        /** The stored opening time: only a suggestion for a new plan's opening call time. It arms and schedules nothing. */
        val defaultOpening: LocalTime? = null,
    ) : CheckInZone

    data class Unusable(
        val problem: CheckInZoneProblem,
    ) : CheckInZone
}

/** The zone of M2's stored check-in plan, which plans and their calls must agree on. */
internal fun interface CheckInZoneSource {
    suspend fun read(): CheckInZone
}

/**
 * Reads the stored schedule rows with M2's existing query and **never** [com.thalia.app.voicecheckins.schedule.OccurrenceStore.plan],
 * which creates the default plan when none exists. Reading must not create or change anything in M2, and
 * a missing or contradictory plan is reported, not replaced by the phone's zone.
 */
internal class RoomCheckInZoneSource(
    private val database: CheckInDatabase,
) : CheckInZoneSource {
    override suspend fun read(): CheckInZone {
        val rows = database.schedules().all()
        if (rows.isEmpty()) return CheckInZone.Unusable(CheckInZoneProblem.NO_CHECK_IN_PLAN)
        if (rows.map { it.type }.toSet() != CheckInType.entries.map { it.name }.toSet() || rows.size != CheckInType.entries.size) {
            return CheckInZone.Unusable(CheckInZoneProblem.INCONSISTENT)
        }
        if (rows.map { it.zoneId }.toSet().size != 1) return CheckInZone.Unusable(CheckInZoneProblem.INCONSISTENT)
        return try {
            val opening = rows.single { it.type == CheckInType.OPENING.name }
            CheckInZone.Known(
                ZoneId.of(rows.first().zoneId),
                opening.enabled,
                runCatching { LocalTime.parse(opening.localTime) }.getOrNull(),
            )
        } catch (e: DateTimeException) {
            CheckInZone.Unusable(CheckInZoneProblem.UNKNOWN_ZONE)
        }
    }
}
