package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.ai.ConversationRoutes
import com.thalia.app.ai.PlanRouteReadiness
import com.thalia.app.ai.planRouteNotice
import com.thalia.app.ai.planRouteReadiness
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.chatgptplan.ChatGptPlanConnection
import com.thalia.chatgptplan.FakePlanService
import com.thalia.chatgptplan.HostAllowlist
import com.thalia.chatgptplan.InMemoryCredentialStore
import com.thalia.chatgptplan.PlanEndpoints
import com.thalia.chatgptplan.PlanScenario
import com.thalia.chatgptplan.sampleCredentials
import com.thalia.conversation.ConversationProvider
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.planning.Action
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The conversation session over the **real production ChatGPT-plan provider** and the real planning store, against a simulated
 * service. It shows that each outcome of a request (a reply, a renewal, a 401, a usage limit, a network failure) either goes
 * through the usual pending-and-tap path or leaves planning data exactly as it was, with a readable message. Every renewal here is
 * simulated; nothing is signed in, sent, or stored on a phone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ChatGptPlanChainTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "chatgpt-chain-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")
    private val instant = Instant.parse("2026-10-06T10:00:00Z")
    private val clockMillis = instant.toEpochMilli()
    private var clock = 1_000L
    private lateinit var store: PlanningStore

    private val runner =
        object : StoreRunner {
            override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
        }

    private suspend fun setUp() {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        store = PlanningStore(db) { clock++ }
        store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        store.saveAction(Action("id-read", "Read paper", null, Priority.NORMAL, 30))
    }

    @After
    fun cleanUp() {
        ConversationRoutes.clear()
        database?.close()
        context.deleteDatabase(dbName)
    }

    /** Stands for the default (Gemini) provider. If the ChatGPT-plan route is installed, nothing may call it. */
    private class DefaultProvider : ConversationProvider {
        var calls = 0

        override suspend fun respond(request: ProviderRequest): ProviderResponse {
            calls++
            return ProviderResponse.Failed(com.thalia.conversation.ProviderFailure.UNAVAILABLE)
        }
    }

    private class Rig(
        val fake: FakePlanService,
        val session: ConversationSession,
        val default: DefaultProvider,
        val readiness: PlanRouteReadiness,
    )

    /** The real composition path: the module's connection, installed as the route, and the session built the way the screen builds it. */
    private fun rig(
        scenario: PlanScenario,
        expiresAt: Long = clockMillis + 3_600_000,
        consented: Boolean = true,
        model: String? = "gpt-test",
        signedIn: Boolean = true,
    ): Rig {
        val fake = FakePlanService(scenario)
        val client =
            OkHttpClient
                .Builder()
                .followRedirects(false)
                .addInterceptor(HostAllowlist(PlanEndpoints.PRODUCTION.allowedHosts))
                .addInterceptor(fake)
                .build()
        val connection =
            ChatGptPlanConnection.create(
                InMemoryCredentialStore(if (signedIn) sampleCredentials(expiresAt) else null),
                { model },
                { consented },
                client = client,
                clock = { clockMillis },
            )
        val readiness = planRouteReadiness(consented, model, connection.session.signedIn() != null)
        ConversationRoutes.installPlan(connection.provider) { readiness }
        val port = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { instant })
        val default = DefaultProvider()
        return Rig(fake, newConversationSession(port, default, null), default, readiness)
    }

    private suspend fun planningState() =
        listOf(
            store.actions().toString(),
            store.outcomes().toString(),
            store.reminders().toString(),
            store.commitments().toString(),
            store.inbox().toString(),
        )

    private val ConversationSession.lines get() = state.value.entries

    @Test
    fun `a successful conversation proposes through the real provider, changes nothing until a tap, and applies once`() =
        runTest {
            setUp()
            val before = planningState()
            val rig = rig(PlanScenario.TOOL_CALL)

            rig.session.send("add a room booking")

            assertEquals(before, planningState())
            assertNotNull(rig.session.state.value.pending)
            assertEquals("AI explanation: Here is a suggestion.", (rig.session.lines.last() as Entry.Assistant).text)
            assertTrue(rig.session.lines.none { it is Entry.Saved })
            rig.session.send("yes")
            assertEquals(before, planningState())

            rig.session.approve()
            rig.session.approve()

            assertEquals(1, store.actions().count { it.title == "Book a room" })
            assertEquals(1, rig.fake.responsesCalls)
            assertEquals(0, rig.fake.otherHostCalls)
        }

    @Test
    fun `expired credentials are renewed (simulated) and the conversation carries on, still waiting for a tap`() =
        runTest {
            setUp()
            val before = planningState()
            val rig = rig(PlanScenario.TOOL_CALL, expiresAt = clockMillis - 1)

            rig.session.send("add a room booking")

            assertEquals(1, rig.fake.refreshCalls)
            assertEquals(listOf("fake-access-2"), rig.fake.bearersSeen)
            assertNotNull(rig.session.state.value.pending)
            assertEquals(before, planningState())
        }

    @Test
    fun `a rejected token is renewed once and retried once, with the same outcome as a first success (simulated renewal)`() =
        runTest {
            setUp()
            val rig = rig(PlanScenario.REJECT_FIRST_TOKEN)
            rig.session.send("hello")
            assertEquals(
                "AI response: Start with the watering screen, because it is a must.",
                (rig.session.lines[1] as Entry.Assistant).text,
            )
            assertEquals(2, rig.fake.responsesCalls)
            assertEquals(1, rig.fake.refreshCalls)
        }

    private suspend fun assertFailureLeavesPlanning(
        scenario: PlanScenario,
        message: String,
        responses: Int,
    ) {
        val before = planningState()
        val rig = rig(scenario)
        rig.session.send("add a room booking")
        val shown = rig.session.lines.last() as Entry.NotApplied
        assertEquals(message, shown.text, scenario.name)
        assertEquals(before, planningState(), scenario.name)
        assertNull(rig.session.state.value.pending, scenario.name)
        assertEquals(responses, rig.fake.responsesCalls, scenario.name)
        assertTrue(rig.session.lines.none { it is Entry.Saved || it is Entry.Assistant }, scenario.name)
    }

    @Test
    fun `a 401 that stays a 401 asks for sign-in in plain words and leaves planning exactly as it was`() =
        runTest {
            setUp()
            assertFailureLeavesPlanning(
                PlanScenario.ALWAYS_UNAUTHORIZED,
                "Thalia is not signed in to your ChatGPT plan, or the sign-in is no longer accepted. Nothing was changed. " +
                    "Planning by hand is unaffected.",
                responses = 2,
            )
        }

    @Test
    fun `the plan's usage limit is reported as ChatGPT's, once, and changes nothing`() =
        runTest {
            setUp()
            val message =
                "ChatGPT reported that your plan's usage limit was reached. Nothing was changed and nothing else was tried. " +
                    "Planning by hand is unaffected."
            assertFailureLeavesPlanning(PlanScenario.USAGE_LIMIT_HTTP, message, responses = 1)
            assertFailureLeavesPlanning(PlanScenario.USAGE_LIMIT_IN_STREAM, message, responses = 1)
            assertFailureLeavesPlanning(
                PlanScenario.USAGE_UNAVAILABLE_IN_STREAM,
                "ChatGPT reported that plan usage is unavailable right now, and did not say why. Nothing was changed.",
                responses = 1,
            )
        }

    @Test
    fun `a network failure and a server error leave planning unchanged with a readable line`() =
        runTest {
            setUp()
            val before = planningState()
            val down = rig(PlanScenario.NETWORK_DOWN)
            down.session.send("hello")
            val network = (down.session.lines.last() as Entry.NotApplied).text
            assertEquals(providerFailureText(com.thalia.conversation.ProviderFailure.NETWORK, budgetNotes = false), network)
            assertTrue("budget" !in network.lowercase(), network)
            val server = rig(PlanScenario.SERVER_ERROR)
            server.session.send("hello")
            assertEquals("The AI service had a problem. Nothing was changed.", (server.session.lines.last() as Entry.NotApplied).text)
            assertEquals(before, planningState())
        }

    @Test
    fun `a failed request leaves an earlier pending proposal waiting and untouched, and a tap still applies exactly it`() =
        runTest {
            setUp()
            val ok = rig(PlanScenario.TOOL_CALL)
            ok.session.send("add a room booking")
            val pending = assertNotNull(ok.session.state.value.pending)
            // The same conversation, but the next request fails: swap the service behind the same provider.
            ok.fake.scenario = PlanScenario.USAGE_LIMIT_HTTP
            ok.session.send("and something else")

            assertEquals(pending, ok.session.state.value.pending)
            assertTrue(ok.session.lines.last() is Entry.NotApplied)
            assertEquals(0, store.actions().count { it.title == "Book a room" })
            ok.session.approve()
            assertEquals(1, store.actions().count { it.title == "Book a room" })
        }

    @Test
    fun `the installed route replaces the default provider, so no Gemini gate, key or ledger is involved`() =
        runTest {
            setUp()
            val rig = rig(PlanScenario.TOOL_CALL)
            rig.session.send("add a room booking")
            assertEquals(0, rig.default.calls)
            assertEquals(1, rig.fake.responsesCalls)
            // The Gemini services (consent file, sealed key, budget ledger) were never created.
            assertTrue(!java.io.File(context.filesDir, "ai").exists())
            assertEquals(PlanRouteReadiness.READY, rig.readiness)
        }

    @Test
    fun `missing consent, a missing model and a missing sign-in are each their own explicit result, with nothing sent`() =
        runTest {
            setUp()
            val before = planningState()
            val cases =
                listOf(
                    Triple(rig(PlanScenario.REPLY, consented = false), PlanRouteReadiness.CONSENT_NEEDED, ProviderFailure.CONSENT_NEEDED),
                    Triple(rig(PlanScenario.REPLY, model = null), PlanRouteReadiness.MODEL_NEEDED, ProviderFailure.MODEL_NEEDED),
                    Triple(rig(PlanScenario.REPLY, signedIn = false), PlanRouteReadiness.SIGN_IN_NEEDED, ProviderFailure.SIGN_IN_NEEDED),
                )
            cases.forEach { (rig, readiness, expected) ->
                assertEquals(readiness, rig.readiness)
                rig.session.send("add a room booking")
                assertEquals(providerFailureText(expected, budgetNotes = false), (rig.session.lines.last() as Entry.NotApplied).text)
                assertEquals(0, rig.fake.calls.size)
                assertEquals(0, rig.default.calls)
                assertNull(rig.session.state.value.pending)
            }
            assertEquals(before, planningState())
            assertEquals(3, cases.map { providerFailureText(it.third, false) }.toSet().size)
        }

    @Test
    fun `without an installed route the default provider is used and the plan service is never reached`() =
        runTest {
            setUp()
            val fake = FakePlanService()
            val port = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { instant })
            val default = DefaultProvider()
            val session = newConversationSession(port, default, null)
            session.send("hello")
            assertEquals(1, default.calls)
            assertEquals(0, fake.calls.size)
            assertNull(ConversationRoutes.installed)
            // The default route keeps its budget wording; the plan route's makes none.
            assertTrue("budget" in providerFailureText(com.thalia.conversation.ProviderFailure.NETWORK))
        }

    @Test
    fun `the route notice is explicit about what is missing and claims nothing about eligibility, cost or metering`() {
        PlanRouteReadiness.entries.forEach {
            val notice = planRouteNotice(it).lowercase()
            listOf("budget", "free", "billing", "quota", "eligible", "metered", "included", "key").forEach { word ->
                assertTrue(word !in notice, "$it: $notice")
            }
        }
        assertTrue("not signed in" in planRouteNotice(PlanRouteReadiness.SIGN_IN_NEEDED))
        assertTrue("no model" in planRouteNotice(PlanRouteReadiness.MODEL_NEEDED))
        assertTrue("not agreed" in planRouteNotice(PlanRouteReadiness.CONSENT_NEEDED))
        assertTrue("without your tap" in planRouteNotice(PlanRouteReadiness.READY))
    }

    @Test
    fun `failures name no key, and the plan failures do not talk about a monthly budget`() {
        listOf(
            com.thalia.conversation.ProviderFailure.SIGN_IN_NEEDED,
            com.thalia.conversation.ProviderFailure.USAGE_LIMIT,
            com.thalia.conversation.ProviderFailure.USAGE_UNAVAILABLE,
            com.thalia.conversation.ProviderFailure.MODEL_NEEDED,
        ).forEach {
            val text = providerFailureText(it, budgetNotes = false)
            assertTrue("key" !in text.lowercase() && "budget" !in text.lowercase(), text)
            assertTrue("Nothing was changed" in text)
        }
    }
}
