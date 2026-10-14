package com.thalia.app.reminders

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.planning.Action
import com.thalia.planning.InboxItem
import com.thalia.planning.PlanningSnapshot
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderReason
import com.thalia.planning.ReminderRules
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import com.thalia.voicecheckins.domain.QuietPeriod
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class FakeAlarms : ReminderAlarms {
    val armed = linkedMapOf<String, Long>()
    var armCalls = 0

    override fun arm(
        id: String,
        atMillis: Long,
    ) {
        armCalls++
        armed[id] = atMillis
    }

    override fun cancel(id: String) {
        armed.remove(id)
    }

    override fun armedIds(): Set<String> = armed.keys.toSet()
}

private class FakeNotifier : ReminderNotifier {
    var allowed = true
    var failOnPost = false
    var duringPost: () -> Unit = {}
    val posted = linkedSetOf<String>()
    var postCalls = 0

    override fun canPost() = allowed

    override fun post(id: String) {
        postCalls++
        duringPost()
        check(!failOnPost) { "the platform refused" }
        posted += id
    }

    override fun withdraw(id: String) {
        posted -= id
    }

    override fun postedIds(): Set<String> = posted.toSet()
}

/** ADR 0014: creation rules, delivery, cancellation, duplicate callbacks and recovery, against fake alarms and a fake notification manager. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReminderServiceTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "reminder-service-test.db"
    private val otherName = "reminder-service-other.db"
    private val opened = mutableListOf<PlanningDatabase>()
    private val utc = ZoneId.of("UTC")
    private val min = 60_000L
    private val noon = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
    private var now = noon
    private var quiet = QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = false)
    private val alarms = FakeAlarms()
    private val notifier = FakeNotifier()
    private val service = ReminderService(alarms, notifier, { quiet }, { utc }, { now })

    private val item = "11111111-1111-4111-8111-111111111111"
    private val action = "22222222-2222-4222-8222-222222222222"

    private fun store(file: String = dbName): PlanningStore {
        val db = plainPlanningDatabase(context, context.getDatabasePath(file))
        opened += db
        return PlanningStore(db) { now }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
        context.deleteDatabase(otherName)
    }

    private suspend fun checkpoint(
        s: PlanningStore,
        inMinutes: Long = 30,
        id: String = ReminderRules.newId(),
    ): Reminder {
        val outcome = service.create(s, ReminderTargetType.NONE, null, "", now + inMinutes * min, id)
        return assertIs<CreateOutcome.Created>(outcome).reminder
    }

    private suspend fun refusedWith(
        s: PlanningStore,
        due: Long,
        type: ReminderTargetType = ReminderTargetType.NONE,
        target: String? = null,
    ) = assertIs<CreateOutcome.Refused>(service.create(s, type, target, "text", due)).why

    // Creation

    @Test
    fun `creating a reminder stores it waiting, arms one alarm at its instant, and remembers the zone`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(item, "  Call the dentist\nabout the crown "))
            val r =
                assertIs<CreateOutcome.Created>(
                    service.create(
                        s,
                        ReminderTargetType.INBOX_ITEM,
                        item,
                        "  Call the dentist\nabout the crown ",
                        now + 90 * min,
                    ),
                ).reminder
            assertEquals(ReminderStatus.SCHEDULED, r.status)
            assertEquals("Call the dentist", r.label)
            assertEquals("UTC", r.zone)
            assertEquals(mapOf(r.id to now + 90 * min), alarms.armed)
            assertEquals(listOf(r), s.reminders())
            assertEquals(0, notifier.postCalls)
        }

    @Test
    fun `a checkpoint has no target and a fixed label`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            assertEquals(ReminderTargetType.NONE, r.targetType)
            assertEquals(ReminderRules.CHECKPOINT_LABEL, r.label)
        }

    @Test
    fun `a refusal stores nothing and arms nothing, and says why`() =
        runTest {
            val s = store()
            assertEquals(ReminderRefusal.TOO_SOON, refusedWith(s, now + 30_000))
            assertEquals(ReminderRefusal.TOO_FAR, refusedWith(s, now + ReminderRules.HORIZON_MILLIS + 1))
            assertEquals(ReminderRefusal.TARGET_NOT_FOUND, refusedWith(s, now + 10 * min, ReminderTargetType.ACTION, action))
            quiet = quiet.copy(enabled = true)
            // 22:30 UTC the same day is inside a 22:00 to 07:00 quiet period.
            assertEquals(ReminderRefusal.QUIET_PERIOD, refusedWith(s, noon + 10 * 60 * min + 30 * min))
            quiet = quiet.copy(enabled = false)
            assertEquals(emptyList(), s.reminders())
            assertTrue(alarms.armed.isEmpty())
            assertEquals(0, alarms.armCalls)
        }

    @Test
    fun `with notifications blocked nothing is created, whatever else is fine`() =
        runTest {
            val s = store()
            notifier.allowed = false
            assertEquals(ReminderRefusal.NOTIFICATIONS_BLOCKED, refusedWith(s, now + 30 * min))
            assertEquals(emptyList(), s.reminders())
            assertEquals(0, alarms.armCalls)
        }

    @Test
    fun `the twentieth reminder is allowed and the twenty-first is refused`() =
        runTest {
            val s = store()
            repeat(ReminderRules.MAX_PENDING) { checkpoint(s, 10L + it) }
            assertEquals(ReminderRefusal.TOO_MANY, refusedWith(s, now + 5 * min))
            assertEquals(ReminderRules.MAX_PENDING, alarms.armed.size)
        }

    @Test
    fun `a retried creation with the same id makes one reminder and one alarm`() =
        runTest {
            val s = store()
            val id = ReminderRules.newId()
            checkpoint(s, 30, id)
            checkpoint(s, 30, id).also { assertEquals(id, it.id) }
            assertEquals(1, s.reminders().size)
            assertEquals(1, alarms.armed.size)
        }

    // Delivery

    @Test
    fun `an alarm posts one generic notification and the row says posted, response unknown`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis + 4 * min // inexact: later than asked, inside the window
            assertEquals(FireOutcome.Posted, service.onAlarm(s, r.id))
            val stored = s.reminder(r.id)!!
            assertEquals(ReminderStatus.POSTED, stored.status)
            assertEquals(now, stored.postedAtMillis)
            assertNull(stored.resolvedAtMillis)
            assertEquals(setOf(r.id), notifier.posted)
            assertEquals("Notification posted; response unknown", ReminderText.status(stored))
        }

    @Test
    fun `a duplicate delivery posts nothing more and changes nothing`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis
            assertEquals(FireOutcome.Posted, service.onAlarm(s, r.id))
            val after = s.reminder(r.id)
            now += 5 * min
            assertEquals(FireOutcome.Ignored, service.onAlarm(s, r.id))
            assertEquals(FireOutcome.Ignored, service.onAlarm(s, r.id))
            assertEquals(1, notifier.postCalls)
            assertEquals(after, s.reminder(r.id))
        }

    @Test
    fun `an alarm for a reminder that is gone is ignored`() =
        runTest {
            assertEquals(FireOutcome.Ignored, service.onAlarm(store(), ReminderRules.newId()))
            assertEquals(0, notifier.postCalls)
        }

    @Test
    fun `past the sixty minute window nothing is posted late, and the reason is the observed one`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis + 61 * min
            assertEquals(FireOutcome.NotPosted(ReminderReason.WINDOW_EXPIRED), service.onAlarm(s, r.id))
            assertEquals(ReminderStatus.NOT_POSTED, s.reminder(r.id)!!.status)
            assertEquals(ReminderReason.WINDOW_EXPIRED, s.reminder(r.id)!!.reason)
            assertEquals(0, notifier.postCalls)
            assertFalse("phone" in ReminderText.status(s.reminder(r.id)!!))
        }

    @Test
    fun `exactly at the edge of the window it still posts`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis + ReminderRules.WINDOW_MILLIS
            assertEquals(FireOutcome.Posted, service.onAlarm(s, r.id))
        }

    @Test
    fun `the quiet period at delivery withholds the notification`() =
        runTest {
            val s = store()
            quiet = quiet.copy(enabled = true)
            // Set at 21:00 for 21:55, which is outside the period; delivered at 22:05, which is inside it.
            now = Instant.parse("2026-10-10T21:00:00Z").toEpochMilli()
            val r = checkpoint(s, 55)
            now = Instant.parse("2026-10-10T22:05:00Z").toEpochMilli()
            assertEquals(FireOutcome.NotPosted(ReminderReason.QUIET_PERIOD), service.onAlarm(s, r.id))
            assertEquals(0, notifier.postCalls)
        }

    @Test
    fun `notifications turned off after creation are recorded as blocked, not posted`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            notifier.allowed = false
            now = r.dueAtMillis
            assertEquals(FireOutcome.NotPosted(ReminderReason.NOTIFICATIONS_BLOCKED), service.onAlarm(s, r.id))
            assertEquals(ReminderReason.NOTIFICATIONS_BLOCKED, s.reminder(r.id)!!.reason)
        }

    @Test
    fun `a platform failure to post is recorded as not posted, never as posted`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            notifier.failOnPost = true
            now = r.dueAtMillis
            assertEquals(FireOutcome.NotPosted(ReminderReason.NOTIFICATIONS_BLOCKED), service.onAlarm(s, r.id))
            assertEquals(ReminderStatus.NOT_POSTED, s.reminder(r.id)!!.status)
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `an alarm that arrives before the instant arms again and changes nothing`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            alarms.armed.clear()
            now = r.dueAtMillis - 5 * min
            assertEquals(FireOutcome.Early, service.onAlarm(s, r.id))
            assertEquals(mapOf(r.id to r.dueAtMillis), alarms.armed)
            assertEquals(r, s.reminder(r.id))
            assertEquals(0, notifier.postCalls)
        }

    @Test
    fun `a cancel that wins the race with a delivery leaves nothing posted`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis
            notifier.duringPost =
                { kotlinx.coroutines.runBlocking { s.moveReminder(r.id, ReminderStatus.SCHEDULED, ReminderStatus.CANCELLED, now) } }
            assertEquals(FireOutcome.Ignored, service.onAlarm(s, r.id))
            assertEquals(ReminderStatus.CANCELLED, s.reminder(r.id)!!.status)
            assertTrue(notifier.posted.isEmpty())
        }

    // Cancellation and the person's answers

    @Test
    fun `cancelling removes the alarm first and then the row, and a late delivery does nothing`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            assertTrue(service.cancel(s, r.id))
            assertTrue(alarms.armed.isEmpty())
            assertEquals(ReminderStatus.CANCELLED, s.reminder(r.id)!!.status)
            now = r.dueAtMillis
            assertEquals(FireOutcome.Ignored, service.onAlarm(s, r.id))
            assertEquals(0, notifier.postCalls)
            assertFalse(service.cancel(s, r.id))
        }

    @Test
    fun `a posted reminder cannot be cancelled, only acknowledged or dismissed`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis
            service.onAlarm(s, r.id)
            assertFalse(service.cancel(s, r.id))
            assertEquals(ReminderStatus.POSTED, s.reminder(r.id)!!.status)
        }

    @Test
    fun `acknowledge and dismiss resolve a posted reminder once and withdraw its notification`() =
        runTest {
            val s = store()
            val a = checkpoint(s)
            val d = checkpoint(s, 31)
            now = d.dueAtMillis
            service.onAlarm(s, a.id)
            service.onAlarm(s, d.id)
            assertTrue(service.acknowledge(s, a.id))
            assertFalse(service.acknowledge(s, a.id))
            assertFalse(service.dismiss(s, a.id))
            assertTrue(service.dismiss(s, d.id))
            assertEquals(ReminderStatus.ACKNOWLEDGED, s.reminder(a.id)!!.status)
            assertEquals(ReminderStatus.DISMISSED, s.reminder(d.id)!!.status)
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `a waiting reminder cannot be acknowledged`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            assertFalse(service.acknowledge(s, r.id))
            assertEquals(ReminderStatus.SCHEDULED, s.reminder(r.id)!!.status)
        }

    @Test
    fun `an unanswered reminder stays posted, response unknown, with no retry, however long it is left`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis
            service.onAlarm(s, r.id)
            val posted = s.reminder(r.id)
            now += 400L * 24 * 60 * min
            repeat(3) { service.reconcile(s) }
            assertEquals(FireOutcome.Ignored, service.onAlarm(s, r.id))
            assertEquals(posted, s.reminder(r.id))
            assertEquals(1, notifier.postCalls)
            assertEquals(setOf(r.id), notifier.posted)
        }

    // Nothing else changes

    @Test
    fun `every step leaves plan data exactly as it was, and a vanished target does not stop the reminder`() =
        runTest {
            val s = store()
            s.captureItem(InboxItem(item, "ring the dentist"))
            s.createAction(Action(action, "File taxes"))
            val plain: suspend () -> PlanningSnapshot = { s.snapshot().copy(reminders = emptyList()) }
            val before = plain()
            val onItem =
                assertIs<CreateOutcome.Created>(
                    service.create(
                        s,
                        ReminderTargetType.INBOX_ITEM,
                        item,
                        "ring the dentist",
                        now + 10 * min,
                    ),
                ).reminder
            val onAction =
                assertIs<CreateOutcome.Created>(
                    service.create(s, ReminderTargetType.ACTION, action, "File taxes", now + 20 * min),
                ).reminder
            val onNothing = checkpoint(s, 30)
            assertEquals(before, plain())
            assertTrue(s.undoCapture(item, "ring the dentist")) // the target goes; this is the Inbox's own write, not the reminder's
            val withoutItem = plain()
            now = onNothing.dueAtMillis
            listOf(onItem, onAction, onNothing).forEach { assertEquals(FireOutcome.Posted, service.onAlarm(s, it.id)) }
            service.acknowledge(s, onItem.id)
            service.dismiss(s, onAction.id)
            service.reconcile(s)
            assertEquals(withoutItem, plain())
            assertEquals(ReminderStatus.ACKNOWLEDGED, s.reminder(onItem.id)!!.status)
            assertEquals("ring the dentist", s.reminder(onItem.id)!!.label)
        }

    // Recovery

    @Test
    fun `after a restart the waiting reminders are armed again and nothing is posted`() =
        runTest {
            val s = store()
            val a = checkpoint(s, 30)
            val b = checkpoint(s, 45)
            alarms.armed.clear() // Android drops every alarm at a restart
            val report = service.reconcile(s)
            assertEquals(mapOf(a.id to a.dueAtMillis, b.id to b.dueAtMillis), alarms.armed)
            assertEquals(2, report.armed)
            assertEquals(0, notifier.postCalls)
        }

    @Test
    fun `a reminder whose window passed while the phone was away is expired, never posted late`() =
        runTest {
            val s = store()
            val r = checkpoint(s, 30)
            alarms.armed.clear()
            now = r.dueAtMillis + 2 * 60 * min
            val report = service.reconcile(s)
            assertEquals(1, report.expired)
            assertEquals(ReminderReason.WINDOW_EXPIRED, s.reminder(r.id)!!.reason)
            assertTrue(alarms.armed.isEmpty())
            assertEquals(0, notifier.postCalls)
        }

    @Test
    fun `reconciling twice changes nothing the second time`() =
        runTest {
            val s = store()
            checkpoint(s, 30)
            val late = checkpoint(s, 31)
            val posted = checkpoint(s, 32)
            now = posted.dueAtMillis
            service.onAlarm(s, posted.id)
            now = late.dueAtMillis + 3 * 60 * min
            service.reconcile(s)
            val rows = s.reminders()
            val armed = alarms.armed.toMap()
            val second = service.reconcile(s)
            assertEquals(rows, s.reminders())
            assertEquals(armed, alarms.armed)
            assertEquals(0, second.expired + second.staleAlarmsCancelled + second.notificationsWithdrawn)
        }

    @Test
    fun `an alarm for a reminder that is no longer waiting is cancelled, and so is a notification with no posted row`() =
        runTest {
            val s = store()
            val done = checkpoint(s, 30)
            now = done.dueAtMillis
            service.onAlarm(s, done.id)
            service.acknowledge(s, done.id)
            alarms.armed["stale-1"] = now + 5 * min
            alarms.armed[done.id] = now + 5 * min
            notifier.posted += "ghost"
            notifier.posted += done.id
            val report = service.reconcile(s)
            assertEquals(2, report.staleAlarmsCancelled)
            assertEquals(2, report.notificationsWithdrawn)
            assertTrue(alarms.armed.isEmpty())
            assertTrue(notifier.posted.isEmpty())
            assertEquals(ReminderStatus.ACKNOWLEDGED, s.reminder(done.id)!!.status)
        }

    @Test
    fun `a posted reminder keeps its notification through reconciliation`() =
        runTest {
            val s = store()
            val r = checkpoint(s)
            now = r.dueAtMillis
            service.onAlarm(s, r.id)
            service.reconcile(s)
            assertEquals(setOf(r.id), notifier.posted)
        }

    @Test
    fun `after a restore the old alarms go, the restored waiting ones are armed, and resolved reminders stay resolved`() =
        runTest {
            // The data to restore: one waiting, one acknowledged, one cancelled, one unanswered.
            val source = store(otherName)
            val waiting = checkpoint(source, 30)
            val acked = checkpoint(source, 31)
            val cancelled = checkpoint(source, 32)
            val unanswered = checkpoint(source, 33)
            now = unanswered.dueAtMillis
            service.onAlarm(source, acked.id)
            service.onAlarm(source, unanswered.id)
            service.acknowledge(source, acked.id)
            service.cancel(source, cancelled.id)
            val snapshot = source.snapshot()

            // The phone being restored holds other alarms and a leftover notification.
            alarms.armed.clear()
            notifier.posted.clear()
            alarms.armed["old-1"] = now + min
            alarms.armed[acked.id] = now + min
            notifier.posted += "old-1"
            val target = store()
            assertTrue(target.restoreIntoEmpty(snapshot))
            service.reconcile(target)

            assertEquals(setOf(waiting.id), alarms.armed.keys)
            assertEquals(ReminderStatus.ACKNOWLEDGED, target.reminder(acked.id)!!.status)
            assertEquals(ReminderStatus.CANCELLED, target.reminder(cancelled.id)!!.status)
            assertEquals(ReminderStatus.POSTED, target.reminder(unanswered.id)!!.status)
            // The leftover notification is withdrawn, and reconciliation never posts one: the unanswered reminder stays unanswered.
            assertTrue(notifier.posted.isEmpty())
        }

    @Test
    fun `setting the data aside leaves no alarm and no notification for the reminders that were there`() =
        runTest {
            val s = store()
            val waiting = checkpoint(s, 30)
            val posted = checkpoint(s, 31)
            now = posted.dueAtMillis
            service.onAlarm(s, posted.id)
            assertTrue(waiting.id in alarms.armed)
            context.deleteDatabase(dbName)
            opened.forEach { it.close() }
            val fresh = store()
            assertEquals(emptyList(), fresh.reminders())
            service.reconcile(fresh)
            assertTrue(alarms.armed.isEmpty())
            assertTrue(notifier.posted.isEmpty())
        }
}
