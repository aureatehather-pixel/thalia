package com.thalia.app.planning.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * The planning loop's own database, `planning.db`, separate from M2's `checkins.db`. ADR 0009.
 *
 * Enums are stored by name, dates as ISO text and moments as epoch milliseconds, as M2 does. The
 * foreign keys refuse a link to a goal or project that does not exist; [PlanningStore] checks the
 * same first, so the user gets a reason rather than a constraint error.
 */
@Entity(tableName = "direction")
internal data class DirectionEntity(
    @PrimaryKey val id: Int = ROW_ID,
    val text: String,
    val updatedAtMillis: Long,
) {
    companion object {
        const val ROW_ID = 1
    }
}

@Entity(tableName = "goals")
internal data class GoalEntity(
    @PrimaryKey val id: String,
    val title: String,
    val successCondition: String,
    val horizon: String?,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

@Entity(
    tableName = "projects",
    foreignKeys = [
        ForeignKey(entity = GoalEntity::class, parentColumns = ["id"], childColumns = ["goalId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("goalId")],
)
internal data class ProjectEntity(
    @PrimaryKey val id: String,
    val title: String,
    val goalId: String?,
    val state: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Schema 4, ADR 0011: the project's context, each null until the person says. */
    val purpose: String? = null,
    val doneWhen: String? = null,
    val constraints: String? = null,
    val blocker: String? = null,
)

@Entity(
    tableName = "actions",
    foreignKeys = [
        ForeignKey(entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index("projectId")],
)
internal data class ActionEntity(
    @PrimaryKey val id: String,
    val title: String,
    val projectId: String?,
    val priority: String,
    val estimateMinutes: Int,
    val dueDate: String?,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** The one row of planning settings. Times are `HH:mm`; the window is absent until the user sets it. */
@Entity(tableName = "planning_settings")
internal data class SettingsEntity(
    @PrimaryKey val id: Int = ROW_ID,
    val rollover: String,
    val windowStart: String?,
    val windowEnd: String?,
) {
    companion object {
        const val ROW_ID = 1
    }
}

/**
 * One revision of an approved day plan. Written once and never updated: there is no update, not even a
 * "superseded" mark, and the latest revision of a day is found by query. `(activeDay, revision)` is
 * unique, so two approvals can never record the same revision, whatever the code above does.
 */
@Entity(tableName = "day_plans", indices = [Index(value = ["activeDay", "revision"], unique = true)])
internal data class DayPlanEntity(
    @PrimaryKey val id: String,
    val activeDay: String,
    val revision: Int,
    val zone: String,
    val rollover: String,
    val windowStart: String,
    val windowEnd: String,
    val freeMinutes: Int,
    val targetMinutes: Int,
    val overloadMinutes: Int,
    val approvedAtMillis: Long,
)

/**
 * An item of a plan, with the action's and project's fields as they were at approval. The action link
 * is a foreign key; the project link is only a remembered id, since the snapshot outlives the project.
 */
@Entity(
    tableName = "plan_items",
    foreignKeys = [
        ForeignKey(entity = DayPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ActionEntity::class, parentColumns = ["id"], childColumns = ["actionId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["planId", "actionId"], unique = true), Index("actionId")],
)
internal data class PlanItemEntity(
    @PrimaryKey val id: String,
    val planId: String,
    val actionId: String,
    val kind: String,
    val start: String?,
    val minutes: Int,
    val position: Int,
    val placement: String?,
    val reason: String?,
    val title: String,
    val priority: String,
    val projectId: String?,
    val projectTitle: String?,
    val projectState: String?,
)

/**
 * The recorded result for a plan item, with the one marker that says it closed the action. ADR 0009 slice 3.
 * One row per item; correcting replaces it. [closedAction] follows the invariants in
 * [com.thalia.planning.outcomeInvariantViolation], and a manual status change clears it.
 */
@Entity(
    tableName = "plan_outcomes",
    foreignKeys = [
        ForeignKey(entity = PlanItemEntity::class, parentColumns = ["id"], childColumns = ["planItemId"], onDelete = ForeignKey.RESTRICT),
    ],
)
internal data class OutcomeEntity(
    @PrimaryKey val planItemId: String,
    val result: String,
    val recordedAtMillis: Long,
    val closedAction: Boolean,
)

/**
 * A plan's opening call: its time and a log of what the link last did. A plan with no row has no opening
 * call. The status authorizes nothing, except that REQUESTED is the only state a launch retry may act on.
 * Approval and import both write NOT_LINKED: the time is kept, with no executable request. REQUESTED is
 * written only by [PlanningStore.authorizeOpeningCall], the user's separate, explicit confirmation.
 */
@Entity(
    tableName = "plan_calls",
    foreignKeys = [
        ForeignKey(entity = DayPlanEntity::class, parentColumns = ["id"], childColumns = ["planId"], onDelete = ForeignKey.RESTRICT),
    ],
)
internal data class PlanCallEntity(
    @PrimaryKey val planId: String,
    val openingTime: String,
    val status: String,
    val detail: String?,
    val updatedAtMillis: Long,
) {
    companion object {
        const val REQUESTED = "REQUESTED"
        const val NOT_LINKED = "NOT_LINKED"
    }
}

/** A fixed commitment at real instants, epoch milliseconds. Schema 4, ADR 0011. Cancelling keeps the row. */
@Entity(tableName = "commitments")
internal data class CommitmentEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startMillis: Long,
    val endMillis: Long,
    val zone: String,
    val status: String,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** One working day, by its label. Schema 4, ADR 0011. At most one row is open; the store enforces it. */
@Entity(tableName = "working_days")
internal data class WorkingDayEntity(
    @PrimaryKey val day: String,
    val openedAtMillis: Long?,
    val closedAtMillis: Long?,
    val focusMinutes: Int?,
    val closingNote: String?,
    val continuedOn: String?,
)

/** One captured item. Schema 5, ADR 0012. [text] never changes; the row is kept in every state, so nothing here is lost by filing. */
@Entity(tableName = "inbox_items")
internal data class InboxItemEntity(
    @PrimaryKey val id: String,
    val text: String,
    val state: String,
    val body: String?,
    val actionId: String?,
    val resolvedAtMillis: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/**
 * One one-shot reminder. Schema 6, ADR 0014. No foreign key: a reminder is never held up by, or changed by, its target.
 * Rows are kept in every status.
 */
@Entity(tableName = "reminders")
internal data class ReminderEntity(
    @PrimaryKey val id: String,
    val targetType: String,
    val targetId: String?,
    val label: String,
    val dueAtMillis: Long,
    val zone: String,
    val status: String,
    val reason: String?,
    val postedAtMillis: Long?,
    val resolvedAtMillis: Long?,
    val createdAtMillis: Long,
)

@Dao
internal interface PlanningDao {
    @Query("SELECT * FROM direction WHERE id = ${DirectionEntity.ROW_ID}")
    suspend fun direction(): DirectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveDirection(entity: DirectionEntity)

    @Query("DELETE FROM direction")
    suspend fun deleteDirection(): Int

    @Query("DELETE FROM goals WHERE id = :id")
    suspend fun deleteGoal(id: String): Int

    @Query("SELECT * FROM goals ORDER BY createdAtMillis, id")
    suspend fun goals(): List<GoalEntity>

    @Query("SELECT * FROM goals WHERE id = :id")
    suspend fun goal(id: String): GoalEntity?

    @Insert
    suspend fun insertGoal(entity: GoalEntity)

    @Update
    suspend fun updateGoal(entity: GoalEntity)

    @Query("SELECT * FROM projects ORDER BY createdAtMillis, id")
    suspend fun projects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun project(id: String): ProjectEntity?

    @Insert
    suspend fun insertProject(entity: ProjectEntity)

    @Update
    suspend fun updateProject(entity: ProjectEntity)

    @Query("SELECT * FROM actions ORDER BY createdAtMillis, id")
    suspend fun actions(): List<ActionEntity>

    @Query("SELECT * FROM actions WHERE id = :id")
    suspend fun action(id: String): ActionEntity?

    @Insert
    suspend fun insertAction(entity: ActionEntity)

    @Update
    suspend fun updateAction(entity: ActionEntity)

    @Query("SELECT * FROM planning_settings WHERE id = ${SettingsEntity.ROW_ID}")
    suspend fun settings(): SettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSettings(entity: SettingsEntity)

    @Query("SELECT MAX(revision) FROM day_plans WHERE activeDay = :activeDay")
    suspend fun latestRevision(activeDay: String): Int?

    /** The revision in force for a day: its highest. */
    @Query("SELECT * FROM day_plans WHERE activeDay = :activeDay ORDER BY revision DESC LIMIT 1")
    suspend fun latestPlan(activeDay: String): DayPlanEntity?

    /** Each day's latest revision. */
    @Query(
        "SELECT * FROM day_plans AS p WHERE p.revision = (SELECT MAX(revision) FROM day_plans WHERE activeDay = p.activeDay) ORDER BY p.activeDay",
    )
    suspend fun latestPlans(): List<DayPlanEntity>

    @Query("SELECT * FROM day_plans WHERE activeDay = :activeDay ORDER BY revision")
    suspend fun plansOfDay(activeDay: String): List<DayPlanEntity>

    @Query("SELECT * FROM day_plans ORDER BY activeDay, revision")
    suspend fun plans(): List<DayPlanEntity>

    @Query(
        "SELECT * FROM plan_items WHERE planId = :planId ORDER BY CASE kind WHEN 'PINNED' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, position",
    )
    suspend fun itemsOf(planId: String): List<PlanItemEntity>

    @Query("SELECT * FROM plan_items ORDER BY planId, CASE kind WHEN 'PINNED' THEN 0 WHEN 'QUEUED' THEN 1 ELSE 2 END, position")
    suspend fun planItems(): List<PlanItemEntity>

    @Insert
    suspend fun insertPlan(entity: DayPlanEntity)

    @Insert
    suspend fun insertPlanItems(entities: List<PlanItemEntity>)

    @Insert
    suspend fun insertPlans(entities: List<DayPlanEntity>)

    /** Every row in every planning table, settings included. Zero means a restore may write into it. */
    @Query(
        "SELECT (SELECT COUNT(*) FROM direction) + (SELECT COUNT(*) FROM planning_settings) + (SELECT COUNT(*) FROM goals)" +
            " + (SELECT COUNT(*) FROM projects) + (SELECT COUNT(*) FROM actions)" +
            " + (SELECT COUNT(*) FROM day_plans) + (SELECT COUNT(*) FROM plan_items)" +
            " + (SELECT COUNT(*) FROM plan_outcomes) + (SELECT COUNT(*) FROM plan_calls)" +
            " + (SELECT COUNT(*) FROM commitments) + (SELECT COUNT(*) FROM working_days)" +
            " + (SELECT COUNT(*) FROM inbox_items) + (SELECT COUNT(*) FROM reminders)",
    )
    suspend fun rowCount(): Int

    @Insert
    suspend fun insertGoals(entities: List<GoalEntity>)

    @Insert
    suspend fun insertProjects(entities: List<ProjectEntity>)

    @Insert
    suspend fun insertActions(entities: List<ActionEntity>)

    @Query("SELECT * FROM plan_items WHERE id = :id")
    suspend fun planItem(id: String): PlanItemEntity?

    @Query("SELECT * FROM day_plans WHERE id = :id")
    suspend fun plan(id: String): DayPlanEntity?

    @Query("SELECT * FROM plan_items WHERE actionId = :actionId")
    suspend fun itemsOfAction(actionId: String): List<PlanItemEntity>

    @Query("SELECT * FROM plan_outcomes WHERE planItemId = :itemId")
    suspend fun outcome(itemId: String): OutcomeEntity?

    @Query("SELECT * FROM plan_outcomes ORDER BY planItemId")
    suspend fun outcomes(): List<OutcomeEntity>

    @Query("SELECT o.* FROM plan_outcomes AS o JOIN plan_items AS i ON i.id = o.planItemId WHERE i.planId = :planId ORDER BY o.planItemId")
    suspend fun outcomesOfPlan(planId: String): List<OutcomeEntity>

    @Query(
        "SELECT o.* FROM plan_outcomes AS o JOIN plan_items AS i ON i.id = o.planItemId WHERE i.actionId = :actionId ORDER BY o.planItemId",
    )
    suspend fun outcomesOfAction(actionId: String): List<OutcomeEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveOutcome(entity: OutcomeEntity)

    @Insert
    suspend fun insertOutcomes(entities: List<OutcomeEntity>)

    @Query("SELECT * FROM plan_calls ORDER BY planId")
    suspend fun calls(): List<PlanCallEntity>

    @Insert
    suspend fun insertCall(entity: PlanCallEntity)

    @Query("SELECT * FROM plan_calls WHERE planId = :planId")
    suspend fun call(planId: String): PlanCallEntity?

    @Query("SELECT * FROM plan_calls WHERE status = :status ORDER BY planId")
    suspend fun callsWithStatus(status: String): List<PlanCallEntity>

    /** Moves a call to [status], but only from one of the [from] statuses. Returns the rows changed. */
    @Query("UPDATE plan_calls SET status = :status, detail = :detail, updatedAtMillis = :now WHERE planId = :planId AND status IN (:from)")
    suspend fun moveCall(
        planId: String,
        from: List<String>,
        status: String,
        detail: String?,
        now: Long,
    ): Int

    /** NOT_LINKED to REQUESTED, and nothing else: any other status is left as it is. Returns the rows changed. */
    @Query(
        "UPDATE plan_calls SET status = 'REQUESTED', detail = NULL, updatedAtMillis = :now WHERE planId = :planId AND status = 'NOT_LINKED'",
    )
    suspend fun requestCall(
        planId: String,
        now: Long,
    ): Int

    @Insert
    suspend fun insertCalls(entities: List<PlanCallEntity>)

    @Query("SELECT * FROM commitments ORDER BY startMillis, id")
    suspend fun commitments(): List<CommitmentEntity>

    @Query("SELECT * FROM commitments WHERE id = :id")
    suspend fun commitment(id: String): CommitmentEntity?

    /** Active commitments that overlap [from, to), in epoch milliseconds. */
    @Query("SELECT * FROM commitments WHERE status = 'ACTIVE' AND startMillis < :to AND endMillis > :from ORDER BY startMillis, id")
    suspend fun commitmentsOverlapping(
        from: Long,
        to: Long,
    ): List<CommitmentEntity>

    @Insert
    suspend fun insertCommitment(entity: CommitmentEntity)

    @Update
    suspend fun updateCommitment(entity: CommitmentEntity)

    @Insert
    suspend fun insertCommitments(entities: List<CommitmentEntity>)

    @Query("SELECT * FROM working_days ORDER BY day")
    suspend fun workingDays(): List<WorkingDayEntity>

    @Query("SELECT * FROM working_days WHERE day = :day")
    suspend fun workingDay(day: String): WorkingDayEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveWorkingDay(entity: WorkingDayEntity)

    @Insert
    suspend fun insertWorkingDays(entities: List<WorkingDayEntity>)

    @Query("SELECT * FROM inbox_items ORDER BY createdAtMillis, id")
    suspend fun inboxItems(): List<InboxItemEntity>

    @Query("SELECT * FROM inbox_items WHERE id = :id")
    suspend fun inboxItem(id: String): InboxItemEntity?

    @Insert
    suspend fun insertInboxItem(entity: InboxItemEntity)

    @Update
    suspend fun updateInboxItem(entity: InboxItemEntity)

    @Query("DELETE FROM inbox_items WHERE id = :id")
    suspend fun deleteInboxItem(id: String)

    @Insert
    suspend fun insertInboxItems(entities: List<InboxItemEntity>)

    @Query("SELECT * FROM reminders ORDER BY dueAtMillis, id")
    suspend fun reminders(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun reminder(id: String): ReminderEntity?

    @Query("SELECT COUNT(*) FROM reminders WHERE status = 'SCHEDULED'")
    suspend fun scheduledReminderCount(): Int

    /** Returns the new row id, or -1 if the id was already there. Nothing is replaced. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertReminder(entity: ReminderEntity): Long

    @Insert
    suspend fun insertReminders(entities: List<ReminderEntity>)

    /** The one write that moves a reminder: only from [from], so a duplicate or stale move changes nothing. Returns the rows changed. */
    @Query(
        "UPDATE reminders SET status = :status, reason = :reason, postedAtMillis = :postedAt, resolvedAtMillis = :resolvedAt " +
            "WHERE id = :id AND status = :from",
    )
    suspend fun moveReminder(
        id: String,
        from: String,
        status: String,
        reason: String?,
        postedAt: Long?,
        resolvedAt: Long?,
    ): Int
}

@Database(
    entities =
        [
            DirectionEntity::class,
            GoalEntity::class,
            ProjectEntity::class,
            ActionEntity::class,
            SettingsEntity::class,
            DayPlanEntity::class,
            PlanItemEntity::class,
            OutcomeEntity::class,
            PlanCallEntity::class,
            CommitmentEntity::class,
            WorkingDayEntity::class,
            InboxItemEntity::class,
            ReminderEntity::class,
        ],
    version = PlanningDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
internal abstract class PlanningDatabase : RoomDatabase() {
    abstract fun planning(): PlanningDao

    companion object {
        /** Also written into every export, which refuses to restore one from a newer schema. */
        const val SCHEMA_VERSION = 6

        /** Every migration, in one place, so the production builder and the tests register the same ones. */
        val MIGRATIONS: Array<Migration> get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)

        /**
         * Schema 1 to 2: adds the day plans and their items, and nothing else. Every existing row is kept.
         * Room's destructive fallback is never enabled; a failing migration is reported as MIGRATION FAILED.
         */
        val MIGRATION_1_2 =
            object : Migration(1, 2) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `day_plans` (`id` TEXT NOT NULL, `activeDay` TEXT NOT NULL, " +
                            "`revision` INTEGER NOT NULL, " +
                            "`zone` TEXT NOT NULL, `rollover` TEXT NOT NULL, `windowStart` TEXT NOT NULL, " +
                            "`windowEnd` TEXT NOT NULL, `freeMinutes` INTEGER NOT NULL, `targetMinutes` INTEGER NOT NULL, " +
                            "`overloadMinutes` INTEGER NOT NULL, `approvedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_day_plans_activeDay_revision` ON `day_plans` (`activeDay`, `revision`)",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `plan_items` (`id` TEXT NOT NULL, `planId` TEXT NOT NULL, `actionId` TEXT NOT NULL, " +
                            "`kind` TEXT NOT NULL, `start` TEXT, `minutes` INTEGER NOT NULL, `position` INTEGER NOT NULL, " +
                            "`placement` TEXT, " +
                            "`reason` TEXT, `title` TEXT NOT NULL, `priority` TEXT NOT NULL, `projectId` TEXT, `projectTitle` TEXT, " +
                            "`projectState` TEXT, PRIMARY KEY(`id`), " +
                            "FOREIGN KEY(`planId`) REFERENCES `day_plans`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT , " +
                            "FOREIGN KEY(`actionId`) REFERENCES `actions`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                    )
                    db.execSQL(
                        "CREATE UNIQUE INDEX IF NOT EXISTS `index_plan_items_planId_actionId` ON `plan_items` (`planId`, `actionId`)",
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_plan_items_actionId` ON `plan_items` (`actionId`)")
                }
            }

        /**
         * Schema 2 to 3: adds the outcomes and the plans' opening calls, and nothing else. No existing table,
         * index or row is touched, so the plan tables stay exactly as they were written.
         */
        val MIGRATION_2_3 =
            object : Migration(2, 3) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `plan_outcomes` (`planItemId` TEXT NOT NULL, `result` TEXT NOT NULL, " +
                            "`recordedAtMillis` INTEGER NOT NULL, `closedAction` INTEGER NOT NULL, PRIMARY KEY(`planItemId`), " +
                            "FOREIGN KEY(`planItemId`) REFERENCES `plan_items`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `plan_calls` (`planId` TEXT NOT NULL, `openingTime` TEXT NOT NULL, " +
                            "`status` TEXT NOT NULL, `detail` TEXT, `updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`planId`), " +
                            "FOREIGN KEY(`planId`) REFERENCES `day_plans`(`id`) ON UPDATE NO ACTION ON DELETE RESTRICT )",
                    )
                }
            }

        /**
         * Schema 3 to 4, ADR 0011: four nullable context columns on projects, and the commitments and working days tables.
         * Every existing row keeps its values; the new columns start null and the new tables empty.
         */
        val MIGRATION_3_4 =
            object : Migration(3, 4) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    listOf("purpose", "doneWhen", "constraints", "blocker").forEach {
                        db.execSQL("ALTER TABLE `projects` ADD COLUMN `$it` TEXT")
                    }
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `commitments` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, " +
                            "`startMillis` INTEGER NOT NULL, `endMillis` INTEGER NOT NULL, `zone` TEXT NOT NULL, " +
                            "`status` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL, " +
                            "`updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `working_days` (`day` TEXT NOT NULL, `openedAtMillis` INTEGER, " +
                            "`closedAtMillis` INTEGER, " +
                            "`focusMinutes` INTEGER, `closingNote` TEXT, `continuedOn` TEXT, PRIMARY KEY(`day`))",
                    )
                }
            }

        /** Schema 4 to 5, ADR 0012: the Inbox table, and nothing else. No existing table or row is touched. */
        val MIGRATION_4_5 =
            object : Migration(4, 5) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `inbox_items` (`id` TEXT NOT NULL, `text` TEXT NOT NULL, `state` TEXT NOT NULL, " +
                            "`body` TEXT, `actionId` TEXT, `resolvedAtMillis` INTEGER, `createdAtMillis` INTEGER NOT NULL, " +
                            "`updatedAtMillis` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                    )
                }
            }

        /** Schema 5 to 6, ADR 0014: the reminders table, and nothing else. No existing table or row is touched. */
        val MIGRATION_5_6 =
            object : Migration(5, 6) {
                override fun migrate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `reminders` (`id` TEXT NOT NULL, `targetType` TEXT NOT NULL, `targetId` TEXT, " +
                            "`label` TEXT NOT NULL, `dueAtMillis` INTEGER NOT NULL, `zone` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                            "`reason` TEXT, `postedAtMillis` INTEGER, `resolvedAtMillis` INTEGER, `createdAtMillis` INTEGER NOT NULL, " +
                            "PRIMARY KEY(`id`))",
                    )
                }
            }
    }
}
