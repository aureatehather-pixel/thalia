package com.thalia.app.voicecheckins.schedule

import com.thalia.voicecheckins.domain.CheckInType

/**
 * What an alarm carries to the receiver: which occurrence is due, what kind it is, and the
 * instant it was armed for.
 *
 * The receiver reads these three values out of an Android intent and hands them here, so
 * that deciding whether they make sense is ordinary Kotlin with ordinary tests.
 */
internal data class AlarmPayload(
    val occurrenceId: String,
    val checkInType: CheckInType,
    val scheduledAtMillis: Long,
) {
    companion object {
        /**
         * Rebuild a payload from the raw values in an intent, or null if any is missing or
         * unusable. An alarm that cannot say what it is for must not ring a call.
         */
        fun parse(
            occurrenceId: String?,
            checkInTypeName: String?,
            scheduledAtMillis: Long?,
        ): AlarmPayload? {
            if (occurrenceId.isNullOrBlank() || scheduledAtMillis == null) return null
            val type = CheckInType.fromStoredName(checkInTypeName) ?: return null
            return AlarmPayload(occurrenceId, type, scheduledAtMillis)
        }
    }
}
