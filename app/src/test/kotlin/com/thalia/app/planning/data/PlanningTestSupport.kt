package com.thalia.app.planning.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import java.io.File

/**
 * Test doubles for planning storage. SQLCipher's native library and the Android Keystore do not run
 * on the JVM, so these tests use Robolectric's plain SQLite and a fake sealer. They prove the logic,
 * the links and the file handling, not encryption: that is checked on the Pixel (ADR 0009 slice 1B).
 */
internal class FakeSealer : PassphraseSealer {
    var refuseToOpen = false
    var refuseToSeal = false

    override fun seal(passphrase: ByteArray): ByteArray {
        check(!refuseToSeal) { "the fake Keystore refuses to seal" }
        return MARK + passphrase.reversedArray()
    }

    override fun open(sealed: ByteArray): ByteArray {
        check(!refuseToOpen) { "the fake Keystore refuses" }
        require(sealed.copyOfRange(0, MARK.size).contentEquals(MARK)) { "not sealed by the fake" }
        return sealed.copyOfRange(MARK.size, sealed.size).reversedArray()
    }

    private companion object {
        val MARK = "FAKE".toByteArray()
    }
}

/** A plain-SQLite planning database at [file], behind the same non-destructive wrapper as production. */
internal fun plainPlanningDatabase(
    context: Context,
    file: File,
): PlanningDatabase =
    Room
        .databaseBuilder(context, PlanningDatabase::class.java, file.path)
        .openHelperFactory(NonDestructiveOpenHelperFactory(FrameworkSQLiteOpenHelperFactory()))
        .addMigrations(*PlanningDatabase.MIGRATIONS)
        .build()

/**
 * An opener for [files] with plain SQLite and [sealer], whose default replacement does not sync
 * directories, which Robolectric cannot. Every database it builds is added to [opened], so a test can
 * close them all: on Windows an open database locks its files, and the next test could not remove them.
 */
internal fun plainOpener(
    context: Context,
    files: PlanningFiles,
    sealer: PassphraseSealer = FakeSealer(),
    opened: MutableList<PlanningDatabase>,
    replacement: PlanningReplacement = PlanningReplacement(files, syncDirectory = {}),
): Pair<PlanningDatabaseOpener, PlanningReplacement> =
    PlanningDatabaseOpener(files, sealer, { file, _ -> plainPlanningDatabase(context, file).also { opened += it } }, replacement) to
        replacement
