package com.thalia.app.planning

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.FakeSealer
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningFiles
import com.thalia.app.planning.data.PlanningOpenResult
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainOpener
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.Direction
import com.thalia.planning.DirectionRecord
import com.thalia.planning.Goal
import com.thalia.planning.GoalStatus
import com.thalia.planning.ImportProblem
import com.thalia.planning.ImportRefusedException
import com.thalia.planning.PlanningArchive
import com.thalia.planning.PlanningCounts
import com.thalia.planning.PlanningExportContent
import com.thalia.planning.PlanningExportFormat
import com.thalia.planning.PlanningExportInfo
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningSnapshot
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import com.thalia.planning.Stamped
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.FileNotFoundException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ADR 0009 slice 1B criteria 2 and 3: export from one planning store and import into another, as
 * from one phone to the next, on Robolectric's plain SQLite with [FakeSealer]. Every refusal must
 * leave the receiving store exactly as it was, with no file created.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningTransferTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val password = "a long export password".toCharArray()
    private val created = mutableListOf<PlanningFiles>()
    private val opened = mutableListOf<PlanningDatabase>()

    /**
     * A planning store as one phone has it: its own files, opener and replacement. Names are short
     * because Robolectric names each test's data directory after the test, and native SQLite on
     * Windows cannot open a path over 260 characters.
     */
    private inner class Phone(
        name: String,
        hook: suspend (PlanningStore) -> Unit = {},
    ) {
        val files = PlanningFiles.of(context, name).also { created += it }
        val sealer = FakeSealer()
        val access: PlanningAccess
        val transfer: PlanningTransfer

        init {
            val (opener, replacement) = plainOpener(context, files, sealer, opened)
            access = PlanningAccess(opener, replacement, Dispatchers.Unconfined, hook)
            transfer =
                PlanningTransfer(
                    access,
                    "0.1-test",
                    zone = { ZoneId.of("Asia/Singapore") },
                    clock = { Instant.parse("2026-10-02T13:14:15Z") },
                    crypto = Dispatchers.Unconfined,
                )
        }

        fun store(): PlanningStore = runBlocking { (access.state() as PlanningAccess.State.Ready).store }

        fun snapshot(): PlanningSnapshot = runBlocking { assertNotNull(access.snapshot()) }

        /** Every file this phone's planning storage has, with its bytes. */
        fun fileState(): Map<String, List<Byte>> =
            (files.live + files.staged + listOf(files.journal) + files.setAsideRoot.walkTopDown().filter(File::isFile))
                .filter(File::exists)
                .associate { it.path to it.readBytes().toList() }
    }

    @After
    fun cleanUp() {
        opened.forEach(PlanningDatabase::close)
        created.forEach { files ->
            (files.live + files.staged + listOf(files.journal)).forEach(File::delete)
            files.setAsideRoot.deleteRecursively()
        }
    }

    /** Planning data entered through the store's own saves, as the screen does. */
    private fun Phone.fill() =
        runBlocking {
            val store = store()
            store.saveDirection(Direction("Build calm, useful things é日"))
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            val goal = UUID.randomUUID().toString()
            assertEquals(
                null,
                store.saveGoal(Goal(goal, "Run a marathon", "Under four hours", LocalDate.of(2027, 4, 1), GoalStatus.ACTIVE)),
            )
            val project = UUID.randomUUID().toString()
            assertEquals(null, store.saveProject(Project(project, "Training block", goal, ProjectState.ACTIVE)))
            assertEquals(null, store.saveProject(Project(UUID.randomUUID().toString(), "Someday", null, ProjectState.SOMEDAY)))
            assertEquals(
                null,
                store.saveAction(Action(UUID.randomUUID().toString(), "Long run", project, Priority.MUST, 120, LocalDate.of(2026, 10, 4))),
            )
            assertEquals(null, store.saveAction(Action(UUID.randomUUID().toString(), "Call bank", status = ActionStatus.DONE)))
        }

    private fun export(phone: Phone): ByteArray = runBlocking { phone.transfer.export(password).archive }

    private fun refusedWith(
        problem: ImportProblem,
        phone: Phone,
        archive: ByteArray,
        password: CharArray = this.password,
    ) {
        val before = phone.fileState()
        val refused = assertFailsWith<ImportRefusedException> { runBlocking { phone.transfer.open(archive, password) } }
        assertEquals(problem, refused.problem)
        assertEquals(before, phone.fileState(), "nothing was written")
    }

    /** An archive whose payload is [edit] applied to the exported JSON, sealed again with the same password. */
    private fun resealed(
        archive: ByteArray,
        edit: (String) -> String,
    ): ByteArray = PlanningArchive.seal(edit(String(PlanningArchive.open(archive, password))).toByteArray(), password)

    @Test
    fun `a round trip restores every row by id and field`() {
        val old = Phone("old").apply { fill() }
        val archive = export(old)

        val new = Phone("new")
        val content = runBlocking { new.transfer.open(archive, password) }
        assertTrue(runBlocking { new.access.restoreIntoEmpty(content.snapshot) })

        assertEquals(old.snapshot(), new.snapshot())
        assertEquals(
            PlanningExportInfo(
                PlanningDatabase.SCHEMA_VERSION,
                "0.1-test",
                Instant.parse("2026-10-02T13:14:15Z"),
                ZoneId.of("Asia/Singapore"),
            ),
            content.info,
        )
    }

    @Test
    fun `the archive holds neither the passphrase nor the sealed key`() {
        val old = Phone("old").apply { fill() }
        val archive = export(old)
        val sealedKey = old.files.key.readBytes()
        val passphrase = old.sealer.open(sealedKey)
        val payload = PlanningArchive.open(archive, password)

        for (secret in listOf(sealedKey, passphrase)) {
            assertFalse(archive.containsBytes(secret))
            assertFalse(payload.containsBytes(secret))
        }
        assertFalse(String(archive, Charsets.ISO_8859_1).contains("Build calm"), "the payload is encrypted")
    }

    @Test
    fun `import into a phone with planning data is refused, nothing changes`() {
        val old = Phone("old").apply { fill() }
        val archive = export(old)
        val other = Phone("other").apply { runBlocking { store().saveDirection(Direction("Mine")) } }
        val before = other.snapshot()

        val content = runBlocking { other.transfer.open(archive, password) }
        assertFalse(runBlocking { other.access.restoreIntoEmpty(content.snapshot) })

        assertEquals(before, other.snapshot())
    }

    @Test
    fun `replace restores the export and keeps the previous data openable`() {
        val old = Phone("old").apply { fill() }
        val archive = export(old)
        val other = Phone("other").apply { runBlocking { store().saveDirection(Direction("Mine")) } }
        val previous = other.snapshot()

        val content = runBlocking { other.transfer.open(archive, password) }
        val folder = runBlocking { other.access.replace(content.snapshot) }

        assertEquals(old.snapshot(), other.snapshot())
        val (opener, _) = plainOpener(context, other.files.inside(folder), other.sealer, opened)
        val database = (opener.open() as PlanningOpenResult.Opened).database
        try {
            assertEquals(previous, runBlocking { PlanningStore(database).snapshot() })
        } finally {
            database.close()
        }
    }

    @Test
    fun `wrong password, tampering and truncation are refused, nothing written`() {
        val archive = export(Phone("old").apply { fill() })
        val target = Phone("new").apply { runBlocking { store().saveDirection(Direction("Mine")) } }

        refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED, target, archive, "not the password".toCharArray())
        refusedWith(
            ImportProblem.WRONG_PASSWORD_OR_DAMAGED,
            target,
            archive.copyOf().also {
                it[it.size - 5] =
                    (it[it.size - 5] + 1).toByte()
            },
        )
        refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED, target, withChangedSalt(archive))
        refusedWith(ImportProblem.WRONG_PASSWORD_OR_DAMAGED, target, archive.copyOf(archive.size - 1))
        refusedWith(ImportProblem.NOT_AN_EXPORT, target, archive.copyOf(30))
        refusedWith(ImportProblem.TOO_LARGE, target, archive + ByteArray(PlanningArchive.MAX_ARCHIVE_BYTES))
        assertEquals(Direction("Mine"), runBlocking { target.store().direction() })
    }

    @Test
    fun `broken reference, newer schema, bad count refused, nothing written`() {
        val archive = export(Phone("old").apply { fill() })
        val target = Phone("new")

        refusedWith(
            ImportProblem.BROKEN_REFERENCE,
            target,
            resealed(archive) {
                it.replace(Regex("\"goalId\":\"[^\"]+\""), "\"goalId\":\"${UUID.randomUUID()}\"")
            },
        )
        refusedWith(
            ImportProblem.NEWER_VERSION,
            target,
            resealed(archive) {
                it.replace(
                    "\"schemaVersion\":${PlanningDatabase.SCHEMA_VERSION}",
                    "\"schemaVersion\":${PlanningDatabase.SCHEMA_VERSION + 1}",
                )
            },
        )
        refusedWith(ImportProblem.INVALID_CONTENT, target, resealed(archive) { it.replace("\"goals\":1", "\"goals\":2") })
        assertEquals(PlanningSnapshot.EMPTY, target.snapshot())
    }

    @Test
    fun `an export too large to import is refused`() {
        val big = Phone("big")
        val title = "x".repeat(200)
        val actions = List(30_000) { Stamped(Action(UUID.randomUUID().toString(), title), it.toLong(), it.toLong()) }
        assertTrue(
            runBlocking {
                big.access.restoreIntoEmpty(PlanningSnapshot(DirectionRecord(Direction("d"), 1), null, emptyList(), emptyList(), actions))
            },
        )

        assertFailsWith<PlanningTransfer.ExportTooLargeException> { runBlocking { big.transfer.export(password) } }
    }

    /** A document provider holding one document, recording every call that reaches it. */
    class RecordingProvider : ContentProvider() {
        lateinit var file: File
        var reportSize = true
        var refuseWrites = false
        var divertWrites = false
        val calls = mutableListOf<String>()

        override fun onCreate() = true

        override fun query(
            uri: Uri,
            projection: Array<out String>?,
            selection: String?,
            selectionArgs: Array<out String>?,
            sortOrder: String?,
        ): Cursor =
            MatrixCursor(arrayOf(OpenableColumns.SIZE)).apply {
                calls += "query"
                addRow(arrayOf<Any?>(if (reportSize) file.length() else null))
            }

        override fun openFile(
            uri: Uri,
            mode: String,
        ): ParcelFileDescriptor {
            calls += "open $mode"
            val writing = mode.contains('w')
            if (writing && refuseWrites) throw FileNotFoundException("refused")
            val target = if (writing && divertWrites) File(file.path + ".elsewhere") else file
            return ParcelFileDescriptor.open(target, ParcelFileDescriptor.parseMode(mode))
        }

        override fun call(
            method: String,
            arg: String?,
            extras: Bundle?,
        ): Bundle? {
            calls += "call $method"
            return null
        }

        override fun delete(
            uri: Uri,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int {
            calls += "delete"
            return 0
        }

        override fun getType(uri: Uri): String? = null

        override fun insert(
            uri: Uri,
            values: ContentValues?,
        ): Uri? = null

        override fun update(
            uri: Uri,
            values: ContentValues?,
            selection: String?,
            selectionArgs: Array<out String>?,
        ): Int = 0
    }

    private val exporter by lazy { Phone("old").apply { fill() } }

    private val documentUri: Uri = Uri.parse("content://thalia.test.documents/document/1")

    private fun provider(content: ByteArray): RecordingProvider =
        Robolectric.setupContentProvider(RecordingProvider::class.java, "thalia.test.documents").apply {
            file = File(this@PlanningTransferTest.context.cacheDir, "picked.thaliaexport").apply { writeBytes(content) }
        }

    private fun exportInto(
        provider: RecordingProvider,
        password: CharArray = this.password,
    ): ExportOutcome =
        runBlocking {
            exporter.transfer.exportToDocument(context.contentResolver, documentUri, password, Dispatchers.Unconfined)
        }

    /** No delete of any kind, by the content provider API or through DocumentsContract's call, ever reaches the provider. */
    private fun assertNothingDeleted(provider: RecordingProvider) =
        assertTrue(provider.calls.none { it == "delete" || it.startsWith("call") }, "${provider.calls}")

    @Test
    fun `an export into an empty document writes the archive, and deletes nothing`() {
        val provider = provider(ByteArray(0))

        assertIs<ExportOutcome.Exported>(exportInto(provider))

        assertTrue(PlanningArchive.open(provider.file.readBytes(), password).isNotEmpty())
        assertNothingDeleted(provider)
    }

    @Test
    fun `a document that is not empty, or of unknown size, is not written to`() {
        val existing = "someone's file".toByteArray()
        for (reportSize in listOf(true, false)) {
            val provider = provider(existing).apply { this.reportSize = reportSize }

            assertEquals(ExportOutcome.NotEmpty, exportInto(provider))

            assertContentEquals(existing, provider.file.readBytes())
            assertTrue(provider.calls.none { it.startsWith("open") }, "${provider.calls}")
            assertNothingDeleted(provider)
        }
        val unknownEmpty = provider(ByteArray(0)).apply { reportSize = false }
        assertEquals(ExportOutcome.NotEmpty, exportInto(unknownEmpty))
        assertNothingDeleted(unknownEmpty)
    }

    @Test
    fun `failed exports leave the document in place and never delete it`() {
        val refused = provider(ByteArray(0)).apply { refuseWrites = true }
        assertIs<ExportOutcome.Failed>(exportInto(refused))
        assertTrue(refused.file.exists())
        assertNothingDeleted(refused)

        val diverted = provider(ByteArray(0)).apply { divertWrites = true }
        assertIs<ExportOutcome.Failed>(exportInto(diverted), "the read-back differs")
        assertTrue(diverted.file.exists())
        assertNothingDeleted(diverted)

        val tooShort = provider(ByteArray(0))
        assertIs<ExportOutcome.Failed>(exportInto(tooShort, "short".toCharArray()))
        assertTrue(tooShort.calls.none { it.startsWith("open") }, "nothing was written")
        assertNothingDeleted(tooShort)
    }

    @Test
    fun `the suggested file name is in local time`() {
        assertEquals("thalia-planning-20261002-2114.thaliaexport", Phone("old").transfer.suggestedFileName())
    }

    @Test
    fun `the app's payload is the format's`() {
        val archive = export(Phone("old").apply { fill() })
        val content: PlanningExportContent =
            PlanningExportFormat.decode(
                PlanningArchive.open(archive, password),
                currentSchemaVersion = PlanningDatabase.SCHEMA_VERSION,
            )
        assertEquals(5, content.snapshot.counts.goals + content.snapshot.counts.projects + content.snapshot.counts.actions)
    }

    /** [fill], then an approved plan and an adjusted revision of it, through the store as the screen does. */
    private fun Phone.fillWithPlans() {
        fill()
        runBlocking {
            val store = store()
            val ids =
                store
                    .actions()
                    .filter { it.status == ActionStatus.OPEN }
                    .map { it.id }
                    .toSet()
            val day = ActiveDay(LocalDate.of(2026, 10, 3))
            val utc = ZoneId.of("UTC")
            val first = assertNotNull(store.propose(day, utc, null, ids, emptyMap()))
            assertIs<ApprovalResult.Approved>(store.approve(first, utc))
            val second = assertNotNull(store.propose(day, utc, PlanningWindow(LocalTime.of(10, 0), LocalTime.of(20, 0)), ids, emptyMap()))
            val approved = assertIs<ApprovalResult.Approved>(store.approve(second, utc, openingTime = LocalTime.of(8, 0)))
            // One outcome with its marker, so every round trip below carries outcomes and an opening time.
            val item = approved.plan.items.first { it.kind != com.thalia.planning.ItemKind.LEFT_OUT }
            assertIs<com.thalia.app.planning.data.OutcomeRecording.Recorded>(
                store.recordOutcome(item.id, com.thalia.planning.OutcomeResult.DONE, day),
            )
        }
    }

    @Test
    fun `a round trip restores plans, every revision and their snapshots, by id and field`() {
        val old = Phone("old").apply { fillWithPlans() }
        val before = old.snapshot()
        assertEquals(2, before.plans.size)
        assertEquals(1, before.planItems.size / 2)
        val archive = export(old)

        val new = Phone("new")
        val content = runBlocking { new.transfer.open(archive, password) }
        assertEquals(PlanningDatabase.SCHEMA_VERSION, content.info.schemaVersion)
        assertTrue(runBlocking { new.access.restoreIntoEmpty(content.snapshot) })

        assertEquals(before, new.snapshot())
        assertEquals(1, before.outcomes.size)
        assertEquals(1, before.openingTimes.size)
        // The call row is the new phone's own: NOT_LINKED, never the old phone's REQUESTED.
        assertEquals(listOf("NOT_LINKED"), runBlocking { new.store().calls() }.map { it.status })
        val restored = runBlocking { new.store().revisionsOf(ActiveDay(LocalDate.of(2026, 10, 3))) }
        assertEquals(listOf(1, 2), restored.map { it.plan.revision })
    }

    @Test
    fun `replace restores plans too, and keeps the previous data`() {
        val old = Phone("old").apply { fillWithPlans() }
        val archive = export(old)
        val target = Phone("new").apply { fill() }

        val content = runBlocking { target.transfer.open(archive, password) }
        runBlocking { target.access.replace(content.snapshot) }

        assertEquals(old.snapshot(), target.snapshot())
        assertEquals(listOf("NOT_LINKED"), runBlocking { target.store().calls() }.map { it.status })
    }

    /** A schema 1 export as slice 1B wrote it: schema 1, no plans. Sealed with the current archive code, whose envelope is unchanged. */
    private fun schemaOneArchive(): ByteArray {
        val goal = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a001"
        val project = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a002"
        val action = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a003"
        val json =
            """{"format":1,"schemaVersion":1,"appVersion":"0.1-m1-debug","createdAt":"2026-10-02T13:14:15Z","zone":"Asia/Singapore",""" +
                """"counts":{"direction":1,"settings":1,"goals":1,"projects":1,"actions":1},""" +
                """"direction":{"text":"Stay curious","updatedAtMillis":1000},""" +
                """"settings":{"rollover":"04:00","windowStart":"12:00","windowEnd":"22:00"},""" +
                """"goals":[{"id":"$goal","title":"Run","successCondition":"Finish",""" +
                """"horizon":null,"status":"ACTIVE","createdAtMillis":10,"updatedAtMillis":20}],""" +
                """"projects":[{"id":"$project","title":"Training","goalId":"$goal",""" +
                """"state":"ACTIVE","createdAtMillis":11,"updatedAtMillis":21}],""" +
                """"actions":[{"id":"$action","title":"Long run","projectId":"$project","priority":"MUST",""" +
                """"estimateMinutes":120,"dueDate":null,"status":"OPEN","createdAtMillis":12,"updatedAtMillis":22}]}"""
        return PlanningArchive.seal(json.toByteArray(), password)
    }

    @Test
    fun `a schema 1 export is restored on a schema 2 phone, then exports as schema 2 and round-trips`() {
        val target = Phone("new")
        val content = runBlocking { target.transfer.open(schemaOneArchive(), password) }
        assertEquals(1, content.info.schemaVersion)
        assertTrue(runBlocking { target.access.restoreIntoEmpty(content.snapshot) })

        val restored = target.snapshot()
        assertEquals(PlanningCounts(direction = 1, settings = 1, goals = 1, projects = 1, actions = 1), restored.counts)
        assertEquals(emptyList(), restored.plans)

        val again = Phone("again")
        val reexported = runBlocking { again.transfer.open(export(target), password) }
        assertEquals(PlanningDatabase.SCHEMA_VERSION, reexported.info.schemaVersion)
        assertEquals(restored, reexported.snapshot)
    }

    @Test
    fun `a schema 1 export replaces too, and a plan can be approved on the restored data`() {
        val target = Phone("new").apply { fillWithPlans() }
        val content = runBlocking { target.transfer.open(schemaOneArchive(), password) }
        runBlocking { target.access.replace(content.snapshot) }

        assertEquals(emptyList(), target.snapshot().plans)
        val store = target.store()
        val day = ActiveDay(LocalDate.of(2026, 10, 3))
        runBlocking {
            val proposal =
                assertNotNull(store.propose(day, ZoneId.of("UTC"), null, setOf("5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a003"), emptyMap()))
            assertIs<ApprovalResult.Approved>(store.approve(proposal, ZoneId.of("UTC")))
        }
    }

    @Test
    fun `a plan that points at a missing action is refused on import, nothing written`() {
        val archive = export(Phone("old").apply { fillWithPlans() })
        val target = Phone("new")
        refusedWith(
            ImportProblem.BROKEN_REFERENCE,
            target,
            resealed(archive) {
                Regex("(\"planItems\":\\[\\{[^}]*\"actionId\":\")[^\"]+").replace(it) { m ->
                    m.groupValues[1] +
                        UUID.randomUUID()
                }
            },
        )
        assertEquals(PlanningSnapshot.EMPTY, target.snapshot())
    }

    /** The archive with one character of its salt changed: the header is still valid, but not the one sealed. */
    private fun withChangedSalt(archive: ByteArray): ByteArray {
        val text = String(archive, Charsets.ISO_8859_1)
        val at = text.indexOf("\"salt\":\"") + "\"salt\":\"".length
        val changed = if (text[at] == 'A') 'B' else 'A'
        return (text.substring(0, at) + changed + text.substring(at + 1)).toByteArray(Charsets.ISO_8859_1)
    }

    private fun ByteArray.containsBytes(other: ByteArray): Boolean =
        (0..size - other.size).any { start -> other.indices.all { this[start + it] == other[it] } }

    // ADR 0012: the Inbox round-trips through the encrypted archive in every state.

    private fun Phone.fillWithInbox() =
        runBlocking {
            fill()
            val store = store()
            val ids = (1..5).map { "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c00$it" }
            store.captureItem(com.thalia.planning.InboxItem(ids[0], "  open, exact text\nwith a line break é日  "))
            store.captureItem(com.thalia.planning.InboxItem(ids[1], "a note"))
            store.resolveItem(ids[1], com.thalia.planning.InboxState.NOTE)
            store.editBody(ids[1], "a note", "an edited note")
            store.captureItem(com.thalia.planning.InboxItem(ids[2], "an idea"))
            store.resolveItem(ids[2], com.thalia.planning.InboxState.IDEA)
            store.captureItem(com.thalia.planning.InboxItem(ids[3], "became an action"))
            store.convertToAction(
                ids[3],
                Action(
                    com.thalia.planning.InboxConversion
                        .actionIdFor(ids[3]),
                    "Made from a capture",
                ),
            )
            store.captureItem(com.thalia.planning.InboxItem(ids[4], "discarded by mistake"))
            store.resolveItem(ids[4], com.thalia.planning.InboxState.DISCARDED)
        }

    @Test
    fun `a round trip restores every Inbox item in every state, with exact text and times`() {
        val old = Phone("old").apply { fillWithInbox() }
        val before = old.snapshot()
        assertEquals(5, before.counts.inboxItems)
        assertEquals(
            setOf(
                com.thalia.planning.InboxState.OPEN,
                com.thalia.planning.InboxState.NOTE,
                com.thalia.planning.InboxState.IDEA,
                com.thalia.planning.InboxState.ACTION,
                com.thalia.planning.InboxState.DISCARDED,
            ),
            before.inboxItems.map { it.value.state }.toSet(),
        )

        val new = Phone("new")
        val content = runBlocking { new.transfer.open(export(old), password) }
        assertEquals(PlanningDatabase.SCHEMA_VERSION, content.info.schemaVersion)
        assertTrue(runBlocking { new.access.restoreIntoEmpty(content.snapshot) })

        assertEquals(before, new.snapshot())
        assertEquals(
            "  open, exact text\nwith a line break é日  ",
            new
                .snapshot()
                .inboxItems
                .first()
                .value.text,
        )
        // The converted item still points at its action, which was restored with it.
        val converted =
            new
                .snapshot()
                .inboxItems
                .single { it.value.state == com.thalia.planning.InboxState.ACTION }
                .value
        assertEquals("Made from a capture", runBlocking { new.store().action(converted.actionId!!) }!!.title)
    }

    @Test
    fun `replace brings the Inbox back too, and the previous data stays openable`() {
        val old = Phone("old").apply { fillWithInbox() }
        val target = Phone("target").apply { fill() }

        val content = runBlocking { target.transfer.open(export(old), password) }
        runBlocking { target.access.replace(content.snapshot) }

        assertEquals(old.snapshot(), target.snapshot())
    }

    @Test
    fun `an export with a damaged Inbox row is refused and nothing is written`() {
        val old = Phone("old").apply { fillWithInbox() }
        val archive = resealed(export(old)) { it.replaceFirst("\"state\":\"IDEA\"", "\"state\":\"SOMEDAY\"") }
        refusedWith(ImportProblem.INVALID_CONTENT, Phone("new"), archive)
    }

    // ADR 0014: reminders travel with the data, and the access tells whoever derives alarms from them when the data changed.

    private val reminderIds = (1..6).map { "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1d00$it" }

    private fun Phone.fillWithReminders() =
        runBlocking {
            fill()
            val store = store()
            reminderIds.forEach {
                store.createReminder(
                    com.thalia.planning.Reminder(
                        it,
                        com.thalia.planning.ReminderTargetType.NONE,
                        null,
                        "Checkpoint",
                        4_000_000_000_000L,
                        "Asia/Singapore",
                        createdAtMillis = 10,
                    ),
                )
            }
            store.moveReminder(reminderIds[1], com.thalia.planning.ReminderStatus.SCHEDULED, com.thalia.planning.ReminderStatus.POSTED, 20)
            store.moveReminder(reminderIds[2], com.thalia.planning.ReminderStatus.SCHEDULED, com.thalia.planning.ReminderStatus.POSTED, 20)
            store.moveReminder(reminderIds[2], com.thalia.planning.ReminderStatus.POSTED, com.thalia.planning.ReminderStatus.DISMISSED, 21)
            store.moveReminder(
                reminderIds[3],
                com.thalia.planning.ReminderStatus.SCHEDULED,
                com.thalia.planning.ReminderStatus.CANCELLED,
                22,
            )
            store.moveReminder(
                reminderIds[4],
                com.thalia.planning.ReminderStatus.SCHEDULED,
                com.thalia.planning.ReminderStatus.NOT_POSTED,
                23,
                com.thalia.planning.ReminderReason.QUIET_PERIOD,
            )
        }

    @Test
    fun `a round trip restores every reminder status exactly, an unanswered one still unanswered`() {
        val old = Phone("old").apply { fillWithReminders() }
        val before = old.snapshot()
        assertEquals(6, before.counts.reminders)

        val new = Phone("new")
        val content = runBlocking { new.transfer.open(export(old), password) }
        assertEquals(6, content.info.schemaVersion)
        assertTrue(runBlocking { new.access.restoreIntoEmpty(content.snapshot) })

        assertEquals(before, new.snapshot())
        assertEquals(
            listOf(
                com.thalia.planning.ReminderStatus.SCHEDULED,
                com.thalia.planning.ReminderStatus.POSTED,
                com.thalia.planning.ReminderStatus.DISMISSED,
                com.thalia.planning.ReminderStatus.CANCELLED,
                com.thalia.planning.ReminderStatus.NOT_POSTED,
                com.thalia.planning.ReminderStatus.SCHEDULED,
            ),
            new
                .snapshot()
                .reminders
                .sortedBy { it.id }
                .map { it.status },
        )
    }

    @Test
    fun `a schema 5 export from before reminders still imports, with none`() {
        val old = Phone("old").apply { fill() }
        val v5 =
            com.thalia.planning.PlanningExportFormat.encode(
                com.thalia.planning.PlanningExportContent(
                    com.thalia.planning.PlanningExportInfo(5, "0.1-test", Instant.parse("2026-10-09T00:00:00Z"), ZoneId.of("UTC")),
                    old.snapshot(),
                ),
            )
        assertFalse("reminders" in String(v5))
        val archive =
            com.thalia.planning.PlanningArchive
                .seal(v5, password)
        val new = Phone("new")
        val content = runBlocking { new.transfer.open(archive, password) }
        assertEquals(5, content.info.schemaVersion)
        assertTrue(runBlocking { new.access.restoreIntoEmpty(content.snapshot) })
        assertEquals(old.snapshot(), new.snapshot())
        assertEquals(0, new.snapshot().counts.reminders)
    }

    @Test
    fun `restore, replace and set aside each tell the hook once, with the store that now holds the data`() {
        val old = Phone("old").apply { fillWithReminders() }
        val seen = mutableListOf<List<String>>()
        val target = Phone("target") { store -> seen += store.reminders().map { it.id }.sorted() }

        val content = runBlocking { target.transfer.open(export(old), password) }
        // Refused because the data is not empty: nothing changed, so nothing is told.
        runBlocking { target.access.state() }
        assertTrue(runBlocking { target.access.restoreIntoEmpty(content.snapshot) })
        assertFalse(runBlocking { target.access.restoreIntoEmpty(content.snapshot) })
        assertEquals(listOf(reminderIds), seen)

        runBlocking { target.access.replace(PlanningSnapshot.EMPTY) }
        assertEquals(listOf(reminderIds, emptyList()), seen)

        runBlocking { target.access.replace(content.snapshot) }
        runBlocking { target.access.setAsideAndStartFresh() }
        assertEquals(listOf(reminderIds, emptyList(), reminderIds, emptyList()), seen)
        assertEquals(0, target.snapshot().counts.reminders)
    }

    @Test
    fun `a hook that fails does not fail the import it follows`() {
        val old = Phone("old").apply { fillWithReminders() }
        val target = Phone("target") { error("alarms are unavailable") }
        val content = runBlocking { target.transfer.open(export(old), password) }
        assertTrue(runBlocking { target.access.restoreIntoEmpty(content.snapshot) })
        assertEquals(old.snapshot(), target.snapshot())
    }
}
