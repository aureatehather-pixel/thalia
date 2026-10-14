package com.thalia.app.planning

import android.content.Context
import com.thalia.app.BuildConfig
import com.thalia.app.planning.data.PlanningDatabaseOpener
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.PlanningOpenProblem
import com.thalia.app.planning.data.PlanningOpenResult
import com.thalia.app.planning.data.PlanningReplacement
import com.thalia.app.planning.data.PlanningStore
import com.thalia.planning.PlanningSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Opens planning data once per process, off the main thread, and only when a planning screen asks.
 * Check-ins never touch it, so a planning failure cannot affect a call.
 *
 * Replacing or setting aside the data happens under the same lock, with the database closed, so no
 * planning access runs while files move. ADR 0009 decision 8.
 */
internal class PlanningAccess(
    private val opener: PlanningDatabaseOpener,
    private val replacement: PlanningReplacement,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    /**
     * Runs after planning data was restored, replaced or set aside, with the store that is now open, so derived state such as
     * reminder alarms follows the new contents. A failure is swallowed: the import has already happened and the next
     * reconciliation (launch, boot) repeats this.
     */
    private val afterDataChanged: suspend (PlanningStore) -> Unit = {},
) {
    private val lock = Mutex()
    private var result: PlanningOpenResult? = null

    sealed interface State {
        data class Ready(
            val store: PlanningStore,
        ) : State

        data class Unavailable(
            val problem: PlanningOpenProblem,
        ) : State
    }

    suspend fun state(): State = lock.withLock { withContext(io) { current() } }

    /**
     * Runs [block] with the open store, under the same lock as replacing and setting aside, so no read
     * or save can reach a database that is being closed or moved. Throws if planning data is not open.
     */
    suspend fun <T> withStore(block: suspend (PlanningStore) -> T): T =
        lock.withLock {
            withContext(io) {
                val store = (current() as? State.Ready)?.store ?: throw IOException("planning data is not open")
                block(store)
            }
        }

    /** Every planning row as one consistent read, or null if planning data is not open. */
    suspend fun snapshot(): PlanningSnapshot? = lock.withLock { withContext(io) { (current() as? State.Ready)?.store?.snapshot() } }

    /**
     * Writes [snapshot] into the open database if it holds no planning row, in one transaction.
     * Returns false, having written nothing, if it holds any or cannot be opened.
     */
    suspend fun restoreIntoEmpty(snapshot: PlanningSnapshot): Boolean =
        lock.withLock {
            withContext(io) {
                val store = (current() as? State.Ready)?.store
                val wrote = store?.restoreIntoEmpty(snapshot) ?: false
                if (wrote) notifyChanged(store)
                wrote
            }
        }

    /**
     * Replaces all planning data with [snapshot]: staged in its own database first, then swapped in
     * by [PlanningReplacement]. The previous data is kept in the returned folder. If staging fails,
     * nothing live has changed and the staged files are set aside.
     */
    suspend fun replace(snapshot: PlanningSnapshot): File =
        lock.withLock {
            withContext(io) {
                replacement.recover()
                val staged = opener.createStaged()
                try {
                    if (!PlanningStore(staged).restoreIntoEmpty(snapshot)) error("the staged database was not empty")
                    replacement.sealStaged(staged)
                } catch (e: Throwable) {
                    staged.close()
                    replacement.recover()
                    throw e
                }
                closeCurrent()
                // If a rename fails after the commit point, opening again runs recovery, which finishes it.
                val folder =
                    try {
                        replacement.replaceWithStaged()
                    } finally {
                        result = opener.open()
                    }
                (current() as? State.Ready)?.store?.let { notifyChanged(it) }
                folder
            }
        }

    /**
     * Moves the current planning files, readable or not, into a dated folder and opens an empty
     * database. Nothing is deleted. Returns the folder.
     */
    suspend fun setAsideAndStartFresh(): File =
        lock.withLock {
            withContext(io) {
                closeCurrent()
                val folder =
                    try {
                        replacement.recover()
                        replacement.setAside()
                    } finally {
                        result = opener.open()
                    }
                (current() as? State.Ready)?.store?.let { notifyChanged(it) }
                folder
            }
        }

    private suspend fun notifyChanged(store: PlanningStore) {
        try {
            afterDataChanged(store)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Swallowed on purpose, see afterDataChanged.
        }
    }

    private fun current(): State =
        when (val opened = result ?: opener.open().also { result = it }) {
            is PlanningOpenResult.Opened -> State.Ready(PlanningStore(opened.database))
            is PlanningOpenResult.Failed -> State.Unavailable(opened.problem)
        }

    private fun closeCurrent() {
        (result as? PlanningOpenResult.Opened)?.database?.close()
        result = null
    }

    companion object {
        /** The app's planning storage: this build's file names and Keystore alias, and the production code. */
        fun production(
            context: Context,
            afterDataChanged: suspend (PlanningStore) -> Unit = {},
        ): PlanningAccess {
            val files = PlanningFiles.of(context, BuildConfig.PLANNING_STORAGE_NAME)
            val replacement = PlanningReplacement(files)
            return PlanningAccess(
                PlanningDatabaseOpener.production(context, files, BuildConfig.PLANNING_KEY_ALIAS, replacement),
                replacement,
                afterDataChanged = afterDataChanged,
            )
        }
    }
}
