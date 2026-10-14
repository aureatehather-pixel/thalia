package com.thalia.app.planning

import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Life's search, filters, groups and project cards come from the stored actions and projects only. */
class LifeModelTest {
    private val thesis = Project("p1", "Thesis demo", state = ProjectState.ACTIVE)
    private val build = Project("p2", "Demo build", state = ProjectState.ACTIVE)
    private val reading = Project("p3", "Reading list", state = ProjectState.PLANNED)
    private val projects = listOf(thesis, build, reading)

    private val script = Action("a1", "Demo script", "p1", Priority.MUST, 120)
    private val paper = Action("a2", "Read paper", "p1", Priority.IMPORTANT, 60)
    private val report = Action("a3", "Write report", "p2", Priority.NORMAL, 90)
    private val tidy = Action("a5", "Tidy notes", "p3", Priority.OPTIONAL, 20, status = ActionStatus.DROPPED)
    private val email = Action("a6", "Email Sam", null, Priority.NORMAL, 15)
    private val room = Action("a8", "Book room", null, Priority.NORMAL, 10, status = ActionStatus.DONE)
    private val all = listOf(script, paper, report, tidy, email, room)

    private fun groups(
        query: String = "",
        status: StatusFilter = StatusFilter.ALL,
        project: String = ProjectFilter.NONE,
    ) = groupActions(all, projects, query, status, project)

    @Test
    fun `actions are grouped under their projects in order, then standalone, and empty groups are left out`() {
        val g = groups()
        assertEquals(listOf("Thesis demo", "Demo build", "Reading list", "Standalone, no project"), g.map { it.name })
        assertEquals(listOf("Demo script", "Read paper"), g.first().actions.map { it.title })
        assertEquals(listOf("Email Sam", "Book room"), g.last().actions.map { it.title })
        assertTrue(groups(project = "p3", status = StatusFilter.OPEN).isEmpty(), "the only action there is dropped")
    }

    @Test
    fun `search matches a title or a project's title, ignoring case and outer spaces`() {
        assertEquals(
            listOf("Read paper", "Write report"),
            groups("pap", StatusFilter.ALL).flatMap { it.actions }.map { it.title } +
                groups("report").flatMap { it.actions }.map { it.title },
        )
        assertEquals(listOf("Demo script", "Read paper"), groups("  thesis ").flatMap { it.actions }.map { it.title })
        assertEquals(listOf("Email Sam", "Book room"), groups("standalone").flatMap { it.actions }.map { it.title })
        assertTrue(groups("zzz").isEmpty())
    }

    @Test
    fun `status filters admit only their own status`() {
        assertEquals(listOf("Book room"), groups(status = StatusFilter.DONE).flatMap { it.actions }.map { it.title })
        assertEquals(listOf("Tidy notes"), groups(status = StatusFilter.DROPPED).flatMap { it.actions }.map { it.title })
        assertEquals(4, groups(status = StatusFilter.OPEN).sumOf { it.actions.size })
        assertEquals(6, groups().sumOf { it.actions.size })
    }

    @Test
    fun `the project filter narrows to one project or to standalone`() {
        assertEquals(listOf("Write report"), groups(project = "p2").flatMap { it.actions }.map { it.title })
        assertEquals(listOf("Email Sam", "Book room"), groups(project = ProjectFilter.STANDALONE).flatMap { it.actions }.map { it.title })
    }

    @Test
    fun `an action whose project is no longer listed is shown as standalone, not lost`() {
        val orphan = Action("x", "Orphan", "gone", Priority.NORMAL, 30)
        val g = groupActions(listOf(orphan), projects, "", StatusFilter.ALL, ProjectFilter.NONE)
        assertEquals("Standalone, no project", g.single().name)
        assertEquals(
            "Orphan",
            g
                .single()
                .actions
                .single()
                .title,
        )
    }

    @Test
    fun `project cards count their own open and done actions`() {
        val cards = projectCards(projects, all, "")
        assertEquals(listOf("Thesis demo", "Demo build", "Reading list", "Standalone actions"), cards.map { it.title })
        assertEquals("2 open · 0 done", cards[0].summary)
        assertEquals("0 open · 0 done", cards[2].summary, "a dropped action is neither open nor done")
        assertEquals("1 open · not in a project", cards.last().summary)
        assertNull(cards.last().project)
        assertEquals(ProjectFilter.STANDALONE, cards.last().filterKey)
        assertEquals("p1", cards.first().filterKey)
    }

    @Test
    fun `a project card matches by its own title or by one of its actions`() {
        assertEquals(listOf("Thesis demo"), projectCards(projects, all, "paper").map { it.title })
        assertEquals(listOf("Reading list"), projectCards(projects, all, "reading").map { it.title })
        assertTrue(projectCards(projects, all, "zzz").isEmpty())
    }

    @Test
    fun `no standalone card when every action is in a project`() {
        assertTrue(projectCards(projects, listOf(script, report), "").none { it.project == null })
    }

    @Test
    fun `the estimate steps by five inside the store's range and recovers from text`() {
        assertEquals("35", stepEstimate("30", 5))
        assertEquals("25", stepEstimate("30", -5))
        assertEquals("5", stepEstimate("5", -5), "never below the store's minimum")
        assertEquals("720", stepEstimate("720", 5), "never above the store's maximum")
        assertEquals("35", stepEstimate("", 5), "unreadable text starts from the default")
    }

    @Test
    fun `unknown segment and status keys fall back to the defaults`() {
        assertEquals(LifeSegment.PROJECTS, LifeSegment.of(null))
        assertEquals(LifeSegment.PROJECTS, LifeSegment.of("nowhere"))
        assertEquals(LifeSegment.ACTIONS, LifeSegment.of("actions"))
        assertEquals(StatusFilter.ALL, StatusFilter.of("nowhere"))
    }
}
