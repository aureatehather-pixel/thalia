package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.planning.InboxItem
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
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
import kotlin.test.assertTrue

/**
 * ADR 0012 and ADR 0010: a provider is reached only by sending a message. Opening the conversation, which is all "Triage with
 * Thalia" does, makes no request and carries no planning data anywhere; and the provider every build wires is unavailable
 * and transmits nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationProviderGateTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "conversation-gate-test.db"
    private var database: PlanningDatabase? = null
    private val utc = ZoneId.of("UTC")

    private class CountingProvider : com.thalia.conversation.ConversationProvider {
        val requests = mutableListOf<ProviderRequest>()

        override suspend fun respond(request: ProviderRequest): ProviderResponse {
            requests += request
            return ProviderResponse.Reply("ok", emptyList())
        }
    }

    private suspend fun port(): ConversationPort {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        val store = PlanningStore(db) { 1_000L }
        store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        store.captureItem(InboxItem("item-1", "Remind me to call the dentist at 3pm"))
        val runner =
            object : StoreRunner {
                override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
            }
        return StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { Instant.parse("2026-10-06T10:00:00Z") })
    }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    @Test
    fun `opening a conversation, with or without a prefilled draft, calls no provider and reads nothing out`() =
        runTest {
            val provider = CountingProvider()
            val session = ConversationSession(port(), provider)
            // What the screen does on opening: build the state it shows, and nothing else. The draft is only text in the box.
            assertEquals(emptyList(), session.state.value.entries)
            session.dismiss()
            session.approve()
            session.undo(1)
            assertEquals(0, provider.requests.size)
        }

    @Test
    fun `a bare yes is answered here and never sent, and only a real message reaches the provider`() =
        runTest {
            val provider = CountingProvider()
            val session = ConversationSession(port(), provider)
            session.send("yes")
            assertEquals(0, provider.requests.size)
            session.send("Let's go through my Inbox, one item at a time.")
            assertEquals(1, provider.requests.size)
            assertTrue("dentist" !in provider.requests.single().contextText, "an ordinary conversation never carries a capture")
            // Only a triage the person started carries the captures they chose.
            ConversationSession(port(), provider, setOf("item-1")).send("Let's go through these captures.")
            assertTrue("Remind me to call the dentist" in provider.requests.last().contextText)
        }

    @Test
    fun `the provider every build wires answers unavailable and sends nothing`() =
        runTest {
            val response =
                UnavailableConversationProvider.respond(
                    ProviderRequest("instruction", "context with a capture", emptyList(), "[]"),
                )
            assertEquals(ProviderFailure.UNAVAILABLE, assertIs<ProviderResponse.Failed>(response).failure)

            val session = ConversationSession(port(), UnavailableConversationProvider)
            session.send("Let's go through my Inbox, one item at a time.")
            val notApplied =
                session.state.value.entries
                    .filterIsInstance<Entry.NotApplied>()
                    .single()
                    .text
            assertTrue(notApplied.startsWith("AI is not set up on this build"))
        }
}
