package com.thalia.voicecheckins.domain

/**
 * The two daily check-ins Thalia places.
 *
 * There are deliberately exactly two. A general "N check-ins per day" model would be
 * more flexible and less honest: the product is two conversations a day, and every
 * screen, notification and stored record is built around that pair.
 *
 * They are named for what they do and not for when they happen, because the active day does not
 * start at midnight: the opening call starts the day and the closing call ends it.
 */
enum class CheckInType {
    OPENING,
    CLOSING,
    ;

    companion object {
        /**
         * The type a stored or in-flight name stands for, or null if it names none.
         *
         * Before the rename the two types were called `MORNING` and `AFTERNOON`. The database is
         * migrated, but an alarm armed by an earlier build carries the old name in its intent until
         * it is re-armed, and it must still be understood. Matching is exact: no other spelling counts.
         */
        fun fromStoredName(name: String?): CheckInType? =
            when (name) {
                "OPENING", "MORNING" -> OPENING
                "CLOSING", "AFTERNOON" -> CLOSING
                else -> null
            }
    }
}
