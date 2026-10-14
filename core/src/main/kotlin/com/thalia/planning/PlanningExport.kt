package com.thalia.planning

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/** A stored row: its value and when it was created and last changed, in epoch milliseconds. */
data class Stamped<T>(
    val value: T,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** The direction statement and when it was last saved. */
data class DirectionRecord(
    val direction: Direction,
    val updatedAtMillis: Long,
)

/**
 * Every planning row, as one consistent read of the database. ADR 0009 decision 8.
 *
 * [settings] is null when the user never saved any, which is kept apart from saved defaults so a
 * restore gives back exactly the rows that were exported.
 */
data class PlanningSnapshot(
    val direction: DirectionRecord?,
    val settings: PlanningSettings?,
    val goals: List<Stamped<Goal>>,
    val projects: List<Stamped<Project>>,
    val actions: List<Stamped<Action>>,
    val plans: List<DayPlan> = emptyList(),
    val planItems: List<PlanItem> = emptyList(),
    val outcomes: List<PlanOutcome> = emptyList(),
    /** Plan id to the plan's opening time. A plan without one is absent. The call's status is never exported. */
    val openingTimes: Map<String, LocalTime> = emptyMap(),
    /** Schema 4, ADR 0011. Cancelled ones are kept, so a restore gives back what was stored. */
    val commitments: List<Stamped<FixedCommitment>> = emptyList(),
    val workingDays: List<WorkingDayRecord> = emptyList(),
    /** Schema 5, ADR 0012. Every state is kept, so a restore gives back the Inbox and what was filed from it. */
    val inboxItems: List<Stamped<InboxItem>> = emptyList(),
    /** Schema 6, ADR 0014. Every status is kept, so a restore never resurrects a resolved reminder or loses an unanswered one. */
    val reminders: List<Reminder> = emptyList(),
) {
    val counts: PlanningCounts
        get() =
            PlanningCounts(
                direction = if (direction == null) 0 else 1,
                settings = if (settings == null) 0 else 1,
                goals = goals.size,
                projects = projects.size,
                actions = actions.size,
                plans = plans.size,
                planItems = planItems.size,
                outcomes = outcomes.size,
                commitments = commitments.size,
                workingDays = workingDays.size,
                inboxItems = inboxItems.size,
                reminders = reminders.size,
            )

    val isEmpty: Boolean get() = counts.total == 0

    companion object {
        val EMPTY = PlanningSnapshot(null, null, emptyList(), emptyList(), emptyList())
    }
}

data class PlanningCounts(
    val direction: Int,
    val settings: Int,
    val goals: Int,
    val projects: Int,
    val actions: Int,
    val plans: Int = 0,
    val planItems: Int = 0,
    val outcomes: Int = 0,
    val commitments: Int = 0,
    val workingDays: Int = 0,
    val inboxItems: Int = 0,
    val reminders: Int = 0,
) {
    val total: Int
        get() =
            direction + settings + goals + projects + actions + plans + planItems + outcomes + commitments + workingDays + inboxItems +
                reminders
}

/** What an export says about itself, shown in the import preview. */
data class PlanningExportInfo(
    val schemaVersion: Int,
    val appVersion: String,
    val createdAt: Instant,
    val zone: ZoneId,
)

data class PlanningExportContent(
    val info: PlanningExportInfo,
    val snapshot: PlanningSnapshot,
)

/**
 * The export's payload: a JSON document of every planning row with its id, before encryption by
 * [PlanningArchive]. ADR 0009 decision 8.
 *
 * Decoding checks everything before a caller writes anything: the nesting depth and size before
 * parsing, then the exact fields, known enum values, valid dates and times, the data model's text
 * and range limits (through the model's own constructors), at most [MAX_ROWS] rows per entity,
 * unique UUID ids, every link between rows, and the counts.
 */
object PlanningExportFormat {
    const val MAX_PAYLOAD_BYTES = 16 * 1024 * 1024
    const val MAX_ROWS = 100_000
    const val MAX_DEPTH = 8
    private const val MAX_APP_VERSION_LENGTH = 64

    /** The oldest payload schema this Thalia still restores, by migrating it. See [decode]. */
    const val OLDEST_SCHEMA_VERSION = 1

    private val rootKeysV1 =
        setOf(
            "format",
            "schemaVersion",
            "appVersion",
            "createdAt",
            "zone",
            "counts",
            "direction",
            "settings",
            "goals",
            "projects",
            "actions",
        )
    private val countKeysV1 = setOf("direction", "settings", "goals", "projects", "actions")

    /** Schema 2 adds the day plans and their items. */
    private val rootKeysV2 = rootKeysV1 + setOf("plans", "planItems")
    private val countKeysV2 = countKeysV1 + setOf("plans", "planItems")

    /** Schema 3 adds the outcomes, and an opening time on each plan. Call status, detail and time are never exported. */
    private val rootKeysV3 = rootKeysV2 + "outcomes"
    private val countKeysV3 = countKeysV2 + "outcomes"

    /** Schema 4 adds fixed commitments, working days and four context fields on each project. ADR 0011. */
    private val rootKeysV4 = rootKeysV3 + setOf("commitments", "workingDays")
    private val countKeysV4 = countKeysV3 + setOf("commitments", "workingDays")

    /** Schema 5 adds the Inbox. ADR 0012. */
    private val rootKeysV5 = rootKeysV4 + "inboxItems"
    private val countKeysV5 = countKeysV4 + "inboxItems"

    /** Schema 6 adds the reminders. ADR 0014. */
    private val rootKeys = rootKeysV5 + "reminders"
    private val countKeys = countKeysV5 + "reminders"
    private val reminderKeys =
        setOf(
            "id",
            "targetType",
            "targetId",
            "label",
            "dueAtMillis",
            "zone",
            "status",
            "reason",
            "postedAtMillis",
            "resolvedAtMillis",
            "createdAtMillis",
        )
    private val inboxKeys = setOf("id", "text", "state", "body", "actionId", "resolvedAtMillis", "createdAtMillis", "updatedAtMillis")
    private val commitmentKeys = setOf("id", "title", "start", "end", "zone", "status", "createdAtMillis", "updatedAtMillis")
    private val workingDayKeys = setOf("day", "openedAtMillis", "closedAtMillis", "focusMinutes", "closingNote", "continuedOn")
    private val outcomeKeys = setOf("planItemId", "result", "recordedAtMillis", "closedAction")
    private val planKeysV2 =
        setOf(
            "id",
            "activeDay",
            "revision",
            "zone",
            "rollover",
            "windowStart",
            "windowEnd",
            "freeMinutes",
            "targetMinutes",
            "overloadMinutes",
            "approvedAtMillis",
        )
    private val planKeys = planKeysV2 + "openingTime"
    private val planItemKeys =
        setOf(
            "id",
            "planId",
            "actionId",
            "kind",
            "start",
            "minutes",
            "position",
            "placement",
            "reason",
            "title",
            "priority",
            "projectId",
            "projectTitle",
            "projectState",
        )
    private val directionKeys = setOf("text", "updatedAtMillis")
    private val settingsKeys = setOf("rollover", "windowStart", "windowEnd")
    private val goalKeys = setOf("id", "title", "successCondition", "horizon", "status", "createdAtMillis", "updatedAtMillis")
    private val projectKeysV3 = setOf("id", "title", "goalId", "state", "createdAtMillis", "updatedAtMillis")
    private val projectKeys = projectKeysV3 + setOf("purpose", "doneWhen", "constraints", "blocker")
    private val actionKeys =
        setOf("id", "title", "projectId", "priority", "estimateMinutes", "dueDate", "status", "createdAtMillis", "updatedAtMillis")

    fun encode(content: PlanningExportContent): ByteArray {
        val (info, snapshot) = content
        require(info.schemaVersion >= 4) { "the payload written is schema 4 or later, got ${info.schemaVersion}" }
        val counts = snapshot.counts
        return buildJsonObject {
            put("format", PlanningArchive.FORMAT_VERSION)
            put("schemaVersion", info.schemaVersion)
            put("appVersion", info.appVersion)
            put("createdAt", info.createdAt.toString())
            put("zone", info.zone.id)
            putJsonObject("counts") {
                put("direction", counts.direction)
                put("settings", counts.settings)
                put("goals", counts.goals)
                put("projects", counts.projects)
                put("actions", counts.actions)
                put("plans", counts.plans)
                put("planItems", counts.planItems)
                put("outcomes", counts.outcomes)
                put("commitments", counts.commitments)
                put("workingDays", counts.workingDays)
                if (info.schemaVersion >= 5) put("inboxItems", counts.inboxItems)
                if (info.schemaVersion >= 6) put("reminders", counts.reminders)
            }
            putNullable("direction", snapshot.direction) {
                put("text", it.direction.text)
                put("updatedAtMillis", it.updatedAtMillis)
            }
            putNullable("settings", snapshot.settings) {
                put("rollover", it.rollover.toString())
                put("windowStart", it.window?.start?.toString())
                put("windowEnd", it.window?.end?.toString())
            }
            putJsonArray("goals") {
                snapshot.goals.forEach { (goal, created, updated) ->
                    addJsonObject {
                        put("id", goal.id)
                        put("title", goal.title)
                        put("successCondition", goal.successCondition)
                        put("horizon", goal.horizon?.toString())
                        put("status", goal.status.name)
                        put("createdAtMillis", created)
                        put("updatedAtMillis", updated)
                    }
                }
            }
            putJsonArray("projects") {
                snapshot.projects.forEach { (project, created, updated) ->
                    addJsonObject {
                        put("id", project.id)
                        put("title", project.title)
                        put("goalId", project.goalId)
                        put("state", project.state.name)
                        put("purpose", project.purpose)
                        put("doneWhen", project.doneWhen)
                        put("constraints", project.constraints)
                        put("blocker", project.blocker)
                        put("createdAtMillis", created)
                        put("updatedAtMillis", updated)
                    }
                }
            }
            putJsonArray("actions") {
                snapshot.actions.forEach { (action, created, updated) ->
                    addJsonObject {
                        put("id", action.id)
                        put("title", action.title)
                        put("projectId", action.projectId)
                        put("priority", action.priority.name)
                        put("estimateMinutes", action.estimateMinutes)
                        put("dueDate", action.dueDate?.toString())
                        put("status", action.status.name)
                        put("createdAtMillis", created)
                        put("updatedAtMillis", updated)
                    }
                }
            }
            putJsonArray("plans") {
                snapshot.plans.forEach { plan ->
                    addJsonObject {
                        put("id", plan.id)
                        put("activeDay", plan.activeDay.toString())
                        put("revision", plan.revision)
                        put("zone", plan.zone.id)
                        put("rollover", plan.rollover.toString())
                        put("windowStart", plan.window.start.toString())
                        put("windowEnd", plan.window.end.toString())
                        put("freeMinutes", plan.freeMinutes)
                        put("targetMinutes", plan.targetMinutes)
                        put("overloadMinutes", plan.overloadMinutes)
                        put("approvedAtMillis", plan.approvedAtMillis)
                        put("openingTime", snapshot.openingTimes[plan.id]?.toString())
                    }
                }
            }
            putJsonArray("planItems") {
                snapshot.planItems.forEach { item ->
                    addJsonObject {
                        put("id", item.id)
                        put("planId", item.planId)
                        put("actionId", item.actionId)
                        put("kind", item.kind.name)
                        put("start", item.start?.toString())
                        put("minutes", item.minutes)
                        put("position", item.position)
                        put("placement", item.placement?.name)
                        put("reason", item.reason?.name)
                        put("title", item.title)
                        put("priority", item.priority.name)
                        put("projectId", item.projectId)
                        put("projectTitle", item.projectTitle)
                        put("projectState", item.projectState?.name)
                    }
                }
            }
            putJsonArray("outcomes") {
                snapshot.outcomes.forEach { outcome ->
                    addJsonObject {
                        put("planItemId", outcome.planItemId)
                        put("result", outcome.result.name)
                        put("recordedAtMillis", outcome.recordedAtMillis)
                        put("closedAction", outcome.closedAction)
                    }
                }
            }
            putJsonArray("commitments") {
                snapshot.commitments.forEach { (commitment, created, updated) ->
                    addJsonObject {
                        put("id", commitment.id)
                        put("title", commitment.title)
                        put("start", commitment.start.toString())
                        put("end", commitment.end.toString())
                        put("zone", commitment.zone.id)
                        put("status", commitment.status.name)
                        put("createdAtMillis", created)
                        put("updatedAtMillis", updated)
                    }
                }
            }
            putJsonArray("workingDays") {
                snapshot.workingDays.forEach { row ->
                    addJsonObject {
                        put("day", row.day.toString())
                        put("openedAtMillis", row.openedAt?.toEpochMilli())
                        put("closedAtMillis", row.closedAt?.toEpochMilli())
                        put("focusMinutes", row.focusMinutes)
                        put("closingNote", row.closingNote)
                        put("continuedOn", row.continuedOn?.toString())
                    }
                }
            }
            if (info.schemaVersion >= 5) {
                putJsonArray("inboxItems") {
                    snapshot.inboxItems.forEach { (item, created, updated) ->
                        addJsonObject {
                            put("id", item.id)
                            put("text", item.text)
                            put("state", item.state.name)
                            put("body", item.body)
                            put("actionId", item.actionId)
                            put("resolvedAtMillis", item.resolvedAtMillis)
                            put("createdAtMillis", created)
                            put("updatedAtMillis", updated)
                        }
                    }
                }
            }
            if (info.schemaVersion >= 6) {
                putJsonArray("reminders") {
                    snapshot.reminders.forEach { r ->
                        addJsonObject {
                            put("id", r.id)
                            put("targetType", r.targetType.name)
                            put("targetId", r.targetId)
                            put("label", r.label)
                            put("dueAtMillis", r.dueAtMillis)
                            put("zone", r.zone)
                            put("status", r.status.name)
                            put("reason", r.reason?.name)
                            put("postedAtMillis", r.postedAtMillis)
                            put("resolvedAtMillis", r.resolvedAtMillis)
                            put("createdAtMillis", r.createdAtMillis)
                        }
                    }
                }
            }
        }.toString().toByteArray(Charsets.UTF_8)
    }

    /**
     * The validated content of a decrypted payload. [currentSchemaVersion] is the database schema
     * this Thalia has; an export from a newer one is refused.
     */
    fun decode(
        payload: ByteArray,
        currentSchemaVersion: Int,
    ): PlanningExportContent {
        if (payload.size > MAX_PAYLOAD_BYTES) refuse(ImportProblem.INVALID_CONTENT, "payload of ${payload.size} bytes")
        val parsed =
            try {
                val text = String(payload, Charsets.UTF_8)
                JsonLimits.requireDepth(text, MAX_DEPTH)
                Json.parseToJsonElement(text)
            } catch (e: IllegalArgumentException) {
                invalid("not JSON within the depth limit")
            }
        val envelope = parsed as? JsonObject ?: invalid("payload is not an object")
        val format = envelope.int("format")
        if (format != PlanningArchive.FORMAT_VERSION) invalid("payload format $format")
        // The payload's own version, kept apart from the encryption envelope's [PlanningArchive.FORMAT_VERSION],
        // which changes only if the envelope's structure does. A schema older than this Thalia's, down to
        // OLDEST_SCHEMA_VERSION, is migrated in memory below; a newer one is refused.
        val schemaVersion = envelope.int("schemaVersion")
        if (schemaVersion > currentSchemaVersion) refuse(ImportProblem.NEWER_VERSION, "schema $schemaVersion")
        if (schemaVersion < OLDEST_SCHEMA_VERSION) invalid("schema $schemaVersion")
        val hasPlans = schemaVersion >= 2
        val hasOutcomes = schemaVersion >= 3
        val hasDayLoop = schemaVersion >= 4
        val hasInbox = schemaVersion >= 5
        val hasReminders = schemaVersion >= 6
        val root =
            parsed.asObject(
                "payload",
                if (hasReminders) {
                    rootKeys
                } else if (hasInbox) {
                    rootKeysV5
                } else if (hasDayLoop) {
                    rootKeysV4
                } else if (hasOutcomes) {
                    rootKeysV3
                } else if (hasPlans) {
                    rootKeysV2
                } else {
                    rootKeysV1
                },
            )
        val appVersion = root.string("appVersion")
        if (appVersion.isBlank() || appVersion.length > MAX_APP_VERSION_LENGTH) invalid("app version")
        val info =
            PlanningExportInfo(
                schemaVersion = schemaVersion,
                appVersion = appVersion,
                createdAt = parse("createdAt") { Instant.parse(root.string("createdAt")) },
                zone = parse("zone") { ZoneId.of(root.string("zone")) },
            )

        val goals = root.rows("goals", goalKeys) { it.goal() }
        val projects = root.rows("projects", if (hasDayLoop) projectKeys else projectKeysV3) { it.project(hasDayLoop) }
        val actions = root.rows("actions", actionKeys) { it.action() }
        val planRows = if (hasPlans) root.rows("plans", if (hasOutcomes) planKeys else planKeysV2) { it.plan(hasOutcomes) } else emptyList()
        val plans = planRows.map { it.first }
        val openingTimes = planRows.mapNotNull { (plan, time) -> time?.let { plan.id to it } }.toMap()
        val planItems = if (hasPlans) root.rows("planItems", planItemKeys) { it.planItem() } else emptyList()
        val outcomes = if (hasOutcomes) root.rows("outcomes", outcomeKeys) { it.outcome() } else emptyList()
        val commitments = if (hasDayLoop) root.rows("commitments", commitmentKeys) { it.commitment() } else emptyList()
        val workingDays = if (hasDayLoop) root.rows("workingDays", workingDayKeys) { it.workingDay() } else emptyList()
        val inboxItems = if (hasInbox) root.rows("inboxItems", inboxKeys) { it.inboxItem() } else emptyList()
        val reminders = if (hasReminders) root.rows("reminders", reminderKeys) { it.reminder() } else emptyList()
        val snapshot =
            PlanningSnapshot(
                direction = root.nullableObject("direction", directionKeys)?.let { it.direction() },
                settings = root.nullableObject("settings", settingsKeys)?.let { it.settings() },
                goals = goals,
                projects = projects,
                actions = actions,
                plans = plans,
                planItems = planItems,
                outcomes = outcomes,
                openingTimes = openingTimes,
                commitments = commitments,
                workingDays = workingDays,
                inboxItems = inboxItems,
                reminders = reminders,
            )

        val counts =
            root.objectOf(
                "counts",
                if (hasReminders) {
                    countKeys
                } else if (hasInbox) {
                    countKeysV5
                } else if (hasDayLoop) {
                    countKeysV4
                } else if (hasOutcomes) {
                    countKeysV3
                } else if (hasPlans) {
                    countKeysV2
                } else {
                    countKeysV1
                },
            )
        val declared =
            PlanningCounts(
                direction = counts.int("direction"),
                settings = counts.int("settings"),
                goals = counts.int("goals"),
                projects = counts.int("projects"),
                actions = counts.int("actions"),
                plans = if (hasPlans) counts.int("plans") else 0,
                planItems = if (hasPlans) counts.int("planItems") else 0,
                outcomes = if (hasOutcomes) counts.int("outcomes") else 0,
                commitments = if (hasDayLoop) counts.int("commitments") else 0,
                workingDays = if (hasDayLoop) counts.int("workingDays") else 0,
                inboxItems = if (hasInbox) counts.int("inboxItems") else 0,
                reminders = if (hasReminders) counts.int("reminders") else 0,
            )
        if (declared != snapshot.counts) invalid("counts do not match the rows")

        checkReferences(snapshot)
        checkPlanReferences(snapshot)
        checkOutcomes(snapshot)
        checkDayLoop(snapshot)
        checkInbox(snapshot)
        checkReminders(snapshot)
        return PlanningExportContent(info, snapshot)
    }

    /** Unique reminder ids and no more than [ReminderRules.MAX_PENDING] waiting. Targets are not checked: a reminder outlives its target. ADR 0014. */
    private fun checkReminders(snapshot: PlanningSnapshot) {
        val ids = snapshot.reminders.map { it.id }
        if (ids.toSet().size != ids.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate reminder id")
        if (snapshot.reminders.count { it.status == ReminderStatus.SCHEDULED } > ReminderRules.MAX_PENDING) {
            invalid("more than ${ReminderRules.MAX_PENDING} waiting reminders")
        }
    }

    private fun JsonObject.reminder(): Reminder =
        parse("reminder") {
            val zone = string("zone")
            ZoneId.of(zone)
            Reminder(
                id = uuid("id"),
                targetType = ReminderTargetType.valueOf(string("targetType")),
                targetId = nullableString("targetId")?.let { uuidValue(it) },
                label = string("label"),
                dueAtMillis = long("dueAtMillis"),
                zone = zone,
                status = ReminderStatus.valueOf(string("status")),
                reason = nullableString("reason")?.let { ReminderReason.valueOf(it) },
                postedAtMillis = nullableLong("postedAtMillis"),
                resolvedAtMillis = nullableLong("resolvedAtMillis"),
                createdAtMillis = long("createdAtMillis"),
            )
        }

    /** Unique item ids, and every converted item points at one stored action that no other item claims. ADR 0012. */
    private fun checkInbox(snapshot: PlanningSnapshot) {
        val ids = snapshot.inboxItems.map { it.value.id }
        if (ids.toSet().size != ids.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate inbox item id")
        val actionIds = snapshot.actions.map { it.value.id }.toSet()
        val claimed = snapshot.inboxItems.mapNotNull { it.value.actionId }
        if (claimed.any { it !in actionIds }) refuse(ImportProblem.BROKEN_REFERENCE, "inbox item to a missing action")
        if (claimed.toSet().size != claimed.size) refuse(ImportProblem.BROKEN_REFERENCE, "two inbox items became one action")
    }

    private fun JsonObject.inboxItem(): Stamped<InboxItem> =
        stamped(
            parse("inbox item") {
                InboxItem(
                    id = uuid("id"),
                    text = string("text"),
                    state = InboxState.valueOf(string("state")),
                    body = nullableString("body"),
                    actionId = nullableString("actionId")?.let { uuidValue(it) },
                    resolvedAtMillis = nullableLong("resolvedAtMillis"),
                )
            },
        )

    /** Unique commitment ids and working-day labels, and at most one working day open. */
    private fun checkDayLoop(snapshot: PlanningSnapshot) {
        val ids = snapshot.commitments.map { it.value.id }
        if (ids.toSet().size != ids.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate commitment id")
        val days = snapshot.workingDays.map { it.day }
        if (days.toSet().size != days.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate working day")
        if (snapshot.workingDays.count { it.isOpen } > 1) invalid("more than one working day open")
    }

    private fun JsonObject.commitment(): Stamped<FixedCommitment> =
        stamped(
            parse("commitment") {
                FixedCommitment(
                    id = uuid("id"),
                    title = string("title"),
                    start = Instant.parse(string("start")),
                    end = Instant.parse(string("end")),
                    zone = ZoneId.of(string("zone")),
                    status = CommitmentStatus.valueOf(string("status")),
                )
            },
        )

    private fun JsonObject.nullableLong(name: String): Long? = if (primitive(name) == JsonNull) null else long(name)

    private fun JsonObject.workingDay(): WorkingDayRecord =
        parse("working day") {
            WorkingDayRecord(
                day = ActiveDay(LocalDate.parse(string("day"))),
                openedAt = nullableLong("openedAtMillis")?.let(Instant::ofEpochMilli),
                closedAt = nullableLong("closedAtMillis")?.let(Instant::ofEpochMilli),
                focusMinutes = nullableLong("focusMinutes")?.let { it.takeIf { m -> m in 0..720 }?.toInt() ?: invalid("focus minutes") },
                closingNote = nullableString("closingNote"),
                continuedOn = nullableString("continuedOn")?.let { ActiveDay(LocalDate.parse(it)) },
            )
        }

    /** Ids are unique UUIDs, projects point at exported goals and actions at exported projects. */
    private fun checkReferences(snapshot: PlanningSnapshot) {
        val goalIds = snapshot.goals.map { it.value.id }
        val projectIds = snapshot.projects.map { it.value.id }
        val actionIds = snapshot.actions.map { it.value.id }
        if (goalIds.toSet().size != goalIds.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate goal id")
        if (projectIds.toSet().size != projectIds.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate project id")
        if (actionIds.toSet().size != actionIds.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate action id")
        val goals = goalIds.toSet()
        val projects = projectIds.toSet()
        snapshot.projects.forEach { (project) ->
            if (project.goalId != null && project.goalId !in goals) refuse(ImportProblem.BROKEN_REFERENCE, "project to a missing goal")
        }
        snapshot.actions.forEach { (action) ->
            if (action.projectId != null && action.projectId !in projects) {
                refuse(ImportProblem.BROKEN_REFERENCE, "action to a missing project")
            }
        }
    }

    private fun checkPlanReferences(snapshot: PlanningSnapshot) {
        val planIds = snapshot.plans.map { it.id }
        val itemIds = snapshot.planItems.map { it.id }
        if (planIds.toSet().size != planIds.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate plan id")
        if (itemIds.toSet().size != itemIds.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate plan item id")
        val revisions = snapshot.plans.map { it.activeDay to it.revision }
        if (revisions.toSet().size != revisions.size) refuse(ImportProblem.BROKEN_REFERENCE, "duplicate plan revision")
        // Revisions of a day are 1..n with no gap: history is only ever added to, and the latest is in force.
        snapshot.plans.groupBy { it.activeDay }.values.forEach { revisionsOfDay ->
            if (revisionsOfDay.map { it.revision }.sorted() != (1..revisionsOfDay.size).toList()) {
                refuse(ImportProblem.BROKEN_REFERENCE, "revisions of a day are not 1 to n")
            }
        }
        val plans = planIds.toSet()
        val actions = snapshot.actions.map { it.value.id }.toSet()
        snapshot.planItems.forEach { item ->
            if (item.planId !in plans) refuse(ImportProblem.BROKEN_REFERENCE, "plan item to a missing plan")
            if (item.actionId !in actions) refuse(ImportProblem.BROKEN_REFERENCE, "plan item to a missing action")
        }
        // An item's project is a snapshot and may name a project since renamed, but never one that never existed.
        val projects = snapshot.projects.map { it.value.id }.toSet()
        snapshot.planItems.forEach { item ->
            if (item.projectId != null &&
                item.projectId !in projects
            ) {
                refuse(ImportProblem.BROKEN_REFERENCE, "plan item to a missing project")
            }
        }
        if (snapshot.planItems.groupBy { it.planId to it.actionId }.any { it.value.size > 1 }) {
            refuse(ImportProblem.BROKEN_REFERENCE, "an action twice in one plan")
        }
    }

    /** The shared outcome invariants, over the imported rows. */
    private fun checkOutcomes(snapshot: PlanningSnapshot) {
        val violation =
            outcomeInvariantViolation(snapshot.outcomes, snapshot.planItems, snapshot.actions.associate { it.value.id to it.value.status })
                ?: return
        refuse(
            if (violation == OutcomeViolation.MISSING_ITEM) ImportProblem.BROKEN_REFERENCE else ImportProblem.INVALID_CONTENT,
            "outcomes: $violation",
        )
    }

    /** The plan and, in schema 3, its opening time as the canonical `HH:mm`. */
    private fun JsonObject.plan(hasOpeningTime: Boolean): Pair<DayPlan, LocalTime?> =
        parse("plan") {
            val opening =
                if (hasOpeningTime) {
                    nullableString("openingTime")?.let { text ->
                        LocalTime
                            .parse(
                                text,
                            ).also { require(it.second == 0 && it.nano == 0 && it.toString() == text) { "an opening time is HH:mm" } }
                    }
                } else {
                    null
                }
            DayPlan(
                id = uuid("id"),
                activeDay = ActiveDay(LocalDate.parse(string("activeDay"))),
                revision = int("revision"),
                zone = ZoneId.of(string("zone")),
                rollover = LocalTime.parse(string("rollover")),
                window = PlanningWindow(LocalTime.parse(string("windowStart")), LocalTime.parse(string("windowEnd"))),
                freeMinutes = int("freeMinutes"),
                targetMinutes = int("targetMinutes"),
                overloadMinutes = int("overloadMinutes"),
                approvedAtMillis = long("approvedAtMillis"),
            ) to opening
        }

    private fun JsonObject.outcome(): PlanOutcome =
        parse("outcome") {
            PlanOutcome(
                planItemId = uuid("planItemId"),
                result = OutcomeResult.valueOf(string("result")),
                recordedAtMillis = long("recordedAtMillis"),
                closedAction = boolean("closedAction"),
            )
        }

    private fun JsonObject.planItem(): PlanItem =
        parse("plan item") {
            PlanItem(
                id = uuid("id"),
                planId = uuid("planId"),
                actionId = uuid("actionId"),
                kind = ItemKind.valueOf(string("kind")),
                start = nullableString("start")?.let(LocalTime::parse),
                minutes = int("minutes"),
                position = int("position"),
                placement = nullableString("placement")?.let { Placement.valueOf(it) },
                reason = nullableString("reason")?.let { LeftOutReason.valueOf(it) },
                title = string("title"),
                priority = Priority.valueOf(string("priority")),
                projectId = nullableString("projectId")?.let { uuidValue(it) },
                projectTitle = nullableString("projectTitle"),
                projectState = nullableString("projectState")?.let { ProjectState.valueOf(it) },
            )
        }

    private fun JsonObject.direction() = DirectionRecord(parse("direction") { Direction(string("text")) }, long("updatedAtMillis"))

    private fun JsonObject.settings(): PlanningSettings {
        val start = nullableString("windowStart")
        val end = nullableString("windowEnd")
        if ((start == null) != (end == null)) invalid("a window needs both ends")
        return parse("settings") {
            PlanningSettings(
                rollover = LocalTime.parse(string("rollover")),
                window = if (start != null && end != null) PlanningWindow(LocalTime.parse(start), LocalTime.parse(end)) else null,
            )
        }
    }

    private fun JsonObject.goal(): Stamped<Goal> =
        stamped(
            parse("goal") {
                Goal(
                    id = uuid("id"),
                    title = string("title"),
                    successCondition = string("successCondition"),
                    horizon = nullableString("horizon")?.let(LocalDate::parse),
                    status = GoalStatus.valueOf(string("status")),
                )
            },
        )

    private fun JsonObject.project(hasContext: Boolean): Stamped<Project> =
        stamped(
            parse("project") {
                Project(
                    id = uuid("id"),
                    title = string("title"),
                    goalId = nullableString("goalId")?.let { uuidValue(it) },
                    state = ProjectState.valueOf(string("state")),
                    purpose = if (hasContext) nullableString("purpose") else null,
                    doneWhen = if (hasContext) nullableString("doneWhen") else null,
                    constraints = if (hasContext) nullableString("constraints") else null,
                    blocker = if (hasContext) nullableString("blocker") else null,
                )
            },
        )

    private fun JsonObject.action(): Stamped<Action> =
        stamped(
            parse("action") {
                Action(
                    id = uuid("id"),
                    title = string("title"),
                    projectId = nullableString("projectId")?.let { uuidValue(it) },
                    priority = Priority.valueOf(string("priority")),
                    estimateMinutes = int("estimateMinutes"),
                    dueDate = nullableString("dueDate")?.let(LocalDate::parse),
                    status = ActionStatus.valueOf(string("status")),
                )
            },
        )

    private fun <T> JsonObject.stamped(value: T) = Stamped(value, long("createdAtMillis"), long("updatedAtMillis"))

    private fun <T> JsonObject.rows(
        name: String,
        keys: Set<String>,
        read: (JsonObject) -> T,
    ): List<T> {
        val array = this[name] as? JsonArray ?: invalid("$name is not a list")
        if (array.size > MAX_ROWS) invalid("more than $MAX_ROWS $name")
        return array.map { read(it.asObject(name, keys)) }
    }

    /**
     * Builds a value, turning the model's own refusals, unknown enum names and unparseable dates, times
     * or zones into [ImportProblem.INVALID_CONTENT]. A refusal from inside, such as a missing field,
     * passes through.
     */
    private inline fun <T> parse(
        what: String,
        make: () -> T,
    ): T =
        try {
            make()
        } catch (e: ImportRefusedException) {
            throw e
        } catch (e: IllegalArgumentException) {
            invalid("$what is not valid")
        } catch (e: DateTimeException) {
            invalid("$what has an invalid date, time or zone")
        }

    private fun JsonElement.asObject(
        what: String,
        keys: Set<String>,
    ): JsonObject {
        val obj = this as? JsonObject ?: invalid("$what is not an object")
        if (obj.keys != keys) invalid("$what has fields ${obj.keys.sorted()}")
        return obj
    }

    private fun JsonObject.objectOf(
        name: String,
        keys: Set<String>,
    ): JsonObject = (this[name] ?: invalid("no $name")).asObject(name, keys)

    private fun JsonObject.nullableObject(
        name: String,
        keys: Set<String>,
    ): JsonObject? =
        when (val value = this[name]) {
            JsonNull -> null
            null -> invalid("no $name")
            else -> value.asObject(name, keys)
        }

    private fun JsonObject.primitive(name: String): JsonPrimitive =
        this[name] as? JsonPrimitive ?: invalid("$name is missing or not a value")

    private fun JsonObject.string(name: String): String = primitive(name).takeIf { it.isString }?.content ?: invalid("$name is not text")

    private fun JsonObject.nullableString(name: String): String? = if (primitive(name) == JsonNull) null else string(name)

    private fun JsonObject.long(name: String): Long =
        primitive(name).takeUnless { it.isString || it == JsonNull }?.content?.toLongOrNull() ?: invalid("$name is not a whole number")

    private fun JsonObject.int(name: String): Int =
        long(name).takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt() ?: invalid("$name is out of range")

    private fun JsonObject.boolean(name: String): Boolean =
        primitive(name).takeUnless { it.isString }?.content?.toBooleanStrictOrNull() ?: invalid("$name is not true or false")

    private fun JsonObject.uuid(name: String): String = uuidValue(string(name))

    /** Ids are the canonical lower-case form of a UUID, as the app makes them. */
    private fun uuidValue(text: String): String =
        text.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) } ?: invalid("an id is not a UUID")

    private fun <T : Any> JsonObjectBuilder.putNullable(
        name: String,
        value: T?,
        fill: JsonObjectBuilder.(T) -> Unit,
    ) {
        if (value == null) put(name, JsonNull) else putJsonObject(name) { fill(value) }
    }

    private fun invalid(detail: String): Nothing = refuse(ImportProblem.INVALID_CONTENT, detail)
}
