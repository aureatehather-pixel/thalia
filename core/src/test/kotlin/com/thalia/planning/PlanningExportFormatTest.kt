package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** ADR 0009 decision 8: the payload's format and the validation done before anything is written. */
class PlanningExportFormatTest {
    private val goalId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a001"
    private val projectId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a002"
    private val actionId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a003"
    private val standaloneId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a004"

    private val snapshot =
        PlanningSnapshot(
            direction = DirectionRecord(Direction("Stay curious, \"quoted\" and ünicode 日本"), 1_000),
            settings = PlanningSettings(LocalTime.of(4, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))),
            goals = listOf(Stamped(Goal(goalId, "Run a marathon", "Finish in under 4 hours", LocalDate.of(2027, 4, 1)), 10, 20)),
            projects = listOf(Stamped(Project(projectId, "Training block", goalId, ProjectState.ACTIVE), 11, 21)),
            actions =
                listOf(
                    Stamped(Action(actionId, "Long run", projectId, Priority.MUST, 120, LocalDate.of(2026, 10, 4)), 12, 22),
                    Stamped(Action(standaloneId, "Call bank", status = ActionStatus.DONE), 13, 23),
                ),
        )

    private val info = PlanningExportInfo(4, "0.1-m1-debug", Instant.parse("2026-10-02T13:14:15Z"), ZoneId.of("Asia/Singapore"))

    private fun json(content: PlanningExportContent = PlanningExportContent(info, snapshot)) = String(PlanningExportFormat.encode(content))

    private fun decode(text: String) = PlanningExportFormat.decode(text.toByteArray(), currentSchemaVersion = 4)

    private fun refusedWith(
        problem: ImportProblem,
        text: String,
    ) = assertEquals(problem, assertFailsWith<ImportRefusedException>(text) { decode(text) }.problem, text)

    @Test
    fun `a payload round-trips every row and field`() {
        assertEquals(PlanningExportContent(info, snapshot), decode(json()))
        val empty = PlanningExportContent(info, PlanningSnapshot.EMPTY)
        assertEquals(empty, decode(json(empty)))
    }

    @Test
    fun `the payload holds no key material, only rows and their metadata`() {
        val text = json()
        listOf("passphrase", "sealed", "keystore", "apiKey", "checkins").forEach { assertTrue(it !in text.lowercase(), it) }
    }

    @Test
    fun `a project linked to a missing goal, or an action to a missing project, is refused`() {
        refusedWith(ImportProblem.BROKEN_REFERENCE, json().replace("\"goalId\":\"$goalId\"", "\"goalId\":\"$actionId\""))
        refusedWith(ImportProblem.BROKEN_REFERENCE, json().replace("\"projectId\":\"$projectId\"", "\"projectId\":\"$goalId\""))
    }

    @Test
    fun `two rows with one id are refused`() {
        refusedWith(ImportProblem.BROKEN_REFERENCE, json().replace(standaloneId, actionId))
    }

    @Test
    fun `counts that do not match the rows are refused`() {
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("\"actions\":2", "\"actions\":3"))
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("\"direction\":1", "\"direction\":0"))
    }

    @Test
    fun `a newer schema is refused as newer, and an impossible one as invalid`() {
        refusedWith(ImportProblem.NEWER_VERSION, json().replace("\"schemaVersion\":4", "\"schemaVersion\":5"))
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("\"schemaVersion\":4", "\"schemaVersion\":0"))
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("\"format\":1", "\"format\":2"))
    }

    @Test
    fun `missing, extra or mistyped fields and unknown values are refused`() {
        listOf(
            json().replace("\"successCondition\":\"Finish in under 4 hours\",", ""),
            json().replace("\"title\":\"Long run\"", "\"title\":\"Long run\",\"extra\":true"),
            json().replace("\"estimateMinutes\":120", "\"estimateMinutes\":\"120\""),
            json().replace("\"createdAtMillis\":10", "\"createdAtMillis\":10.5"),
            json().replace("\"status\":\"DONE\"", "\"status\":\"FINISHED\""),
            json().replace("\"priority\":\"MUST\"", "\"priority\":\"must\""),
            json().replace("2027-04-01", "2027-02-30"),
            json().replace("\"windowEnd\":\"22:00\"", "\"windowEnd\":null"),
            json().replace("\"rollover\":\"04:00\"", "\"rollover\":\"04:00:30\""),
            json().replace("Asia/Singapore", "Mars/Olympus"),
            json().replace("2026-10-02T13:14:15Z", "yesterday"),
            json().replace(actionId, "not-a-uuid"),
            json().replace(actionId, actionId.uppercase()),
            json().replace("\"goals\":[", "\"goals\":{\"x\":[").replace("],\"projects\"", "]},\"projects\""),
            "[]",
            "not json",
            "",
        ).forEach { refusedWith(ImportProblem.INVALID_CONTENT, it) }
    }

    @Test
    fun `text over the data model's limits and out-of-range estimates are refused`() {
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("Run a marathon", "x".repeat(PlanningText.TITLE_MAX + 1)))
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("Stay curious", "x".repeat(PlanningText.DIRECTION_MAX + 1)))
        refusedWith(
            ImportProblem.INVALID_CONTENT,
            json().replace("Finish in under 4 hours", "x".repeat(PlanningText.SUCCESS_CONDITION_MAX + 1)),
        )
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("\"estimateMinutes\":120", "\"estimateMinutes\":721"))
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("\"title\":\"Long run\"", "\"title\":\" \""))
        refusedWith(ImportProblem.INVALID_CONTENT, json().replace("0.1-m1-debug", "v".repeat(65)))
    }

    @Test
    fun `nesting deeper than the limit is refused before parsing`() {
        refusedWith(ImportProblem.INVALID_CONTENT, "[".repeat(100_000) + "]".repeat(100_000))
        val deep = json().replace("\"goals\":[", "\"goals\":[" + "[".repeat(8)).replace("],\"projects\"", "]".repeat(8) + "],\"projects\"")
        refusedWith(ImportProblem.INVALID_CONTENT, deep)
    }

    @Test
    fun `brackets inside text do not count towards the depth limit`() {
        val bracketed = snapshot.copy(direction = DirectionRecord(Direction("[[[[[[[[[[{{{{{{{{{{ \\\" ]]]"), 5))
        assertEquals(bracketed, decode(json(PlanningExportContent(info, bracketed))).snapshot)
    }

    @Test
    fun `more than 100,000 rows of one entity are refused by their count, before any row is read`() {
        val actions = List(PlanningExportFormat.MAX_ROWS + 1) { "0" }.joinToString(",", "[", "]")
        val text = json(PlanningExportContent(info, PlanningSnapshot.EMPTY)).replace("\"actions\":[]", "\"actions\":$actions")
        val refused = assertFailsWith<ImportRefusedException> { decode(text) }
        assertEquals(ImportProblem.INVALID_CONTENT, refused.problem)
        assertTrue("more than 100000 actions" in refused.message.orEmpty(), refused.message)
    }

    @Test
    fun `a payload over 16 MiB is refused unparsed`() {
        assertEquals(
            ImportProblem.INVALID_CONTENT,
            assertFailsWith<ImportRefusedException> {
                PlanningExportFormat.decode(ByteArray(PlanningExportFormat.MAX_PAYLOAD_BYTES + 1), currentSchemaVersion = 4)
            }.problem,
        )
    }
}
