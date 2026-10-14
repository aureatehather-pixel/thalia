package com.thalia.app.planning.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Replaces live planning data, or sets it aside, in steps that each survive the process dying.
 * ADR 0009 decision 8, "crash-safe replacement".
 *
 * 1. **Stage** (the caller): restore into [PlanningFiles.restoreDatabase] with its own passphrase,
 *    then [sealStaged] checkpoints every page into the file, closes it and syncs it.
 * 2. **Commit point:** the journal, written atomically and synced, lists every rename, and only
 *    renames whose source exists at that moment. Until it exists the live files are untouched;
 *    staged files left without a journal are set aside on the next [recover], never deleted.
 * 3. **Swap:** with every planning connection closed, the live database, its sidecars and its key
 *    move into a new dated folder, then the staged database, any sidecar SQLite kept beside it,
 *    and its key move into the live names. A WAL always moves with its database.
 * 4. **Finish:** the journal, which holds no user data, is deleted. It is the only file ever deleted.
 * 5. **Recovery:** [recover], run before every open, repeats the journal's renames. Every listed
 *    source existed at the commit point, and every destination was free then or freed by an
 *    earlier rename, so a rename whose destination exists was done and one whose source is gone
 *    was done too. Both are skipped, so repeating any prefix of the sequence is safe.
 *
 * "Set aside and start fresh" is the same sequence without a staged database: the live files move
 * into the folder, and the next open creates an empty database.
 *
 * Durable ordering, which an exception or a simulated stop cannot show, only the order of syncs can:
 * the staged files and their directories are synced before the journal exists; the journal's
 * contents are synced before its rename, and its directory after; every directory a rename touched is
 * synced before the journal is deleted; and the journal's directory is synced after that. A folder
 * that recovery has to create again has its parents synced before anything is moved into it.
 *
 * [checkpoint] is called after each durable step. Tests make it throw to stop the sequence there, as
 * if the process had died, and then run [recover].
 */
internal class PlanningReplacement(
    private val files: PlanningFiles,
    private val syncDirectory: (File) -> Unit = PlanningFiles::syncDirectory,
    private val syncFile: (File) -> Unit = PlanningFiles::syncFile,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now(ZoneId.systemDefault()) },
    private val checkpoint: (String) -> Unit = {},
) {
    private data class Move(
        val from: File,
        val to: File,
    )

    /** Finishes an interrupted replacement or set-aside, then sets aside any staged files left without one. */
    fun recover() {
        if (files.journal.exists()) {
            val moves = readJournal()
            moves.map { it.to.parentFile!! }.distinct().filterNot(File::exists).forEach { folder ->
                folder.mkdirs()
                // Its parents up to the app's data directory, which hold the entries mkdirs just made.
                val root = files.root.canonicalFile
                generateSequence(folder.canonicalFile.parentFile) { it.parentFile }
                    .takeWhile { it == root || it.path.startsWith(root.path + File.separator) }
                    .forEach(syncDirectory)
            }
            runMoves(moves)
            finish()
        }
        if (files.staged.any(File::exists)) {
            commitAndRun(folderFor("staged"), files.staged)
        }
    }

    /** Moves the live files into a new dated folder, so the next open starts empty. Returns the folder. */
    fun setAside(): File = folderFor("set-aside").also { commitAndRun(it, files.live) }

    /** Moves the live files into a new dated folder and the staged files into their place. Returns the folder. */
    fun replaceWithStaged(): File {
        if (!files.restoreDatabase.exists() || !files.restoreKey.exists()) throw IOException("nothing is staged")
        val folder = folderFor("replaced")
        val stagedIntoLive =
            listOf(Move(files.restoreDatabase, files.database)) +
                files.restoreSidecars.zip(files.databaseSidecars, ::Move) +
                Move(files.restoreKey, files.key)
        commitAndRun(folder, files.live, stagedIntoLive)
        return folder
    }

    /**
     * Checkpoints every page of the staged database into its file and closes it, then syncs it, any
     * sidecar SQLite kept, its key and their directories. Closes [database].
     */
    fun sealStaged(database: PlanningDatabase) {
        try {
            database.openHelper.writableDatabase
                .query("PRAGMA wal_checkpoint(TRUNCATE)")
                .use { it.moveToFirst() }
        } finally {
            database.close()
        }
        files.staged.filter(File::exists).forEach(syncFile)
        syncDirectory(files.databaseDir)
        syncDirectory(files.keyDir)
        checkpoint("staged")
    }

    private fun commitAndRun(
        folder: File,
        setAside: List<File>,
        then: List<Move> = emptyList(),
    ) {
        // Recovery must run first: a journal here belongs to an operation that has not finished.
        if (files.journal.exists()) throw IOException("an earlier replacement has not been recovered")
        val moves = (setAside.map { Move(it, File(folder, it.name)) } + then).filter { it.from.exists() }
        // Strict before the commit point: every destination is free, or freed by an earlier rename in the list.
        // Only recovery may skip a rename whose destination exists, because only then was it already done.
        moves.forEachIndexed { i, move ->
            if (move.to.exists() &&
                moves.take(i).none { it.from == move.to }
            ) {
                throw IOException("a destination is not free: ${move.to.name}")
            }
        }
        folder.mkdirs()
        syncDirectory(files.setAsideRoot)
        syncDirectory(files.keyDir)
        PlanningFiles.writeSynced(files.journal, journalText(moves).toByteArray(Charsets.UTF_8))
        syncDirectory(files.keyDir)
        checkpoint("committed")
        runMoves(moves)
        finish()
    }

    private fun runMoves(moves: List<Move>) {
        moves.forEach { (from, to) ->
            if (from.exists() && !to.exists()) {
                Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
                checkpoint("moved ${from.name}")
            }
        }
        moves.flatMap { listOf(it.from.parentFile!!, it.to.parentFile!!) }.distinct().forEach(syncDirectory)
        checkpoint("swapped")
    }

    private fun finish() {
        if (files.journal.exists() && !files.journal.delete()) throw IOException("could not delete the replacement journal")
        syncDirectory(files.keyDir)
        checkpoint("finished")
    }

    /** A new folder under [PlanningFiles.setAsideRoot], named by local time and [reason], never one that exists. */
    private fun folderFor(reason: String): File {
        val stamp = now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return generateSequence(1) { it + 1 }
            .map { n -> File(files.setAsideRoot, if (n == 1) "$stamp-$reason" else "$stamp-$reason-$n") }
            .first { !it.exists() }
    }

    private fun journalText(moves: List<Move>): String =
        buildString {
            appendLine(JOURNAL_HEADER)
            moves.forEach { appendLine("move\t${relative(it.from)}\t${relative(it.to)}") }
        }

    private fun readJournal(): List<Move> {
        val lines =
            files.journal
                .readText(Charsets.UTF_8)
                .lines()
                .filter(String::isNotEmpty)
        if (lines.firstOrNull() != JOURNAL_HEADER) throw IOException("unreadable replacement journal")
        return lines.drop(1).map { line ->
            val parts = line.split('\t')
            if (parts.size != 3 || parts[0] != "move") throw IOException("unreadable replacement journal")
            Move(resolve(parts[1]), resolve(parts[2]))
        }
    }

    private fun relative(file: File): String = file.canonicalFile.relativeTo(files.root.canonicalFile).invariantSeparatorsPath

    /** A journal path, which must stay inside [PlanningFiles.root]. */
    private fun resolve(path: String): File {
        val root = files.root.canonicalFile
        val file = File(root, path).canonicalFile
        if (path.isEmpty() || File(path).isAbsolute || !file.path.startsWith(root.path + File.separator)) {
            throw IOException("a replacement journal path leaves the app's data")
        }
        return file
    }

    private companion object {
        const val JOURNAL_HEADER = "thalia-planning-replace-journal 1"
    }
}
