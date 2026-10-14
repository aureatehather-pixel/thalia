package com.thalia.app.conversation

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.thalia.voicecheckins.domain.CheckInType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class CallHandoffTest {
    @Test
    fun `each call opens its own message, unsent, in the app's own task and not over the lock screen`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (type in CheckInType.entries) {
            val intent = CallHandoff.intent(context, type)
            assertEquals(
                if (type == CheckInType.OPENING) ConversationActivity.OPENING_DRAFT else ConversationActivity.CLOSING_DRAFT,
                intent.getStringExtra(ConversationActivity.EXTRA_DRAFT),
            )
            assertEquals(type, CallHandoff.typeOf(intent))
            // A new task every time: an existing conversation task would otherwise swallow this intent (found on the Pixel).
            val both = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            assertEquals(both, intent.flags and both)
            assertEquals(0, intent.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
        assertNull(CallHandoff.typeOf(Intent().putExtra(CallHandoff.EXTRA_TYPE, "MORNING")))
        assertNull(CallHandoff.typeOf(Intent()))
    }

    @Test
    fun `the label says call handoff and claims nothing about the day, a plan or an outcome`() {
        for (type in CheckInType.entries) {
            val text = CallHandoff.notice(type)
            assertTrue(text.startsWith("Call handoff"))
            assertTrue(text.contains("not a phone conversation") && text.contains("has not been sent"))
            assertTrue(text.contains("No planning changes were made.") && text.contains("recorded as answered"))
            assertFalse(text.contains("nothing was said or recorded"))
            assertFalse(Regex("""(was|were|is|has been) (opened|closed|accepted|approved|reconciled)""").containsMatchIn(text))
        }
        assertTrue(CallHandoff.notice(CheckInType.OPENING).contains("did not open your day"))
        assertTrue(CallHandoff.notice(CheckInType.CLOSING).contains("did not close your day"))
        assertTrue(CallHandoff.notice(CheckInType.CLOSING).contains("stays unknown"))
    }

    @Test
    fun `the call's message is applied once, when the screen is first made, and never to a restored one`() {
        assertEquals("Help me start my day.", initialDraft("Help me start my day.", restored = false))
        assertEquals("", initialDraft("Help me start my day.", restored = true))
        assertEquals("", initialDraft(null, restored = false))
        // After that the draft belongs to the person: nothing here is called again, so an edit is never overwritten.
        val composer = ConversationComposer(null, initialDraft("suggestion", restored = false))
        composer.edit("my own words")
        assertEquals("my own words", composer.draft.value)
    }

    @Test
    fun `with AI unusable an answered call opens the structured check-in, not the conversation, with the same task flags`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        for (type in CheckInType.entries) {
            val intent = CallHandoff.intent(context, type, aiUsable = false)
            assertEquals(com.thalia.app.checkin.GuidedCheckInActivity::class.java.name, intent.component?.className)
            assertEquals(type.name, intent.getStringExtra(com.thalia.app.checkin.GuidedCheckInActivity.EXTRA_TYPE))
            assertNull(intent.getStringExtra(ConversationActivity.EXTRA_DRAFT), "no message is composed for a flow that sends nothing")
            val both = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
            assertEquals(both, intent.flags and both)
            assertEquals(ConversationActivity::class.java.name, CallHandoff.intent(context, type, aiUsable = true).component?.className)
        }
    }
}
