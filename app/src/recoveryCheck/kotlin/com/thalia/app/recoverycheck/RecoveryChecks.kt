package com.thalia.app.recoverycheck

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import com.thalia.app.planning.PlanningAccess
import com.thalia.app.planning.PlanningTransfer
import com.thalia.app.planning.data.KeystorePassphraseSealer
import com.thalia.app.planning.data.PlanningDatabaseOpener
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.PlanningOpenProblem
import com.thalia.app.planning.data.PlanningOpenResult
import com.thalia.app.planning.data.PlanningReplacement
import com.thalia.app.planning.data.PlanningStore
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.DayPlan
import com.thalia.planning.Direction
import com.thalia.planning.DirectionRecord
import com.thalia.planning.Goal
import com.thalia.planning.ImportProblem
import com.thalia.planning.ImportRefusedException
import com.thalia.planning.ItemKind
import com.thalia.planning.OutcomeResult
import com.thalia.planning.Placement
import com.thalia.planning.PlanItem
import com.thalia.planning.PlanOutcome
import com.thalia.planning.PlanningArchive
import com.thalia.planning.PlanningSnapshot
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.Stamped
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.SecureRandom
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * Device checks of the production planning storage, on real SQLCipher and the real Android Keystore,
 * in scratch files of this isolated app only. ADR 0009 slice 1B, criterion 5.
 *
 * Everything runs through the production classes: [PlanningDatabaseOpener.production],
 * [KeystorePassphraseSealer], [PlanningReplacement], [PlanningStore], [PlanningAccess] and
 * [PlanningTransfer]. Only the file name, [SCRATCH], and the Keystore alias, [SCRATCH_ALIAS], differ,
 * so the checks never touch this app's own planning data, let alone another app's. The text written
 * is synthetic. Results are logged under [LOG_TAG] by check name and outcome only.
 */
internal class RecoveryChecks(
    private val context: Context,
) {
    data class Result(
        val name: String,
        val passed: Boolean,
        val detail: String,
    )

    private class Stop(
        at: String,
    ) : RuntimeException("stopped at $at")

    private val files = PlanningFiles.of(context, SCRATCH)
    private val sealer = KeystorePassphraseSealer(SCRATCH_ALIAS)
    private val results = mutableListOf<Result>()

    /** Runs every check, blocking. Call it off the main thread. */
    fun runAll(): List<Result> {
        results.clear()
        verify("PBKDF2 matches independent reference vectors") { kdfVectors() }
        verify("planning.db is encrypted at rest and is not plain SQLite") { encryptedAtRest() }
        verify("the sealed key is not the passphrase, and the Keystore opens it") { sealedKey() }
        verify("a wrongly keyed database is reported and kept byte-identical") { wrongKeyKept() }
        verify("a corrupt database is reported and kept byte-identical") { corruptKept() }
        verify("set aside and start fresh keeps the unreadable files and opens empty") { setAsideKeeps() }
        verify("replacement stopped at every step recovers to the old or the new data") { interruptedReplacement() }
        verify("set-aside stopped at every step recovers") { interruptedSetAside() }
        verify("export and import round-trip on the device; a wrong password is refused") { archiveRoundTrip() }
        wipe()
        return results.toList()
    }

    private fun verify(
        name: String,
        body: () -> String,
    ) {
        val started = System.nanoTime()
        val result =
            try {
                Result(name, true, body() + " (${(System.nanoTime() - started) / 1_000_000} ms)")
            } catch (e: Throwable) {
                Result(name, false, "${e.javaClass.simpleName}: ${e.message}")
            }
        results += result
        Log.i(LOG_TAG, "${if (result.passed) "PASS" else "FAIL"} ${result.name}: ${result.detail}")
    }

    private fun opener(
        target: PlanningFiles = files,
        replacement: PlanningReplacement = PlanningReplacement(target),
    ) = PlanningDatabaseOpener.production(context, target, SCRATCH_ALIAS, replacement)

    private fun openStore(target: PlanningFiles = files): Pair<PlanningStore, () -> Unit> {
        val opened = opener(target).open()
        val database = (opened as? PlanningOpenResult.Opened)?.database ?: error("did not open: $opened")
        return PlanningStore(database) to { database.close() }
    }

    /** Scratch files only: every name below starts with [SCRATCH]. */
    private fun wipe() {
        (files.live + files.staged + listOf(files.journal)).forEach { it.delete() }
        files.setAsideRoot.deleteRecursively()
        File(context.cacheDir, "$SCRATCH-copy.db").delete()
    }

    /** A fresh scratch database holding [snapshot], closed. */
    private fun fresh(snapshot: PlanningSnapshot) {
        wipe()
        val (store, close) = openStore()
        try {
            check(runBlocking { store.restoreIntoEmpty(snapshot) })
        } finally {
            close()
        }
    }

    private fun read(): PlanningSnapshot {
        val (store, close) = openStore()
        return try {
            runBlocking { store.snapshot() }
        } finally {
            close()
        }
    }

    private fun bytes(target: PlanningFiles = files): Map<String, List<Byte>?> =
        (target.live + target.staged).associate { it.name to it.takeIf(File::exists)?.readBytes()?.toList() }

    private fun kdfVectors(): String {
        val rfc = PlanningArchive.deriveKey("passwd".toCharArray(), "salt".toByteArray(), 1).hex()
        check(rfc == "55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc") { "RFC 7914 vector differs" }
        val started = System.nanoTime()
        val nonAscii = PlanningArchive.deriveKey("pässwörd-日本語-12".toCharArray(), ByteArray(16) { it.toByte() }, 600_000)
        val ms = (System.nanoTime() - started) / 1_000_000
        check(nonAscii.hex() == "ac84849bb3e5643e3031b21169c6c1936a64721d691b9bb678e6dec4a8eb6f8a") { "non-ASCII vector differs" }
        return "600,000 iterations took $ms ms"
    }

    private fun encryptedAtRest(): String {
        fresh(sampleA)
        val started = System.nanoTime()
        val (_, close) = openStore()
        val openMs = (System.nanoTime() - started) / 1_000_000
        close()
        val database = files.database.readBytes()
        check(!database.copyOfRange(0, 16).contentEquals("SQLite format 3\u0000".toByteArray())) { "plain SQLite header" }
        val marker = MARKER.toByteArray()
        (listOf(files.database) + files.databaseSidecars).filter(File::exists).forEach {
            check(!it.readBytes().contains(marker)) { "${it.name} holds the text in the clear" }
        }
        val copy = File(context.cacheDir, "$SCRATCH-copy.db").also { files.database.copyTo(it, overwrite = true) }
        val plain =
            runCatching {
                SQLiteDatabase.openDatabase(copy.path, null, SQLiteDatabase.OPEN_READONLY).use {
                    it.rawQuery("SELECT count(*) FROM sqlite_master", null).use { c -> c.moveToFirst() }
                }
            }
        copy.delete()
        check(plain.isFailure) { "a copy opened as plain SQLite" }
        return "header is not SQLite's, no clear text, plain open refused; reopening took $openMs ms"
    }

    private fun sealedKey(): String {
        check(sealer.hasKey()) { "no Keystore key under the scratch alias" }
        val sealed = files.key.readBytes()
        val passphrase = sealer.open(sealed)
        check(passphrase.size == 32) { "passphrase of ${passphrase.size} bytes" }
        check(!sealed.contains(passphrase)) { "the sealed file holds the passphrase" }
        return "32-byte passphrase, sealed in ${sealed.size} bytes"
    }

    private fun wrongKeyKept(): String {
        fresh(sampleA)
        val original = files.key.readBytes()
        files.key.writeBytes(sealer.seal(ByteArray(32).also(SecureRandom()::nextBytes)))
        val before = bytes()
        val result = opener().open()
        check(result == PlanningOpenResult.Failed(PlanningOpenProblem.DATABASE_UNREADABLE)) { "opened as $result" }
        check(bytes() == before) { "files changed" }
        files.key.writeBytes(original)
        check(read() == sampleA) { "the right key no longer opens the data" }
        return "reported DATABASE_UNREADABLE; files identical; the right key still opens it"
    }

    private fun corruptKept(): String {
        fresh(sampleA)
        corruptFirstPage()
        val before = bytes()
        val result = opener().open()
        check(result == PlanningOpenResult.Failed(PlanningOpenProblem.DATABASE_UNREADABLE)) { "opened as $result" }
        check(bytes() == before) { "files changed" }
        return "reported DATABASE_UNREADABLE; files identical"
    }

    private fun setAsideKeeps(): String {
        fresh(sampleA)
        corruptFirstPage()
        val before = bytes()
        val access = PlanningAccess(opener(), PlanningReplacement(files))
        val folder = runBlocking { access.setAsideAndStartFresh() }
        val state = runBlocking { access.state() }
        check(
            state is PlanningAccess.State.Ready && runBlocking { state.store.snapshot() } == PlanningSnapshot.EMPTY,
        ) { "not empty: $state" }
        val kept = before.filterValues { it != null }
        kept.forEach { (name, content) -> check(File(folder, name).readBytes().toList() == content) { "$name not kept identical" } }
        return "${kept.size} files kept identical in ${folder.name}; a fresh database opened"
    }

    /** Stops [PlanningReplacement] at checkpoint [stopAt], as if the process died there. */
    private fun stopping(stopAt: Int): PlanningReplacement {
        var seen = 0
        return PlanningReplacement(files, checkpoint = { name -> if (seen++ == stopAt) throw Stop(name) })
    }

    private fun interruptedReplacement(): String {
        var step = 0
        val outcomes = mutableListOf<String>()
        while (true) {
            fresh(sampleA)
            val replacement = stopping(step)
            val stoppedAt =
                try {
                    val staged = opener(replacement = replacement).createStaged()
                    check(runBlocking { PlanningStore(staged).restoreIntoEmpty(sampleB) })
                    replacement.sealStaged(staged)
                    replacement.replaceWithStaged()
                    null
                } catch (e: Stop) {
                    e.message
                }
            val after = read()
            val committed = stoppedAt != null && stoppedAt != "stopped at staged"
            if (stoppedAt == null || committed) {
                check(after == sampleB) { "step $step ($stoppedAt): not the new data" }
                val folder = files.setAsideRoot.listFiles()!!.single { it.name.endsWith("-replaced") }
                check(read(files.inside(folder)) == sampleA) { "step $step: the set-aside data does not open as the old data" }
            } else {
                check(after == sampleA) { "step $step ($stoppedAt): the old data changed" }
                check(
                    files.setAsideRoot.listFiles()!!.any { it.name.endsWith("-staged") },
                ) { "step $step: staged files were not set aside" }
            }
            check(!files.journal.exists()) { "step $step: journal left" }
            outcomes += stoppedAt ?: "completed"
            if (stoppedAt == null) break
            step++
        }
        return "${outcomes.size} runs: ${outcomes.joinToString { it.removePrefix("stopped at ") }}"
    }

    private fun read(target: PlanningFiles): PlanningSnapshot {
        val (store, close) = openStore(target)
        return try {
            runBlocking { store.snapshot() }
        } finally {
            close()
        }
    }

    private fun interruptedSetAside(): String {
        var step = 0
        while (true) {
            fresh(sampleA)
            val stoppedAt =
                try {
                    stopping(step).setAside()
                    null
                } catch (e: Stop) {
                    e.message
                }
            check(read() == PlanningSnapshot.EMPTY) { "step $step ($stoppedAt): not empty after recovery" }
            val folder = files.setAsideRoot.listFiles()!!.single { it.name.endsWith("-set-aside") }
            check(read(files.inside(folder)) == sampleA) { "step $step: the set-aside data does not open as the old data" }
            if (stoppedAt == null) return "${step + 1} runs"
            step++
        }
    }

    private fun archiveRoundTrip(): String {
        fresh(sampleA)
        val access = PlanningAccess(opener(), PlanningReplacement(files))
        val transfer = PlanningTransfer(access, "recovery-check")
        val password = "a long synthetic password".toCharArray()
        val exported = runBlocking { transfer.export(password) }
        val content = runBlocking { transfer.open(exported.archive, password) }
        check(content.snapshot == sampleA) { "round trip differs" }
        val refused = runCatching { runBlocking { transfer.open(exported.archive, "the wrong long password".toCharArray()) } }
        check((refused.exceptionOrNull() as? ImportRefusedException)?.problem == ImportProblem.WRONG_PASSWORD_OR_DAMAGED) {
            "wrong password: $refused"
        }
        return "${exported.archive.size} bytes"
    }

    /** Overwrites the database's first page, where SQLCipher keeps its salt, with random bytes. */
    private fun corruptFirstPage() {
        val content = files.database.readBytes()
        check(content.size >= PAGE_BYTES) { "database smaller than one page" }
        ByteArray(PAGE_BYTES).also(SecureRandom()::nextBytes).copyInto(content)
        files.database.writeBytes(content)
    }

    companion object {
        const val LOG_TAG = "ThaliaRecoveryCheck"
        const val SCRATCH = "recoverycheck-scratch"
        const val SCRATCH_ALIAS = "thalia-recoverycheck-scratch-v1"
        const val MARKER = "Synthetic recovery check direction"
        private const val PAGE_BYTES = 4096

        private val goal = UUID.randomUUID().toString()
        private val project = UUID.randomUUID().toString()

        private val action = UUID.randomUUID().toString()
        private val plan = UUID.randomUUID().toString()
        private val item = UUID.randomUUID().toString()

        /** One plan with an opening time and an outcome that closed its action, so the checks cover schema 3 too. */
        val sampleA =
            PlanningSnapshot(
                direction = DirectionRecord(Direction(MARKER), 1),
                settings = null,
                goals = listOf(Stamped(Goal(goal, "Synthetic goal", "Synthetic success"), 2, 3)),
                projects = listOf(Stamped(Project(project, "Synthetic project", goal), 4, 5)),
                actions = listOf(Stamped(Action(action, "Synthetic action", project, status = ActionStatus.DONE), 6, 7)),
                plans =
                    listOf(
                        DayPlan(
                            plan,
                            ActiveDay(LocalDate.of(2026, 10, 3)),
                            1,
                            ZoneId.of("UTC"),
                            LocalTime.of(4, 0),
                            PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)),
                            600,
                            480,
                            0,
                            11,
                        ),
                    ),
                planItems =
                    listOf(
                        PlanItem(
                            item,
                            plan,
                            action,
                            ItemKind.QUEUED,
                            null,
                            30,
                            0,
                            Placement.WITHIN_TARGET,
                            null,
                            "Synthetic action",
                            Priority.NORMAL,
                            project,
                            "Synthetic project",
                            ProjectState.PLANNED,
                        ),
                    ),
                outcomes = listOf(PlanOutcome(item, OutcomeResult.DONE, 12, closedAction = true)),
                openingTimes = mapOf(plan to LocalTime.of(8, 0)),
            )

        val sampleB =
            PlanningSnapshot(
                direction = DirectionRecord(Direction("Synthetic replacement direction"), 8),
                settings = null,
                goals = emptyList(),
                projects = emptyList(),
                actions = listOf(Stamped(Action(UUID.randomUUID().toString(), "Synthetic standalone action"), 9, 10)),
            )

        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

        private fun ByteArray.contains(other: ByteArray): Boolean =
            (0..size - other.size).any { start -> other.indices.all { this[start + it] == other[it] } }
    }
}
