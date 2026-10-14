package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.Project

/** Which actions the list shows. All includes every status. */
internal enum class StatusFilter(
    val key: String,
    val label: String,
) {
    ALL("all", "All"),
    OPEN("open", "Open"),
    DONE("done", "Done"),
    DROPPED("dropped", "Dropped"),
    ;

    fun admits(status: ActionStatus): Boolean =
        when (this) {
            ALL -> true
            OPEN -> status == ActionStatus.OPEN
            DONE -> status == ActionStatus.DONE
            DROPPED -> status == ActionStatus.DROPPED
        }

    companion object {
        fun of(key: String?): StatusFilter = entries.firstOrNull { it.key == key } ?: ALL
    }
}

/** The project filter as one saveable value: none, standalone only, or one project's id. */
internal object ProjectFilter {
    const val NONE = ""
    const val STANDALONE = "~standalone"
}

/** Actions under one heading: a project, or those with no project. */
internal data class ActionGroup(
    val key: String,
    val name: String,
    val actions: List<Action>,
)

/** One project card: its counts come from its own actions. A null [project] is the standalone card. */
internal data class ProjectCard(
    val project: Project?,
    val title: String,
    val stateText: String,
    val open: Int,
    val done: Int,
) {
    val filterKey: String get() = project?.id ?: ProjectFilter.STANDALONE
    val summary: String
        get() = if (project == null) "$open open · not in a project" else "$open open · $done done"
}

private fun String.matches(query: String) = query.isEmpty() || lowercase().contains(query)

/** True when [query], trimmed and ignoring case, is in the action's title or its project's title. */
internal fun actionMatches(
    action: Action,
    projectTitle: String?,
    query: String,
): Boolean {
    val q = query.trim().lowercase()
    return q.isEmpty() || action.title.matches(q) || (projectTitle ?: "standalone").matches(q)
}

/** The actions to list under their headings, in the stores' order: each project in turn, then standalone. Empty groups are left out. */
internal fun groupActions(
    actions: List<Action>,
    projects: List<Project>,
    query: String,
    status: StatusFilter,
    projectFilter: String,
): List<ActionGroup> {
    val titleOf = projects.associate { it.id to it.title }
    val shown =
        actions.filter { a ->
            val title = a.projectId?.let { titleOf[it] }
            actionMatches(a, title, query) &&
                status.admits(a.status) &&
                when (projectFilter) {
                    ProjectFilter.NONE -> true
                    ProjectFilter.STANDALONE -> a.projectId == null
                    else -> a.projectId == projectFilter
                }
        }
    val known = projects.map { p -> ActionGroup(p.id, p.title, shown.filter { it.projectId == p.id }) }
    // An action whose project is gone from the list is still shown, under standalone, rather than vanishing.
    val loose = shown.filter { a -> a.projectId == null || a.projectId !in titleOf }
    return (known + ActionGroup(ProjectFilter.STANDALONE, "Standalone, no project", loose)).filter { it.actions.isNotEmpty() }
}

/** The project cards that match [query]: a project by its title or any of its actions', and the standalone card by its words. */
internal fun projectCards(
    projects: List<Project>,
    actions: List<Action>,
    query: String,
): List<ProjectCard> {
    val q = query.trim().lowercase()
    val cards =
        projects
            .filter { p -> q.isEmpty() || p.title.matches(q) || actions.any { it.projectId == p.id && it.title.matches(q) } }
            .map { p ->
                val mine = actions.filter { it.projectId == p.id }
                ProjectCard(
                    p,
                    p.title,
                    p.state.name
                        .lowercase()
                        .replaceFirstChar { it.uppercase() },
                    mine.count { it.status == ActionStatus.OPEN },
                    mine.count { it.status == ActionStatus.DONE },
                )
            }
    val loose = actions.filter { it.projectId == null || projects.none { p -> p.id == it.projectId } }
    val standalone =
        if (loose.isNotEmpty() && (q.isEmpty() || "standalone".contains(q) || loose.any { it.title.matches(q) })) {
            listOf(
                ProjectCard(
                    null,
                    "Standalone actions",
                    "No project",
                    loose.count { it.status == ActionStatus.OPEN },
                    loose.count {
                        it.status ==
                            ActionStatus.DONE
                    },
                ),
            )
        } else {
            emptyList()
        }
    return cards + standalone
}
