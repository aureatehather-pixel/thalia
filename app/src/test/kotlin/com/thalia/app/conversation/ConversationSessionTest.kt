package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneProblem
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.ApprovalResult
import com.thalia.app.planning.data.ChangeActionResult
import com.thalia.app.planning.data.CreateActionResult
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RawCall
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.ActiveDay
import com.thalia.planning.OutcomeResult
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A scripted stand-in for the AI: it returns what a test queues and records what it was asked. */
internal class ScriptedProvider : ConversationProvider {
    val queue = ArrayDeque<ProviderResponse>()
    val requests = mutableListOf<ProviderRequest>()

    fun reply(
        text: String? = null,
        vararg calls: RawCall,
    ) = queue.addLast(ProviderResponse.Reply(text, calls.toList()))

    override suspend fun respond(request: ProviderRequest): ProviderResponse {
        requests += request
        return queue.removeFirst()
    }
}

/** A port whose next [createAction] commits and then fails, as a process stop or a lost reply would look. */
private class FlakyPort(
    private val inner: ConversationPort,
) : ConversationPort by inner {
    var failAfterCreate = false

    override suspend fun createAction(action: Action): CreateActionResult {
        val result = inner.createAction(action)
        if (failAfterCreate) {
            failAfterCreate = false
            throw IOException("the reply was lost after the commit")
        }
        return result
    }

    override suspend fun createCommitment(
        commitment: com.thalia.planning.FixedCommitment,
    ): com.thalia.app.planning.data.CreateCommitmentResult {
        val result = inner.createCommitment(commitment)
        if (failAfterCreate) {
            failAfterCreate = false
            throw IOException("the reply was lost after the commit")
        }
        return result
    }
}

/**
 * The operation boundary end to end: a scripted provider, the real session and port, and the real planning store on
 * Robolectric's plain SQLite. Nothing here reaches a network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationSessionTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "conversation-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")
    private val instant = Instant.parse("2026-10-06T10:00:00Z")
    private val rollover = LocalTime.of(4, 0)
    private val today = ActiveDay.containing(instant, utc, rollover)
    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))
    private var clock = 1_000L
    private var ids = 0

    private val write = Action("id-write", "Write intro", "proj", Priority.MUST, 60)
    private val read = Action("id-read", "Read paper", "proj", Priority.NORMAL, 30)
    private val email = Action("id-email", "Email tutor", null, Priority.IMPORTANT, 15)
    private val old = Action("id-old", "Old thing", null, Priority.NORMAL, 15, status = ActionStatus.DONE)

    private lateinit var store: PlanningStore
    private lateinit var port: ConversationPort
    private val provider = ScriptedProvider()

    private val runner =
        object : StoreRunner {
            override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
        }

    private suspend fun setUp(approveToday: Boolean = true) {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        store = PlanningStore(db) { clock++ }
        store.saveSettings(PlanningSettings(rollover, window))
        store.saveProject(Project("proj", "Thesis", null, ProjectState.ACTIVE))
        listOf(write, read, email, old).forEach { store.saveAction(it) }
        port = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { instant })
        if (approveToday) approve(setOf(write.id))
    }

    private suspend fun approve(selected: Set<String>) {
        val proposal = checkNotNull(store.propose(today, utc, null, selected, emptyMap()))
        assertIs<ApprovalResult.Approved>(store.approve(proposal, utc))
    }

    private fun session(p: ConversationPort = port) = ConversationSession(p, provider) { "new-${ids++}" }

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

    private suspend fun planRevision() = store.planFor(today)?.plan?.revision

    // Facts

    @Test
    fun `a stated outcome waits for a tap, changes nothing before it, is applied once, and can then be undone`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Nice work, I recorded it.", call("record_outcome", """{"action_ref":"a1","result":"done"}"""))

            s.send("I finished the intro")

            // Before the tap: nothing is committed, and nothing says it was.
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertTrue(store.outcomes().isEmpty())
            assertNotNull(s.state.value.pending)
            assertTrue(s.entries().none { it is Entry.Saved })
            assertTrue(
                s.entries().filterIsInstance<Entry.Assistant>().all {
                    it.text.startsWith(ReplyText.AI_EXPLANATION)
                },
                "the model's words are labelled, never a status",
            )
            assertTrue(
                s.entries().filterIsInstance<Entry.Note>().last().text.contains(
                    "Proposed, not applied yet. Record \"Write intro\" as done.",
                ),
            )

            s.approve()
            s.approve()
            s.approve()

            val saved = s.entries().filterIsInstance<Entry.Saved>().single()
            assertEquals("Recorded: \"Write intro\" as done. The action is now closed.", saved.text)
            assertNull(s.state.value.pending)
            assertEquals(ActionStatus.DONE, store.action("id-write")?.status)
            assertEquals(1, store.outcomes().size)
            assertEquals(2, s.entries().count { it is Entry.Note && "Nothing is waiting for approval" in it.text })

            s.undo(saved.id)

            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertEquals(OutcomeResult.UNKNOWN, store.outcomes().single().result)
            assertNull(
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .first()
                    .undo,
            )
        }

    @Test
    fun `dismissing a pending stated outcome leaves planning unchanged and a later tap applies nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"done"}"""))
            s.send("I finished the intro")

            s.dismiss()
            s.approve()

            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertTrue(store.outcomes().isEmpty())
            assertNull(s.state.value.pending)
            assertTrue(s.entries().any { it is Entry.Note && it.text == "Dismissed. Nothing was applied." })
            assertTrue(s.entries().none { it is Entry.Saved })
        }

    @Test
    fun `a stated outcome that changed before the tap is refused and the newer value stays`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"done"}"""))
            s.send("I finished the intro")
            val item = checkNotNull(store.planFor(today)).items.single()
            store.recordOutcome(item.id, OutcomeResult.PARTIAL, today)

            s.approve()

            assertEquals(OutcomeResult.PARTIAL, store.outcomes().single().result)
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertTrue((s.entries().last() as Entry.NotApplied).text.contains("changed since this was proposed"))
            assertNull(s.state.value.pending)
        }

    @Test
    fun `two stated facts in one reply leave one pending and refuse the other, applying neither`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                null,
                call("record_outcome", """{"action_ref":"a1","result":"done"}"""),
                call("record_focus_capacity", """{"minutes":150}"""),
            )
            s.send("finished the intro and I have 150 minutes of focus")

            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertNull(store.workingDay(today)?.focusMinutes)
            assertTrue(s.entries().any { it is Entry.NotApplied && "one suggestion at a time" in it.text })
            assertTrue(s.entries().none { it is Entry.Saved })
            assertTrue(
                s.state.value.pending
                    ?.proposal is com.thalia.conversation.Proposal.Outcome,
            )
        }

    @Test
    fun `an outcome the validator refuses changes nothing, and an existing outcome is not overwritten`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_outcome", """{"action_ref":"a9","result":"done"}"""))
            provider.reply(null, call("record_outcome", """{"action_ref":"a2","result":"done"}"""))
            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"partial"}"""))
            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"done"}"""))

            s.send("did a9")
            s.send("did the second one")
            s.send("started the intro")
            s.approve()
            s.send("actually finished the intro")

            val asked = s.entries().filterIsInstance<Entry.Assistant>().map { it.text }
            assertTrue(asked.single().contains("Which one is it?") && !refToken.containsMatchIn(asked.single()), asked.toString())
            val refused = s.entries().filterIsInstance<Entry.NotApplied>().map { it.text }
            assertTrue(refused[0].contains("not in today's approved plan"))
            assertTrue(refused[1].contains("already has an outcome"))
            assertEquals(OutcomeResult.PARTIAL, store.outcomes().single().result)
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
        }

    @Test
    fun `a completed action drops out of the snapshot, so a conversation cannot reopen or change it`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"done"}"""))
            s.send("finished the intro")
            s.approve()
            provider.reply(null, call("propose_action_change", """{"action_ref":"a4","title":"Rewrite everything"}"""))
            s.send("rename the old thing")

            assertTrue("Write intro" !in provider.requests.last().contextText)
            assertEquals("Old thing", store.action("id-old")?.title)
            assertEquals(ActionStatus.DONE, store.action("id-write")?.status)
            assertNull(s.state.value.pending)
        }

    @Test
    fun `a request carries the bounded snapshot, never an id, and says what is unknown`() =
        runTest {
            setUp()
            provider.reply("Hello.")
            session().send("hi")
            val request = provider.requests.single()
            assertTrue("id-write" !in request.contextText)
            assertTrue("a1 \"Write intro\"" in request.contextText)
            assertTrue("Project context fields shown as unknown are unknown to you" in request.contextText)
            assertTrue(
                "p1 \"Thesis\" state=ACTIVE purpose=unknown done_when=unknown constraints=unknown blocker=unknown" in request.contextText,
            )
            assertEquals(1, request.turns.size)
        }

    // Suggestions

    @Test
    fun `a new action waits for approval, is then created once, and is not put in today's plan`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                "Here is one.",
                call("propose_new_action", """{"title":"Book a room","project_ref":"p1","reason":"needed for the talk"}"""),
            )

            s.send("what should I add?")

            assertNotNull(s.state.value.pending)
            assertNull(store.actions().firstOrNull { it.title == "Book a room" })
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.Note>()
                    .last()
                    .text
                    .contains("Proposed, not applied yet"),
            )

            s.approve()
            s.approve()

            assertEquals(1, store.actions().count { it.title == "Book a room" })
            assertEquals(1, planRevision())
            assertEquals(setOf("id-write"), store.planFor(today)?.selected)
            assertNull(s.state.value.pending)
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .single()
                    .text
                    .contains("not in today's plan"),
            )
        }

    @Test
    fun `a creation that commits and then fails is reported from the stored state, and cannot be duplicated`() =
        runTest {
            setUp()
            val flaky = FlakyPort(port)
            val s = session(flaky)
            provider.reply(null, call("propose_new_action", """{"title":"Book a room"}"""))
            s.send("add one")
            flaky.failAfterCreate = true

            s.approve()

            assertEquals(1, store.actions().count { it.title == "Book a room" })
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .single()
                    .text
                    .startsWith("Added \"Book a room\""),
            )

            // Asking again after the interruption is refused by the snapshot, and the store would refuse it too.
            provider.reply(null, call("propose_new_action", """{"title":"book a room"}"""))
            s.send("add it again")
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .last()
                    .text
                    .contains("already exists"),
            )
            assertEquals(1, store.actions().count { it.title == "Book a room" })
        }

    @Test
    fun `the store never creates one action twice, nor overwrites it, nor repeats an open title`() =
        runTest {
            setUp()
            val fresh = Action("n1", "Plan the talk", null, Priority.NORMAL, 20)
            assertEquals(CreateActionResult.CREATED, store.createAction(fresh))
            store.saveAction(fresh.copy(estimateMinutes = 90))
            assertEquals(CreateActionResult.ALREADY_EXISTS, store.createAction(fresh))
            assertEquals(90, store.action("n1")?.estimateMinutes)
            assertEquals(CreateActionResult.DUPLICATE_TITLE, store.createAction(fresh.copy(id = "n2", title = "plan the TALK")))
            assertEquals(CreateActionResult.PROJECT_NOT_FOUND, store.createAction(fresh.copy(id = "n3", title = "x", projectId = "nope")))
            store.saveProject(Project("done", "Finished", null, ProjectState.COMPLETED))
            assertEquals(CreateActionResult.PROJECT_CLOSED, store.createAction(fresh.copy(id = "n4", title = "y", projectId = "done")))
            // A closed action with the same title does not block a new one.
            assertEquals(CreateActionResult.CREATED, store.createAction(old.copy(id = "n5", status = ActionStatus.OPEN)))
        }

    @Test
    fun `a change applies only to what was proposed, and a stale one is refused with nothing written`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_action_change", """{"action_ref":"a3","estimate_minutes":45}"""))
            s.send("read paper will take longer")
            // The action is edited by hand before approval.
            store.saveAction(read.copy(title = "Read the paper"))

            s.approve()

            assertEquals(read.copy(title = "Read the paper"), store.action("id-read"))
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
                    .contains("different from when this was proposed"),
            )
            assertNull(s.state.value.pending)

            provider.reply(null, call("propose_action_change", """{"action_ref":"a3","estimate_minutes":45}"""))
            s.send("again")
            s.approve()
            assertEquals(45, store.action("id-read")?.estimateMinutes)
            assertEquals(ChangeActionResult.STALE, store.changeAction(read, read.copy(estimateMinutes = 50)))
        }

    @Test
    fun `a day selection goes through the planner and approval, writing nothing until approved`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Add the email.", call("propose_day_selection", """{"action_refs":["a1","a2"],"reason":"short and due"}"""))

            s.send("what should today be?")

            val pending = assertNotNull(s.state.value.pending)
            assertNotNull(pending.preview)
            assertTrue("Email tutor" in pending.summary && "Added: Email tutor" in pending.summary)
            assertEquals(1, planRevision())

            s.approve()

            assertEquals(2, planRevision())
            assertEquals(setOf("id-write", "id-email"), store.planFor(today)?.selected)
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.Saved>()
                    .single()
                    .text
                    .contains("revision 2"),
            )
            assertTrue(store.calls().isEmpty())
        }

    @Test
    fun `a day selection whose data changed is planned again and needs a fresh approval`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_day_selection", """{"action_refs":["a1","a2"]}"""))
            s.send("plan today")
            store.saveAction(email.copy(estimateMinutes = 25))

            s.approve()

            assertEquals(1, planRevision())
            assertNotNull(s.state.value.pending)
            assertTrue(s.entries().any { it is Entry.NotApplied && "Nothing was saved" in it.text })
            assertTrue(s.entries().last().let { it is Entry.Note && "Updated proposal" in it.text })

            s.approve()

            assertEquals(2, planRevision())
            assertEquals(
                25,
                store
                    .planFor(today)
                    ?.items
                    ?.first { it.actionId == "id-email" }
                    ?.minutes,
            )
        }

    @Test
    fun `a plan with recorded outcomes cannot be replaced from a conversation`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"partial"}"""))
            s.send("started the intro")
            s.approve()
            provider.reply(null, call("propose_day_selection", """{"action_refs":["a2"]}"""))
            s.send("change today")

            assertNull(s.state.value.pending)
            assertTrue(
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
                    .contains("recorded outcomes"),
            )
            assertEquals(1, planRevision())
        }

    // R2: human-facing replies

    private val refToken = Regex("""\b[aip]\d+\b""")

    private fun ConversationSession.thaliaText() =
        entries().filterNot { it is Entry.User }.map {
            when (it) {
                is Entry.Assistant -> it.text
                is Entry.Saved -> it.text
                is Entry.NotApplied -> it.text
                is Entry.Redirect -> it.text
                is Entry.Note -> it.text
                is Entry.User -> ""
            }
        }

    @Test
    fun `a question written with references is shown with titles, and a tool-only question is visible`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("ask_clarification", """{"question":"Did you mean a3, “Read paper,” or a2? Or p1?"}"""))

            s.send("I did it")

            assertEquals("Did you mean \"Read paper\" or \"Email tutor\"? Or \"Thesis\"?", (s.entries().last() as Entry.Assistant).text)
            assertTrue(s.thaliaText().none { refToken.containsMatchIn(it) })
        }

    @Test
    fun `a refusal never shows a reference, and an unknown action is a question with titles`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_outcome", """{"action_ref":"a9","result":"done"}"""))
            provider.reply(null, call("propose_action_change", """{"action_ref":"a3","priority":"normal"}"""))

            s.send("did a9")
            s.send("make reading normal")

            val shown = s.entries().filterIsInstance<Entry.NotApplied>().map { it.text }
            val asked =
                s
                    .entries()
                    .filterIsInstance<Entry.Assistant>()
                    .single()
                    .text
            assertTrue("Which one is it?" in asked && !refToken.containsMatchIn(asked), asked)
            assertTrue(shown.all { !refToken.containsMatchIn(it) }, shown.toString())
            assertEquals(1, shown.size)
            assertNull(s.state.value.pending)
        }

    @Test
    fun `a reply of nothing, or of an unreadable call, still leaves visible text`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null)
            s.send("hello")
            assertEquals(ReplyText.NO_ANSWER, (s.entries().last() as Entry.Note).text)
            assertEquals(0, store.outcomes().size)
        }

    @Test
    fun `ordinary conversation is kept as the AI response, with a separate status that no planning changes were applied`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("You have two open actions today. Want to look at the first?")
            s.send("what is on today?")

            val shown = s.entries().takeLast(2)
            assertEquals("AI response: You have two open actions today. Want to look at the first?", (shown[0] as Entry.Assistant).text)
            assertEquals(ReplyText.NO_CHANGES, (shown[1] as Entry.Note).text)
            assertEquals("No planning changes were applied.", ReplyText.NO_CHANGES)
        }

    @Test
    fun `a completion claim in any wording stays an AI response beside the deterministic status, and nothing is applied`() =
        runTest {
            setUp()
            val s = session()
            // Wordings a pattern would not catch: the status never depends on reading the words.
            listOf(
                "Consider it handled; the milk is on your list.",
                "All sorted, your reminder will go off at three.",
                "Hecho: the outcome is now in the books.",
            ).forEach { claim ->
                provider.reply(claim)
                s.send("add milk")
                val shown = s.entries().takeLast(2)
                assertEquals("AI response: $claim", (shown[0] as Entry.Assistant).text)
                assertEquals(ReplyText.NO_CHANGES, (shown[1] as Entry.Note).text)
            }
            assertEquals(0, store.actions().count { it.title.contains("milk", ignoreCase = true) })
            assertTrue(store.outcomes().isEmpty())
            assertTrue(s.entries().none { it is Entry.Saved })
        }

    @Test
    fun `a pending change is described by titles and says it is not applied, and the applied and dismissed lines say so`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                "Done! I changed it.",
                call("propose_action_change", """{"action_ref":"a3","priority":"must","reason":"a3 is urgent"}"""),
            )
            s.send("make reading a must")

            val note =
                s
                    .entries()
                    .filterIsInstance<Entry.Note>()
                    .last()
                    .text
            assertTrue(note.startsWith("Proposed, not applied yet."), note)
            assertTrue("\"Read paper\"" in note && "AI explanation: \"Read paper\" is urgent" in note, note)
            assertTrue(s.thaliaText().none { refToken.containsMatchIn(it) })
            // The model's words are shown, labelled as an explanation, after Kotlin's own line, and are not a status.
            val last = s.entries().takeLast(2)
            assertTrue((last[0] as Entry.Note).text.startsWith("Proposed, not applied yet."))
            assertEquals("AI explanation: Done! I changed it.", (last[1] as Entry.Assistant).text)
            assertTrue(s.entries().none { it is Entry.Saved })
            assertEquals(Priority.NORMAL, store.action("id-read")?.priority)

            s.dismiss()
            assertEquals("Dismissed. Nothing was applied.", (s.entries().last() as Entry.Note).text)
            assertTrue(s.entries().none { it is Entry.Saved })
            assertEquals(Priority.NORMAL, store.action("id-read")?.priority)

            provider.reply(null, call("propose_action_change", """{"action_ref":"a3","priority":"must"}"""))
            s.send("make reading a must")
            s.approve()
            assertEquals("Changed \"Read paper\".", (s.entries().last() as Entry.Saved).text)
        }

    @Test
    fun `the proof-data prefix is not shown in questions, proposals or saved lines, and the person's own words are untouched`() =
        runTest {
            setUp()
            store.saveAction(Action("id-syn", "Synthetic: Pay the fees", null, Priority.NORMAL, 15))
            val s = session()
            val ref = "a4"
            provider.reply(null, call("record_outcome", """{"action_ref":"$ref","result":"done"}"""))
            provider.reply(null, call("ask_clarification", """{"question":"Is $ref still open?"}"""))
            s.send("Synthetic: fees are paid")
            s.thaliaText().forEach { assertTrue("Synthetic:" !in it, it) }
            assertEquals("Synthetic: fees are paid", (s.entries().first() as Entry.User).text)
            s.send("Is it?")
            s.thaliaText().forEach { assertTrue("Synthetic:" !in it, it) }
        }

    @Test
    fun `a refused extra change stays visible beside the pending one, with titles`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                null,
                call("record_outcome", """{"action_ref":"a1","result":"done"}"""),
                call("propose_new_action", """{"title":"Book a room"}"""),
            )
            s.send("finished the intro and add a room")
            val refused =
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
            assertTrue("another suggestion was not kept" in refused, refused)
            assertNotNull(s.state.value.pending)
        }

    // R5: a priority change keeps every other field

    @Test
    fun `a priority-only change waits for a tap, writes nothing before it, and changes only the priority`() =
        runTest {
            setUp()
            val s = session()
            val before = store.action("id-read")!!
            provider.reply(null, call("propose_action_change", """{"action_ref":"a3","priority":"must"}"""))
            s.send("make reading a must")

            assertEquals(before, store.action("id-read"))
            assertNotNull(s.state.value.pending)
            s.approve()
            s.approve()
            assertEquals(before.copy(priority = Priority.MUST), store.action("id-read"))
        }

    @Test
    fun `restating unchanged values beside a priority change stays priority-only`() =
        runTest {
            setUp()
            val s = session()
            val before = store.action("id-read")!!
            provider.reply(
                null,
                call(
                    "propose_action_change",
                    """{"action_ref":"a3","title":"Read paper","priority":"must","estimate_minutes":30,"clear_due_date":false}""",
                ),
            )
            s.send("make reading a must")
            assertNotNull(s.state.value.pending)
            assertEquals(before, store.action("id-read"))
            s.approve()
            assertEquals(before.copy(priority = Priority.MUST), store.action("id-read"))
        }

    @Test
    fun `a priority change that also adds or clears a due date, changes the estimate or the title asks and offers nothing`() =
        runTest {
            setUp()
            store.saveAction(Action("id-fees", "Pay fees", null, Priority.NORMAL, 15, java.time.LocalDate.of(2026, 10, 12)))
            provider.reply("Hello.")
            val s = session()
            s.send("hi")
            val fees = Regex("""(a\d+) "Pay fees"""").find(provider.requests.last().contextText)!!.groupValues[1]
            val reads = Regex("""(a\d+) "Read paper"""").find(provider.requests.last().contextText)!!.groupValues[1]
            val reading = store.action("id-read")!!
            val feesBefore = store.action("id-fees")!!

            listOf(
                """{"action_ref":"$reads","priority":"must","due_date":"2026-10-20"}""" to "the due date (adding 2026-10-20)",
                """{"action_ref":"$fees","priority":"must","clear_due_date":true}""" to "the due date (removing 2026-10-12)",
                """{"action_ref":"$reads","priority":"must","estimate_minutes":45}""" to "the estimate from 30 to 45 minutes",
                """{"action_ref":"$reads","priority":"must","title":"Read the paper"}""" to "the title to \"Read the paper\"",
            ).forEach { (args, expected) ->
                provider.reply(null, call("propose_action_change", args))
                s.send("make it a must")

                val question = (s.entries().last() as Entry.Assistant).text
                assertTrue(expected in question, question)
                assertTrue(!refToken.containsMatchIn(question), question)
                assertNull(s.state.value.pending)
                assertEquals(reading, store.action("id-read"))
                assertEquals(feesBefore, store.action("id-fees"))
                s.approve()
                assertEquals("Nothing is waiting for approval.", (s.entries().last() as Entry.Note).text)
            }
            assertTrue(s.entries().none { it is Entry.Saved })
        }

    @Test
    fun `the two changes asked for separately are each offered, and a due date change without a priority is not held`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_action_change", """{"action_ref":"a3","priority":"must"}"""))
            s.send("make reading a must")
            s.approve()
            provider.reply("Noted.")
            s.send("what is next?")
            val reads = Regex("""(a\d+) "Read paper"""").find(provider.requests.last().contextText)!!.groupValues[1]
            provider.reply(null, call("propose_action_change", """{"action_ref":"$reads","due_date":"2026-10-20"}"""))
            s.send("and due on the 20th")
            assertNotNull(s.state.value.pending)
            s.approve()
            val read = store.action("id-read")!!
            assertEquals(Priority.MUST, read.priority)
            assertEquals(java.time.LocalDate.of(2026, 10, 20), read.dueDate)
        }

    // R4: what is missing, unsure or unsupported

    @Test
    fun `an ambiguous target is a question, nothing is recorded, and a guess at either is not accepted`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("ask_clarification", """{"question":"Which one did you mean, a1 or a2?"}"""))
            s.send("I did it")
            assertEquals("Which one did you mean, \"Write intro\" or \"Email tutor\"?", (s.entries().last() as Entry.Assistant).text)
            assertNull(s.state.value.pending)

            // A made-up reference is not guessed into a real action: Kotlin asks, offering today's plan.
            provider.reply(null, call("record_outcome", """{"action_ref":"a77","result":"done"}"""))
            s.send("I did the thing")
            val asked = (s.entries().last() as Entry.Assistant).text
            assertTrue("Which one is it? Today's plan has \"Write intro\"" in asked, asked)
            assertNull(s.state.value.pending)
            assertTrue(store.outcomes().isEmpty())
        }

    @Test
    fun `a missing or ambiguous time is asked for and nothing is added`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_fixed_commitment", """{"title":"Vendor","start":"tomorrow morning","end":"later"}"""))
            s.send("add the vendor tomorrow morning")
            assertTrue((s.entries().last() as Entry.Assistant).text.startsWith("What date, start time and end time"))
            provider.reply(null, call("propose_fixed_commitment", """{"title":"Vendor","start":"2026-10-07T09:00"}"""))
            s.send("vendor at 9")
            assertTrue((s.entries().last() as Entry.Assistant).text.startsWith("What date, start time and end time"))
            provider.reply(
                null,
                call("propose_fixed_commitment", """{"title":"Vendor","start":"2026-10-07T11:00","end":"2026-10-07T10:00"}"""),
            )
            s.send("vendor 11 to 10")
            assertTrue((s.entries().last() as Entry.Assistant).text.startsWith("That ends at or before it starts"))
            assertNull(s.state.value.pending)
            assertEquals(emptyList(), store.commitments())
        }

    @Test
    fun `an outcome the person is unsure of stays unknown, and a plain question records nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Did you finish it?", call("record_outcome", """{"action_ref":"a1","result":"unknown"}"""))
            s.send("I'm not sure whether I finished the intro")
            assertTrue((s.entries().filterIsInstance<Entry.NotApplied>().single()).text.contains("stays unknown"))
            assertTrue(store.outcomes().isEmpty())
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)

            provider.reply("No problem. Tell me when you know.")
            s.send("I'm still not sure")
            assertEquals("AI response: No problem. Tell me when you know.", (s.entries().takeLast(2).first() as Entry.Assistant).text)
            assertTrue(store.outcomes().isEmpty())
            assertNull(s.state.value.pending)
        }

    @Test
    fun `an unsupported request is refused in plain words without a tool name, and the model's claim is only an explanation`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Reminder set for 3pm.", call("set_reminder", """{"at":"15:00"}"""))
            s.send("remind me at 3pm to call the vendor")

            val refused =
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
            assertEquals("I cannot do that from here, so nothing changed.", refused)
            assertEquals("AI explanation: Reminder set for 3pm.", (s.entries().last() as Entry.Assistant).text)
            assertNull(s.state.value.pending)
            assertTrue(s.entries().none { it is Entry.Saved })
        }

    // R3: the clock

    @Test
    fun `every request carries the clock of that moment, in the local zone, and refreshes at local midnight`() =
        runTest {
            setUp()
            val kl = ZoneId.of("Asia/Kuala_Lumpur")
            var current = Instant.parse("2026-10-06T15:59:00Z")
            val clocked = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(kl) }, { current })
            val s = session(clocked)
            provider.reply("One.")
            provider.reply("Two.")
            provider.reply("Three.")

            s.send("first")
            current = Instant.parse("2026-10-06T16:00:00Z")
            s.send("second")
            current = Instant.parse("2026-10-06T16:00:00Z").plusSeconds(3_600)
            s.send("third")

            val lines = provider.requests.map { r -> r.contextText.lines().first { it.startsWith("Now:") } }
            assertTrue(
                lines[0].startsWith("Now: 2026-10-06 Tuesday 23:59 in Asia/Kuala_Lumpur. Tomorrow is 2026-10-07 Wednesday."),
                lines[0],
            )
            assertTrue(
                lines[1].startsWith("Now: 2026-10-07 Wednesday 00:00 in Asia/Kuala_Lumpur. Tomorrow is 2026-10-08 Thursday."),
                lines[1],
            )
            assertTrue(lines[2].startsWith("Now: 2026-10-07 Wednesday 01:00 in Asia/Kuala_Lumpur."), lines[2])
            // The calendar date is not the active day: before the 04:00 rollover the active day is still the earlier one.
            assertTrue("Active day: 2026-10-06" in provider.requests[2].contextText)
        }

    @Test
    fun `the local date is used where it differs from UTC`() =
        runTest {
            setUp()
            val la = ZoneId.of("America/Los_Angeles")
            val clocked =
                StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(la) }, { Instant.parse("2026-10-07T03:30:00Z") })
            provider.reply("Hi.")
            session(clocked).send("hi")
            assertTrue(
                "Now: 2026-10-06 Tuesday 20:30 in America/Los_Angeles. Tomorrow is 2026-10-07 Wednesday." in
                    provider.requests.single().contextText,
            )
        }

    // Approval mechanics

    @Test
    fun `a typed yes never approves, with nothing pending it is answered locally, and a tap applies exactly once`() =
        runTest {
            setUp()
            val s = session()
            s.send("yes")
            assertEquals(0, provider.requests.size)
            assertEquals("Nothing is waiting for approval.", (s.entries().last() as Entry.Note).text)

            provider.reply(null, call("propose_new_action", """{"title":"Book a room"}"""))
            s.send("add a room booking")
            listOf("Yes.", "ok", "go ahead", "approve", "yes please").forEach { s.send(it) }

            assertEquals(1, provider.requests.size)
            assertEquals(0, store.actions().count { it.title == "Book a room" })
            assertNotNull(s.state.value.pending)
            assertTrue((s.entries().last() as Entry.Note).text.startsWith("Nothing was applied. Tap Approve"))

            s.approve()
            s.approve()
            s.send("yes")

            assertEquals(1, store.actions().count { it.title == "Book a room" })
            assertEquals(1, s.entries().count { it is Entry.Saved })
        }

    @Test
    fun `yes cannot apply a pending outcome, focus capacity or action change, and one tap each applies exactly once`() =
        runTest {
            setUp()
            val s = session()

            provider.reply(null, call("record_outcome", """{"action_ref":"a1","result":"done"}"""))
            s.send("finished the intro")
            s.send("yes")
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertTrue(store.outcomes().isEmpty())
            s.approve()
            s.approve()
            assertEquals(ActionStatus.DONE, store.action("id-write")?.status)
            assertEquals(1, store.outcomes().size)

            provider.reply(null, call("record_focus_capacity", """{"minutes":150}"""))
            s.send("150 minutes of focus")
            s.send("yes")
            assertNull(store.workingDay(today)?.focusMinutes)
            s.approve()
            s.approve()
            assertEquals(150, store.workingDay(today)?.focusMinutes)

            provider.reply(null, call("propose_action_change", """{"action_ref":"a2","priority":"must"}"""))
            s.send("make reading a must")
            s.send("yes")
            assertEquals(Priority.NORMAL, store.action("id-read")?.priority)
            s.approve()
            s.approve()
            assertEquals(Priority.MUST, store.action("id-read")?.priority)

            assertEquals(3, s.entries().count { it is Entry.Saved })
        }

    @Test
    fun `model explanations beside a pending change are labelled, follow the deterministic line, and never count as applied`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Done, I added it for you.", call("propose_new_action", """{"title":"Book a room"}"""))
            s.send("add a room booking")

            val tail = s.entries().takeLast(2)
            assertTrue((tail[0] as Entry.Note).text.startsWith("Proposed, not applied yet."))
            assertEquals("AI explanation: Done, I added it for you.", (tail[1] as Entry.Assistant).text)
            assertTrue(s.entries().none { it is Entry.Saved })
            assertEquals(0, store.actions().count { it.title == "Book a room" })

            // A stated fact and a refused extra change are the same: Kotlin's lines first, the explanation labelled.
            provider.reply(
                "Recorded both.",
                call("record_outcome", """{"action_ref":"a1","result":"done"}"""),
                call("record_focus_capacity", """{"minutes":90}"""),
            )
            s.send("finished the intro and 90 minutes of focus")
            assertTrue(s.entries().any { it is Entry.NotApplied && "was not kept" in it.text })
            assertEquals("AI explanation: Recorded both.", (s.entries().last() as Entry.Assistant).text)
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
        }

    @Test
    fun `a second requested change is refused visibly and named, the first stays pending, and neither is applied`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                null,
                call("propose_new_action", """{"title":"Book a room"}"""),
                call("record_outcome", """{"action_ref":"a1","result":"done"}"""),
            )
            s.send("add a room booking and I finished the intro")

            val refused =
                s
                    .entries()
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
            assertTrue("\"Write intro\" as done was not kept" in refused, refused)
            assertTrue(
                s.state.value.pending
                    ?.proposal is com.thalia.conversation.Proposal.NewAction,
            )
            assertEquals(ActionStatus.OPEN, store.action("id-write")?.status)
            assertEquals(0, store.actions().count { it.title == "Book a room" })
        }

    @Test
    fun `a newer suggestion replaces the pending one visibly, and dismiss applies nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_new_action", """{"title":"One"}"""))
            provider.reply(null, call("propose_new_action", """{"title":"Two"}"""))
            s.send("one")
            s.send("two")

            assertTrue(s.entries().any { it is Entry.Note && "replaced and was not applied" in it.text })
            assertEquals(
                1,
                s.state.value.entries
                    .count { it is Entry.Note && "Proposed" in it.text && "Two" in it.text },
            )

            s.dismiss()

            assertNull(s.state.value.pending)
            assertNull(store.actions().firstOrNull { it.title == "One" || it.title == "Two" })
        }

    @Test
    fun `an unsupported or malformed call is refused whole and nothing is applied`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(
                "Done!",
                call("drop_all_actions", "{}"),
                call("propose_action_change", """{"action_ref":"a3","status":"DONE"}"""),
                call("record_outcome", "oops"),
            )

            s.send("clean up")

            assertEquals(3, s.entries().count { it is Entry.NotApplied })
            assertNull(s.state.value.pending)
            assertEquals(listOf(write, read, email, old).map { it.id }.toSet(), store.actions().map { it.id }.toSet())
            assertTrue(store.actions().all { it.status != ActionStatus.DONE || it.id == "id-old" })
        }

    // Failure and AI off

    @Test
    fun `when the provider fails or is unavailable nothing changes and manual planning still works`() =
        runTest {
            setUp()
            val s = ConversationSession(port, UnavailableConversationProvider) { "x" }
            s.send("I finished the intro")
            assertTrue(s.entries().last().let { it is Entry.NotApplied && "AI is not set up" in it.text })

            val failing = session()
            provider.queue.addLast(ProviderResponse.Failed(ProviderFailure.NETWORK))
            failing.send("hello")
            assertTrue(failing.entries().last().let { it is Entry.NotApplied && "Nothing was changed" in it.text })

            val boom =
                object : ConversationProvider {
                    override suspend fun respond(request: ProviderRequest): ProviderResponse = error("boom")
                }
            val throwing = ConversationSession(port, boom) { "y" }
            throwing.send("hello")
            assertTrue(throwing.entries().last() is Entry.NotApplied)

            // The manual path is unaffected: an outcome is still recorded by hand.
            val item = checkNotNull(store.planFor(today)).items.single()
            store.recordOutcome(item.id, OutcomeResult.DONE, today)
            assertEquals(ActionStatus.DONE, store.action("id-write")?.status)
        }

    @Test
    fun `an unusable time zone stops the conversation before any request`() =
        runTest {
            setUp()
            val noZone = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Unusable(CheckInZoneProblem.NO_CHECK_IN_PLAN) })
            val s = session(noZone)
            s.send("hello")
            assertTrue(provider.requests.isEmpty())
            assertTrue(s.entries().last().let { it is Entry.NotApplied && "Planning is not ready" in it.text })
        }

    // ADR 0011: focus, project context and commitments

    @Test
    fun `a stated focus capacity waits for a tap, is then saved once with Undo, and an inferred one behaves the same`() =
        runTest {
            setUp()
            val s = session()
            provider.reply("Saved.", call("record_focus_capacity", """{"minutes":150}"""))
            s.send("I have about two and a half hours of focus")
            assertNull(store.workingDay(today)?.focusMinutes)
            assertNotNull(s.state.value.pending)
            assertTrue(s.entries().none { it is Entry.Saved })
            assertTrue(s.entries().filterIsInstance<Entry.Assistant>().all { it.text.startsWith(ReplyText.AI_EXPLANATION) })

            s.approve()
            s.approve()
            assertEquals(150, store.workingDay(today)?.focusMinutes)
            val saved = s.entries().filterIsInstance<Entry.Saved>().single()
            assertNotNull(saved.undo)
            s.undo(saved.id)
            assertNull(store.workingDay(today)?.focusMinutes)

            provider.reply(null, call("propose_focus_capacity", """{"minutes":90,"reason":"You said you slept badly"}"""))
            s.send("I slept badly")
            assertNull(store.workingDay(today)?.focusMinutes)
            assertNotNull(s.state.value.pending)
            s.send("yes")
            assertNull(store.workingDay(today)?.focusMinutes)
            s.approve()
            assertEquals(90, store.workingDay(today)?.focusMinutes)
        }

    @Test
    fun `dismissing a pending stated focus capacity saves nothing`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("record_focus_capacity", """{"minutes":150}"""))
            s.send("about 150 minutes of focus")
            s.dismiss()
            s.approve()
            assertNull(store.workingDay(today)?.focusMinutes)
            assertTrue(s.entries().none { it is Entry.Saved })
        }

    @Test
    fun `a project context suggestion is saved only on approval, and not over a newer value`() =
        runTest {
            setUp()
            val s = session()
            provider.reply(null, call("propose_project_context", """{"project_ref":"p1","field":"purpose","text":"Finish the degree"}"""))
            s.send("the thesis is how I finish the degree")
            assertNull(store.project("proj")?.purpose)
            s.approve()
            assertEquals("Finish the degree", store.project("proj")?.purpose)

            provider.reply(null, call("propose_project_context", """{"project_ref":"p1","field":"blocker","text":"Waiting for data"}"""))
            s.send("I'm waiting for data")
            store.changeProjectContext("proj", com.thalia.planning.ContextField.BLOCKER, null, "Supervisor away")
            s.approve()
            assertEquals("Supervisor away", store.project("proj")?.blocker)
            assertTrue((s.entries().last() as Entry.NotApplied).text.contains("changed since this was proposed"))
        }

    @Test
    fun `a commitment is added once on approval, a lost reply is read back, and a repeat is refused`() =
        runTest {
            setUp()
            val flaky = FlakyPort(port)
            val s = session(flaky)
            provider.reply(
                null,
                call("propose_fixed_commitment", """{"title":"Seminar","start":"2026-10-06T14:00","end":"2026-10-06T15:00"}"""),
            )
            s.send("seminar at 2 today for an hour")
            assertEquals(emptyList(), store.commitments())
            flaky.failAfterCreate = true
            s.approve()
            assertEquals(1, store.commitments().size)
            assertTrue((s.entries().last() as Entry.Saved).text.startsWith("Added the fixed commitment"))

            provider.reply(
                null,
                call("propose_fixed_commitment", """{"title":"Seminar","start":"2026-10-06T14:00","end":"2026-10-06T15:00"}"""),
            )
            s.send("seminar at 2")
            s.approve()
            assertEquals(1, store.commitments().size)
            assertTrue((s.entries().last() as Entry.NotApplied).text.contains("already recorded"))
        }
}
