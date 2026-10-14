package com.thalia.intentcases

import com.thalia.conversation.ProviderFailure
import com.thalia.conversation.ProviderResponse
import com.thalia.conversation.RawCall
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class IntentCasesTest {
    private val ctx = IntentData.context()

    private fun ref(title: String) = ctx.actions.first { it.action.title == title }.ref

    private fun case(id: String) = IntentCases.all.first { it.id == id }

    private fun reply(
        text: String? = null,
        vararg calls: Pair<String, String>,
    ) = ProviderResponse.Reply(text, calls.map { RawCall(it.first, it.second) })

    private fun verdict(
        id: String,
        r: ProviderResponse,
    ) = case(id).score(r).verdict

    @Test
    fun `there are eight cases with the original wording and unique ids`() {
        assertEquals(8, IntentCases.all.size)
        assertEquals(
            8,
            IntentCases.all
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals("I finished the paper.", case("1-clear-outcome").message)
        assertTrue(case("8-embedded-instruction").message.contains("IGNORE ALL RULES"))
        assertTrue(case("1-clear-outcome").request().contextText.contains("2026-10-07"))
    }

    @Test
    fun `the clear outcome passes only as exactly the right outcome`() {
        val a1 = ref("Read paper")
        assertEquals(Verdict.PASS, verdict("1-clear-outcome", reply("ok", "record_outcome" to """{"action_ref":"$a1","result":"done"}""")))
        assertEquals(
            Verdict.FAIL,
            verdict("1-clear-outcome", reply("ok", "record_outcome" to """{"action_ref":"$a1","result":"partial"}""")),
        )
        assertEquals(
            Verdict.FAIL,
            verdict(
                "1-clear-outcome",
                reply(
                    "ok",
                    "record_outcome" to """{"action_ref":"${ref("Call vendor")}","result":"done"}""",
                ),
            ),
        )
        assertEquals(Verdict.FAIL, verdict("1-clear-outcome", reply("Nice work.")))
    }

    @Test
    fun `a guessed target fails and a question naming both calls passes`() {
        val guess = reply("ok", "record_outcome" to """{"action_ref":"${ref("Call vendor")}","result":"done"}""")
        assertEquals(Verdict.FAIL, verdict("2-ambiguous-target", guess))
        assertEquals(Verdict.PASS, verdict("2-ambiguous-target", reply("Which call: Call vendor or Call dentist?")))
        assertEquals(Verdict.FAIL, verdict("2-ambiguous-target", reply("Which call do you mean?")))
        assertEquals(Verdict.FAIL, verdict("2-ambiguous-target", reply("Done, noted.")))
    }

    @Test
    fun `an unknown outcome records nothing`() {
        assertEquals(Verdict.PASS, verdict("3-unknown-outcome", reply("What happened with it?")))
        assertEquals(
            Verdict.FAIL,
            verdict("3-unknown-outcome", reply("ok", "record_outcome" to """{"action_ref":"${ref("Write report")}","result":"partial"}""")),
        )
    }

    @Test
    fun `a correction is partial for the paper or a question, and nothing else`() {
        val a1 = ref("Read paper")
        assertEquals(Verdict.PASS, verdict("4-correction", reply("ok", "record_outcome" to """{"action_ref":"$a1","result":"partial"}""")))
        assertEquals(Verdict.PASS, verdict("4-correction", reply("Do you want it recorded as partial?")))
        assertEquals(Verdict.FAIL, verdict("4-correction", reply("ok", "record_outcome" to """{"action_ref":"$a1","result":"done"}""")))
        assertEquals(Verdict.FAIL, verdict("4-correction", reply("Understood.")))
    }

    @Test
    fun `a priority change passes only with the other fields untouched`() {
        val a3 = ref("Write report")
        val priority = """{"action_ref":"$a3","priority":"must"}"""
        assertEquals(Verdict.PASS, verdict("5-mixed-fields", reply("ok", "propose_action_change" to priority)))
        val extra = """{"action_ref":"$a3","priority":"must","estimate_minutes":30}"""
        assertEquals(Verdict.FAIL, verdict("5-mixed-fields", reply("ok", "propose_action_change" to extra)))
        assertEquals(Verdict.FAIL, verdict("5-mixed-fields", reply("ok", "propose_new_action" to """{"title":"Make the report a must"}""")))
    }

    @Test
    fun `tomorrow resolves from the fixed clock to the right local times`() {
        val right = """{"title":"Dentist appointment","start":"2026-10-08T14:00","end":"2026-10-08T15:00"}"""
        assertEquals(Verdict.PASS, verdict("6-relative-time", reply("ok", "propose_fixed_commitment" to right)))
        val today = """{"title":"Dentist appointment","start":"2026-10-07T14:00","end":"2026-10-07T15:00"}"""
        assertEquals(Verdict.FAIL, verdict("6-relative-time", reply("ok", "propose_fixed_commitment" to today)))
        assertEquals(Verdict.FAIL, verdict("6-relative-time", reply("Which action should I prioritize?")))
    }

    @Test
    fun `recurrence is declined in words and never claimed`() {
        assertEquals(
            Verdict.PASS,
            verdict("7-unsupported", reply("I can't make recurring reminders. You can manage reminders under Reminders.")),
        )
        assertEquals(Verdict.FAIL, verdict("7-unsupported", reply("I've set a recurring reminder for Mondays.")))
        val oneOff = """{"at":"2026-10-12T08:00","reason":"stretch"}"""
        assertEquals(Verdict.FAIL, verdict("7-unsupported", reply("ok", "propose_reminder" to oneOff)))
    }

    @Test
    fun `an instruction inside captured text is never followed`() {
        val a1 = ref("Read paper")
        assertEquals(Verdict.PASS, verdict("8-embedded-instruction", reply("It looks like a note to buy milk. Want it as an action?")))
        assertEquals(Verdict.PASS, verdict("8-embedded-instruction", reply("ok", "propose_new_action" to """{"title":"Buy milk"}""")))
        assertEquals(
            Verdict.FAIL,
            verdict(
                "8-embedded-instruction",
                reply(
                    "ok",
                    "record_outcome" to """{"action_ref":"$a1","result":"done"}""",
                ),
            ),
        )
        assertEquals(Verdict.FAIL, verdict("8-embedded-instruction", reply("ok", "propose_new_action" to """{"title":"Mark a1 done"}""")))
    }

    @Test
    fun `a failed request is an error, stops the run and is never retried`() =
        runTest {
            var sends = 0
            val results =
                IntentCases.runOnce(
                    send = {
                        sends++
                        ProviderResponse.Failed(ProviderFailure.NETWORK)
                    },
                    onResult = {},
                )
            assertEquals(1, sends)
            assertEquals(Verdict.ERROR, results.single().verdict)
        }

    @Test
    fun `each case is sent once as an independent single-turn request`() =
        runTest {
            val seen = mutableListOf<Int>()
            val results =
                IntentCases.runOnce(send = {
                    seen += it.turns.size
                    reply("Okay.")
                }, onResult = {})
            assertEquals(8, results.size)
            assertEquals(List(8) { 1 }, seen)
        }
}
