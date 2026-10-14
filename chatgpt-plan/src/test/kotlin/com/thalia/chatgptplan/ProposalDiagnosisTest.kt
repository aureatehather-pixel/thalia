package com.thalia.chatgptplan

import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RawCall
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The B2 correction failed with "changed more than priority", and the live log cut the call at 110 characters, so the extra
 * field is unknown. These tests use the real parser, validator and scorer to show which shapes of call pass and which fail,
 * and what the reason names. They do not recover the missing live field.
 */
class ProposalDiagnosisTest {
    private val context = ProposalData.context(false)
    private val venue = context.actions.first { "venue" in it.action.title }.ref
    private val venueTitle = "Synthetic: email the venue about the room"

    private fun correction(args: String) =
        ProposalCases
            .byId("correction")
            .score(ProviderResponse.Reply(null, listOf(RawCall("propose_action_change", args))))

    @Test
    fun `the venue email is a4 as in the live run, with no due date and a 15 minute estimate`() {
        assertEquals("a4", venue)
        val action = context.action(venue)!!.action
        assertEquals(null, action.dueDate)
        assertEquals(15, action.estimateMinutes)
    }

    @Test
    fun `the logged prefix of the failing call, title priority and estimate, passes on its own`() {
        val s = correction("""{"action_ref":"$venue","title":"$venueTitle","priority":"must","estimate_minutes":15}""")
        assertEquals(Verdict.PASS, s.verdict, s.reasons.toString())
        assertTrue("restated unchanged: title, estimate" in s.reasons.single(), s.reasons.toString())
    }

    @Test
    fun `an empty due date, a false clear flag and a clear on an action with no due date change nothing`() {
        val args =
            """{"action_ref":"$venue","title":"$venueTitle","priority":"must","estimate_minutes":15,""" +
                """"due_date":"","clear_due_date":false}"""
        assertEquals(Verdict.PASS, correction(args).verdict)
        val cleared = """{"action_ref":"$venue","priority":"must","clear_due_date":true}"""
        assertEquals(Verdict.PASS, correction(cleared).verdict)
    }

    @Test
    fun `an added due date fails and the reason names the due date and its values`() {
        val s =
            correction("""{"action_ref":"$venue","title":"$venueTitle","priority":"must","estimate_minutes":15,"due_date":"2026-10-09"}""")
        assertEquals(Verdict.FAIL, s.verdict)
        assertTrue("the due date (adding 2026-10-09)" in s.reasons.single(), s.reasons.toString())
        assertTrue("asked instead of offering a priority-only change" in s.reasons.single())
    }

    @Test
    fun `a changed estimate or title fails and is named`() {
        val estimate = correction("""{"action_ref":"$venue","priority":"must","estimate_minutes":30}""")
        assertEquals(Verdict.FAIL, estimate.verdict)
        assertTrue("the estimate from 15 to 30 minutes" in estimate.reasons.single())
        val title = correction("""{"action_ref":"$venue","priority":"must","title":"Email the venue"}""")
        assertEquals(Verdict.FAIL, title.verdict)
        assertTrue("the title to \"Email the venue\"" in title.reasons.single())
    }

    @Test
    fun `an existing due date restated as it was is no change, so the same shape passes on the demo script`() {
        val script = context.actions.first { "demo script" in it.action.title }
        val s =
            ProposalCases
                .byId("priority-change")
                .score(
                    ProviderResponse.Reply(
                        null,
                        listOf(
                            RawCall(
                                "propose_action_change",
                                """{"action_ref":"${script.ref}","title":"${script.action.title}","priority":"must",""" +
                                    """"estimate_minutes":45,"due_date":"${script.action.dueDate}"}""",
                            ),
                        ),
                    ),
                )
        assertEquals(Verdict.PASS, s.verdict, s.reasons.toString())
        assertTrue("restated unchanged: title, estimate, due date" in s.reasons.single(), s.reasons.toString())
    }

    @Test
    fun `restating a value differently from the stored one is a change, even by one character of the title`() {
        val s = correction("""{"action_ref":"$venue","title":"Synthetic: email the venue about the room.","priority":"must"}""")
        assertEquals(Verdict.FAIL, s.verdict)
        assertTrue("title" in s.reasons.single())
    }

    @Test
    fun `the full report keeps the whole reply text and every call argument, uncut`() {
        val long = "x".repeat(50) + " " + "y".repeat(400)
        val args =
            """{"action_ref":"$venue","title":"$venueTitle","priority":"must","estimate_minutes":15,""" +
                """"due_date":"2026-10-09","reason":"$long"}"""
        val response = ProviderResponse.Reply("Here is the full text of the reply. $long", listOf(RawCall("propose_action_change", args)))
        val case = ProposalCases.byId("correction")
        var n = 0
        val decisions =
            com.thalia.conversation.OperationValidator
                .validateAll(ProposalData.context(false), response.calls) { "n${++n}" }
        val report = ProposalReport.of(ProposalResult(case.id, response, case.score(response), decisions))
        assertTrue(args in report, report)
        assertTrue("Here is the full text of the reply. $long" in report)
        assertTrue("kotlin: ask" in report && "the due date (adding 2026-10-09)" in report, report)
        assertTrue("correction: FAIL" in report)
    }

    @Test
    fun `a failed request is reported as failed and a report holds no credential even if the reply echoed one`() {
        val case = ProposalCases.byId("correction")
        val failed = ProviderResponse.Failed(com.thalia.conversation.ProviderFailure.NETWORK)
        val report = ProposalReport.of(ProposalResult(case.id, failed, case.score(failed), emptyList()))
        assertTrue("request failed" in report)
        val echoed = ProviderResponse.Reply("Authorization: Bearer abcdefgh12345678", emptyList())
        val line =
            ProofRecord(
                0,
                "proposal-full",
                "x",
                "o",
                detail = ProposalReport.of(ProposalResult(case.id, echoed, case.score(echoed), emptyList())),
                detailLimit = ProofRecord.FULL_DETAIL,
            ).line()
        assertFalse("abcdefgh12345678" in line)
    }

    @Test
    fun `an evidence line is cut at 700 characters by default and holds a whole report when asked`() {
        val text = "word ".repeat(400)
        assertTrue(ProofRecord(0, "k", "s", "o", detail = text).line().length < 900)
        val full = ProofRecord(0, "k", "s", "o", detail = text, detailLimit = ProofRecord.FULL_DETAIL).line()
        assertTrue(full.length > text.trim().length)
    }
}
