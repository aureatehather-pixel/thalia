package com.thalia.app.voicecheckins.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.voicecheckins.schedule.CheckInConfirmer
import com.thalia.app.voicecheckins.schedule.ConfirmResult
import com.thalia.app.voicecheckins.schedule.FakeAlarmScheduler
import com.thalia.app.voicecheckins.schedule.KUALA_LUMPUR
import com.thalia.app.voicecheckins.schedule.OccurrenceScheduler
import com.thalia.app.voicecheckins.schedule.TestClock
import com.thalia.voicecheckins.domain.CheckInPlan
import com.thalia.voicecheckins.domain.CheckInStatus
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.domain.OccurrenceReason
import com.thalia.voicecheckins.domain.QuietPeriod
import com.thalia.voicecheckins.domain.nextRevisionAt
import com.thalia.voicecheckins.domain.occurrenceOn
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * The version 1 to 2 migration, on a real file built exactly as version 1 built it.
 *
 * The file is made by hand from the exported version 1 schema, with the old `MORNING` and
 * `AFTERNOON` names in it, and then opened by the current database. Nothing here reads the current
 * schema to make the old one, so the test cannot pass by agreeing with itself.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CheckInDatabaseMigrationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val file = "migration-test.db"
    private var open: CheckInDatabase? = null

    @After
    fun cleanUp() {
        open?.close()
        context.deleteDatabase(file)
    }

    /** A version 1 file, as version 1 left it after some use. */
    private fun writeVersion1() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(file), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `occurrences` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`scheduledAtMillis` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                "`reason` TEXT, `firedAtMillis` INTEGER, `resolvedAtMillis` INTEGER, PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_status` ON `occurrences` (`status`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedules` (`type` TEXT NOT NULL, `localTime` TEXT NOT NULL, " +
                "`zoneId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`type`))",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '86ccd797d39387438cfe4467dba3804a')")

        fun occurrence(
            id: String,
            type: String,
            at: Long,
            status: String,
            reason: String? = null,
            fired: Long? = null,
            resolved: Long? = null,
        ) = db.execSQL(
            "INSERT INTO occurrences VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any?>(id, type, at, "Asia/Kuala_Lumpur", status, reason, fired, resolved),
        )
        occurrence("dev-1000", "MORNING", 1_000, "DECLINED", fired = 1_100, resolved = 1_200)
        occurrence("armed-one", "MORNING", 2_000, "SCHEDULED")
        occurrence("armed-two", "AFTERNOON", 3_000, "SCHEDULED")
        occurrence("gone", "AFTERNOON", 4_000, "MISSED", reason = "ALARM_MISSING", resolved = 4_500)
        occurrence("in-call", "MORNING", 5_000, "RINGING", fired = 5_010)
        // A debug alarm due at 10:00 on 21 September 2026, Kuala Lumpur: the same day and type a confirmation would use.
        occurrence("dev-1789955880000", "MORNING", 1_789_956_000_000, "SCHEDULED")

        db.execSQL("INSERT INTO schedules VALUES ('MORNING', '09:30', 'Asia/Kuala_Lumpur', 0)")
        db.execSQL("INSERT INTO schedules VALUES ('AFTERNOON', '17:00', 'Asia/Kuala_Lumpur', 1)")
        db.version = 1
        db.close()
    }

    private fun migrated(): RoomOccurrenceStore {
        val db =
            Room
                .databaseBuilder(context, CheckInDatabase::class.java, file)
                .addMigrations(*CheckInDatabase.MIGRATIONS)
                .build()
        open = db
        return RoomOccurrenceStore(db) { KUALA_LUMPUR }
    }

    @Test
    fun `every occurrence survives with its id, status and moments and only its type renamed`() =
        runTest {
            writeVersion1()
            val store = migrated()

            val dev = assertNotNull(store.get("dev-1000"))
            assertEquals(CheckInType.OPENING, dev.type)
            assertEquals(CheckInStatus.DECLINED, dev.status)
            assertEquals(Instant.ofEpochMilli(1_000), dev.scheduledAt)
            assertEquals(Instant.ofEpochMilli(1_100), dev.firedAt)
            assertEquals(Instant.ofEpochMilli(1_200), dev.resolvedAt)

            assertEquals(CheckInType.OPENING, store.get("armed-one")?.type)
            assertEquals(CheckInType.CLOSING, store.get("armed-two")?.type)
            assertEquals(CheckInStatus.SCHEDULED, store.get("armed-two")?.status)

            val gone = assertNotNull(store.get("gone"))
            assertEquals(CheckInType.CLOSING, gone.type)
            assertEquals(OccurrenceReason.ALARM_MISSING, gone.reason)

            assertEquals(CheckInStatus.RINGING, store.get("in-call")?.status)
            assertEquals(Instant.ofEpochMilli(5_010), store.get("in-call")?.firedAt)
        }

    @Test
    fun `every migrated occurrence becomes revision 1 of a slot named by its own id`() =
        runTest {
            writeVersion1()
            val store = migrated()

            listOf("dev-1000", "armed-one", "armed-two", "gone", "in-call").forEach { id ->
                val o = assertNotNull(store.get(id))
                assertEquals(id, o.slot, id)
                assertEquals(1, o.revision, id)
                assertEquals(listOf(id), store.slot(id).map { it.id })
            }
        }

    @Test
    fun `a migrated scheduled occurrence can be superseded and the history keeps the original`() =
        runTest {
            writeVersion1()
            val store = migrated()
            val old = assertNotNull(store.get("armed-one"))

            assertEquals(true, store.supersede(old.id, old.nextRevisionAt(LocalTime.of(11, 0)), Instant.ofEpochMilli(9_000)))

            assertEquals(listOf("armed-one", "armed-one-r2"), store.slot("armed-one").map { it.id })
            assertEquals(CheckInStatus.CANCELLED, store.get("armed-one")?.status)
            assertEquals(OccurrenceReason.SUPERSEDED, store.get("armed-one")?.reason)
        }

    @Test
    fun `a migrated debug alarm never shares a slot with a confirmed day and is never touched by it`() =
        runTest {
            writeVersion1()
            val store = migrated()
            store.savePlan(CheckInPlan.default(KUALA_LUMPUR)) // the fixture's migrated plan has the opening switched off
            val clock = TestClock(Instant.parse("2026-09-21T01:00:00Z").toEpochMilli())
            val alarms = FakeAlarmScheduler(clock)
            val confirmer = CheckInConfirmer(store, OccurrenceScheduler(store, alarms, clock), clock)
            val day = LocalDate.of(2026, 9, 21)
            val legacy = assertNotNull(store.get("dev-1789955880000"))

            val confirmed = assertIs<ConfirmResult.Confirmed>(confirmer.confirm(CheckInType.OPENING, day)).occurrence

            assertEquals("2026-09-21-opening", confirmed.id)
            assertEquals(listOf(confirmed.id), store.slot("2026-09-21-opening").map { it.id }, "the debug row is not in the day's slot")
            assertEquals(listOf(legacy.id), store.slot(legacy.id).map { it.id })
            assertEquals(legacy.scheduledAt, confirmed.scheduledAt, "same day, type and moment, yet two separate records")

            confirmer.reschedule(CheckInType.OPENING, day, LocalTime.of(11, 0))
            confirmer.cancel(CheckInType.OPENING, day)
            confirmer.confirm(CheckInType.OPENING, day)

            assertEquals(legacy, store.get(legacy.id), "moving, cancelling and re-confirming leave the debug row exactly as it was")
            assertEquals(listOf(1, 2, 3), store.slot("2026-09-21-opening").map { it.revision })
        }

    @Test
    fun `no id a confirmation can produce matches any id an earlier build could have stored`() {
        val earlier = listOf(Regex("""dev-\d+"""), Regex("""\d{4}-\d{2}-\d{2}-(morning|afternoon)"""))
        val plan = CheckInPlan.default(KUALA_LUMPUR)
        val ids =
            generateSequence(LocalDate.of(2026, 1, 1)) { it.plusDays(1) }.take(800).flatMap { day ->
                CheckInType.entries.flatMap { type ->
                    var o = plan[type].occurrenceOn(day)
                    (1..3).map { o.id.also { o = o.nextRevisionAt(LocalTime.NOON) } }
                }
            }

        assertEquals(emptyList(), ids.filter { id -> earlier.any { it.matches(id) } }.toList())
    }

    @Test
    fun `a version 2 file migrates to 3 and keeps every row and its indexes`() =
        runTest {
            val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(file), null)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `occurrences` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`scheduledAtMillis` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`reason` TEXT, `firedAtMillis` INTEGER, `resolvedAtMillis` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_status` ON `occurrences` (`status`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `schedules` (`type` TEXT NOT NULL, `localTime` TEXT NOT NULL, " +
                    "`zoneId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`type`))",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '86ccd797d39387438cfe4467dba3804a')")
            val kl = "'Asia/Kuala_Lumpur'"
            db.execSQL("INSERT INTO occurrences VALUES ('2026-09-21-opening', 'OPENING', 1000, $kl, 'SCHEDULED', NULL, NULL, NULL)")
            db.execSQL("INSERT INTO occurrences VALUES ('2026-09-21-closing', 'CLOSING', 2000, $kl, 'MISSED', 'ALARM_MISSING', NULL, 2500)")
            db.execSQL("INSERT INTO schedules VALUES ('OPENING', '10:00', 'Asia/Kuala_Lumpur', 1)")
            db.execSQL("INSERT INTO schedules VALUES ('CLOSING', '15:00', 'Asia/Kuala_Lumpur', 1)")
            db.version = 2
            db.close()

            val store = migrated()

            val opening = assertNotNull(store.get("2026-09-21-opening"))
            assertEquals("2026-09-21-opening", opening.slot)
            assertEquals(1, opening.revision)
            assertEquals(CheckInStatus.SCHEDULED, opening.status)
            assertEquals(OccurrenceReason.ALARM_MISSING, store.get("2026-09-21-closing")?.reason)
            assertEquals(LocalTime.of(15, 0), store.plan().closing.localTime)

            val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READONLY)
            val indexes =
                "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'occurrences' " +
                    "AND name LIKE 'index_%' ORDER BY name"
            raw.rawQuery(indexes, null).use { c ->
                val names = generateSequence { if (c.moveToNext()) c.getString(0) else null }.toList()
                assertEquals(listOf("index_occurrences_slot", "index_occurrences_status"), names)
            }
            raw.close()
        }

    @Test
    fun `the open occurrences are exactly the ones that were open`() =
        runTest {
            writeVersion1()
            val store = migrated()

            assertEquals(listOf("armed-one", "armed-two", "in-call", "dev-1789955880000"), store.open().map { it.id })
        }

    @Test
    fun `the stored plan keeps its times and switches under the new names`() =
        runTest {
            writeVersion1()
            val plan = migrated().plan()

            assertEquals(LocalTime.of(9, 30), plan.opening.localTime)
            assertEquals(false, plan.opening.enabled)
            assertEquals(LocalTime.of(17, 0), plan.closing.localTime)
            assertEquals(true, plan.closing.enabled)
        }

    @Test
    fun `no default row is added over the migrated plan`() =
        runTest {
            writeVersion1()
            val store = migrated()
            store.plan()

            val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READONLY)
            raw.rawQuery("SELECT type FROM schedules ORDER BY type", null).use { cursor ->
                val types = generateSequence { if (cursor.moveToNext()) cursor.getString(0) else null }.toList()
                assertEquals(listOf("CLOSING", "OPENING"), types)
            }
            raw.close()
        }

    @Test
    fun `migrating an empty version 1 file works`() =
        runTest {
            val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(file), null)
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `occurrences` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                    "`scheduledAtMillis` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `status` TEXT NOT NULL, " +
                    "`reason` TEXT, `firedAtMillis` INTEGER, `resolvedAtMillis` INTEGER, PRIMARY KEY(`id`))",
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_status` ON `occurrences` (`status`)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `schedules` (`type` TEXT NOT NULL, `localTime` TEXT NOT NULL, " +
                    "`zoneId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`type`))",
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '86ccd797d39387438cfe4467dba3804a')")
            db.version = 1
            db.close()

            val store = migrated()

            assertEquals(emptyList(), store.open())
            assertEquals(LocalTime.of(10, 0), store.plan().opening.localTime)
        }

    @Test
    fun `opening a version 1 file without the migration refuses and leaves every row as it was`() {
        writeVersion1()
        val db = Room.databaseBuilder(context, CheckInDatabase::class.java, file).build()
        open = db

        assertFailsWith<IllegalStateException> { db.openHelper.writableDatabase }
        db.close()
        open = null

        val raw = SQLiteDatabase.openDatabase(context.getDatabasePath(file).path, null, SQLiteDatabase.OPEN_READONLY)
        assertEquals(1, raw.version)
        raw.rawQuery("SELECT COUNT(*) FROM occurrences WHERE type IN ('MORNING', 'AFTERNOON')", null).use {
            it.moveToFirst()
            assertEquals(6, it.getInt(0))
        }
        raw.close()
    }

    /** A version 3 file, as the build now on the phone leaves it: slots, revisions, a confirmed day and an armed alarm. */
    private fun writeVersion3() {
        val db = SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(file), null)
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `occurrences` (`id` TEXT NOT NULL, `type` TEXT NOT NULL, " +
                "`scheduledAtMillis` INTEGER NOT NULL, `zoneId` TEXT NOT NULL, `status` TEXT NOT NULL, `reason` TEXT, " +
                "`firedAtMillis` INTEGER, `resolvedAtMillis` INTEGER, `slot` TEXT NOT NULL, `revision` INTEGER NOT NULL, " +
                "PRIMARY KEY(`id`))",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_status` ON `occurrences` (`status`)")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_occurrences_slot` ON `occurrences` (`slot`)")
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `schedules` (`type` TEXT NOT NULL, `localTime` TEXT NOT NULL, " +
                "`zoneId` TEXT NOT NULL, `enabled` INTEGER NOT NULL, PRIMARY KEY(`type`))",
        )
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'b4e979d6bf07dc543b472d9b1d8f8dbc')")
        db.execSQL(
            "INSERT INTO occurrences VALUES ('2026-09-30-opening', 'OPENING', 1790760360000, 'Asia/Kuala_Lumpur', " +
                "'CANCELLED', 'SUPERSEDED', NULL, 1790759873361, '2026-09-30-opening', 1)",
        )
        db.execSQL(
            "INSERT INTO occurrences VALUES ('2026-10-01-opening-r5', 'OPENING', 1790820000000, 'Asia/Kuala_Lumpur', " +
                "'SCHEDULED', NULL, NULL, NULL, '2026-10-01-opening', 5)",
        )
        db.execSQL("INSERT INTO schedules VALUES ('OPENING', '10:00', 'Asia/Kuala_Lumpur', 1)")
        db.execSQL("INSERT INTO schedules VALUES ('CLOSING', '15:00', 'Asia/Kuala_Lumpur', 1)")
        db.version = 3
        db.close()
    }

    @Test
    fun `version 3 to 4 keeps every occurrence and the plan, and starts the quiet period switched off`() =
        runTest {
            writeVersion3()
            val store = migrated()

            val armed = assertNotNull(store.get("2026-10-01-opening-r5"))
            assertEquals(CheckInStatus.SCHEDULED, armed.status)
            assertEquals(5, armed.revision)
            assertEquals(Instant.ofEpochMilli(1_790_820_000_000), armed.scheduledAt)
            assertEquals(OccurrenceReason.SUPERSEDED, store.get("2026-09-30-opening")?.reason)
            assertEquals(LocalTime.of(10, 0), store.plan().opening.localTime)

            assertEquals(QuietPeriod.DEFAULT, store.quietPeriod())
            assertFalse(store.quietPeriod().enabled, "the upgrade changes no behaviour until the user turns it on")
        }

    @Test
    fun `after version 4 a suppression and a quiet period are stored and read back`() =
        runTest {
            writeVersion3()
            val store = migrated()
            val night = QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = true)

            store.saveQuietPeriod(night)
            val suppressed =
                store.transition(
                    "2026-10-01-opening-r5",
                    CheckInStatus.SCHEDULED,
                    CheckInStatus.SUPPRESSED,
                    Instant.ofEpochMilli(1_790_820_000_000),
                    OccurrenceReason.QUIET_PERIOD,
                )

            assertEquals(true, suppressed)
            assertEquals(night, store.quietPeriod())
            val record = assertNotNull(store.get("2026-10-01-opening-r5"))
            assertEquals(CheckInStatus.SUPPRESSED, record.status)
            assertEquals(OccurrenceReason.QUIET_PERIOD, record.reason)
        }
}
