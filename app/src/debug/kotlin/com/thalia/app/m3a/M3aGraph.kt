package com.thalia.app.m3a

import android.content.Context
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.secret.KeystoreAesGcmCipher
import java.io.File

/**
 * The M3A spike's process-wide stores, one of each, so that every start in the process is
 * serialised through the same [ReservationLedger]. All three files are in the app-private
 * no-backup directory, and the app disables backup besides.
 */
class M3aGraph private constructor(
    directory: File,
) {
    val consent = LiveConsentStore(File(directory, "consent.properties"))
    val apiKeys = ApiKeyStore(File(directory, "gemini-key.sealed"), KeystoreAesGcmCipher())
    val ledger = ReservationLedger(File(directory, "reservations.jsonl"))

    companion object {
        @Volatile
        private var instance: M3aGraph? = null

        fun get(context: Context): M3aGraph =
            instance ?: synchronized(this) {
                instance ?: M3aGraph(File(context.applicationContext.noBackupFilesDir, "m3a")).also { instance = it }
            }
    }
}
