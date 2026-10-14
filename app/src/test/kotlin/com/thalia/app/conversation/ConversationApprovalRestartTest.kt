package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.conversation.RawCall
import com.thalia.planning.Action
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The 2026-10-12 livecheck flow ("Make Call vendor important.") offline: a scripted provider, the real session, parser,
 * validator, port and planning store on a file-backed database that is closed and reopened. No network, no key.
 * The Approve button itself is Compose and is not exercised here: it calls [ConversationSession.approve] through the
 * ViewModel, which is the only caller.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ConversationApprovalRestartTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "approval-restart-test.db"
    private val opened = mutableListOf<PlanningDatabase>()
    private val utc = ZoneId.of("UTC")
    private val instant = Instant.parse("2026-10-12T08:00:00Z")
    private var clock = 1_000L
    private var ids = 0
    private val provider = ScriptedProvider()

    private val vendor = Action("id-vendor", "Call vendor", null, Priority.NORMAL, 30)
    private val report = Action("id-report", "Write report", null, Priority.NORMAL, 30)

    private fun openStore(): PlanningStore {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        opened += db
        return PlanningStore(db) { clock++ }
    }

    private fun sessionOn(store: PlanningStore): ConversationSession {
        val runner =
            object : StoreRunner {
                override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
            }
        val port = StoreConversationPort(runner, CheckInZoneSource { CheckInZone.Known(utc) }, { instant })
        return ConversationSession(port, provider) { "new-${ids++}" }
    }

    @After
    fun cleanUp() {
        opened.forEach { it.close() }
        context.deleteDatabase(dbName)
    }

    @Test
    fun `a pending change writes nothing, yes changes nothing, only Approve saves, and it survives reopening`() =
        runTest {
            val store = openStore()
            store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
            listOf(vendor, report).forEach { store.saveAction(it) }
            val s = sessionOn(store)
            val before = store.actions()

            // No plan is approved and no working day is open, as on the Pixel: the proposal must still be possible.
            provider.reply(null, RawCall("propose_action_change", """{"action_ref":"a1","priority":"important"}"""))
            s.send("Make Call vendor important.")

            val pending = assertNotNull(s.state.value.pending, "a valid proposal should be pending")
            assertEquals(1, provider.requests.size)
            assertEquals(before, store.actions(), "pending must write nothing")
            assertEquals(
                0,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )
            assertTrue(pending.toString().contains("Call vendor"))

            // Typed yes, and a heard yes: words reach the draft only, then go through the same send path as typing.
            val composer = ConversationComposer(null)
            val sent = mutableListOf<String>()
            listOf("yes", "Yes.", "go ahead", "approve", "yes please").forEach {
                s.send(it)
                sent += "typed '$it' -> ${provider.requests.size} requests"
            }
            listOf("yes", "ok").forEach {
                composer.edit(it) // what a finished recognition leaves in the draft
                s.send(checkNotNull(composer.take()))
                sent += "heard '$it' -> ${provider.requests.size} requests"
            }
            assertEquals(1, provider.requests.size, "a yes must never reach the provider while a change is pending: $sent")
            assertEquals(before, store.actions(), "yes must change nothing")
            assertNotNull(s.state.value.pending)
            assertEquals(
                0,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )

            // Only approve() saves, and a second tap applies nothing more.
            s.approve()
            s.approve()
            assertNull(s.state.value.pending)
            assertEquals(
                1,
                s.state.value.entries
                    .count { it is Entry.Saved },
            )
            assertEquals(Priority.IMPORTANT, store.action("id-vendor")?.priority)
            assertEquals(report, store.action("id-report"), "no other action may change")
            assertEquals(vendor.copy(priority = Priority.IMPORTANT), store.action("id-vendor"), "only the priority may change")

            // Restart: close the database, open the same file again with a new store and a new session.
            opened.forEach { it.close() }
            opened.clear()
            val reopened = openStore()
            assertEquals(vendor.copy(priority = Priority.IMPORTANT), reopened.action("id-vendor"))
            assertEquals(report, reopened.action("id-report"))
            val fresh = sessionOn(reopened)
            assertNull(fresh.state.value.pending, "a new session holds no proposal")
            fresh.send("yes")
            assertEquals(1, provider.requests.size)
            assertEquals(Priority.IMPORTANT, reopened.action("id-vendor")?.priority)
        }
}
