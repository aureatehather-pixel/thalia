package com.thalia.voicecheckins.domain

import java.time.LocalTime
import java.time.ZoneId

/**
 * When one of the two daily check-ins should ring, expressed in local wall-clock time.
 *
 * Wall-clock rather than an absolute instant is the whole point: "10:00" must stay
 * 10:00 after travel or a time-zone change, rather than drifting to whatever absolute
 * moment 10:00 happened to be when the schedule was created.
 */
data class CheckInSchedule(
    val type: CheckInType,
    val localTime: LocalTime,
    val zone: ZoneId,
    val enabled: Boolean = true,
) {
    init {
        require(localTime.second == 0 && localTime.nano == 0) {
            "Check-in times are minute precision, got $localTime"
        }
    }
}

/**
 * The user's complete daily check-in setup: exactly one opening and one closing slot.
 *
 * The pair is modelled as a single value because the invariant that matters (opening
 * comes before closing, both in the same zone) is a property of the pair, not of
 * either schedule alone.
 */
data class CheckInPlan(
    val opening: CheckInSchedule,
    val closing: CheckInSchedule,
) {
    init {
        require(opening.type == CheckInType.OPENING) {
            "opening slot must hold an OPENING schedule, got ${opening.type}"
        }
        require(closing.type == CheckInType.CLOSING) {
            "closing slot must hold a CLOSING schedule, got ${closing.type}"
        }
        require(opening.zone == closing.zone) {
            "both check-ins must share one zone, got ${opening.zone} and ${closing.zone}"
        }
        require(opening.localTime < closing.localTime) {
            "opening (${opening.localTime}) must fall before closing (${closing.localTime})"
        }
    }

    val zone: ZoneId get() = opening.zone

    operator fun get(type: CheckInType): CheckInSchedule =
        when (type) {
            CheckInType.OPENING -> opening
            CheckInType.CLOSING -> closing
        }

    /** Reschedule one of the two slots. Throws if the result would break the pair invariant. */
    fun withTime(
        type: CheckInType,
        localTime: LocalTime,
    ): CheckInPlan =
        when (type) {
            CheckInType.OPENING -> copy(opening = opening.copy(localTime = localTime))
            CheckInType.CLOSING -> copy(closing = closing.copy(localTime = localTime))
        }

    /** Turn one of the two slots on or off without forgetting its time. */
    fun withEnabled(
        type: CheckInType,
        enabled: Boolean,
    ): CheckInPlan =
        when (type) {
            CheckInType.OPENING -> copy(opening = opening.copy(enabled = enabled))
            CheckInType.CLOSING -> copy(closing = closing.copy(enabled = enabled))
        }

    companion object {
        /** Where the user actually is. Changing this is a user setting, not a code change. */
        val DEFAULT_ZONE: ZoneId = ZoneId.of("Asia/Kuala_Lumpur")

        /** The PRD defaults: 10:00 and 15:00 local. */
        fun default(zone: ZoneId = DEFAULT_ZONE): CheckInPlan =
            CheckInPlan(
                opening = CheckInSchedule(CheckInType.OPENING, LocalTime.of(10, 0), zone),
                closing = CheckInSchedule(CheckInType.CLOSING, LocalTime.of(15, 0), zone),
            )
    }
}
