package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Direction
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADR 0009 decision 7's open table, on Robolectric's plain SQLite with [FakeSealer]. Every failure
 * must leave the files byte for byte as they were: nothing is created, deleted or rewritten.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningDatabaseOpenerTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val files: PlanningFiles get() = PlanningFiles.of(context, "planning-opener-test")
    private val name = "planning-opener-test.db"
    private val databaseFile: File get() = files.database
    private val keyFile: File get() = files.key
    private val newKeyFile: File get() = files.newKey
    private val sealer = FakeSealer()
    private val opened = mutableListOf<PlanningDatabase>()
    private val passphrases = mutableListOf<ByteArray>()

    private var buildFailure: Throwable? = null

    private fun opener() =
        PlanningDatabaseOpener(files, sealer, build = { file, passphrase ->
            buildFailure?.let { throw it }
            passphrases += passphrase
            plainPlanningDatabase(context, file).also { opened += it }
        }, replacement = PlanningReplacement(files, syncDirectory = {}))

    private fun closeAll() = opened.forEach { it.close() }

    @Before
    fun clearLog() = ShadowLog.clear()

    @After
    fun cleanUp() {
        closeAll()
        context.deleteDatabase(name)
        keyFile.delete()
        newKeyFile.delete()
    }

    /** A planning database with one saved direction, closed, as a previous run would leave it. */
    private suspend fun createWithData() {
        val result = assertIs<PlanningOpenResult.Opened>(opener().open())
        PlanningStore(result.database).saveDirection(Direction("Stay curious"))
        closeAll()
    }

    private val sidecars: List<File> get() = listOf("-wal", "-shm", "-journal").map { File(databaseFile.path + it) }

    /** Every planning file, including the WAL, SHM and journal sidecars, by name: its bytes, or null if absent. */
    private fun snapshot(): Map<String, ByteArray?> =
        (listOf(databaseFile, keyFile, newKeyFile) + sidecars).associate { it.name to it.takeIf(File::exists)?.readBytes() }

    private fun assertUnchanged(before: Map<String, ByteArray?>) {
        val after = snapshot()
        assertEquals(before.keys, after.keys)
        before.forEach { (file, bytes) ->
            if (bytes == null) assertFalse(after[file] != null, "$file was created") else assertContentEquals(bytes, after[file], file)
        }
    }

    @Test
    fun `first use creates the database and seals a fresh 32-byte passphrase, leaving no staging key`() {
        val result = opener().open()

        assertIs<PlanningOpenResult.Opened>(result)
        assertTrue(databaseFile.exists())
        assertTrue(keyFile.exists())
        assertFalse(newKeyFile.exists())
        assertEquals(32, passphrases.single().size)
        assertContentEquals(passphrases.single(), sealer.open(keyFile.readBytes()))
    }

    @Test
    fun `a second open uses the stored passphrase and finds the data`() =
        runTest {
            createWithData()

            val result = assertIs<PlanningOpenResult.Opened>(opener().open())

            assertContentEquals(passphrases[0], passphrases[1])
            assertEquals(Direction("Stay curious"), PlanningStore(result.database).direction())
        }

    @Test
    fun `a missing key is reported, and no new key or database is made`() =
        runTest {
            createWithData()
            keyFile.delete()
            val before = snapshot()

            val result = opener().open()

            assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.KEY_UNAVAILABLE), result)
            assertUnchanged(before)
        }

    @Test
    fun `a key the Keystore cannot open is reported, and the files are untouched`() =
        runTest {
            createWithData()
            sealer.refuseToOpen = true
            val before = snapshot()

            val result = opener().open()

            assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.KEY_UNAVAILABLE), result)
            assertUnchanged(before)
        }

    @Test
    fun `a missing database with its key present is reported, and not silently recreated`() =
        runTest {
            createWithData()
            context.deleteDatabase(name)
            val before = snapshot()

            val result = opener().open()

            assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.DATABASE_MISSING), result)
            assertUnchanged(before)
            assertTrue(passphrases.size == 1, "no database was built")
        }

    @Test
    fun `a crash between creating the database and renaming the key is finished on the next open`() =
        runTest {
            createWithData()
            assertTrue(keyFile.renameTo(newKeyFile))

            val result = assertIs<PlanningOpenResult.Opened>(opener().open())

            assertTrue(keyFile.exists())
            assertFalse(newKeyFile.exists())
            assertEquals(Direction("Stay curious"), PlanningStore(result.database).direction())
        }

    @Test
    fun `an unreadable database is reported, and the corruption handler does not delete it`() {
        databaseFile.parentFile?.mkdirs()
        databaseFile.writeBytes(ByteArray(8192) { (it * 31).toByte() })
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(sealer.seal(ByteArray(32) { 7 }))
        val before = snapshot()

        val result = opener().open()

        assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.DATABASE_UNREADABLE), result)
        assertUnchanged(before)
    }

    @Test
    fun `a database from a newer schema is reported as a failed migration, and kept`() {
        databaseFile.parentFile?.mkdirs()
        // In WAL mode, as Room makes every planning.db. A rollback-journal file would have two header
        // bytes rewritten by Room switching it to WAL before the migration check; only Room makes this file.
        SQLiteDatabase.openOrCreateDatabase(databaseFile, null).use { db ->
            db.enableWriteAheadLogging()
            db.execSQL("CREATE TABLE kept (value TEXT)")
            db.execSQL("INSERT INTO kept VALUES ('from a newer Thalia')")
            db.version = 99
        }
        keyFile.parentFile?.mkdirs()
        keyFile.writeBytes(sealer.seal(ByteArray(32) { 7 }))
        val before = snapshot()

        val result = opener().open()

        assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.MIGRATION_FAILED), result)
        assertUnchanged(before)
    }

    @Test
    fun `a failure is logged by its class only`() =
        runTest {
            createWithData()
            keyFile.delete()

            opener().open()

            val logged = ShadowLog.getLogsForTag("ThaliaPlanning").map { it.msg }
            assertEquals(listOf("planning data not opened: KEY_UNAVAILABLE"), logged)
        }

    @Test
    fun `a leftover WAL, SHM or journal without its database is reported as missing, and nothing is created`() {
        for (sidecar in sidecars) {
            sidecar.parentFile?.mkdirs()
            sidecar.writeBytes(byteArrayOf(1, 2, 3))
            val before = snapshot()

            val result = opener().open()

            assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.DATABASE_MISSING), result, sidecar.name)
            assertUnchanged(before)
            sidecar.delete()
        }
        assertTrue(passphrases.isEmpty(), "no database was built")
    }

    @Test
    fun `a staging key left by a crash before the database existed is replaced on first use`() {
        newKeyFile.parentFile?.mkdirs()
        newKeyFile.writeBytes(sealer.seal(ByteArray(32) { 9 }))

        assertIs<PlanningOpenResult.Opened>(opener().open())

        assertFalse(newKeyFile.exists())
        assertContentEquals(passphrases.single(), sealer.open(keyFile.readBytes()))
        assertFalse(passphrases.single().contentEquals(ByteArray(32) { 9 }), "a fresh passphrase, not the leftover one")
    }

    @Test
    fun `a Keystore that refuses to seal at first use is reported, and no file is created`() {
        sealer.refuseToSeal = true
        val before = snapshot()

        val result = opener().open()

        assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.STORAGE_UNAVAILABLE), result)
        assertUnchanged(before)
    }

    @Test
    fun `an encryption library that fails to load is reported, and the files are untouched`() =
        runTest {
            createWithData()
            buildFailure = UnsatisfiedLinkError("no sqlcipher")
            val before = snapshot()

            val result = opener().open()

            assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.STORAGE_UNAVAILABLE), result)
            assertUnchanged(before)
        }
}
