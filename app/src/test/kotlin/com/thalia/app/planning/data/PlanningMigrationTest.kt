package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR 0009 slice 2: a real schema 1 database, built from the committed `1.json` that shipped (its own
 * `CREATE` statements and identity hash), opens through the production migration with every row kept.
 * The migrated tables are then checked by Room's own schema validation, which fails the open on any
 * difference from `2.json`'s entities.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "planning-migration-test.db"
    private var database: PlanningDatabase? = null

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    private fun schemaJson(version: Int) =
        JSONObject(File("schemas/com.thalia.app.planning.data.PlanningDatabase/$version.json").readText()).getJSONObject("database")

    /** The database slice 1 left on the phone: schema 1's statements, its identity hash, `user_version` 1 and some rows. */
    private fun createSchemaOneDatabase(): File =
        createSchemaDatabase(1) { raw ->
            raw.execSQL("INSERT INTO direction (id, text, updatedAtMillis) VALUES (1, 'Stay curious', 100)")
            raw.execSQL("INSERT INTO planning_settings (id, rollover, windowStart, windowEnd) VALUES (1, '04:00', '12:00', '22:00')")
            raw.execSQL("INSERT INTO goals VALUES ('g1', 'Run', 'Finish', '2027-04-01', 'ACTIVE', 10, 20)")
            raw.execSQL("INSERT INTO projects VALUES ('p1', 'Training', 'g1', 'ACTIVE', 11, 21)")
            raw.execSQL("INSERT INTO actions VALUES ('a1', 'Long run', 'p1', 'MUST', 120, '2026-10-04', 'OPEN', 12, 22)")
            raw.execSQL("INSERT INTO actions VALUES ('a2', 'Call bank', NULL, 'NORMAL', 30, NULL, 'DONE', 13, 23)")
        }

    /** A database at [version], from that version's committed JSON (its own statements and identity hash), then [rows]. */
    private fun createSchemaDatabase(
        version: Int,
        rows: (SQLiteDatabase) -> Unit,
    ): File {
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = schemaJson(version)
        assertEquals(version, schema.getInt("version"))
        val raw = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val entity = entities.getJSONObject(i)
                val table = entity.getString("tableName")
                raw.execSQL(entity.getString("createSql").replace("\${TABLE_NAME}", table))
                val indices = entity.optJSONArray("indices")
                for (j in 0 until (indices?.length() ?: 0)) {
                    raw.execSQL(
                        indices!!.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table),
                    )
                }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) raw.execSQL(setup.getString(i))
            rows(raw)
            raw.version = version
        } finally {
            raw.close()
        }
        return file
    }

    @Test
    fun `a schema 1 database opens at the current schema with every row kept and no plans`() =
        runTest {
            val file = createSchemaOneDatabase()
            val db = plainPlanningDatabase(context, file).also { database = it }
            val store = PlanningStore(db) { 1_000L }

            assertTrue(db.openHelper.writableDatabase.isOpen) // Room validated the migrated schema against 2.json's entities
            assertEquals(PlanningDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)

            assertEquals(Direction("Stay curious"), store.direction())
            assertEquals(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))), store.settings())
            assertEquals(listOf(Goal("g1", "Run", "Finish", LocalDate.of(2027, 4, 1))), store.goals())
            assertEquals(listOf(Project("p1", "Training", "g1", ProjectState.ACTIVE)), store.projects())
            assertEquals(
                listOf(
                    Action("a1", "Long run", "p1", Priority.MUST, 120, LocalDate.of(2026, 10, 4)),
                    Action("a2", "Call bank", null, Priority.NORMAL, 30, null, com.thalia.planning.ActionStatus.DONE),
                ),
                store.actions(),
            )
            val snapshot = store.snapshot()
            assertEquals(emptyList(), snapshot.plans)
            assertEquals(emptyList(), snapshot.planItems)
            assertEquals(6, snapshot.counts.total)
        }

    @Test
    fun `a migrated database can plan, approve and keep revisions`() =
        runTest {
            val file = createSchemaOneDatabase()
            val db = plainPlanningDatabase(context, file).also { database = it }
            val store = PlanningStore(db) { 2_000L }
            val day = ActiveDay(LocalDate.of(2026, 10, 3))

            val proposal = assertNotNull(store.propose(day, ZoneId.of("UTC"), null, setOf("a1"), emptyMap()))
            assertIs<ApprovalResult.Approved>(store.approve(proposal, ZoneId.of("UTC")))
            assertEquals(1, store.planFor(day)!!.plan.revision)
            assertEquals(listOf("a1"), store.planFor(day)!!.items.map { it.actionId })
        }

    @Test
    fun `reopening a migrated database does not migrate again or change a row`() =
        runTest {
            val file = createSchemaOneDatabase()
            plainPlanningDatabase(context, file).also { database = it }.openHelper.writableDatabase
            val first = PlanningStore(database!!) { 1L }.snapshot()
            database!!.close()

            database = plainPlanningDatabase(context, file)
            assertEquals(first, PlanningStore(database!!) { 1L }.snapshot())
        }

    private val existingTables =
        listOf("direction", "planning_settings", "goals", "projects", "actions", "day_plans", "plan_items")

    /** The columns [table] had at schema [version], from that version's committed JSON. */
    private fun columnsAt(
        version: Int,
        table: String,
    ): String {
        val entities = schemaJson(version).getJSONArray("entities")
        val entity = (0 until entities.length()).map { entities.getJSONObject(it) }.single { it.getString("tableName") == table }
        val fields = entity.getJSONArray("fields")
        return (0 until fields.length()).joinToString(", ") { "`" + fields.getJSONObject(it).getString("columnName") + "`" }
    }

    /**
     * Every row of every table of [tables], as text, in storage order, reading only the columns each had at schema [version]:
     * the logical records, so a later migration that adds a column does not count as changing them.
     */
    private fun dumpExisting(
        file: File,
        tables: List<String> = existingTables,
        version: Int = 2,
    ): Map<String, List<String>> {
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return tables.associateWith { table ->
                raw.rawQuery("SELECT ${columnsAt(version, table)} FROM $table ORDER BY rowid", null).use { c ->
                    buildList {
                        while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { c.getString(it) ?: "NULL" })
                    }
                }
            }
        } finally {
            raw.close()
        }
    }

    private fun countRows(
        file: File,
        table: String,
    ): Int {
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            return raw.rawQuery("SELECT COUNT(*) FROM $table", null).use {
                it.moveToFirst()
                it.getInt(0)
            }
        } finally {
            raw.close()
        }
    }

    private fun schemaTwoRows(
        raw: SQLiteDatabase,
        projectValues: String = "'p1', 'Training', 'g1', 'ACTIVE', 11, 21",
    ) {
        raw.execSQL("INSERT INTO direction (id, text, updatedAtMillis) VALUES (1, 'Stay curious', 100)")
        raw.execSQL("INSERT INTO planning_settings (id, rollover, windowStart, windowEnd) VALUES (1, '04:00', '12:00', '22:00')")
        raw.execSQL("INSERT INTO goals VALUES ('g1', 'Run', 'Finish', '2027-04-01', 'ACTIVE', 10, 20)")
        raw.execSQL("INSERT INTO projects VALUES ($projectValues)")
        raw.execSQL("INSERT INTO actions VALUES ('a1', 'Long run', 'p1', 'MUST', 120, '2026-10-04', 'OPEN', 12, 22)")
        raw.execSQL("INSERT INTO actions VALUES ('a2', 'Call bank', NULL, 'NORMAL', 30, NULL, 'DONE', 13, 23)")
        raw.execSQL("INSERT INTO day_plans VALUES ('plan1', '2026-10-03', 1, 'UTC', '04:00', '12:00', '22:00', 600, 480, 0, 500)")
        raw.execSQL("INSERT INTO day_plans VALUES ('plan2', '2026-10-03', 2, 'UTC', '04:00', '12:00', '22:00', 600, 480, 0, 600)")
        raw.execSQL(
            "INSERT INTO plan_items VALUES ('item1', 'plan1', 'a1', 'QUEUED', NULL, 120, 0, 'WITHIN_TARGET', NULL, 'Long run', 'MUST', 'p1', 'Training', 'ACTIVE')",
        )
        raw.execSQL(
            "INSERT INTO plan_items VALUES ('item2', 'plan2', 'a1', 'QUEUED', NULL, 120, 0, 'WITHIN_TARGET', NULL, 'Long run', 'MUST', 'p1', 'Training', 'ACTIVE')",
        )
        raw.execSQL(
            "INSERT INTO plan_items VALUES ('item3', 'plan2', 'a2', 'PINNED', '14:00', 30, 0, NULL, NULL, 'Call bank', 'NORMAL', NULL, NULL, NULL)",
        )
    }

    @Test
    fun `schema 2 to 3 leaves every existing row byte for byte, adds two empty tables and keeps revisions immutable`() =
        runTest {
            val file = createSchemaDatabase(2, ::schemaTwoRows)
            val before = dumpExisting(file)
            assertEquals(3, before.getValue("plan_items").size)

            val db = plainPlanningDatabase(context, file).also { database = it }
            // Room validated the result against the current schema's entities, through 3 and then 4.
            assertEquals(PlanningDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            val store = PlanningStore(db) { 5_000L }
            val snapshot = store.snapshot()
            assertEquals(emptyList(), snapshot.outcomes)
            assertEquals(emptyMap(), snapshot.openingTimes)
            assertEquals(listOf(1, 2), snapshot.plans.map { it.revision })
            db.close()
            database = null

            assertEquals(before, dumpExisting(file))
            assertEquals(0, countRows(file, "plan_outcomes"))
            assertEquals(0, countRows(file, "plan_calls"))
        }

    @Test
    fun `a migrated schema 2 database can record an outcome, and the plan rows still do not change`() =
        runTest {
            val file = createSchemaDatabase(2, ::schemaTwoRows)
            val before = dumpExisting(file)
            val db = plainPlanningDatabase(context, file).also { database = it }
            val store = PlanningStore(db) { 5_000L }
            val today = ActiveDay(LocalDate.of(2026, 10, 3))

            assertEquals(
                OutcomeRefusal.NOT_ELIGIBLE,
                (store.recordOutcome("item1", com.thalia.planning.OutcomeResult.DONE, today) as OutcomeRecording.Refused).reason,
            )
            assertIs<OutcomeRecording.Recorded>(store.recordOutcome("item2", com.thalia.planning.OutcomeResult.PARTIAL, today))
            db.close()
            database = null

            val after = dumpExisting(file)
            assertEquals(before.filterKeys { it != "actions" }, after.filterKeys { it != "actions" })
            assertEquals(before.getValue("actions"), after.getValue("actions"))
            assertEquals(1, countRows(file, "plan_outcomes"))
        }

    @Test
    fun `the production builder itself migrates a schema 2 database, only its SQLCipher open helper replaced`() =
        runTest {
            val file = createSchemaDatabase(2, ::schemaTwoRows)
            val db = productionBuilderOnPlainSqlite(file).build().also { database = it }
            assertEquals(PlanningDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            assertEquals(listOf("a1", "a2"), PlanningStore(db) { 1L }.actions().map { it.id })
        }

    private fun productionBuilderOnPlainSqlite(file: File) =
        PlanningDatabaseOpener
            .productionBuilder(context, file, ByteArray(32))
            .openHelperFactory(NonDestructiveOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))

    @Test
    fun `the migrations production registers form a chain from schema 1 to the current schema`() {
        val chain = PlanningDatabase.MIGRATIONS.map { it.startVersion to it.endVersion }
        assertEquals(listOf(1 to 2, 2 to 3, 3 to 4, 4 to 5, 5 to 6), chain)
        assertEquals(PlanningDatabase.SCHEMA_VERSION, chain.last().second)
    }

    @Test
    fun `the production builder itself migrates a schema 1 database, only its SQLCipher open helper replaced`() =
        runTest {
            val file = createSchemaOneDatabase()
            val db = productionBuilderOnPlainSqlite(file).build().also { database = it }
            assertEquals(PlanningDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            assertEquals(listOf("a1", "a2"), PlanningStore(db) { 1L }.actions().map { it.id })
            assertEquals(Direction("Stay curious"), PlanningStore(db) { 1L }.direction())
        }

    @Test
    fun `the production builder has no destructive fallback, so a database it cannot migrate is kept`() {
        val file = createSchemaOneDatabase()
        val raw = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        raw.version = 99
        raw.close()

        val db = productionBuilderOnPlainSqlite(file).build().also { database = it }
        assertFailsWith<IllegalStateException> { db.openHelper.writableDatabase }
        db.close()

        val after = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            assertEquals(99, after.version)
            assertEquals(
                2,
                after.rawQuery("SELECT COUNT(*) FROM actions", null).use {
                    it.moveToFirst()
                    it.getInt(0)
                },
            )
        } finally {
            after.close()
        }
    }

    // Schema 3 to 4, ADR 0011

    private val schemaThreeTables = existingTables + listOf("plan_outcomes", "plan_calls")

    private fun schemaThreeRows(
        raw: SQLiteDatabase,
        projectValues: String = "'p1', 'Training', 'g1', 'ACTIVE', 11, 21",
    ) {
        schemaTwoRows(raw, projectValues)
        raw.execSQL("INSERT INTO plan_outcomes VALUES ('item3', 'PARTIAL', 700, 0)")
        raw.execSQL("INSERT INTO plan_calls VALUES ('plan2', '08:00', 'NOT_LINKED', NULL, 800)")
    }

    @Test
    fun `schema 3 to 4 keeps every logical record, starts project context unknown and adds two empty tables`() =
        runTest {
            val file = createSchemaDatabase(3, ::schemaThreeRows)
            val before = dumpExisting(file, schemaThreeTables, version = 3)
            assertEquals(1, before.getValue("plan_outcomes").size)

            val db = plainPlanningDatabase(context, file).also { database = it }
            assertEquals(PlanningDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            val store = PlanningStore(db) { 5_000L }
            val project = store.projects().single()
            assertEquals(listOf(null, null, null, null), listOf(project.purpose, project.doneWhen, project.constraints, project.blocker))
            assertEquals(emptyList(), store.commitments())
            assertEquals(emptyList(), store.workingDays())
            assertEquals(listOf(1, 2), store.snapshot().plans.map { it.revision })
            db.close()
            database = null

            assertEquals(before, dumpExisting(file, schemaThreeTables, version = 3))
            assertEquals(0, countRows(file, "commitments"))
            assertEquals(0, countRows(file, "working_days"))
        }

    // Schema 4 to 5, ADR 0012

    private val schemaFourTables = schemaThreeTables + listOf("commitments", "working_days")

    private fun schemaFourRows(raw: SQLiteDatabase) {
        schemaThreeRows(raw, "'p1', 'Training', 'g1', 'ACTIVE', 11, 21, 'Run it', NULL, 'Two long runs', NULL")
        raw.execSQL("INSERT INTO commitments VALUES ('c1', 'Seminar', 1000, 2000, 'UTC', 'ACTIVE', 30, 31)")
        raw.execSQL("INSERT INTO working_days VALUES ('2026-10-06', 100, NULL, 90, 'a note', NULL)")
    }

    @Test
    fun `schema 4 to 5 keeps every logical record and adds one empty inbox table`() =
        runTest {
            val file = createSchemaDatabase(4, ::schemaFourRows)
            val before = dumpExisting(file, schemaFourTables, version = 4)
            assertEquals(1, before.getValue("commitments").size)
            assertEquals(1, before.getValue("working_days").size)

            val db = plainPlanningDatabase(context, file).also { database = it }
            assertEquals(PlanningDatabase.SCHEMA_VERSION, db.openHelper.writableDatabase.version)
            val store = PlanningStore(db) { 5_000L }
            assertEquals(emptyList(), store.inbox())
            assertEquals(listOf("c1"), store.commitments().map { it.id })
            assertEquals(listOf(1, 2), store.snapshot().plans.map { it.revision })
            db.close()
            database = null

            assertEquals(before, dumpExisting(file, schemaFourTables, version = 4))
            assertEquals(0, countRows(file, "inbox_items"))
        }

    // Schema 5 to 6, ADR 0014

    private val schemaFiveTables = schemaFourTables + "inbox_items"

    private fun schemaFiveRows(raw: SQLiteDatabase) {
        schemaFourRows(raw)
        raw.execSQL("INSERT INTO inbox_items VALUES ('i1', 'Remind me about the form', 'OPEN', NULL, NULL, NULL, 40, 40)")
        raw.execSQL("INSERT INTO inbox_items VALUES ('i2', 'a note', 'NOTE', 'a note', NULL, 60, 41, 60)")
    }

    @Test
    fun `schema 5 to 6 keeps every logical record and adds one empty reminders table`() =
        runTest {
            val file = createSchemaDatabase(5, ::schemaFiveRows)
            val before = dumpExisting(file, schemaFiveTables, version = 5)
            assertEquals(2, before.getValue("inbox_items").size)

            val db = plainPlanningDatabase(context, file).also { database = it }
            assertEquals(6, db.openHelper.writableDatabase.version) // Room validated the migrated schema against 6.json's entities
            val store = PlanningStore(db) { 5_000L }
            assertEquals(emptyList(), store.reminders())
            assertEquals(listOf("i1", "i2"), store.inbox().map { it.value.id })
            assertEquals(listOf("c1"), store.commitments().map { it.id })
            assertEquals(listOf(1, 2), store.snapshot().plans.map { it.revision })
            assertEquals(0, store.snapshot().counts.reminders)
            db.close()
            database = null

            assertEquals(before, dumpExisting(file, schemaFiveTables, version = 5))
            assertEquals(0, countRows(file, "reminders"))
        }
}
