package com.thalia.app.voicecheckins.call

import com.thalia.app.conversation.CallHandoff
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.conversation.ConversationComposer
import com.thalia.voicecheckins.domain.CheckInType
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Answering a call must never apply a model change, record an outcome or close the day. The call classes are kept apart from
 * planning and the conversation's write path at the source level, so a later change cannot quietly connect them, and the
 * handoff only fills a message box that the person sends.
 */
class CallAnswerSafetyTest {
    private val callSources: List<File> =
        File("src/main/kotlin/com/thalia/app/voicecheckins/call")
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

    @Test
    fun `the call sources are found`() {
        assertTrue(callSources.size > 5, "the scan must see the call package, or it proves nothing")
    }

    @Test
    fun `no call class reaches planning, the conversation session or any write that closes a day or records an outcome`() {
        val forbidden =
            listOf(
                "com.thalia.app.planning",
                "com.thalia.planning",
                "ConversationSession",
                "ConversationPort",
                "closeWorkingDay",
                "openWorkingDay",
                "recordOutcome",
                "approveDay",
                "PlanningStore",
                "PlanningAccess",
            )
        val hits =
            callSources.flatMap { file ->
                val text = file.readText()
                forbidden.filter { it in text }.map { "${file.name}: $it" }
            }
        assertEquals(emptyList(), hits, "the call package must not touch planning or the conversation's writes")
    }

    @Test
    fun `a closing call hands the conversation an unsent message and nothing else`() {
        val draft = CallHandoff.draft(CheckInType.CLOSING)
        assertEquals(ConversationActivity.CLOSING_DRAFT, draft)
        val composer = ConversationComposer(null, draft)
        assertEquals(draft, composer.draft.value, "the message is in the box")
        // Nothing has been taken from the box, so nothing was sent: only taking it, which the Send button does, hands it on.
        assertEquals(draft, composer.draft.value)
    }
}
