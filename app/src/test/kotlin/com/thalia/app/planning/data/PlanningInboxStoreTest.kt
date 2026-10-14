package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.InboxConversion
import com.thalia.planning.InboxItem
import com.thalia.planning.InboxState
import com.thalia.planning.InboxText
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ADR 0012 at the store: capture, filing, editing, atomic conversion, undo limits and restore. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningInboxStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "planning-inbox-test.db"
    private val otherName = "planning-inbox-restore.db"
    private val opened = mutableListOf<PlanningDatabase>()
    private var now = 1_000L

    private val a = "11111111-1111-4111-8111-111111111111"
    private val b = "22222222-2222-4222-8222-222222222222"

    private fun store(file: String = name): PlanningStore {
        val db = plainPlanningDatabase(context, context.getDatabasePath(file))
        opened += db
        return PlanningStore(db) { now++ }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(name)
        context.deleteDatabase(otherName)
    }

    @Test
    fun `a capture keeps its text exactly, including spaces, line breaks and non-ASCII, and its time`() =
        runTest {
            val s = store()
            val text = "  Café 日本 \"quoted\"\nsecond line  \n"
            assertEquals(CaptureResult.CAPTURED, s.captureItem(InboxItem(a, text)))
            val stored = s.inbox().single()
            assertEquals(text, stored.value.text)
            assertEquals(InboxState.OPEN, stored.value.state)
            assertEquals(1_000L, stored.createdAtMillis)
            assertEquals(stored.createdAtMillis, stored.updatedAtMillis)
        }

    @Test
    fun `an excess capture is rejected whole and never cut, and the limit itself is accepted`() =
        runTest {
            val s = store()
            val problem = InboxText.problem("x".repeat(InboxText.MAX + 1))
            assertTrue(problem!!.contains("nothing was cut", ignoreCase = true))
            assertFailsWith<IllegalArgumentException> { InboxItem(a, "x".repeat(InboxText.MAX + 1)) }
            assertEquals(CaptureResult.CAPTURED, s.captureItem(InboxItem(a, "x".repeat(InboxText.MAX))))
            assertEquals(InboxText.MAX, s.inboxItem(a)!!.text.length)
            assertEquals("There is nothing to capture.", InboxText.problem("  \n "))
        }

    @Test
    fun `saving the same capture twice makes one item, and the same id with other text overwrites nothing`() =
        runTest {
            val s = store()
            assertEquals(CaptureResult.CAPTURED, s.captureItem(InboxItem(a, "first")))
            assertEquals(CaptureResult.ALREADY_CAPTURED, s.captureItem(InboxItem(a, "first")))
            assertEquals(CaptureResult.CONFLICT, s.captureItem(InboxItem(a, "other")))
            assertEquals(listOf("first"), s.inbox().map { it.value.text })
        }

    @Test
    fun `a capture is not an action and is in no plan`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "Remind me to call the dentist at 3pm"))
            assertEquals(emptyList(), s.actions())
            val snapshot = s.snapshot()
            assertEquals(emptyList(), snapshot.plans)
            assertEquals(emptyList(), snapshot.commitments)
        }

    @Test
    fun `undo removes only a capture that is still exactly as captured`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "untouched"))
            assertTrue(s.undoCapture(a, "untouched"))
            assertNull(s.inboxItem(a))

            s.captureItem(InboxItem(a, "filed since"))
            s.resolveItem(a, InboxState.NOTE)
            assertFalse(s.undoCapture(a, "filed since"))
            s.editBody(a, "filed since", "edited")
            assertFalse(s.undoCapture(a, "filed since"))
            assertEquals("edited", s.inboxItem(a)!!.body)

            s.captureItem(InboxItem(b, "became an action"))
            s.convertToAction(b, Action(InboxConversion.actionIdFor(b), "Do it"))
            assertFalse(s.undoCapture(b, "became an action"))
            assertEquals(1, s.actions().size)
        }

    @Test
    fun `filing as a note keeps the original text, starts the body as it and keeps the times`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "an idea about gardens"))
            assertEquals(ResolveInboxResult.RESOLVED, s.resolveItem(a, InboxState.NOTE))
            val row = s.inbox().single()
            assertEquals(InboxState.NOTE, row.value.state)
            assertEquals("an idea about gardens", row.value.text)
            assertEquals("an idea about gardens", row.value.body)
            assertEquals(1_000L, row.createdAtMillis)
            assertTrue(row.value.resolvedAtMillis!! > row.createdAtMillis)
        }

    @Test
    fun `a retried filing is a success only when it matches, and a conflicting one is not`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "thing"))
            assertEquals(ResolveInboxResult.RESOLVED, s.resolveItem(a, InboxState.NOTE))
            assertEquals(ResolveInboxResult.ALREADY_RESOLVED, s.resolveItem(a, InboxState.NOTE))
            assertEquals(ResolveInboxResult.NOT_OPEN, s.resolveItem(a, InboxState.IDEA))
            assertEquals(ResolveInboxResult.NOT_OPEN, s.resolveItem(a, InboxState.NOTE, body = "a different wording"))
            assertEquals(InboxState.NOTE, s.inboxItem(a)!!.state)
            assertEquals("thing", s.inboxItem(a)!!.body)
            assertEquals(ResolveInboxResult.NOT_FOUND, s.resolveItem(b, InboxState.NOTE))
        }

    @Test
    fun `archive and discard keep the row, and a discard is recoverable`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "old"))
            s.captureItem(InboxItem(b, "wrong"))
            s.resolveItem(a, InboxState.ARCHIVED)
            s.resolveItem(b, InboxState.DISCARDED)
            assertEquals(listOf(InboxState.ARCHIVED, InboxState.DISCARDED), s.inbox().map { it.value.state })
            assertEquals(listOf("old", "wrong"), s.inbox().map { it.value.text })
            assertEquals(ReopenInboxResult.REOPENED, s.reopenItem(b, InboxState.DISCARDED))
            assertEquals(InboxState.OPEN, s.inboxItem(b)!!.state)
            assertNull(s.inboxItem(b)!!.resolvedAtMillis)
        }

    @Test
    fun `a body edit is guarded against a stale read and never touches the original text`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "original"))
            s.resolveItem(a, InboxState.IDEA)
            assertEquals(EditBodyResult.EDITED, s.editBody(a, "original", "first edit"))
            assertEquals(EditBodyResult.STALE, s.editBody(a, "original", "from an old screen"))
            assertEquals("first edit", s.inboxItem(a)!!.body)
            assertEquals("original", s.inboxItem(a)!!.text)

            s.captureItem(InboxItem(b, "archived one"))
            s.resolveItem(b, InboxState.ARCHIVED)
            assertEquals(EditBodyResult.NOT_EDITABLE, s.editBody(b, null, "x"))
            assertEquals(EditBodyResult.NOT_FOUND, s.editBody("33333333-3333-4333-8333-333333333333", null, "x"))
        }

    @Test
    fun `putting a note back keeps its edited body, and a stale reopen is refused`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "original"))
            s.resolveItem(a, InboxState.NOTE)
            s.editBody(a, "original", "my edit")
            assertEquals(ReopenInboxResult.STALE, s.reopenItem(a, InboxState.IDEA))
            assertEquals(ReopenInboxResult.REOPENED, s.reopenItem(a, InboxState.NOTE))
            val reopened = s.inboxItem(a)!!
            assertEquals(InboxState.OPEN, reopened.state)
            assertEquals("my edit", reopened.body)
            assertEquals("original", reopened.text)
            // Filed again as a note, the earlier edit is still what it shows.
            s.resolveItem(a, InboxState.NOTE)
            assertEquals("my edit", s.inboxItem(a)!!.body)
        }

    @Test
    fun `converting creates the action and resolves the item together, without planning anything`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "call the bank"))
            val action = Action(InboxConversion.actionIdFor(a), "Call the bank")
            assertEquals(ConvertInboxResult.CONVERTED, s.convertToAction(a, action))
            val item = s.inboxItem(a)!!
            assertEquals(InboxState.ACTION, item.state)
            assertEquals(action.id, item.actionId)
            assertEquals("call the bank", item.text)
            assertEquals(action, s.action(action.id))
            assertEquals(emptyList(), s.snapshot().plans)
            assertEquals(emptyList(), s.snapshot().planItems)
        }

    @Test
    fun `a retried conversion is a success once, and a conflicting one never succeeds silently`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "call the bank"))
            val action = Action(InboxConversion.actionIdFor(a), "Call the bank")
            assertEquals(ConvertInboxResult.CONVERTED, s.convertToAction(a, action))
            assertEquals(ConvertInboxResult.ALREADY_CONVERTED, s.convertToAction(a, action))
            assertEquals(1, s.actions().size)

            assertEquals(ConvertInboxResult.CONFLICT, s.convertToAction(a, action.copy(title = "A different action")))
            assertEquals(ConvertInboxResult.CONFLICT, s.convertToAction(a, Action("another-id", "Call the bank")))
            assertEquals(listOf("Call the bank"), s.actions().map { it.title })
        }

    @Test
    fun `a conversion that is refused leaves the item open and creates no action`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "call the bank"))
            s.saveAction(Action("existing", "Call the bank"))
            assertEquals(ConvertInboxResult.DUPLICATE_TITLE, s.convertToAction(a, Action(InboxConversion.actionIdFor(a), "Call the bank")))
            assertEquals(
                ConvertInboxResult.PROJECT_NOT_FOUND,
                s.convertToAction(a, Action(InboxConversion.actionIdFor(a), "Other", "missing")),
            )
            s.saveProject(Project("done", "Done", null, ProjectState.COMPLETED))
            assertEquals(ConvertInboxResult.PROJECT_CLOSED, s.convertToAction(a, Action(InboxConversion.actionIdFor(a), "Other", "done")))
            assertEquals(InboxState.OPEN, s.inboxItem(a)!!.state)
            assertEquals(listOf("existing"), s.actions().map { it.id })
            assertEquals(ConvertInboxResult.ITEM_NOT_FOUND, s.convertToAction(b, Action(InboxConversion.actionIdFor(b), "x")))
        }

    @Test
    fun `an interruption inside the conversion rolls back both the action and the item`() =
        runTest {
            val s = store()
            val db = opened.last()
            s.captureItem(InboxItem(a, "call the bank"))
            runCatching {
                db.withTransaction {
                    check(s.convertToAction(a, Action(InboxConversion.actionIdFor(a), "Call the bank")) == ConvertInboxResult.CONVERTED)
                    error("interrupted before commit")
                }
            }
            assertEquals(InboxState.OPEN, s.inboxItem(a)!!.state)
            assertEquals(emptyList(), s.actions())
            assertEquals(ConvertInboxResult.CONVERTED, s.convertToAction(a, Action(InboxConversion.actionIdFor(a), "Call the bank")))
        }

    @Test
    fun `an item that became an action is not put back in the Inbox, and nor is a filed one through the wrong state`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "x"))
            s.convertToAction(a, Action(InboxConversion.actionIdFor(a), "X"))
            assertEquals(ReopenInboxResult.NOT_REOPENABLE, s.reopenItem(a, InboxState.ACTION))
            assertEquals(ResolveInboxResult.NOT_OPEN, s.resolveItem(a, InboxState.ARCHIVED))
            assertEquals(InboxState.ACTION, s.inboxItem(a)!!.state)
            assertEquals(1, s.actions().size)
        }

    @Test
    fun `every state survives a snapshot and a restore into an empty database`() =
        runTest {
            val s = store()
            val c = "33333333-3333-4333-8333-333333333333"
            val d = "44444444-4444-4444-8444-444444444444"
            s.captureItem(InboxItem(a, "open one"))
            s.captureItem(InboxItem(b, "a note"))
            s.resolveItem(b, InboxState.NOTE)
            s.editBody(b, "a note", "edited note")
            s.captureItem(InboxItem(c, "to action"))
            s.convertToAction(c, Action(InboxConversion.actionIdFor(c), "Made"))
            s.captureItem(InboxItem(d, "dropped"))
            s.resolveItem(d, InboxState.DISCARDED)
            val snapshot = s.snapshot()
            assertEquals(4, snapshot.counts.inboxItems)

            val other = store(otherName)
            assertTrue(other.restoreIntoEmpty(snapshot))
            assertEquals(snapshot.inboxItems, other.snapshot().inboxItems)
            assertEquals(snapshot, other.snapshot())
        }

    @Test
    fun `a database holding only an Inbox item is not empty, so a restore refuses to write into it`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(a, "one"))
            assertFalse(s.restoreIntoEmpty(store(otherName).also { it.captureItem(InboxItem(b, "two")) }.snapshot()))
            assertEquals(listOf("one"), s.inbox().map { it.value.text })
        }
}
