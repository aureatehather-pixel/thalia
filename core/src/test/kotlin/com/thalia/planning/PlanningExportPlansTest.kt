package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR 0009 slice 2: the payload gains plans and items (schema 2), and a schema 1 export, exactly as
 * slice 1B wrote it, is still restored. The encryption envelope's own version is not touched.
 */
class PlanningExportPlansTest {
    private val goalId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a001"
    private val projectId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a002"
    private val actionId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a003"
    private val standaloneId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a004"
    private val plan1 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b001"
    private val plan2 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b002"

    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))
    private val day = ActiveDay(LocalDate.of(2026, 10, 3))

    private fun plan(
        id: String,
        revision: Int,
    ) = DayPlan(id, day, revision, ZoneId.of("Asia/Singapore"), LocalTime.of(4, 0), window, 540, 432, 60, 7_000L + revision)

    private val items =
        listOf(
            PlanItem(
                "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c001",
                plan2,
                actionId,
                ItemKind.QUEUED,
                null,
                120,
                0,
                Placement.IN_BUFFER,
                null,
                "Long run",
                Priority.MUST,
                projectId,
                "Training block",
                ProjectState.ACTIVE,
            ),
            PlanItem(
                "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c002",
                plan2,
                standaloneId,
                ItemKind.PINNED,
                LocalTime.of(14, 0),
                60,
                0,
                null,
                null,
                "Call \"bank\" 日本",
                Priority.NORMAL,
                null,
                null,
                null,
            ),
            PlanItem(
                "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c003",
                plan1,
                actionId,
                ItemKind.LEFT_OUT,
                null,
                120,
                0,
                null,
                LeftOutReason.PROJECT_LIMIT,
                "Old title",
                Priority.NORMAL,
                projectId,
                "Old project name",
                ProjectState.PAUSED,
            ),
        )

    private val snapshot =
        PlanningSnapshot(
            direction = DirectionRecord(Direction("Stay curious"), 1_000),
            settings = PlanningSettings(LocalTime.of(4, 0), window),
            goals = listOf(Stamped(Goal(goalId, "Run a marathon", "Finish"), 10, 20)),
            projects = listOf(Stamped(Project(projectId, "Training block", goalId, ProjectState.ACTIVE), 11, 21)),
            actions =
                listOf(
                    Stamped(Action(actionId, "Long run", projectId, Priority.MUST, 120), 12, 22),
                    Stamped(Action(standaloneId, "Call bank"), 13, 23),
                ),
            plans = listOf(plan(plan1, 1), plan(plan2, 2)),
            planItems = items,
        )

    private val info = PlanningExportInfo(4, "0.1-test", Instant.parse("2026-10-03T08:00:00Z"), ZoneId.of("Asia/Singapore"))

    private fun json(s: PlanningSnapshot = snapshot) = String(PlanningExportFormat.encode(PlanningExportContent(info, s)))

    private fun decode(text: String) = PlanningExportFormat.decode(text.toByteArray(), currentSchemaVersion = 4)

    private fun refused(
        problem: ImportProblem,
        text: String,
    ) = assertEquals(problem, assertFailsWith<ImportRefusedException>(text) { decode(text) }.problem, text)

    @Test
    fun `plans and items round-trip with every field, including snapshots that differ from the live rows`() {
        assertEquals(PlanningExportContent(info, snapshot), decode(json()))
        assertEquals(PlanningCounts(1, 1, 1, 1, 2, plans = 2, planItems = 3), snapshot.counts)
    }

    @Test
    fun `a payload still carries the envelope's format 1 beside its own schema 4`() {
        val text = json()
        assertTrue("\"format\":${PlanningArchive.FORMAT_VERSION}" in text)
        assertTrue("\"schemaVersion\":4" in text)
    }

    /** The payload slice 1B wrote: schema 1, no plans, counts without them. Written from that encoder's output shape. */
    private val schemaOnePayload =
        """{"format":1,"schemaVersion":1,"appVersion":"0.1-m1-debug","createdAt":"2026-10-02T13:14:15Z","zone":"Asia/Singapore",""" +
            """"counts":{"direction":1,"settings":1,"goals":1,"projects":1,"actions":2},""" +
            """"direction":{"text":"Stay curious","updatedAtMillis":1000},""" +
            """"settings":{"rollover":"04:00","windowStart":"12:00","windowEnd":"22:00"},""" +
            """"goals":[{"id":"$goalId","title":"Run a marathon","successCondition":"Finish",""" +
            """"horizon":null,"status":"ACTIVE","createdAtMillis":10,"updatedAtMillis":20}],""" +
            """"projects":[{"id":"$projectId","title":"Training block","goalId":"$goalId",""" +
            """"state":"ACTIVE","createdAtMillis":11,"updatedAtMillis":21}],""" +
            """"actions":[{"id":"$actionId","title":"Long run","projectId":"$projectId","priority":"MUST",""" +
            """"estimateMinutes":120,"dueDate":null,"status":"OPEN","createdAtMillis":12,"updatedAtMillis":22},""" +
            """{"id":"$standaloneId","title":"Call bank","projectId":null,"priority":"NORMAL","estimateMinutes":30,"dueDate":null,"status":"OPEN","createdAtMillis":13,"updatedAtMillis":23}]}"""

    @Test
    fun `a schema 1 export is restored, migrated to no plans, keeping every row and its original version`() {
        val content = decode(schemaOnePayload)
        assertEquals(1, content.info.schemaVersion)
        assertEquals(snapshot.copy(plans = emptyList(), planItems = emptyList()), content.snapshot)
        assertEquals(PlanningCounts(1, 1, 1, 1, 2), content.snapshot.counts)
    }

    @Test
    fun `a schema 1 payload is strict, so schema 2 fields in it or schema 1 fields missing are refused`() {
        refused(ImportProblem.INVALID_CONTENT, schemaOnePayload.replace("\"actions\":[{", "\"plans\":[],\"actions\":[{"))
        refused(ImportProblem.INVALID_CONTENT, schemaOnePayload.replace("\"counts\":{", "\"counts\":{\"plans\":0,"))
        refused(ImportProblem.INVALID_CONTENT, schemaOnePayload.replace("\"zone\":\"Asia/Singapore\",", ""))
    }

    @Test
    fun `a schema 2 payload without its plans, or schema 0, is refused and a schema 5 is newer`() {
        refused(ImportProblem.INVALID_CONTENT, schemaOnePayload.replace("\"schemaVersion\":1", "\"schemaVersion\":2"))
        refused(ImportProblem.INVALID_CONTENT, schemaOnePayload.replace("\"schemaVersion\":1", "\"schemaVersion\":0"))
        refused(ImportProblem.NEWER_VERSION, json().replace("\"schemaVersion\":4", "\"schemaVersion\":5"))
    }

    @Test
    fun `the writer refuses to write the old shape`() {
        assertFailsWith<IllegalArgumentException> {
            PlanningExportFormat.encode(
                PlanningExportContent(info.copy(schemaVersion = 1), snapshot),
            )
        }
    }

    @Test
    fun `counts that do not match the plans or items are refused`() {
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"plans\":2", "\"plans\":1"))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"planItems\":3", "\"planItems\":2"))
    }

    @Test
    fun `broken plan references are refused`() {
        refused(ImportProblem.BROKEN_REFERENCE, json().replace("\"planId\":\"$plan1\"", "\"planId\":\"$goalId\""))
        refused(ImportProblem.BROKEN_REFERENCE, json().replace("\"actionId\":\"$standaloneId\"", "\"actionId\":\"$goalId\""))
        refused(
            ImportProblem.BROKEN_REFERENCE,
            json().replace("\"projectId\":\"$projectId\",\"projectTitle\":\"Old", "\"projectId\":\"$goalId\",\"projectTitle\":\"Old"),
        )
        refused(ImportProblem.BROKEN_REFERENCE, json().replace("\"id\":\"$plan2\"", "\"id\":\"$plan1\""))
        refused(
            ImportProblem.BROKEN_REFERENCE,
            json().replace("5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c002", "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c001"),
        )
    }

    @Test
    fun `a day cannot have a duplicate revision or a gap, so history is only ever added to`() {
        val dup = snapshot.copy(plans = listOf(plan(plan1, 2), plan(plan2, 2)))
        refused(ImportProblem.BROKEN_REFERENCE, json(dup))
        val gap = snapshot.copy(plans = listOf(plan(plan1, 1), plan(plan2, 3)))
        refused(ImportProblem.BROKEN_REFERENCE, json(gap))
        val fromTwo = snapshot.copy(plans = listOf(plan(plan1, 2)))
        refused(ImportProblem.BROKEN_REFERENCE, json(fromTwo.copy(planItems = emptyList())))
    }

    @Test
    fun `a plan has no status field, so a payload that carries one is refused`() {
        refused(
            ImportProblem.INVALID_CONTENT,
            json().replace("\"zone\":\"Asia/Singapore\",\"rollover\"", "\"status\":\"APPROVED\",\"zone\":\"Asia/Singapore\",\"rollover\""),
        )
    }

    @Test
    fun `a rest day plan with no items round-trips`() {
        val rest = snapshot.copy(plans = listOf(plan(plan1, 1)), planItems = emptyList())
        assertEquals(PlanningExportContent(info, rest), decode(json(rest)))
    }

    @Test
    fun `an action twice in one plan is refused`() {
        val twice = snapshot.copy(planItems = items + items[0].copy(id = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c009"))
        refused(ImportProblem.BROKEN_REFERENCE, json(twice))
    }

    @Test
    fun `invalid plan fields, enums, extra or missing keys and inconsistent items are refused`() {
        listOf(
            json().replace("\"kind\":\"PINNED\"", "\"kind\":\"pinned\""),
            json().replace("\"revision\":2", "\"revision\":0"),
            json().replace("\"activeDay\":\"2026-10-03\"", "\"activeDay\":\"2026-02-30\""),
            json().replace("\"zone\":\"Asia/Singapore\",\"rollover\"", "\"zone\":\"Mars/Olympus\",\"rollover\""),
            json().replace("\"targetMinutes\":432", "\"targetMinutes\":9999"),
            json().replace("\"freeMinutes\":540", "\"freeMinutes\":-1"),
            json().replace("\"minutes\":60", "\"minutes\":4"),
            json().replace("\"start\":\"14:00\"", "\"start\":null"),
            json().replace("\"placement\":\"IN_BUFFER\"", "\"placement\":null"),
            json().replace("\"reason\":\"PROJECT_LIMIT\"", "\"reason\":\"OVER_TARGET\",\"x\":1"),
            json().replace("\"title\":\"Old title\"", "\"title\":\" \""),
            json().replace("\"title\":\"Old title\"", "\"title\":\"${"x".repeat(PlanningText.TITLE_MAX + 1)}\""),
            json().replace("\"projectTitle\":\"Old project name\"", "\"projectTitle\":null"),
            json().replace("\"overloadMinutes\":60", "\"overloadMinutes\":\"60\""),
        ).forEach { refused(ImportProblem.INVALID_CONTENT, it) }
    }

    @Test
    fun `more than 100,000 plans are refused by their count`() {
        val many = List(PlanningExportFormat.MAX_ROWS + 1) { "0" }.joinToString(",", "[", "]")
        val text = json(PlanningSnapshot.EMPTY).replace("\"plans\":[]", "\"plans\":$many")
        assertTrue("more than 100000 plans" in assertFailsWith<ImportRefusedException> { decode(text) }.message.orEmpty())
    }
}
