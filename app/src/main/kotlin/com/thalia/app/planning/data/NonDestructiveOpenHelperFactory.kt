package com.thalia.app.planning.data

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper

/**
 * Wraps another open-helper factory so that a database reported as corrupt is never deleted.
 *
 * `SupportSQLiteOpenHelper.Callback.onCorruption`'s default implementation deletes the database
 * file, and Room does not override it. A file that SQLCipher cannot decrypt, because the key is
 * wrong or it is not a database, is reported as corruption. Planning data must be kept and the
 * error shown instead (ADR 0009 decision 7), so this callback does nothing on corruption and the
 * open fails with its exception, which [PlanningDatabaseOpener] classifies.
 */
internal class NonDestructiveOpenHelperFactory(
    internal val delegate: SupportSQLiteOpenHelper.Factory,
) : SupportSQLiteOpenHelper.Factory {
    override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
        delegate.create(
            SupportSQLiteOpenHelper.Configuration
                .builder(configuration.context)
                .name(configuration.name)
                .callback(KeepOnCorruption(configuration.callback))
                .noBackupDirectory(configuration.useNoBackupDirectory)
                .allowDataLossOnRecovery(false)
                .build(),
        )

    private class KeepOnCorruption(
        private val delegate: SupportSQLiteOpenHelper.Callback,
    ) : SupportSQLiteOpenHelper.Callback(delegate.version) {
        override fun onConfigure(db: SupportSQLiteDatabase) = delegate.onConfigure(db)

        override fun onCreate(db: SupportSQLiteDatabase) = delegate.onCreate(db)

        override fun onUpgrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = delegate.onUpgrade(db, oldVersion, newVersion)

        override fun onDowngrade(
            db: SupportSQLiteDatabase,
            oldVersion: Int,
            newVersion: Int,
        ) = delegate.onDowngrade(db, oldVersion, newVersion)

        override fun onOpen(db: SupportSQLiteDatabase) = delegate.onOpen(db)

        /** Keep the file. The open then fails, and the failure is shown. */
        override fun onCorruption(db: SupportSQLiteDatabase) = Unit
    }
}
