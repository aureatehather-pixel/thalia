package com.thalia.app.planning.data

import android.content.Context
import android.database.SQLException
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.SecureRandom

/** Why planning data could not be opened. Each is shown to the user; none deletes a file. ADR 0009 decision 7. */
enum class PlanningOpenProblem {
    /** The sealed passphrase is missing, or this phone's Keystore cannot open it. */
    KEY_UNAVAILABLE,

    /** The database exists but cannot be read with its passphrase. */
    DATABASE_UNREADABLE,

    /** The database needs a schema change this version of Thalia cannot make. */
    MIGRATION_FAILED,

    /** The key, or a leftover WAL, SHM or journal file, exists but the database does not, so it was lost. */
    DATABASE_MISSING,

    /** Storage could not be prepared: the encryption library did not load, the Keystore refused to seal, or a file could not be written. */
    STORAGE_UNAVAILABLE,
}

internal sealed interface PlanningOpenResult {
    data class Opened(
        val database: PlanningDatabase,
    ) : PlanningOpenResult

    data class Failed(
        val problem: PlanningOpenProblem,
    ) : PlanningOpenResult
}

/**
 * Opens `planning.db`, creating it only on first use, and never deleting or recreating an existing
 * file. ADR 0009 decision 7.
 *
 * | `planning.db` | sealed key | meaning | action |
 * | --- | --- | --- | --- |
 * | absent, and no WAL, SHM or journal | absent | first use | create: seal to `.new`, create the database, then rename the key |
 * | present | present | normal | open |
 * | present | absent, `.new` present | crash after creating, before the rename | finish the rename, open |
 * | present | absent | key lost | [PlanningOpenProblem.KEY_UNAVAILABLE] |
 * | absent | present, or a WAL, SHM or journal remains | database lost | [PlanningOpenProblem.DATABASE_MISSING] |
 *
 * Before any of that, [PlanningReplacement.recover] finishes a replacement or set-aside that the
 * process died during (ADR 0009 decision 8). A `.new` key left by a crash before the database
 * existed is replaced on the next first use, because no database was ever made with it. Every
 * exception is turned into a [PlanningOpenResult.Failed], so opening can never crash the screen; a
 * failed open is retried only when the process starts again, or after a set-aside.
 *
 * [build] makes the Room database at a file for a passphrase. Production uses
 * [encryptedHelperFactory]; tests pass a plain SQLite factory, because SQLCipher's native library
 * does not run on the JVM.
 */
internal class PlanningDatabaseOpener(
    private val files: PlanningFiles,
    private val sealer: PassphraseSealer,
    private val build: (database: File, passphrase: ByteArray) -> PlanningDatabase,
    private val replacement: PlanningReplacement = PlanningReplacement(files),
    private val random: SecureRandom = SecureRandom(),
) {
    private val databaseFile = files.database
    private val keyFile = files.key
    private val newKeyFile = files.newKey
    private val sidecars = files.databaseSidecars

    fun open(): PlanningOpenResult =
        try {
            replacement.recover()
            openOrCreate()
        } catch (e: IOException) {
            fail(PlanningOpenProblem.STORAGE_UNAVAILABLE, e)
        }

    /**
     * Creates the staging database for an import, with a fresh passphrase sealed into its own key
     * file, and returns it open. The caller restores into it and then seals it with
     * [PlanningReplacement.sealStaged]. Any staged files must have been set aside first.
     */
    fun createStaged(): PlanningDatabase {
        check(files.staged.none(File::exists)) { "staged files are left over" }
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        writeSynced(files.restoreKey, sealer.seal(passphrase))
        val database = build(files.restoreDatabase, passphrase)
        try {
            database.openHelper.writableDatabase
        } catch (e: RuntimeException) {
            database.close()
            throw e
        }
        return database
    }

    private fun openOrCreate(): PlanningOpenResult {
        val databaseExists = databaseFile.exists()
        if (databaseExists && !keyFile.exists() && newKeyFile.exists()) {
            Files.move(newKeyFile.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        return when {
            !databaseExists && (keyFile.exists() || sidecars.any(File::exists)) -> fail(PlanningOpenProblem.DATABASE_MISSING, null)
            !databaseExists -> create()
            !keyFile.exists() -> fail(PlanningOpenProblem.KEY_UNAVAILABLE, null)
            else -> openExisting()
        }
    }

    private fun create(): PlanningOpenResult {
        val passphrase = ByteArray(PASSPHRASE_BYTES).also(random::nextBytes)
        val sealed =
            try {
                sealer.seal(passphrase)
            } catch (e: Exception) {
                return fail(PlanningOpenProblem.STORAGE_UNAVAILABLE, e)
            }
        writeSynced(newKeyFile, sealed)
        val result = openWith(passphrase)
        if (result is PlanningOpenResult.Opened) {
            try {
                Files.move(newKeyFile.toPath(), keyFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                // The database exists and `.new` holds its key, so the next start finishes the rename.
                result.database.close()
                return fail(PlanningOpenProblem.STORAGE_UNAVAILABLE, e)
            }
        }
        return result
    }

    private fun openExisting(): PlanningOpenResult {
        val passphrase =
            try {
                sealer.open(keyFile.readBytes())
            } catch (e: Exception) {
                return fail(PlanningOpenProblem.KEY_UNAVAILABLE, e)
            }
        return openWith(passphrase)
    }

    /** Builds the database and forces it open now, so a failure is found here and not at first use. */
    private fun openWith(passphrase: ByteArray): PlanningOpenResult {
        val database =
            try {
                build(databaseFile, passphrase)
            } catch (e: UnsatisfiedLinkError) {
                return fail(PlanningOpenProblem.STORAGE_UNAVAILABLE, e)
            } catch (e: RuntimeException) {
                return fail(PlanningOpenProblem.STORAGE_UNAVAILABLE, e)
            }
        return try {
            database.openHelper.writableDatabase
            PlanningOpenResult.Opened(database)
        } catch (e: IllegalStateException) {
            // Room reports a schema version it has no migration for as IllegalStateException.
            database.close()
            fail(PlanningOpenProblem.MIGRATION_FAILED, e)
        } catch (e: SQLException) {
            database.close()
            fail(PlanningOpenProblem.DATABASE_UNREADABLE, e)
        } catch (e: RuntimeException) {
            database.close()
            fail(PlanningOpenProblem.DATABASE_UNREADABLE, e)
        }
    }

    private fun fail(
        problem: PlanningOpenProblem,
        cause: Throwable?,
    ): PlanningOpenResult.Failed {
        // The class of the problem and of the exception only: never a path, a title or a message.
        Log.w(LOG_TAG, listOfNotNull("planning data not opened:", problem.name, cause?.javaClass?.simpleName).joinToString(" "))
        return PlanningOpenResult.Failed(problem)
    }

    companion object {
        private const val LOG_TAG = "ThaliaPlanning"
        private const val PASSPHRASE_BYTES = 32

        /**
         * The production opener for [files]: SQLCipher through [KeepingSqlCipherOpenHelperFactory], the
         * passphrase sealed under the Keystore key [keyAlias], and no destructive migration fallback.
         */
        fun production(
            context: Context,
            files: PlanningFiles,
            keyAlias: String,
            replacement: PlanningReplacement = PlanningReplacement(files),
        ): PlanningDatabaseOpener {
            val app = context.applicationContext
            return PlanningDatabaseOpener(
                files = files,
                sealer = KeystorePassphraseSealer(keyAlias),
                build = { database, passphrase ->
                    System.loadLibrary("sqlcipher")
                    productionBuilder(app, database, passphrase).build()
                },
                replacement = replacement,
            )
        }

        /**
         * The Room builder production uses for every planning database: SQLCipher through
         * [encryptedHelperFactory], every migration registered, and no destructive fallback. It is separate
         * from loading the native library so a test can take it, swap only the open helper for plain
         * SQLite, and prove that what production registers actually migrates.
         */
        internal fun productionBuilder(
            context: Context,
            database: File,
            passphrase: ByteArray,
        ): RoomDatabase.Builder<PlanningDatabase> =
            Room
                .databaseBuilder(context, PlanningDatabase::class.java, database.path)
                .openHelperFactory(encryptedHelperFactory(passphrase))
                .addMigrations(*PlanningDatabase.MIGRATIONS)

        /**
         * SQLCipher with a corruption handler that keeps the file, behind the Room-level wrapper that
         * does the same for any helper that does call Room's callback.
         */
        fun encryptedHelperFactory(passphrase: ByteArray): SupportSQLiteOpenHelper.Factory =
            NonDestructiveOpenHelperFactory(KeepingSqlCipherOpenHelperFactory(passphrase))

        internal fun writeSynced(
            file: File,
            bytes: ByteArray,
        ) = PlanningFiles.writeSynced(file, bytes)
    }
}
