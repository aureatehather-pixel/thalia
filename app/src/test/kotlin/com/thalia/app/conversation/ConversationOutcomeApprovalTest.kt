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
import com.thalia.conversation.Proposal
import com.thalia.conversation.RawCall
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.OutcomeResult
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
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Owner decision, 2026-10-12: only tapping Approve may apply a model-proposed change, including an outcome or a focus capacity
 * taken from a typed or spoken message. These run the real session, parser, validator, port and planning store against a
 * scripted provider and a file-backed database. No network, no key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationOutcomeApprovalTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "outcome-approval-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")
    private val instant = Instant.parse("2026-10-12T10:00:00Z")
    private val rollover = LocalTime.of(4, 0)
    private val today = ActiveDay.containing(instant, utc, rollover)
    private var clock = 1_000L
    private var ids = 0
    private val provider = ScriptedProvider()
    private val intro = Action("id-intro", "Write intro", null, Priority.MUST, 60)

    private lateinit var store: PlanningStore
    private lateinit var port: ConversationPort

    private suspend fun setUp() {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        store = PlanningStore(db) { clock++ }
        store.saveSettings(PlanningSettings(rollover, PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        store.saveAction(intro)
        val proposal = checkNotNull(store.propose(today, utc, null, setOf(intro.id), emptyMap()))
        assertIs<ApprovalResult.Approved>(store.approve(proposal, utc))
        val runner =
            object : StoreRunner {
                override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
            }
        port = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { instant })
    }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    private fun session() = ConversationSession(port, provider) { "new-${ids++}" }

    private fun outcome(result: String) = RawCall("record_outcome", """{"action_ref":"a1","result":"$result"}""")

    private suspend fun nothingSaved() {
        assertTrue(store.outcomes().isEmpty(), "no outcome may be stored")
        assertEquals(ActionStatus.OPEN, store.action("id-intro")?.status)
    }

    /** The words the session answers itself and never sends to the model. */
    private val bareYes = listOf("yes", "Yes.", "y", "ok", "okay", "go ahead", "approve", "yes please")

    @Test
    fun `I finished X stays pending and writes nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Nice, that is done.", outcome("done"))

            s.send("I finished the intro")

            nothingSaved()
            assertIs<Proposal.Outcome>(assertNotNull(s.state.value.pending).proposal)
            assertEquals(
                "Record outcome",
                s.state.value.pending
                    ?.approveLabel,
            )
            assertTrue(
                s.state.value.entries
                    .none { it is Entry.Saved },
            )
        }

    @Test
    fun `a typed yes and a transcribed yes save nothing, never reach the provider, and leave it pending`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, outcome("done"))
            s.send("I finished the intro")
            assertEquals(1, provider.requests.size)

            bareYes.forEach { s.send(it) }
            val composer = ConversationComposer(null)
            bareYes.forEach {
                composer.edit(it) // what a finished recognition leaves in the draft
                s.send(checkNotNull(composer.take()))
            }

            nothingSaved()
            assertEquals(1, provider.requests.size, "a yes has no referent, so it is never sent to the model")
            assertNotNull(s.state.value.pending)
            assertTrue(
                s.state.value.entries
                    .none { it is Entry.Saved },
            )
            assertTrue(
                s.state.value.entries
                    .any { it is Entry.Note && "Nothing was applied. Tap Approve" in it.text },
            )
        }

    @Test
    fun `other affirmations may reach the model but can still save nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, outcome("done"))
            s.send("I finished the intro")
            provider.reply("Tap Approve if that is right.")
            provider.reply("Tap Approve if that is right.")

            s.send("yeah")
            s.send("do it")

            nothingSaved()
            assertNotNull(s.state.value.pending, "still waiting for the tap")
            assertTrue(
                s.state.value.entries
                    .none { it is Entry.Saved },
            )
        }

    @Test
    fun `a spoken sentence that states an outcome is only a proposal until Approve`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, outcome("partial"))
            val composer = ConversationComposer(null)
            composer.edit("I got halfway through the intro") // a transcription, edited or not, is only text
            s.send(checkNotNull(composer.take()))

            nothingSaved()
            assertNotNull(s.state.value.pending)
        }

    @Test
    fun `Approve saves exactly once, and a later yes or Approve saves nothing more`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, outcome("done"))
            s.send("I finished the intro")

            s.approve()
            s.approve()
            s.send("yes")
            s.approve()

            assertEquals(1, store.outcomes().size)
            assertEquals(OutcomeResult.DONE, store.outcomes().single().result)
            assertEquals(ActionStatus.DONE, store.action("id-intro")?.status)
            assertEquals(
                1,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )
            assertNull(s.state.value.pending)
        }

    @Test
    fun `Dismiss saves nothing, and a later yes or Approve still saves nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, outcome("done"))
            s.send("I finished the intro")

            s.dismiss()
            s.send("yes")
            s.approve()

            nothingSaved()
            assertNull(s.state.value.pending)
            assertTrue(
                s.state.value.entries
                    .none { it is Entry.Saved },
            )
        }

    @Test
    fun `a replaced proposal is not applied, and Approve applies only the one now pending`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, outcome("partial"))
            provider.reply(null, outcome("done"))
            s.send("I started the intro")
            s.send("no, I finished the intro")

            s.approve()

            assertEquals(OutcomeResult.DONE, store.outcomes().single().result)
            assertEquals(
                1,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )
        }

    @Test
    fun `a stated focus capacity waits for Approve too, and a yes saves nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, RawCall("record_focus_capacity", """{"minutes":150}"""))
            s.send("I only have 150 minutes of focus today")

            s.send("yes")
            assertNull(store.workingDay(today)?.focusMinutes)
            assertIs<Proposal.Focus>(assertNotNull(s.state.value.pending).proposal)

            s.approve()
            assertEquals(150, store.workingDay(today)?.focusMinutes)
            assertEquals(
                1,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )
        }

    @Test
    fun `the model's own words never say it was saved`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Done, I saved it for you.", outcome("done"))
            s.send("I finished the intro")

            nothingSaved()
            val assistant =
                s.state.value.entries
                    .filterIsInstance<Entry.Assistant>()
            assertTrue(assistant.all { it.text.startsWith(ReplyText.AI_EXPLANATION) }, "labelled as the model's words, not a status")
        }
}
