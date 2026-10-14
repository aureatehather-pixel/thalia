package com.thalia.app.planning.data

import android.database.sqlite.SQLiteException
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.DatabaseErrorHandler
import net.zetetic.database.sqlcipher.SQLiteDatabase
import net.zetetic.database.sqlcipher.SQLiteOpenHelper

/**
 * SQLCipher's open helper, exactly as `SupportOpenHelperFactory(passphrase)` builds it, except that a
 * database reported as corrupt is kept rather than deleted. ADR 0009 decision 7.
 *
 * Read from sqlcipher-android 4.19.1's bytecode: `SupportHelper` passes a null error handler, so
 * SQLCipher installs `DefaultDatabaseErrorHandler`, whose `onCorruption` deletes the database file, and
 * Room's `Callback.onCorruption` is never called. A file SQLCipher cannot read, for example because the
 * passphrase does not match, can be reported as corrupt. So this factory builds the same
 * `SQLiteOpenHelper` with [KeepFile] instead: the open fails, the file stays, and
 * [PlanningDatabaseOpener] reports the failure.
 *
 * Everything else matches the library's defaults: no hook, write-ahead logging left to Room, and a
 * minimum supported version of 0, which never deletes an old database. The passphrase is kept, not
 * cleared, so Room can reopen connections.
 */
internal class KeepingSqlCipherOpenHelperFactory(
    private val passphrase: ByteArray,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper {
        val callback = configuration.callback
        return object : SQLiteOpenHelper(
            configuration.context,
            configuration.name,
            passphrase,
            null,
            callback.version,
            MINIMUM_SUPPORTED_VERSION,
            KeepFile,
            null,
            false,
        ) {
            override fun onConfigure(db: SQLiteDatabase) = callback.onConfigure(db)

            override fun onCreate(db: SQLiteDatabase) = callback.onCreate(db)

            override fun onUpgrade(
                db: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = callback.onUpgrade(db, oldVersion, newVersion)

            override fun onDowngrade(
                db: SQLiteDatabase,
                oldVersion: Int,
                newVersion: Int,
            ) = callback.onDowngrade(db, oldVersion, newVersion)

            override fun onOpen(db: SQLiteDatabase) = callback.onOpen(db)
        }
    }

    /** Keeps the file. SQLCipher then fails the open, and the failure is shown. */
    internal object KeepFile : DatabaseErrorHandler {
        override fun onCorruption(
            dbObj: SQLiteDatabase,
            exception: SQLiteException,
        ) = Unit
    }

    private companion object {
        /** The library's own default; a positive value would delete databases older than it. */
        const val MINIMUM_SUPPORTED_VERSION = 0
    }
}
