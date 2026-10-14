package com.thalia.app.planning.data

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Where planning data lives, every file named from one [name]. ADR 0009 decisions 7 and 8.
 *
 * Production uses `planning`: `databases/planning.db` and `no_backup/planning-key.sealed`. The
 * isolated `recoveryCheck` build uses its own name, and its own Keystore alias, with the same code.
 * A set-aside folder has the same layout inside it, so the data kept there opens like any other.
 */
internal class PlanningFiles(
    /** The directory every path in the replacement journal is relative to: the app's data directory. */
    val root: File,
    val databaseDir: File,
    val keyDir: File,
    val name: String,
) {
    val database = File(databaseDir, "$name.db")
    val databaseSidecars = sidecars(database)
    val key = File(keyDir, "$name-key.sealed")
    val newKey = File(keyDir, "${key.name}.new")

    /** The database an import is staged in, with its own passphrase, before it replaces [database]. */
    val restoreDatabase = File(databaseDir, "$name-restore.db")
    val restoreSidecars = sidecars(restoreDatabase)
    val restoreKey = File(keyDir, "$name-restore-key.sealed")

    /** Exists only between the commit point of a replacement or set-aside and its last rename. */
    val journal = File(keyDir, "$name-replace.journal")

    /** Dated folders of set-aside data. In `no_backup`, and excluded from device transfer too. */
    val setAsideRoot = File(keyDir, "$name-set-aside")

    /** The live files, in the order they are set aside. */
    val live: List<File> get() = listOf(database) + databaseSidecars + listOf(key, newKey)

    /** The staged files, in the order they are set aside when left over. */
    val staged: List<File> get() = listOf(restoreDatabase) + restoreSidecars + listOf(restoreKey)

    /** The same layout inside one set-aside [folder]. */
    fun inside(folder: File) = PlanningFiles(root = folder, databaseDir = folder, keyDir = folder, name = name)

    companion object {
        /** The name production uses. Changing it would lose every user's data. */
        const val PRODUCTION_NAME = "planning"

        fun of(
            context: Context,
            name: String,
        ): PlanningFiles {
            val app = context.applicationContext
            return PlanningFiles(
                root = app.dataDir,
                databaseDir = requireNotNull(app.getDatabasePath(name).parentFile),
                keyDir = app.noBackupFilesDir,
                name = name,
            )
        }

        private fun sidecars(database: File) = listOf("-wal", "-shm", "-journal").map { File(database.path + it) }

        /** Write [bytes] to [file] so that a crash leaves the old contents or the new, never a mixture. */
        fun writeSynced(
            file: File,
            bytes: ByteArray,
        ) {
            file.parentFile?.mkdirs()
            val staging = File(file.parentFile, "${file.name}.staging")
            FileOutputStream(staging).use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(staging.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        /** Flushes an existing file's contents to storage. */
        fun syncFile(file: File) = RandomAccessFile(file, "r").use { it.fd.sync() }

        /** Flushes a directory's entries, so renames and new files in it survive a power loss. */
        fun syncDirectory(directory: File) {
            try {
                val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                try {
                    Os.fsync(fd)
                } finally {
                    Os.close(fd)
                }
            } catch (e: ErrnoException) {
                throw IOException("could not sync a planning directory", e)
            }
        }
    }
}
