package com.thalia.app.conversation

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.planning.CheckInZone
import com.thalia.app.planning.CheckInZoneSource
import com.thalia.app.planning.data.PlanningDatabase
import com.thalia.app.planning.data.PlanningStore
import com.thalia.app.planning.data.plainPlanningDatabase
import com.thalia.conversation.ProviderRequest
import com.thalia.conversation.ProviderResponse
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
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

private class HeardSource : SpeechSource {
    private var done: ((SpeechOutcome) -> Unit)? = null
    override val deviceTag = "en-MY"

    override suspend fun readiness() = SpeechReadiness.Available(listOf("en-MY"))

    override fun listen(
        languageTag: String,
        onDone: (SpeechOutcome) -> Unit,
    ) {
        done = onDone
    }

    override fun cancel() = Unit

    override fun release() = Unit

    fun hear(text: String) = done!!(SpeechOutcome.Heard(text))
}

/** Send after speaking: off unless asked for, and then at most one send per heard phrase, never of an empty or changed draft. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class SendAfterSpeakingTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val dbName = "send-after-speaking-test.db"
    private var database: PlanningDatabase? = null
    private val requests = mutableListOf<ProviderRequest>()

    private val provider =
        object : com.thalia.conversation.ConversationProvider {
            override suspend fun respond(request: ProviderRequest): ProviderResponse {
                requests += request
                return ProviderResponse.Reply("ok", emptyList())
            }
        }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(dbName)
    }

    private suspend fun model(
        scope: TestScope,
        autoSend: Boolean,
    ): Pair<ConversationViewModel, HeardSource> {
        val db = plainPlanningDatabase(context, context.getDatabasePath(dbName))
        database = db
        val store = PlanningStore(db) { 1_000L }
        store.saveSettings(PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))))
        val runner =
            object : StoreRunner {
                override suspend fun <T> run(block: suspend (PlanningStore) -> T): T = block(store)
            }
        val port =
            StoreConversationPort(
                runner,
                CheckInZoneSource { CheckInZone.Known(ZoneId.of("UTC")) },
                { Instant.parse("2026-10-06T10:00:00Z") },
            )
        val source = HeardSource()
        val composer = ConversationComposer(source)
        val vm = ConversationViewModel(ConversationSession(port, provider), composer, null, { autoSend }, scope.backgroundScope)
        composer.voice!!.check()
        composer.voice.select("en-MY")
        composer.voice.start()
        return vm to source
    }

    /** The store works off the test dispatcher, so wait (briefly) for a send that is expected to arrive. */
    private fun TestScope.settle(expected: Int) {
        repeat(250) {
            advanceTimeBy(500)
            if (requests.size >= expected) return
            Thread.sleep(20)
        }
    }

    @Test
    fun `with the toggle off a heard phrase waits in the box and nothing is sent`() =
        runTest(StandardTestDispatcher()) {
            val (vm, source) = model(this, autoSend = false)
            source.hear("Let's go through my Inbox, one item at a time.")
            advanceTimeBy(1000)
            assertEquals(0, requests.size)
            assertEquals("Let's go through my Inbox, one item at a time.", vm.composer.draft.value)
        }

    @Test
    fun `with the toggle on a heard phrase is sent once after the short pause`() =
        runTest(StandardTestDispatcher()) {
            val (vm, source) = model(this, autoSend = true)
            source.hear("Let's go through my Inbox, one item at a time.")
            advanceTimeBy(ConversationViewModel.SEND_AFTER_SPEAKING_DELAY_MS - 1)
            assertEquals(0, requests.size)
            settle(1)
            assertEquals(1, requests.size)
            assertEquals("", vm.composer.draft.value)
        }

    @Test
    fun `an empty or blank transcript is never sent`() =
        runTest(StandardTestDispatcher()) {
            val (vm, source) = model(this, autoSend = true)
            source.hear("   ")
            advanceTimeBy(1000)
            vm.sendDraft()
            advanceTimeBy(1000)
            assertEquals(0, requests.size)
        }

    @Test
    fun `pressing Send during the pause, or hearing the same phrase twice, sends once`() =
        runTest(StandardTestDispatcher()) {
            val (vm, source) = model(this, autoSend = true)
            source.hear("Let's go through my Inbox, one item at a time.")
            vm.sendDraft()
            settle(1)
            assertEquals(1, requests.size)
            // The pending automatic send finds an empty box and does nothing.
            settle(2)
            assertEquals(1, requests.size)

            vm.composer.voice!!.start()
            source.hear("Let's go through my Inbox again.")
            // A second result for the same phrase (a recognizer that reports twice) cannot send twice.
            vm.composer.afterHeard!!.invoke()
            settle(2)
            assertEquals(2, requests.size)
            settle(3)
            assertEquals(2, requests.size)
        }

    @Test
    fun `editing the draft during the pause cancels the automatic send`() =
        runTest(StandardTestDispatcher()) {
            val (vm, source) = model(this, autoSend = true)
            source.hear("Let's go through my Inbox, one item at a time.")
            vm.composer.edit("Let's go through my Inbox, one item at a time. And also the dentist.")
            advanceTimeBy(1000)
            assertEquals(0, requests.size)
            assertEquals(
                true,
                vm.composer.draft.value
                    .endsWith("dentist."),
            )
        }
}
