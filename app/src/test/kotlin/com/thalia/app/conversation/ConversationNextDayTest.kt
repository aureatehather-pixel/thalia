package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.conversation.DayTarget
import com.thalia.conversation.Proposal
import com.thalia.conversation.RawCall
import com.thalia.planning.Action
import com.thalia.planning.ActiveDay
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The closing conversation plans the next active day through the same deterministic planner and the same Approve tap. A scripted
 * provider, the real session, parser, validator, port and planning store on a file-backed database. No network, no key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationNextDayTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "next-day-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")
    private val rollover = LocalTime.of(4, 0)
    private var now = Instant.parse("2026-10-12T20:00:00Z")
    private var clock = 1_000L
    private var ids = 0
    private val provider = ScriptedProvider()

    private val oct12 = ActiveDay(LocalDate.of(2026, 10, 12))
    private val oct13 = ActiveDay(LocalDate.of(2026, 10, 13))
    private val oct14 = ActiveDay(LocalDate.of(2026, 10, 14))
    private val write = Action("id-write", "Write intro", null, Priority.MUST, 60)
    private val read = Action("id-read", "Read paper", null, Priority.NORMAL, 30)
    private val email = Action("id-email", "Email tutor", null, Priority.IMPORTANT, 15)

    private lateinit var store: PlanningStore
    private lateinit var port: ConversationPort

    private suspend fun setUp(at: String = "2026-10-12T20:00:00Z") {
        now = Instant.parse(at)
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        store = PlanningStore(db) { clock++ }
        store.saveSettings(PlanningSettings(rollover, PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        listOf(write, read, email).forEach { store.saveAction(it) }
        // Today's plan, as the person approved it: the intro, which is still unfinished tonight.
        approveFor(ActiveDay.containing(now, utc, rollover), setOf(write.id))
        val runner =
            object : StoreRunner {
                override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
            }
        port = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { now })
    }

    private suspend fun approveFor(
        day: ActiveDay,
        selected: Set<String>,
    ) {
        val proposal = checkNotNull(store.propose(day, utc, null, selected, emptyMap()))
        assertIs<ApprovalResult.Approved>(store.approve(proposal, utc))
    }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    private fun session() = ConversationSession(port, provider) { "new-${ids++}" }

    /** The refs are positional in one snapshot: a1 is today's own action, then by priority. */
    private fun planNext(vararg refs: String) =
        RawCall("propose_day_selection", """{"action_refs":[${refs.joinToString { "\"$it\"" }}],"day":"next"}""")

    private val carry = arrayOf("a1", "a2") // Write intro (today's, unfinished) and Email tutor (a2, by priority).

    private suspend fun pendingNext(s: ConversationSession): Proposal.DaySelection {
        provider.reply(null, planNext(*carry))
        s.send("Plan tomorrow")
        return assertIs<Proposal.DaySelection>(assertNotNull(s.state.value.pending).proposal)
    }

    @Test
    fun `a next-day proposal names its date, applies nothing, and leaves today's plan alone`() =
        runTest {
            setUp()
            val before = checkNotNull(store.planFor(oct12))
            val s = session()

            val proposal = pendingNext(s)

            assertEquals(DayTarget.NEXT, proposal.target)
            assertEquals(oct13, proposal.day)
            assertEquals(oct12, proposal.baseDay)
            val pending = assertNotNull(s.state.value.pending)
            assertEquals("Approve plan for Tue 13 Oct", pending.approveLabel)
            assertTrue(pending.summary.startsWith("The plan for Tue 13 Oct, the next active day, would be:"), pending.summary)
            assertTrue("Today's plan is not changed." in pending.summary)
            assertNull(store.planFor(oct13), "nothing is saved for the next day")
            assertEquals(before, store.planFor(oct12), "today's plan is untouched")
            assertTrue(
                s.state.value.entries
                    .none { it is Entry.Saved },
            )
        }

    @Test
    fun `a typed or transcribed yes saves nothing for the next day`() =
        runTest {
            setUp()
            val s = session()
            pendingNext(s)

            listOf("yes", "Yes.", "ok", "go ahead", "approve", "yes please").forEach { s.send(it) }
            val composer = ConversationComposer(null)
            listOf("yes", "okay").forEach {
                composer.edit(it)
                s.send(checkNotNull(composer.take()))
            }

            assertNull(store.planFor(oct13))
            assertEquals(1, provider.requests.size)
            assertNotNull(s.state.value.pending)
        }

    @Test
    fun `Approve saves the next day once, names the date, and today's plan stays as it was`() =
        runTest {
            setUp()
            val before = checkNotNull(store.planFor(oct12))
            val s = session()
            val proposal = pendingNext(s)

            s.approve()
            s.approve()
            s.send("yes")

            val saved = checkNotNull(store.planFor(oct13))
            assertEquals(1, saved.plan.revision)
            assertEquals(proposal.selected, saved.selected)
            assertEquals(before, store.planFor(oct12), "today's plan is byte-for-byte what it was")
            assertEquals(
                1,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )
            val text =
                s.state.value.entries
                    .filterIsInstance<Entry.Saved>()
                    .single()
                    .text
            assertTrue(text.startsWith("The plan for Tue 13 Oct was saved as revision 1. Today's plan is not changed."), text)
            assertNull(s.state.value.pending)
        }

    @Test
    fun `Dismiss saves nothing, and a later yes or Approve still saves nothing`() =
        runTest {
            setUp()
            val s = session()
            pendingNext(s)

            s.dismiss()
            s.send("yes")
            s.approve()

            assertNull(store.planFor(oct13))
            assertNull(s.state.value.pending)
            assertTrue(
                s.state.value.entries
                    .none { it is Entry.Saved },
            )
        }

    @Test
    fun `a date the model names, or any day other than today or next, is refused with nothing pending`() =
        runTest {
            setUp()
            val s = session()
            listOf("2026-10-14", "tomorrow", "monday").forEach { value ->
                provider.reply(null, RawCall("propose_day_selection", """{"action_refs":["a1"],"day":"$value"}"""))
                s.send("plan $value")
            }
            provider.reply(null, RawCall("propose_day_selection", """{"action_refs":["a1"],"date":"2026-10-14"}"""))
            s.send("plan the 14th")

            assertNull(s.state.value.pending)
            assertNull(store.planFor(oct13))
            assertNull(store.planFor(oct14))
            assertEquals(
                4,
                s.state.value.entries
                    .count { it is Entry.NotApplied },
            )
        }

    @Test
    fun `between midnight and the rollover the next active day is the day after, not the calendar date plus one`() =
        runTest {
            setUp(at = "2026-10-13T01:00:00Z")
            // At 01:00 on 13 October the active day is still 12 October, so that is today's plan.
            assertNotNull(store.planFor(oct12))
            val s = session()
            val proposal = pendingNext(s)

            assertEquals(oct13, proposal.day, "calendar plus one would have been 14 October")
            assertEquals(
                "Approve plan for Tue 13 Oct",
                s.state.value.pending
                    ?.approveLabel,
            )
            s.approve()

            assertNotNull(store.planFor(oct13))
            assertNull(store.planFor(oct14))
        }

    @Test
    fun `a proposal made before the day rolled over is refused as stale and saves nothing`() =
        runTest {
            setUp()
            val s = session()
            pendingNext(s)
            now = Instant.parse("2026-10-13T05:00:00Z") // past the 04:00 rollover: planning is now on 13 October

            s.approve()

            assertNull(store.planFor(oct13), "the old next day is now today, and nothing was saved for it")
            assertNull(s.state.value.pending)
            val refusal =
                s.state.value.entries
                    .filterIsInstance<Entry.NotApplied>()
                    .last()
                    .text
            assertTrue("the day changed since this was proposed" in refusal, refusal)
        }

    @Test
    fun `if the next day's plan changed after the proposal, the old proposal is not saved and a new preview waits`() =
        runTest {
            setUp()
            val s = session()
            val proposal = pendingNext(s)
            approveFor(oct13, setOf(email.id)) // changed in the Plan tab meanwhile

            s.approve()

            assertEquals(setOf(email.id), checkNotNull(store.planFor(oct13)).selected, "the newer plan stands")
            assertEquals(1, checkNotNull(store.planFor(oct13)).plan.revision)
            val fresh = assertIs<Proposal.DaySelection>(assertNotNull(s.state.value.pending).proposal)
            assertEquals(proposal.selected, fresh.selected)
            assertTrue(
                s.state.value.pending
                    ?.approveLabel
                    ?.endsWith("for Tue 13 Oct") == true,
            )
            assertTrue(
                s.state.value.entries
                    .any { it is Entry.NotApplied && "things changed since this was proposed" in it.text },
            )

            s.approve()

            assertEquals(2, checkNotNull(store.planFor(oct13)).plan.revision)
            assertEquals(proposal.selected, checkNotNull(store.planFor(oct13)).selected)
        }

    @Test
    fun `while an earlier working day awaits a choice there is no next day to plan`() =
        runTest {
            setUp()
            store.openWorkingDay(Instant.parse("2026-10-10T12:00:00Z"), utc) // never closed, now two days old
            val s = session()

            provider.reply(null, planNext("a1"))
            s.send("Plan tomorrow")

            assertNull(s.state.value.pending)
            assertNull(store.planFor(oct13))
            val refusal =
                s.state.value.entries
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
            assertTrue("next active day cannot be named yet" in refusal, refusal)
        }

    @Test
    fun `the snapshot the model sees names the next active day and says not to name a date`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Nothing more tonight.")
            s.send("What about tomorrow?")

            val text = provider.requests.single().contextText
            assertTrue("Next active day: 2026-10-13 (Tue 13 Oct)" in text, text)
            assertTrue("never name a date" in text)
        }
}
