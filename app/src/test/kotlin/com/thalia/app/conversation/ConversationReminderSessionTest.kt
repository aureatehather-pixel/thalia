package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.app.reminders.CreateOutcome
import com.thalia.app.reminders.ReminderAlarms
import com.thalia.app.reminders.ReminderNotifier
import com.thalia.app.reminders.ReminderService
import com.thalia.conversation.Proposal
import com.thalia.conversation.RawCall
import com.thalia.planning.Action
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Reminder
import com.thalia.planning.ReminderStatus
import com.thalia.planning.ReminderTargetType
import com.thalia.voicecheckins.domain.QuietPeriod
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class RecordingAlarms : ReminderAlarms {
    val armed = linkedMapOf<String, Long>()

    override fun arm(
        id: String,
        atMillis: Long,
    ) {
        armed[id] = atMillis
    }

    override fun cancel(id: String) {
        armed.remove(id)
    }

    override fun armedIds(): Set<String> = armed.keys.toSet()
}

private class QuietNotifier : ReminderNotifier {
    var allowed = true
    val posted = linkedSetOf<String>()

    override fun canPost() = allowed

    override fun post(id: String) {
        posted += id
    }

    override fun withdraw(id: String) {
        posted -= id
    }

    override fun postedIds(): Set<String> = posted.toSet()
}

/** A port whose reminder write commits and then loses its reply, as a process stop would look. */
private class LostReplyPort(
    private val inner: ConversationPort,
) : ConversationPort by inner {
    override suspend fun createReminder(proposal: Proposal.Reminder): CreateOutcome {
        inner.createReminder(proposal)
        throw IOException("the reply was lost after the commit")
    }
}

/** A port whose reminder cancel commits and then loses its reply, or fails before writing, or cannot read the reminder back. */
private class CancelLostPort(
    private val inner: ConversationPort,
    private val failAfterCommit: Boolean,
    private val unreadable: Boolean = false,
) : ConversationPort by inner {
    var fail = true

    override suspend fun cancelReminder(id: String): Boolean {
        if (!failAfterCommit) {
            if (fail) throw IOException("the write failed")
            return inner.cancelReminder(id)
        }
        inner.cancelReminder(id)
        throw IOException("the reply was lost after the commit")
    }

    override suspend fun reminder(id: String): Reminder? = if (unreadable && fail) throw IOException("cannot read") else inner.reminder(id)
}

/** Slice 5 reminders through the conversation: the real session, port, reminder service and store, with fake alarms and a fake notifier. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationReminderSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "conversation-reminder-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")
    private var current = Instant.parse("2026-10-06T10:00:00Z")
    private var clock = 1_000L
    private val provider = ScriptedProvider()
    private val alarms = RecordingAlarms()
    private val notifier = QuietNotifier()
    private var quiet = QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = false)
    private lateinit var store: PlanningStore
    private val service = ReminderService(alarms, notifier, { quiet }, { utc }, { current.toEpochMilli() })

    private val runner =
        object : StoreRunner {
            override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
        }

    private fun port(withReminders: Boolean = true) =
        StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { current }, if (withReminders) service else null)

    private suspend fun setUp() {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        store = PlanningStore(db) { clock++ }
        store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        store.saveAction(Action("id-read", "Read paper", null, Priority.NORMAL, 30))
    }

    private fun session(p: ConversationPort = port()) = ConversationSession(p, provider) { "rem-${clock++}" }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    private fun call(
        name: String,
        args: String,
    ) = RawCall(name, args)

    private fun ConversationSession.entries() = state.value.entries

    private suspend fun noReminder() {
        assertTrue(store.reminders().isEmpty())
        assertTrue(alarms.armed.isEmpty())
        assertTrue(notifier.posted.isEmpty())
    }

    @Test
    fun `a reminder waits for a tap with an accurate summary, and nothing is stored, armed or claimed before it`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                "Reminder set for 3pm!",
                call("propose_reminder", """{"at":"2026-10-07T15:00","action_ref":"a1","reason":"a1 is due"}"""),
            )

            s.send("remind me about the paper tomorrow at 3")

            noReminder()
            val pending = assertNotNull(s.state.value.pending)
            assertEquals("Set reminder", pending.approveLabel)
            assertEquals(
                "Set a one-time reminder around Wed 7 Oct, 15:00 about \"Read paper\". " +
                    "Thalia will post one quiet notification, then wait for you. You can cancel it under Reminders.",
                pending.summary,
            )
            assertTrue(s.entries().none { it is Entry.Saved })
            // A model-authored reason is labelled and never part of the deterministic summary.
            val proposed =
                s
                    .entries()
                    .filterIsInstance<Entry.Note>()
                    .first { "Proposed, not applied yet." in it.text }
                    .text
            assertTrue(proposed.endsWith("${pending.summary} AI explanation: \"Read paper\" is due"), proposed)
            assertTrue("AI explanation" !in pending.summary)
            assertEquals("AI explanation: Reminder set for 3pm!", (s.entries().last() as Entry.Assistant).text)
            s.send("yes")
            noReminder()
        }

    @Test
    fun `one tap sets exactly one reminder for the action, repeated taps add nothing, and only then is it claimed`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-07T15:00","action_ref":"a1"}"""))
            s.send("remind me about the paper tomorrow at 3")

            s.approve()
            s.approve()
            s.send("yes")

            val reminder = store.reminders().single()
            assertEquals(ReminderTargetType.ACTION, reminder.targetType)
            assertEquals("id-read", reminder.targetId)
            assertEquals("Read paper", reminder.label)
            assertEquals(ReminderStatus.SCHEDULED, reminder.status)
            assertEquals(Instant.parse("2026-10-07T15:00:00Z").toEpochMilli(), reminder.dueAtMillis)
            assertEquals(setOf(reminder.id), alarms.armed.keys)
            assertTrue(notifier.posted.isEmpty())
            val saved =
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .single()
                    .text
            assertEquals(
                "Reminder set for Wed 7 Oct, 15:00. Thalia will post one quiet notification, then wait. Cancel it under Reminders.",
                saved,
            )
            // Nothing else was touched.
            assertEquals("Read paper", store.action("id-read")?.title)
            assertNull(s.state.value.pending)
        }

    @Test
    fun `a checkpoint reminder has no target, and dismissing a pending reminder sets nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-06T16:00"}"""))
            s.send("check in with me at four")
            assertTrue(
                "as a generic checkpoint, which carries no description of what it is for" in assertNotNull(s.state.value.pending).summary,
            )

            s.dismiss()
            s.approve()

            noReminder()
            assertTrue(s.entries().any { it is Entry.Note && it.text == "Dismissed. Nothing was applied." })
            assertTrue(s.entries().none { it is Entry.Saved })

            provider.reply(null, call("propose_reminder", """{"at":"2026-10-06T16:00"}"""))
            s.send("check in with me at four")
            s.approve()
            val reminder = store.reminders().single()
            assertEquals(ReminderTargetType.NONE, reminder.targetType)
            assertNull(reminder.targetId)
        }

    @Test
    fun `a dropped target cannot be detected by Kotlin, so the summary says the checkpoint is generic before the tap`() =
        runTest {
            setUp()
            val s = session()
            // The model drops "call the vendor": the call has no field for the person's words, so this is a valid call.
            provider.reply("I'll remind you to call the vendor.", call("propose_reminder", """{"at":"2026-10-06T15:00"}"""))
            s.send("remind me at 3pm to call the vendor")

            val pending = assertNotNull(s.state.value.pending)
            assertEquals(
                "Set a one-time reminder around Tue 6 Oct, 15:00 as a generic checkpoint, " +
                    "which carries no description of what it is for. " +
                    "Thalia will post one quiet notification, then wait for you. You can cancel it under Reminders.",
                pending.summary,
            )
            // The model's claim is only an explanation, and nothing is stored until the tap.
            assertEquals("AI explanation: I'll remind you to call the vendor.", (s.entries().last() as Entry.Assistant).text)
            noReminder()
            s.dismiss()
            noReminder()
            // The matching action, by contrast, is named in the summary the person approves.
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-06T15:00","action_ref":"a1"}"""))
            s.send("remind me at 3pm to read the paper")
            assertTrue("about \"Read paper\"" in assertNotNull(s.state.value.pending).summary)
        }

    @Test
    fun `missing, invalid or out of range inputs are asked about and nothing is stored`() =
        runTest {
            setUp()
            val s = session()
            listOf(
                """{"action_ref":"a1"}""" to "What day and time should the reminder be for?",
                """{"at":"tomorrow at three"}""" to "What day and time should the reminder be for?",
                """{"at":"2026-10-06T09:00"}""" to "too soon or already past",
                """{"at":"2026-10-20T09:00"}""" to "too far away",
                """{"at":"2026-10-07T15:00","action_ref":"a42"}""" to "Which one is it?",
            ).forEach { (args, expected) ->
                provider.reply(null, call("propose_reminder", args))
                s.send("remind me")
                val asked = (s.entries().last() as Entry.Assistant).text
                assertTrue(expected in asked, asked)
                assertNull(s.state.value.pending)
                s.approve()
                noReminder()
            }
            assertTrue(s.entries().none { it is Entry.Saved })
        }

    @Test
    fun `the reminder rules apply again at the tap, so a refusal says nothing was scheduled and claims nothing`() =
        runTest {
            setUp()
            val s = session()

            // Notifications turned off after the proposal.
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-07T15:00"}"""))
            s.send("remind me tomorrow at 3")
            notifier.allowed = false
            s.approve()
            assertTrue("Nothing was scheduled" in (s.entries().last() as Entry.NotApplied).text)
            noReminder()
            notifier.allowed = true

            // A quiet period over the time.
            quiet = QuietPeriod(LocalTime.of(14, 0), LocalTime.of(16, 0), enabled = true)
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-07T15:00"}"""))
            s.send("remind me tomorrow at 3")
            s.approve()
            assertTrue("quiet period" in (s.entries().last() as Entry.NotApplied).text)
            noReminder()
            quiet = QuietPeriod(LocalTime.of(22, 0), LocalTime.of(7, 0), enabled = false)

            // Time moved on before the tap: the minute of lead is gone.
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-06T10:30"}"""))
            s.send("remind me at half past ten")
            current = Instant.parse("2026-10-06T10:29:30Z")
            s.approve()
            assertTrue("at least a minute from now" in (s.entries().last() as Entry.NotApplied).text)
            noReminder()
            assertTrue(s.entries().none { it is Entry.Saved })
            assertNull(s.state.value.pending)
        }

    @Test
    fun `a reply lost after the commit is read back and reported once, and a build without the service never proposes one`() =
        runTest {
            setUp()
            val s = session(LostReplyPort(port()))
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-07T15:00"}"""))
            s.send("remind me tomorrow at 3")
            s.approve()
            assertEquals(1, store.reminders().size)
            assertEquals(1, s.entries().count { it is Entry.Saved })
            s.approve()
            assertEquals(1, store.reminders().size)

            val bare = session(port(withReminders = false))
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-08T15:00"}"""))
            bare.send("remind me")
            assertNull(bare.state.value.pending)
            assertTrue("not available in this build" in (bare.entries().last() as Entry.NotApplied).text)
            assertEquals(1, store.reminders().size)
        }

    @Test
    fun `recurring, snooze, calendar and cancel requests get precise refusals, and the model's claim is only an explanation`() =
        runTest {
            setUp()
            val s = session()
            listOf(
                "set_recurring_reminder" to "I can only set one-time reminders, not repeating ones, so nothing changed.",
                "snooze_reminder" to "I cannot snooze a reminder. Dismiss or acknowledge it under Reminders, then set a new one.",
                "add_calendar_event" to
                    "I cannot use a calendar. Thalia keeps its own one-time reminders and fixed commitments, so nothing changed.",
                "cancel_reminder" to "I cannot change or cancel a reminder from here. Do that under Reminders, so nothing changed.",
            ).forEach { (tool, words) ->
                provider.reply("Done, it is set.", call(tool, "{}"))
                s.send("do that")
                assertEquals(
                    words,
                    s
                        .entries()
                        .filterIsInstance<Entry.NotApplied>()
                        .last()
                        .text,
                )
                assertEquals("AI explanation: Done, it is set.", (s.entries().last() as Entry.Assistant).text)
                assertNull(s.state.value.pending)
            }
            noReminder()
        }

    @Test
    fun `a reminder and another change in one reply leave one pending and name the one that was not kept`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                null,
                call("propose_reminder", """{"at":"2026-10-07T15:00"}"""),
                call("propose_new_action", """{"title":"Book a room"}"""),
            )
            s.send("remind me tomorrow at 3 and add a room booking")
            assertTrue(
                s.state.value.pending
                    ?.proposal is Proposal.Reminder,
            )
            assertTrue(s.entries().any { it is Entry.NotApplied && "another suggestion was not kept" in it.text })
            noReminder()
            assertEquals(0, store.actions().count { it.title == "Book a room" })
        }

    private suspend fun setReminderAndFindSaved(s: ConversationSession): Entry.Saved {
        provider.reply(null, call("propose_reminder", """{"at":"2026-10-07T15:00","action_ref":"a1"}"""))
        s.send("remind me about the paper tomorrow at 3")
        s.approve()
        return s.entries().filterIsInstance<Entry.Saved>().single()
    }

    @Test
    fun `a set reminder carries its card facts without a zone, and the saved line names no zone`() =
        runTest {
            setUp()
            val s = session()
            val saved = setReminderAndFindSaved(s)
            assertEquals(listOf("About" to "Read paper", "Date" to "Wed 7 Oct", "Time" to "15:00"), saved.facts)
            assertTrue("UTC" !in saved.text && "(" !in saved.text, saved.text)
            assertNotNull(saved.undo)
            // The zone is still what the reminder was placed in.
            assertEquals("UTC", store.reminders().single().zone)
        }

    @Test
    fun `Undo cancels that exact reminder through the service, its alarm goes, and cancellation is said only after it succeeds`() =
        runTest {
            setUp()
            val s = session()
            val other =
                Reminder(
                    "other",
                    ReminderTargetType.NONE,
                    null,
                    "Checkpoint",
                    Instant.parse("2026-10-08T09:00:00Z").toEpochMilli(),
                    "UTC",
                    createdAtMillis = 1L,
                )
            store.createReminder(other)
            alarms.arm("other", other.dueAtMillis)
            val saved = setReminderAndFindSaved(s)
            val id = store.reminders().first { it.id != "other" }.id
            assertTrue(id in alarms.armed)

            s.undo(saved.id)
            s.undo(saved.id)

            assertEquals(ReminderStatus.CANCELLED, store.reminder(id)?.status)
            assertTrue(id !in alarms.armed)
            // Only that reminder: the other one is untouched and still armed.
            assertEquals(ReminderStatus.SCHEDULED, store.reminder("other")?.status)
            assertTrue("other" in alarms.armed)
            val undone = s.entries().filterIsInstance<Entry.Saved>().filter { "Undone" in it.text }
            assertEquals(1, undone.size)
            assertEquals("Undone: the reminder for Wed 7 Oct, 15:00 was cancelled. Nothing will be posted.", undone.single().text)
            assertNull(
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .first()
                    .undo,
            )
        }

    @Test
    fun `Undo on a reminder that is no longer waiting cancels nothing and says so`() =
        runTest {
            setUp()
            val s = session()
            val saved = setReminderAndFindSaved(s)
            val id = store.reminders().single().id
            // The alarm fired and the notification was posted before the person pressed Undo.
            current = Instant.parse("2026-10-07T15:00:30Z")
            assertTrue(service.onAlarm(store, id) is com.thalia.app.reminders.FireOutcome.Posted)

            s.undo(saved.id)

            assertEquals(ReminderStatus.POSTED, store.reminder(id)?.status)
            assertTrue(s.entries().none { it is Entry.Saved && "Undone" in it.text })
            val refused =
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .last()
                    .text
            assertTrue("no longer waiting, so nothing was cancelled" in refused, refused)
            assertNull(
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .first()
                    .undo,
            )
        }

    @Test
    fun `a failure during Undo is read back, a lost reply is reported cancelled once, and a failed write can be retried`() =
        runTest {
            setUp()
            // The cancel commits and the reply is lost.
            val lost = CancelLostPort(port(), failAfterCommit = true)
            val s = session(lost)
            val saved = setReminderAndFindSaved(s)
            val id = store.reminders().single().id
            s.undo(saved.id)
            assertEquals(ReminderStatus.CANCELLED, store.reminder(id)?.status)
            assertEquals(1, s.entries().count { it is Entry.Saved && "Undone" in it.text })

            // The cancel fails before writing: still waiting, said so, Undo stays available.
            val failing = CancelLostPort(port(), failAfterCommit = false)
            val t = session(failing)
            val saved2 = setReminderAndFindSaved(t)
            val id2 = store.reminders().first { it.id != id }.id
            t.undo(saved2.id)
            assertEquals(ReminderStatus.SCHEDULED, store.reminder(id2)?.status)
            assertTrue(t.entries().none { it is Entry.Saved && "Undone" in it.text })
            assertTrue("still waiting" in (t.entries().last() as Entry.NotApplied).text)
            assertNotNull(
                t
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .first()
                    .undo,
            )
            failing.fail = false
            t.undo(saved2.id)
            assertEquals(ReminderStatus.CANCELLED, store.reminder(id2)?.status)
        }

    @Test
    fun `an unreadable state after a failed Undo is said to be unconfirmed`() =
        runTest {
            setUp()
            val port = CancelLostPort(port(), failAfterCommit = false, unreadable = true)
            val s = session(port)
            val saved = setReminderAndFindSaved(s)
            s.undo(saved.id)
            assertTrue("Could not confirm whether the reminder was cancelled" in (s.entries().last() as Entry.Note).text)
            assertTrue(s.entries().none { it is Entry.Saved && "Undone" in it.text })
        }

    @Test
    fun `an existing reminder is untouched when a new one is approved`() =
        runTest {
            setUp()
            val existing =
                Reminder(
                    "old",
                    ReminderTargetType.NONE,
                    null,
                    "Checkpoint",
                    Instant.parse("2026-10-08T09:00:00Z").toEpochMilli(),
                    "UTC",
                    createdAtMillis = 1L,
                )
            store.createReminder(existing)
            val s = session()
            provider.reply(null, call("propose_reminder", """{"at":"2026-10-07T15:00"}"""))
            s.send("another one")
            s.approve()
            assertEquals(2, store.reminders().size)
            assertEquals(existing, store.reminder("old"))
        }
}
