package com.thalia.app.voicecheckins.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One occurrence, as stored.
 *
 * Enums are stored by name and moments as epoch milliseconds, so the file can be read by eye and
 * a later schema change has nothing clever to migrate. The domain type is the authority on what a
 * valid combination is, and refuses to be built from a row that breaks it.
 */
@Entity(tableName = "occurrences", indices = [Index("status"), Index("slot")])
internal data class OccurrenceEntity(
    @PrimaryKey val id: String,
    val type: String,
    val scheduledAtMillis: Long,
    val zoneId: String,
    val status: String,
    val reason: String?,
    val firedAtMillis: Long?,
    val resolvedAtMillis: Long?,
    /** The day-and-type this occurrence belongs to. Several revisions can share one slot over time. */
    val slot: String,
    /** Which occurrence of its slot this is, from 1. */
    val revision: Int,
)

/** One check-in slot of the plan: which slot, when, in what zone, and whether it is on. */
@Entity(tableName = "schedules")
internal data class ScheduleEntity(
    @PrimaryKey val type: String,
    /** Local time as `HH:mm`. */
    val localTime: String,
    val zoneId: String,
    val enabled: Boolean,
)

/** The user's quiet period. The table only ever holds the one row, [ROW_ID]. */
@Entity(tableName = "quiet_period")
internal data class QuietPeriodEntity(
    @PrimaryKey val id: Int = ROW_ID,
    /** Local time as `HH:mm`. */
    val startTime: String,
    /** Local time as `HH:mm`. */
    val endTime: String,
    val enabled: Boolean,
) {
    companion object {
        const val ROW_ID = 1
    }
}

@Dao
internal abstract class OccurrenceDao {
    /** Returns the new row id, or -1 if that occurrence id was already there. Nothing is replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    abstract suspend fun insert(entity: OccurrenceEntity): Long

    @Query("SELECT * FROM occurrences WHERE id = :id")
    abstract suspend fun get(id: String): OccurrenceEntity?

    @Query("SELECT * FROM occurrences WHERE status IN ('SCHEDULED', 'RINGING', 'ANSWERED') ORDER BY scheduledAtMillis ASC, id ASC")
    abstract suspend fun open(): List<OccurrenceEntity>

    @Query("SELECT * FROM occurrences ORDER BY scheduledAtMillis DESC, id DESC LIMIT :limit")
    abstract fun observeRecent(limit: Int): Flow<List<OccurrenceEntity>>

    /**
     * The claim itself. One statement, so SQLite decides a single winner: it changes the row only if
     * it is still scheduled and no occurrence at all is ringing or answered, and reports how many rows
     * it changed. The second condition makes the one-call rule hold even for a caller that does not go
     * through [claimOrSuppress].
     */
    @Query(
        "UPDATE occurrences SET status = 'RINGING', firedAtMillis = :at WHERE id = :id AND status = 'SCHEDULED' " +
            "AND NOT EXISTS (SELECT 1 FROM occurrences WHERE status IN ('RINGING', 'ANSWERED'))",
    )
    abstract suspend fun claim(
        id: String,
        at: Long,
    ): Int

    /** How many occurrences other than [id] are ringing or answered: zero or one. */
    @Query("SELECT COUNT(*) FROM occurrences WHERE status IN ('RINGING', 'ANSWERED') AND id != :id")
    abstract suspend fun activeCallsOtherThan(id: String): Int

    /**
     * The atomic claim, with the one-call rule. One transaction, so no other claim can run between
     * its read and its write: it takes the occurrence to ringing when no other call is ringing or
     * answered, and otherwise ends it suppressed for [OccurrenceReason.CALL_IN_PROGRESS] in the same
     * step. Returns one of [CLAIMED], [SUPPRESSED] or [NOT_CLAIMED].
     */
    @Transaction
    open suspend fun claimOrSuppress(
        id: String,
        at: Long,
    ): Int {
        if (activeCallsOtherThan(id) > 0) {
            val suppressed = transition(id, "SCHEDULED", "SUPPRESSED", "CALL_IN_PROGRESS", at)
            return if (suppressed == 1) SUPPRESSED else NOT_CLAIMED
        }
        return if (claim(id, at) == 1) CLAIMED else NOT_CLAIMED
    }

    /** Moves a row only if it is still in [from]. Returns how many rows it changed, zero or one. */
    @Query(
        "UPDATE occurrences SET status = :to, reason = :reason, resolvedAtMillis = :resolvedAt " +
            "WHERE id = :id AND status = :from",
    )
    abstract suspend fun transition(
        id: String,
        from: String,
        to: String,
        reason: String?,
        resolvedAt: Long?,
    ): Int

    /** Every occurrence of [slot], oldest revision first. */
    @Query("SELECT * FROM occurrences WHERE slot = :slot ORDER BY revision ASC")
    abstract suspend fun slot(slot: String): List<OccurrenceEntity>

    /**
     * Retire [oldId] as superseded and record [replacement] in its place, or do neither.
     *
     * One transaction, so no reader and no other writer ever sees the slot with both occurrences
     * eligible, or with neither. It gives up, changing nothing, unless the old one is still
     * scheduled and the replacement's id is free.
     */
    @Transaction
    open suspend fun supersede(
        oldId: String,
        replacement: OccurrenceEntity,
        resolvedAt: Long,
    ): Boolean {
        val old = get(oldId) ?: return false
        require(old.slot == replacement.slot && replacement.revision == old.revision + 1) {
            "${replacement.id} is not the next revision of $oldId"
        }
        if (transition(oldId, "SCHEDULED", "CANCELLED", "SUPERSEDED", resolvedAt) != 1) return false
        if (insert(replacement) == -1L) throw SupersedeAbort()
        return true
    }

    companion object {
        const val CLAIMED = 1
        const val SUPPRESSED = 2
        const val NOT_CLAIMED = 0
    }
}

/** Thrown inside [OccurrenceDao.supersede] to roll its transaction back. */
internal class SupersedeAbort : RuntimeException("the replacement could not be recorded")

@Dao
internal interface ScheduleDao {
    @Query("SELECT * FROM schedules")
    suspend fun all(): List<ScheduleEntity>

    /** Adds the slots that are missing and leaves the ones already stored alone. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMissing(entities: List<ScheduleEntity>)

    /** Replaces the whole plan in one transaction. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun replace(entities: List<ScheduleEntity>)
}

@Dao
internal interface QuietPeriodDao {
    @Query("SELECT * FROM quiet_period WHERE id = ${QuietPeriodEntity.ROW_ID}")
    suspend fun get(): QuietPeriodEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: QuietPeriodEntity)
}

@Database(
    entities = [OccurrenceEntity::class, ScheduleEntity::class, QuietPeriodEntity::class],
    version = 4,
    exportSchema = true,
)
internal abstract class CheckInDatabase : RoomDatabase() {
    abstract fun occurrences(): OccurrenceDao

    abstract fun schedules(): ScheduleDao

    abstract fun quietPeriod(): QuietPeriodDao

    companion object {
        const val FILE_NAME = "checkins.db"

        /**
         * Version 1 to 2: the two check-ins are renamed from morning and afternoon to opening and
         * closing.
         *
         * Only the `type` values change, in both tables. No table is created, dropped or copied, no
         * column changes, and no occurrence id is touched, so an id stays what it was and an armed
         * alarm, which is found by that id, still finds its record. Room runs the migration in one
         * transaction, so it either completes or leaves the version 1 file as it was.
         */
        val MIGRATION_1_2: Migration =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    for (table in listOf("occurrences", "schedules")) {
                        db.execSQL("UPDATE `$table` SET `type` = 'OPENING' WHERE `type` = 'MORNING'")
                        db.execSQL("UPDATE `$table` SET `type` = 'CLOSING' WHERE `type` = 'AFTERNOON'")
                    }
                }
            }

        /**
         * Version 2 to 3: every occurrence gets a slot and a revision, so a day and type can hold a
         * history of occurrences with only one of them live.
         *
         * Every existing row becomes revision 1 of a slot named by its own id. Its id is not touched,
         * which is what keeps an alarm armed for it, and any record of it, pointing at the same row.
         * The table is rebuilt and copied rather than altered, so the result is exactly the table
         * Room expects, and nothing is dropped until the copy is in place. It runs in one transaction.
         */
        val MIGRATION_2_3: Migration =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE `occurrences_new` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                            "`scheduledAtMillis` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                            "`reason` TEXT, `firedAtMillis` INTEGER, `resolvedAtMillis` INTEGER, " +
                            "`slot` TEXT NOT NULL, `revision` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "INSERT INTO `occurrences_new` (`id`, `type`, `scheduledAtMillis`, `zoneId`, `status`, `reason`, " +
                            "`firedAtMillis`, `resolvedAtMillis`, `slot`, `revision`) " +
                            "SELECT `id`, `type`, `scheduledAtMillis`, `zoneId`, `status`, `reason`, " +
                            "`firedAtMillis`, `resolvedAtMillis`, `id`, 1 FROM `occurrences`",
                    )
                    db.execSQL("DROP TABLE `occurrences`")
                    db.execSQL("ALTER TABLE `occurrences_new` RENAME TO `occurrences`")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_status` ON `occurrences` (`status`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_slot` ON `occurrences` (`slot`)")
                }
            }

        /**
         * Version 3 to 4: the quiet period gets its own one-row table.
         *
         * Nothing existing is touched. The table starts empty, and an empty table reads as the default
         * period, switched off, so the upgrade changes no behaviour until the user turns it on. The new
         * [CheckInStatus.SUPPRESSED] status needs no migration, because statuses are stored by name.
         */
        val MIGRATION_3_4: Migration =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `quiet_period` (`id` INTEGER NOT NULL, `startTime` TEXT NOT NULL, " +
                            "`endTime` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                }
            }

        val MIGRATIONS: Array<Migration> = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
    }
}
