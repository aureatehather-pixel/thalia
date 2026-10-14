package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.InboxItem
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderReason
import com.thalia.planning.ReminderRules
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
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

/** ADR 0014 at the store: creation, the compare-and-set moves, the table in snapshot and restore, and independence from targets. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningReminderStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "planning-reminder-test.db"
    private val otherName = "planning-reminder-restore.db"
    private val opened = mutableListOf<PlanningDatabase>()
    private var now = 1_000L

    private val ids = (1..25).map { "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c%03d".format(it) }
    private val item = "11111111-1111-4111-8111-111111111111"
    private val action = "22222222-2222-4222-8222-222222222222"

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

    private fun reminder(
        id: String = ids[0],
        type: ReminderTargetType = ReminderTargetType.NONE,
        targetId: String? = null,
    ) = Reminder(id, type, targetId, "label", 500_000L, "UTC", createdAtMillis = 10)

    @Test
    fun `a checkpoint is stored whole, and creating it again is a retry that changes nothing`() =
        runTest {
            val s = store()
            assertEquals(CreateReminderResult.CREATED, s.createReminder(reminder()))
            assertEquals(CreateReminderResult.ALREADY_CREATED, s.createReminder(reminder()))
            assertEquals(listOf(reminder()), s.reminders())
            assertEquals(CreateReminderResult.CONFLICT, s.createReminder(reminder().copy(dueAtMillis = 600_000L)))
            assertEquals(500_000L, s.reminder(ids[0])!!.dueAtMillis)
        }

    @Test
    fun `a target must exist when the reminder is made, and is not needed afterwards`() =
        runTest {
            val s = store()
            assertEquals(
                CreateReminderResult.TARGET_NOT_FOUND,
                s.createReminder(reminder(type = ReminderTargetType.INBOX_ITEM, targetId = item)),
            )
            assertEquals(
                CreateReminderResult.TARGET_NOT_FOUND,
                s.createReminder(reminder(type = ReminderTargetType.ACTION, targetId = action)),
            )
            s.captureItem(InboxItem(item, "ring the dentist"))
            s.createAction(Action(action, "File taxes"))
            assertEquals(CreateReminderResult.CREATED, s.createReminder(reminder(ids[0], ReminderTargetType.INBOX_ITEM, item)))
            assertEquals(CreateReminderResult.CREATED, s.createReminder(reminder(ids[1], ReminderTargetType.ACTION, action)))
            assertEquals(2, s.reminders().size)
        }

    @Test
    fun `removing the target leaves the reminder as it was and changes no plan data`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(item, "ring the dentist"))
            s.createReminder(reminder(ids[0], ReminderTargetType.INBOX_ITEM, item))
            val plainBefore = s.snapshot().copy(reminders = emptyList(), inboxItems = emptyList())
            // Undo of a capture removes the row; a reminder has no foreign key to hold it or follow it.
            assertTrue(s.undoCapture(item, "ring the dentist"))
            assertNull(s.inboxItem(item))
            assertEquals(listOf(reminder(ids[0], ReminderTargetType.INBOX_ITEM, item)), s.reminders())
            assertEquals(plainBefore, s.snapshot().copy(reminders = emptyList(), inboxItems = emptyList()))
        }

    @Test
    fun `no more than twenty can be waiting, and a resolved or cancelled one frees a place`() =
        runTest {
            val s = store()
            (0 until ReminderRules.MAX_PENDING).forEach { assertEquals(CreateReminderResult.CREATED, s.createReminder(reminder(ids[it]))) }
            assertEquals(CreateReminderResult.TOO_MANY, s.createReminder(reminder(ids[20])))
            assertTrue(s.moveReminder(ids[3], ReminderStatus.SCHEDULED, ReminderStatus.CANCELLED, 9))
            assertEquals(CreateReminderResult.CREATED, s.createReminder(reminder(ids[20])))
            assertEquals(ReminderRules.MAX_PENDING + 1, s.reminders().size)
        }

    @Test
    fun `a move happens once, from the stated status only, so duplicates and stale taps change nothing`() =
        runTest {
            val s = store()
            s.createReminder(reminder())
            assertFalse(s.moveReminder(ids[0], ReminderStatus.POSTED, ReminderStatus.ACKNOWLEDGED, 5))
            assertTrue(s.moveReminder(ids[0], ReminderStatus.SCHEDULED, ReminderStatus.POSTED, 5))
            assertFalse(s.moveReminder(ids[0], ReminderStatus.SCHEDULED, ReminderStatus.POSTED, 6))
            assertEquals(5L, s.reminder(ids[0])!!.postedAtMillis)
            assertTrue(s.moveReminder(ids[0], ReminderStatus.POSTED, ReminderStatus.DISMISSED, 7))
            // A resolved reminder is never revived, whatever the stale caller believed.
            assertFalse(s.moveReminder(ids[0], ReminderStatus.SCHEDULED, ReminderStatus.POSTED, 8))
            assertFalse(s.moveReminder(ids[1], ReminderStatus.SCHEDULED, ReminderStatus.POSTED, 8))
            assertEquals(ReminderStatus.DISMISSED, s.reminder(ids[0])!!.status)
            assertEquals(7L, s.reminder(ids[0])!!.resolvedAtMillis)
            // An illegal step is refused by the model rather than written.
            s.createReminder(reminder(ids[2]))
            assertFailsWith<IllegalArgumentException> { s.moveReminder(ids[2], ReminderStatus.SCHEDULED, ReminderStatus.ACKNOWLEDGED, 9) }
            assertEquals(ReminderStatus.SCHEDULED, s.reminder(ids[2])!!.status)
        }

    @Test
    fun `a not-posted move records its observed reason`() =
        runTest {
            val s = store()
            s.createReminder(reminder())
            assertTrue(s.moveReminder(ids[0], ReminderStatus.SCHEDULED, ReminderStatus.NOT_POSTED, 5, ReminderReason.QUIET_PERIOD))
            val r = s.reminder(ids[0])!!
            assertEquals(ReminderReason.QUIET_PERIOD, r.reason)
            assertNull(r.postedAtMillis)
            assertEquals(5L, r.resolvedAtMillis)
        }

    @Test
    fun `every status round-trips through a snapshot, and a restore never changes one`() =
        runTest {
            val s = store()
            (0 until 5).forEach { s.createReminder(reminder(ids[it])) }
            s.moveReminder(ids[1], ReminderStatus.SCHEDULED, ReminderStatus.POSTED, 20)
            s.moveReminder(ids[2], ReminderStatus.SCHEDULED, ReminderStatus.POSTED, 20)
            s.moveReminder(ids[2], ReminderStatus.POSTED, ReminderStatus.ACKNOWLEDGED, 21)
            s.moveReminder(ids[3], ReminderStatus.SCHEDULED, ReminderStatus.CANCELLED, 22)
            s.moveReminder(ids[4], ReminderStatus.SCHEDULED, ReminderStatus.NOT_POSTED, 23, ReminderReason.WINDOW_EXPIRED)
            val snapshot = s.snapshot()
            assertEquals(5, snapshot.counts.reminders)

            val other = store(otherName)
            assertTrue(other.restoreIntoEmpty(snapshot))
            assertEquals(snapshot.reminders, other.snapshot().reminders)
            assertEquals(
                listOf(
                    ReminderStatus.SCHEDULED,
                    ReminderStatus.POSTED,
                    ReminderStatus.ACKNOWLEDGED,
                    ReminderStatus.CANCELLED,
                    ReminderStatus.NOT_POSTED,
                ),
                other.reminders().sortedBy { it.id }.map { it.status },
            )
        }

    @Test
    fun `a database holding only a reminder is not empty, so a restore refuses to merge into it`() =
        runTest {
            val s = store()
            s.createReminder(reminder())
            assertFalse(s.restoreIntoEmpty(store(otherName).snapshot()))
            assertEquals(1, s.reminders().size)
        }
}
