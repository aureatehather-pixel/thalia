package com.thalia.planning

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ADR 0009 slice 3: the payload gains outcomes and each plan's opening time (schema 3), carries no call
 * status, and exports from schemas 1 and 2 still restore. Outcome invariants are checked on import by the
 * same function the storage uses.
 */
class PlanningExportOutcomesTest {
    private val actionA = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a003"
    private val actionB = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1a004"
    private val plan1 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b001"
    private val plan2 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1b002"
    private val itemA1 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c001"
    private val itemB1 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c002"
    private val itemA2 = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c003"
    private val itemLeftOut = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c004"

    private val window = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))

    private fun plan(
        id: String,
        day: Int,
    ) = DayPlan(id, ActiveDay(LocalDate.of(2026, 10, day)), 1, ZoneId.of("Asia/Singapore"), LocalTime.of(4, 0), window, 540, 432, 0, 7_000)

    private fun item(
        id: String,
        plan: String,
        action: String,
        kind: ItemKind = ItemKind.QUEUED,
    ) = PlanItem(
        id,
        plan,
        action,
        kind,
        if (kind == ItemKind.PINNED) LocalTime.of(14, 0) else null,
        30,
        0,
        if (kind == ItemKind.QUEUED) Placement.WITHIN_TARGET else null,
        if (kind == ItemKind.LEFT_OUT) LeftOutReason.OVER_TARGET else null,
        "t",
        Priority.NORMAL,
        null,
        null,
        null,
    )

    private fun snapshot(
        statusA: ActionStatus = ActionStatus.DONE,
        outcomes: List<PlanOutcome> =
            listOf(
                PlanOutcome(itemA1, OutcomeResult.DONE, 100, closedAction = true),
                PlanOutcome(itemB1, OutcomeResult.PARTIAL, 101),
                PlanOutcome(itemA2, OutcomeResult.DONE, 102),
            ),
    ) = PlanningSnapshot(
        direction = null,
        settings = null,
        goals = emptyList(),
        projects = emptyList(),
        actions = listOf(Stamped(Action(actionA, "A", status = statusA), 1, 2), Stamped(Action(actionB, "B"), 3, 4)),
        plans = listOf(plan(plan1, 3), plan(plan2, 4)),
        planItems =
            listOf(
                item(itemA1, plan1, actionA),
                item(itemB1, plan1, actionB, ItemKind.PINNED),
                item(itemA2, plan2, actionA),
                item(itemLeftOut, plan2, actionB, ItemKind.LEFT_OUT),
            ),
        outcomes = outcomes,
        openingTimes = mapOf(plan1 to LocalTime.of(8, 0)),
    )

    private val info = PlanningExportInfo(4, "0.1-test", Instant.parse("2026-10-04T08:00:00Z"), ZoneId.of("Asia/Singapore"))

    private fun json(s: PlanningSnapshot = snapshot()) = String(PlanningExportFormat.encode(PlanningExportContent(info, s)))

    private fun decode(text: String) = PlanningExportFormat.decode(text.toByteArray(), currentSchemaVersion = 4)

    private fun refused(
        problem: ImportProblem,
        text: String,
    ) = assertEquals(problem, assertFailsWith<ImportRefusedException>(text) { decode(text) }.problem, text)

    /** The schema 3 shape of a payload this encoder wrote: no commitments, working days or project context. */
    private fun schemaThree(text: String) =
        text
            .replace("\"schemaVersion\":4", "\"schemaVersion\":3")
            .replace(Regex(",\"commitments\":\\[.*?],\"workingDays\":\\[.*?]}$"), "}")
            .replace(Regex(",\"commitments\":\\d+,\"workingDays\":\\d+"), "")
            .replace(
                Regex(
                    ",\"purpose\":(null|\"[^\"]*\"),\"doneWhen\":(null|\"[^\"]*\"),\"constraints\":(null|\"[^\"]*\"),\"blocker\":(null|\"[^\"]*\")",
                ),
                "",
            )

    @Test
    fun `outcomes with their marker and each plan's opening time round-trip, counted`() {
        val s = snapshot()
        assertEquals(PlanningExportContent(info, s), decode(json(s)))
        assertEquals(3, s.counts.outcomes)
        assertTrue("\"openingTime\":\"08:00\"" in json(s))
        assertTrue("\"openingTime\":null" in json(s))
    }

    @Test
    fun `the payload carries no call status, detail or update time`() {
        val text = json()
        listOf("status\":\"REQUESTED", "CONFIRMED", "NOT_LINKED", "\"detail\"", "callStatus", "\"updatedAtMillis\":7").forEach {
            assertFalse(it in text, it)
        }
    }

    @Test
    fun `a schema 2 export restores, migrated in memory to no outcomes and no opening times`() {
        val v2 =
            schemaThree(json())
                .replace("\"schemaVersion\":3", "\"schemaVersion\":2")
                .replace(Regex(",\"outcomes\":\\[.*]}$"), "}")
                .replace(Regex(",\"outcomes\":\\d+"), "")
                .replace(Regex(",\"openingTime\":(null|\"[^\"]*\")"), "")
        val content = decode(v2)
        assertEquals(2, content.info.schemaVersion)
        assertEquals(snapshot().copy(outcomes = emptyList(), openingTimes = emptyMap()), content.snapshot)
    }

    @Test
    fun `a schema 2 payload is strict, so schema 3 fields in it are refused`() {
        refused(ImportProblem.INVALID_CONTENT, schemaThree(json()).replace("\"schemaVersion\":3", "\"schemaVersion\":2"))
    }

    @Test
    fun `a schema 3 payload is strict about its new fields, counts and values`() {
        refused(ImportProblem.INVALID_CONTENT, json().replace(Regex(",\"openingTime\":(null|\"[^\"]*\")"), ""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"outcomes\":3", "\"outcomes\":2"))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"result\":\"PARTIAL\"", "\"result\":\"partial\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"result\":\"PARTIAL\"", "\"result\":\"PARTIAL\",\"x\":1"))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"closedAction\":true", "\"closedAction\":\"true\""))
        refused(ImportProblem.INVALID_CONTENT, json().replace("\"recordedAtMillis\":101", "\"recordedAtMillis\":\"101\""))
        listOf("8:00", "08:00:30", "25:00", "noon").forEach {
            refused(ImportProblem.INVALID_CONTENT, json().replace("\"openingTime\":\"08:00\"", "\"openingTime\":\"$it\""))
        }
    }

    @Test
    fun `a marker on any result but done is refused`() {
        refused(
            ImportProblem.INVALID_CONTENT,
            json().replace(
                "\"result\":\"PARTIAL\",\"recordedAtMillis\":101,\"closedAction\":false",
                "\"result\":\"PARTIAL\",\"recordedAtMillis\":101,\"closedAction\":true",
            ),
        )
    }

    @Test
    fun `a marker while the action is not done is refused`() {
        refused(ImportProblem.INVALID_CONTENT, json(snapshot(statusA = ActionStatus.OPEN)))
        refused(ImportProblem.INVALID_CONTENT, json(snapshot(statusA = ActionStatus.DROPPED)))
    }

    @Test
    fun `two markers for one action are refused`() {
        val twice =
            listOf(
                PlanOutcome(itemA1, OutcomeResult.DONE, 100, closedAction = true),
                PlanOutcome(itemA2, OutcomeResult.DONE, 102, closedAction = true),
            )
        refused(ImportProblem.INVALID_CONTENT, json(snapshot(outcomes = twice)))
    }

    @Test
    fun `an outcome for a missing item is a broken reference, and for a left-out item or twice for one item it is invalid`() {
        val missing = PlanOutcome("5f0c2a52-8a4e-4c43-9a35-0f3c6ef1c0ff", OutcomeResult.SKIPPED, 1)
        refused(ImportProblem.BROKEN_REFERENCE, json(snapshot(outcomes = listOf(missing))))
        refused(ImportProblem.INVALID_CONTENT, json(snapshot(outcomes = listOf(PlanOutcome(itemLeftOut, OutcomeResult.SKIPPED, 1)))))
        val twice = listOf(PlanOutcome(itemB1, OutcomeResult.SKIPPED, 1), PlanOutcome(itemB1, OutcomeResult.BLOCKED, 2))
        refused(ImportProblem.INVALID_CONTENT, json(snapshot(outcomes = twice)))
    }

    @Test
    fun `the shared invariant check names each violation and accepts a consistent set`() {
        val s = snapshot()
        val statuses = s.actions.associate { it.value.id to it.value.status }
        assertNull(outcomeInvariantViolation(s.outcomes, s.planItems, statuses))
        assertNull(outcomeInvariantViolation(emptyList(), s.planItems, statuses))
        assertEquals(
            OutcomeViolation.MARKER_WHILE_NOT_DONE,
            outcomeInvariantViolation(s.outcomes, s.planItems, statuses + (actionA to ActionStatus.OPEN)),
        )
        assertEquals(
            OutcomeViolation.TWO_MARKERS_FOR_ACTION,
            outcomeInvariantViolation(s.outcomes.map { it.copy(closedAction = it.result == OutcomeResult.DONE) }, s.planItems, statuses),
        )
        assertEquals(
            OutcomeViolation.LEFT_OUT_ITEM,
            outcomeInvariantViolation(listOf(PlanOutcome(itemLeftOut, OutcomeResult.DONE, 1)), s.planItems, statuses),
        )
    }

    // Schema 4, ADR 0011

    private val commitmentId = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1d001"

    private fun dayLoop(): PlanningSnapshot =
        snapshot().copy(
            commitments =
                listOf(
                    Stamped(
                        FixedCommitment(
                            commitmentId,
                            "Seminar",
                            Instant.parse("2026-10-05T06:00:00Z"),
                            Instant.parse("2026-10-05T07:30:00Z"),
                            ZoneId.of("Asia/Kuala_Lumpur"),
                        ),
                        5,
                        6,
                    ),
                ),
            workingDays =
                listOf(
                    WorkingDayRecord(
                        ActiveDay(LocalDate.of(2026, 10, 3)),
                        Instant.ofEpochMilli(10),
                        Instant.ofEpochMilli(20),
                        180,
                        "Draft next",
                    ),
                    WorkingDayRecord(
                        ActiveDay(LocalDate.of(2026, 10, 4)),
                        Instant.ofEpochMilli(30),
                        continuedOn = ActiveDay(LocalDate.of(2026, 10, 5)),
                    ),
                    WorkingDayRecord(ActiveDay(LocalDate.of(2026, 10, 9)), focusMinutes = 90),
                ),
        )

    @Test
    fun `commitments, working days and project context round-trip in schema 4, counted`() {
        val goal = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1e001"
        val project = "5f0c2a52-8a4e-4c43-9a35-0f3c6ef1e002"
        val s =
            dayLoop().copy(
                goals = listOf(Stamped(Goal(goal, "G", "S"), 1, 1)),
                projects =
                    listOf(
                        Stamped(Project(project, "P", goal, ProjectState.ACTIVE, "Why \"it\" matters", null, "Evenings only", null), 1, 1),
                    ),
            )
        assertEquals(PlanningExportContent(info, s), decode(json(s)))
        assertEquals(1, s.counts.commitments)
        assertEquals(3, s.counts.workingDays)
    }

    @Test
    fun `a schema 3 export restores with no commitments, no working days and unknown project context`() {
        val content = decode(schemaThree(json()))
        assertEquals(3, content.info.schemaVersion)
        assertEquals(snapshot(), content.snapshot)
    }

    @Test
    fun `schema 4 refuses two open working days, a duplicate day and a commitment that ends before it starts`() {
        val twoOpen =
            dayLoop().copy(
                workingDays =
                    listOf(
                        WorkingDayRecord(ActiveDay(LocalDate.of(2026, 10, 3)), Instant.ofEpochMilli(1)),
                        WorkingDayRecord(ActiveDay(LocalDate.of(2026, 10, 4)), Instant.ofEpochMilli(2)),
                    ),
            )
        refused(ImportProblem.INVALID_CONTENT, json(twoOpen))
        val twice =
            dayLoop().copy(
                workingDays =
                    listOf(
                        WorkingDayRecord(ActiveDay(LocalDate.of(2026, 10, 3))),
                        WorkingDayRecord(ActiveDay(LocalDate.of(2026, 10, 3))),
                    ),
            )
        refused(ImportProblem.BROKEN_REFERENCE, json(twice))
        refused(ImportProblem.INVALID_CONTENT, json(dayLoop()).replace("2026-10-05T07:30:00Z", "2026-10-05T05:00:00Z"))
        refused(ImportProblem.INVALID_CONTENT, schemaThree(json()).replace("\"schemaVersion\":3", "\"schemaVersion\":4"))
    }
}
