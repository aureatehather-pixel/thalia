package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.PlanningAccess
import com.thalia.planning.Action
import com.thalia.planning.Direction
import com.thalia.planning.DirectionRecord
import com.thalia.planning.Goal
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningSnapshot
import com.thalia.planning.Project
import com.thalia.planning.Stamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * ADR 0009 decision 8's crash-safe replacement and "set aside and start fresh", on Robolectric's plain
 * SQLite with [FakeSealer]. Process death is simulated by stopping the sequence at each durable step,
 * with no cleanup running, and then opening again as a new process would, which runs recovery.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningReplacementTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    // Short: Robolectric names each test's data directory after the test, and native SQLite on
    // Windows cannot open a path over 260 characters.
    private val files: PlanningFiles get() = PlanningFiles.of(context, "rt")
    private val sealer = FakeSealer()

    private class Stop(
        val at: String,
    ) : RuntimeException(at)

    private val opened = mutableListOf<PlanningDatabase>()

    @After
    fun closeAndCleanUp() {
        opened.forEach(PlanningDatabase::close)
        cleanUp()
    }

    private fun cleanUp() {
        (files.live + files.staged + listOf(files.journal)).forEach { it.delete() }
        files.setAsideRoot.deleteRecursively()
    }

    /** A replacement that throws at its [stopAt]th checkpoint, recording each checkpoint it reaches. */
    private fun stopping(
        stopAt: Int,
        reached: MutableList<String> = mutableListOf(),
        synced: MutableList<File> = mutableListOf(),
    ) = PlanningReplacement(files, syncDirectory = { synced += it.canonicalFile }, checkpoint = { name ->
        if (reached.size == stopAt) throw Stop(name)
        reached += name
    })

    private fun opener(replacement: PlanningReplacement = PlanningReplacement(files, syncDirectory = {})) =
        plainOpener(context, files, sealer, opened, replacement).first

    /** Opens [target] as a new process would, which first recovers, and reads every row. */
    private fun read(target: PlanningFiles = files): PlanningSnapshot {
        val opener = plainOpener(context, target, sealer, opened).first
        val database = opener.open().let { assertIs<PlanningOpenResult.Opened>(it, "$it") }.database
        return try {
            runBlocking { PlanningStore(database).snapshot() }
        } finally {
            database.close()
        }
    }

    private fun fresh(snapshot: PlanningSnapshot) {
        cleanUp()
        val database = opener().open().let { assertIs<PlanningOpenResult.Opened>(it, "$it") }.database
        try {
            assertTrue(runBlocking { PlanningStore(database).restoreIntoEmpty(snapshot) })
        } finally {
            database.close()
        }
    }

    /**
     * Stages [snapshot] and swaps it in through [replacement], as [PlanningAccess.replace] does, without
     * its cleanup. With [keptSidecars], empty `-wal` and `-shm` files stay beside the staged database,
     * as a SQLite build that keeps them after closing would leave them.
     */
    private fun replaceWith(
        snapshot: PlanningSnapshot,
        replacement: PlanningReplacement,
        keptSidecars: Boolean = false,
    ) {
        val staged = opener(replacement).createStaged()
        assertTrue(runBlocking { PlanningStore(staged).restoreIntoEmpty(snapshot) })
        replacement.sealStaged(staged)
        if (keptSidecars) files.restoreSidecars.take(2).forEach { it.writeBytes(ByteArray(0)) }
        replacement.replaceWithStaged()
    }

    private fun folders(suffix: String): List<File> =
        files.setAsideRoot
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(suffix) }

    @Test
    fun `a stopped replacement recovers old or new data`() = stoppedReplacement(keptSidecars = false)

    @Test
    fun `a stopped replacement with kept sidecars recovers too`() = stoppedReplacement(keptSidecars = true)

    private fun stoppedReplacement(keptSidecars: Boolean) {
        val reachedInFull = mutableListOf<String>()
        fresh(sampleA)
        replaceWith(sampleB, stopping(Int.MAX_VALUE, reachedInFull), keptSidecars)
        assertEquals("staged", reachedInFull.first())
        assertEquals("committed", reachedInFull[1])
        assertEquals("finished", reachedInFull.last())
        assertEquals(keptSidecars, "moved ${files.restoreSidecars[0].name}" in reachedInFull)

        for (step in reachedInFull.indices) {
            fresh(sampleA)
            val liveBefore =
                files.live
                    .filter(File::exists)
                    .map(File::getName)
                    .toSet()
            val stop = assertFailsWith<Stop> { replaceWith(sampleB, stopping(step), keptSidecars) }

            val after = read()

            if (stop.at == "staged") {
                assertEquals(sampleA, after, "stopped at ${stop.at}: the live data is untouched before the commit point")
                assertTrue(folders("-staged").isNotEmpty(), "the staged files are set aside, not deleted")
                // The staged database and its key keep their names, which are the layout of a "<name>-restore" store.
                val staged = folders("-staged").single()
                assertEquals(
                    sampleB,
                    read(PlanningFiles(staged, staged, staged, "${files.name}-restore")),
                    "the staged data is kept and opens",
                )
            } else {
                assertEquals(sampleB, after, "stopped at ${stop.at}: the replacement is finished")
                val folder = folders("-replaced").single()
                assertEquals(liveBefore, folder.list()!!.toSet(), "stopped at ${stop.at}: the folder holds exactly the old live files")
                assertEquals(sampleA, read(files.inside(folder)), "stopped at ${stop.at}: the old data is kept and opens")
            }
            assertFalse(files.journal.exists(), "stopped at ${stop.at}: the journal is gone after recovery")
            assertTrue(files.staged.none(File::exists), "stopped at ${stop.at}: nothing is left staged")
        }
    }

    @Test
    fun `replacement syncs its directories and keeps the old data openable`() {
        fresh(sampleA)
        val synced = mutableListOf<File>()
        replaceWith(sampleB, stopping(Int.MAX_VALUE, synced = synced))

        assertEquals(sampleB, read())
        val folder = folders("-replaced").single()
        assertEquals(sampleA, read(files.inside(folder)))
        assertTrue(files.databaseDir.canonicalFile in synced)
        assertTrue(files.keyDir.canonicalFile in synced)
        assertTrue(folder.canonicalFile in synced)
    }

    @Test
    fun `set-aside stopped at any step recovers empty, old data kept`() {
        val reachedInFull = mutableListOf<String>()
        fresh(sampleA)
        stopping(Int.MAX_VALUE, reachedInFull).setAside()

        for (step in reachedInFull.indices) {
            fresh(sampleA)
            val stop = assertFailsWith<Stop> { stopping(step).setAside() }

            assertEquals(PlanningSnapshot.EMPTY, read(), "stopped at ${stop.at}")
            assertEquals(sampleA, read(files.inside(folders("-set-aside").single())), "stopped at ${stop.at}")
            assertFalse(files.journal.exists())
        }
    }

    @Test
    fun `every live file moves into the folder byte-identical`() {
        fresh(sampleA)
        val sidecars = files.databaseSidecars + files.newKey
        sidecars.forEachIndexed { i, file -> file.writeBytes(byteArrayOf(i.toByte(), 42)) }
        val before = files.live.associate { it.name to it.readBytes() }

        val folder = PlanningReplacement(files, syncDirectory = {}).setAside()

        files.live.forEach { assertFalse(it.exists(), "${it.name} left in place") }
        before.forEach { (name, bytes) -> assertContentEquals(bytes, File(folder, name).readBytes(), name) }
    }

    @Test
    fun `staged files without a journal are set aside, live data untouched`() {
        fresh(sampleA)
        files.restoreDatabase.writeBytes(byteArrayOf(1, 2, 3))
        files.restoreKey.writeBytes(byteArrayOf(4, 5))

        assertEquals(sampleA, read())

        val folder = folders("-staged").single()
        assertContentEquals(byteArrayOf(1, 2, 3), File(folder, files.restoreDatabase.name).readBytes())
        assertContentEquals(byteArrayOf(4, 5), File(folder, files.restoreKey.name).readBytes())
    }

    @Test
    fun `a failure while staging replaces nothing and keeps the staged files`() =
        runBlocking {
            fresh(sampleA)
            val replacement = PlanningReplacement(files, syncDirectory = {})
            val access = PlanningAccess(opener(replacement), replacement, Dispatchers.Unconfined)
            // An action under a project that is not there: the foreign key refuses it midway through the insert.
            val broken =
                sampleB.copy(
                    actions = listOf(Stamped(Action(UUID.randomUUID().toString(), "Orphan", UUID.randomUUID().toString()), 1, 1)),
                )

            assertFailsWith<Exception> { access.replace(broken) }

            assertEquals(sampleA, read())
            assertTrue(folders("-replaced").isEmpty())
            assertTrue(folders("-staged").isNotEmpty())
            assertTrue(files.staged.none(File::exists))
        }

    @Test
    fun `access reopens the data after replace and set-aside`() =
        runBlocking {
            fresh(sampleA)
            val replacement = PlanningReplacement(files, syncDirectory = {})
            val access = PlanningAccess(opener(replacement), replacement, Dispatchers.Unconfined)
            assertIs<PlanningAccess.State.Ready>(access.state())

            access.replace(sampleB)
            assertEquals(sampleB, access.snapshot())

            access.setAsideAndStartFresh()
            assertEquals(PlanningSnapshot.EMPTY, access.snapshot())
            assertEquals(sampleB, read(files.inside(folders("-set-aside").single())))
        }

    @Test
    fun `recovery stopped partway is finished by the next recovery`() {
        fresh(sampleA)
        val liveBefore =
            files.live
                .filter(File::exists)
                .map(File::getName)
                .toSet()
        val reached = mutableListOf<String>()
        replaceWith(sampleB, stopping(Int.MAX_VALUE, reached))
        cleanUp()
        fresh(sampleA)
        // Stop the replacement at its first rename, then stop recovery at its own first rename.
        assertFailsWith<Stop> { replaceWith(sampleB, stopping(reached.indexOfFirst { it.startsWith("moved") })) }
        assertTrue(files.journal.exists())
        assertFailsWith<Stop> { stopping(0).recover() }
        assertTrue(files.journal.exists(), "an interrupted recovery leaves the journal")

        assertEquals(sampleB, read())
        val folder = folders("-replaced").single()
        assertEquals(liveBefore, folder.list()!!.toSet())
        assertEquals(sampleA, read(files.inside(folder)))
    }

    @Test
    fun `a new set-aside refuses to run over an unrecovered journal`() {
        fresh(sampleA)
        val journal = "thalia-planning-replace-journal 1\n"
        files.journal.writeText(journal)

        assertFailsWith<java.io.IOException> { PlanningReplacement(files, syncDirectory = {}).setAside() }

        assertEquals(journal, files.journal.readText())
        assertTrue(files.database.exists())
    }

    /** Records file syncs, directory syncs (with whether the journal existed then) and checkpoints, in order. */
    private fun recording(
        events: MutableList<String>,
        failOnce: ((File) -> Boolean)? = null,
    ): PlanningReplacement {
        var failed = false

        fun label(dir: File): String =
            when (dir.canonicalFile) {
                files.databaseDir.canonicalFile -> "databases"
                files.keyDir.canonicalFile -> "keys"
                files.setAsideRoot.canonicalFile -> "set-aside-root"
                files.root.canonicalFile -> "root"
                else -> if (dir.canonicalFile.parentFile == files.setAsideRoot.canonicalFile) "folder" else dir.name
            }
        return PlanningReplacement(
            files,
            syncDirectory = { dir ->
                if (!failed && failOnce?.invoke(dir) == true) {
                    failed = true
                    throw java.io.IOException("simulated sync failure")
                }
                events += "dir ${label(dir)} journal=${files.journal.exists()}"
            },
            syncFile = { events += "file ${it.name}" },
            checkpoint = { events += "step $it" },
        )
    }

    @Test
    fun `a replacement syncs in durable order`() {
        fresh(sampleA)
        val events = mutableListOf<String>()
        replaceWith(sampleB, recording(events))

        fun at(event: String) = events.indexOf(event).also { assertTrue(it >= 0, "missing: $event in $events") }
        val staged = at("step staged")
        val committed = at("step committed")
        val swapped = at("step swapped")
        val finished = at("step finished")
        val lastMove = events.indexOfLast { it.startsWith("step moved") }

        // Stage: the staged database and key, then their directories, before the journal exists.
        assertTrue(at("file ${files.restoreDatabase.name}") < staged)
        assertTrue(at("file ${files.restoreKey.name}") < staged)
        assertTrue(at("dir databases journal=false") < staged)
        assertTrue(at("dir keys journal=false") < staged)
        // Commit: the folder's entry, then the journal's directory after the journal exists.
        assertTrue(at("dir set-aside-root journal=false") < committed)
        assertTrue(
            events.subList(staged, committed).contains("dir keys journal=true"),
            "the journal's directory is synced before the commit point",
        )
        // Swap: every directory a rename touched is synced while the journal still exists.
        for (dir in listOf("databases", "keys", "folder")) {
            assertTrue(events.subList(lastMove, swapped).contains("dir $dir journal=true"), "$dir synced before the journal goes: $events")
        }
        // Finish: the journal's directory is synced after the journal is deleted.
        assertTrue(events.subList(swapped, finished).contains("dir keys journal=false"))
        assertEquals(sampleB, read())
    }

    @Test
    fun `recovery that must recreate the folder syncs its parents before moving into it`() {
        fresh(sampleA)
        val liveBefore =
            files.live
                .filter(File::exists)
                .map(File::getName)
                .toSet()
        val reached = mutableListOf<String>()
        assertFailsWith<Stop> { replaceWith(sampleB, stopping(1, reached)) }
        assertEquals(listOf("staged"), reached, "stopped just after the commit point")
        // The empty folder's entry was lost, as an unsynced directory entry could be after a power cut.
        assertTrue(folders("-replaced").single().delete())

        val events = mutableListOf<String>()
        recording(events).recover()

        val firstMove = events.indexOfFirst { it.startsWith("step moved") }
        assertTrue(
            events
                .subList(
                    0,
                    firstMove,
                ).containsAll(listOf("dir set-aside-root journal=true", "dir keys journal=true", "dir root journal=true")),
            "$events",
        )
        assertEquals(sampleB, read())
        assertEquals(liveBefore, folders("-replaced").single().list()!!.toSet())
    }

    @Test
    fun `a failure after the commit point is finished by the reopen in the same process`() =
        runBlocking {
            fresh(sampleA)
            val events = mutableListOf<String>()
            // The first directory sync after the journal exists fails, as an I/O error would.
            val replacement = recording(events) { files.journal.exists() }
            val access = PlanningAccess(opener(replacement), replacement, Dispatchers.Unconfined)

            assertFailsWith<java.io.IOException> { access.replace(sampleB) }

            assertEquals(sampleB, access.snapshot(), "the reopen ran recovery and finished the replacement")
            assertFalse(files.journal.exists())
            assertEquals(sampleA, read(files.inside(folders("-replaced").single())))
        }

    @Test
    fun `an unreadable journal stops the open and moves nothing`() {
        fresh(sampleA)
        files.journal.writeText("not a journal\n")
        val before = files.live.filter(File::exists).associate { it.name to it.readBytes() }

        assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.STORAGE_UNAVAILABLE), opener().open())
        files.live.filter(File::exists).forEach { assertContentEquals(before[it.name], it.readBytes(), it.name) }
        assertTrue(files.journal.exists(), "the journal is kept for inspection")
    }

    @Test
    fun `a journal path that leaves the app's data is refused`() {
        fresh(sampleA)
        files.journal.writeText("thalia-planning-replace-journal 1\nmove\tdatabases/${files.database.name}\t../outside.db\n")

        assertEquals(PlanningOpenResult.Failed(PlanningOpenProblem.STORAGE_UNAVAILABLE), opener().open())
        assertTrue(files.database.exists())
    }

    private companion object {
        val goal = UUID.randomUUID().toString()
        val project = UUID.randomUUID().toString()

        val sampleA =
            PlanningSnapshot(
                direction = DirectionRecord(Direction("The old direction"), 1),
                settings = PlanningSettings(),
                goals = listOf(Stamped(Goal(goal, "Old goal", "Old success"), 2, 3)),
                projects = listOf(Stamped(Project(project, "Old project", goal), 4, 5)),
                actions = listOf(Stamped(Action(UUID.randomUUID().toString(), "Old action", project), 6, 7)),
            )

        val sampleB =
            PlanningSnapshot(
                direction = DirectionRecord(Direction("The new direction"), 8),
                settings = null,
                goals = emptyList(),
                projects = emptyList(),
                actions = listOf(Stamped(Action(UUID.randomUUID().toString(), "New action"), 9, 10)),
            )
    }
}
