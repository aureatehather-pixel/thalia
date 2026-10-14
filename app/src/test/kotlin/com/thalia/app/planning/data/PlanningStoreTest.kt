package com.thalia.app.planning.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.test.core.app.ApplicationProvider
import com.thalia.planning.Action
import com.thalia.planning.ActionStatus
import com.thalia.planning.Direction
import com.thalia.planning.Goal
import com.thalia.planning.GoalStatus
import com.thalia.planning.PlanningSettings
import com.thalia.planning.PlanningWindow
import com.thalia.planning.Priority
import com.thalia.planning.Project
import com.thalia.planning.ProjectState
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The links and persistence of slice 1A, on Robolectric's plain SQLite. See [FakeSealer]. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class PlanningStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "planning-store-test.db"
    private var database: PlanningDatabase? = null
    private var now = 1_000L

    private fun store(): PlanningStore {
        database?.close()
        val db = plainPlanningDatabase(context, context.getDatabasePath(name))
        database = db
        return PlanningStore(db) { now++ }
    }

    @After
    fun cleanUp() {
        database?.close()
        context.deleteDatabase(name)
    }

    @Test
    fun `the direction is one statement, replaced when edited`() =
        runTest {
            val store = store()
            assertNull(store.direction())

            store.saveDirection(Direction("Build calm, useful things"))
            store.saveDirection(Direction("Build calm, useful things, and rest"))

            assertEquals(Direction("Build calm, useful things, and rest"), store.direction())
        }

    @Test
    fun `a goal is created, then edited in place`() =
        runTest {
            val store = store()
            val goal = Goal("g1", "Finish the book", "A full draft exists", LocalDate.of(2027, 3, 1))

            assertNull(store.saveGoal(goal))
            assertNull(store.saveGoal(goal.copy(title = "Finish the novel", status = GoalStatus.ACHIEVED, horizon = null)))

            assertEquals(listOf(goal.copy(title = "Finish the novel", status = GoalStatus.ACHIEVED, horizon = null)), store.goals())
        }

    @Test
    fun `a project links to an existing goal or to none, and a missing goal is refused with nothing written`() =
        runTest {
            val store = store()
            store.saveGoal(Goal("g1", "Finish the book", "A full draft exists"))

            assertNull(store.saveProject(Project("p1", "Write chapters", goalId = "g1", state = ProjectState.ACTIVE)))
            assertNull(store.saveProject(Project("p2", "Side project")))
            assertEquals(PlanningRefusal.GOAL_NOT_FOUND, store.saveProject(Project("p3", "Orphan", goalId = "missing")))
            assertEquals(PlanningRefusal.GOAL_NOT_FOUND, store.saveProject(Project("p2", "Side project", goalId = "missing")))

            assertEquals(
                listOf(Project("p1", "Write chapters", "g1", ProjectState.ACTIVE), Project("p2", "Side project")),
                store.projects(),
            )
        }

    @Test
    fun `an action can be standalone with only a title, or under an open project`() =
        runTest {
            val store = store()
            store.saveProject(Project("p1", "Write chapters", state = ProjectState.ACTIVE))

            assertNull(store.saveAction(Action("a1", "Book flights")))
            assertNull(store.saveAction(Action("a2", "Draft chapter 2", "p1", Priority.MUST, 120, LocalDate.of(2026, 10, 9))))

            assertEquals(
                listOf(Action("a1", "Book flights"), Action("a2", "Draft chapter 2", "p1", Priority.MUST, 120, LocalDate.of(2026, 10, 9))),
                store.actions(),
            )
        }

    @Test
    fun `an action cannot be added to, or moved into, a missing or closed project`() =
        runTest {
            val store = store()
            store.saveProject(Project("open", "Open", state = ProjectState.ACTIVE))
            store.saveProject(Project("done", "Done", state = ProjectState.COMPLETED))
            store.saveAction(Action("a1", "Tidy desk", "open"))

            assertEquals(PlanningRefusal.PROJECT_NOT_FOUND, store.saveAction(Action("a2", "Lost", "missing")))
            assertEquals(PlanningRefusal.PROJECT_CLOSED, store.saveAction(Action("a3", "Late", "done")))
            assertEquals(PlanningRefusal.PROJECT_CLOSED, store.saveAction(Action("a1", "Tidy desk", "done")))

            assertEquals(listOf(Action("a1", "Tidy desk", "open")), store.actions())
        }

    @Test
    fun `an action already in a project that has since closed can still be edited`() =
        runTest {
            val store = store()
            store.saveProject(Project("p1", "Launch", state = ProjectState.ACTIVE))
            store.saveAction(Action("a1", "Announce", "p1"))
            store.saveProject(Project("p1", "Launch", state = ProjectState.COMPLETED))

            assertNull(store.saveAction(Action("a1", "Announce", "p1", status = ActionStatus.DONE)))

            assertEquals(ActionStatus.DONE, store.actions().single().status)
        }

    @Test
    fun `the database refuses a dangling link even if the store's check were skipped`() =
        runTest {
            val store = store()
            val dao = requireNotNull(database).planning()

            assertFailsWith<SQLiteConstraintException> {
                dao.insertProject(ProjectEntity("p1", "Orphan", "missing", "PLANNED", 1, 1))
            }
            assertFailsWith<SQLiteConstraintException> {
                dao.insertAction(ActionEntity("a1", "Orphan", "missing", "NORMAL", 30, null, "OPEN", 1, 1))
            }
            assertEquals(emptyList(), store.projects())
        }

    @Test
    fun `settings default to a 4am rollover and no window, and keep what is saved`() =
        runTest {
            val store = store()
            assertEquals(PlanningSettings(), store.settings())

            val chosen = PlanningSettings(LocalTime.of(5, 0), PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0)))
            store.saveSettings(chosen)

            assertEquals(chosen, store.settings())
        }

    @Test
    fun `everything survives closing and reopening the database`() =
        runTest {
            store().apply {
                saveDirection(Direction("Stay curious"))
                saveGoal(Goal("g1", "Run a 10k", "Finish under an hour"))
                saveProject(Project("p1", "Training", goalId = "g1", state = ProjectState.ACTIVE))
                saveAction(Action("a1", "Buy shoes", "p1"))
                saveSettings(PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW))
            }

            val reopened = store()

            assertEquals(Direction("Stay curious"), reopened.direction())
            assertEquals(listOf(Goal("g1", "Run a 10k", "Finish under an hour")), reopened.goals())
            assertEquals(listOf(Project("p1", "Training", "g1", ProjectState.ACTIVE)), reopened.projects())
            assertEquals(listOf(Action("a1", "Buy shoes", "p1")), reopened.actions())
            assertEquals(PlanningSettings(window = PlanningSettings.SUGGESTED_WINDOW), reopened.settings())
        }

    @Test
    fun `a due date can be set, changed and cleared, and each state survives reopening`() =
        runTest {
            val first = LocalDate.of(2026, 10, 20)
            val second = LocalDate.of(2026, 11, 2)
            // Each step writes through one store and reads back through a freshly opened one.
            assertNull(store().saveAction(Action("a1", "Send invoice")))
            assertNull(store().actions().single().dueDate)

            assertNull(store().saveAction(Action("a1", "Send invoice", dueDate = first)))
            assertEquals(first, store().actions().single().dueDate)

            assertNull(store().saveAction(Action("a1", "Send invoice", dueDate = second)))
            assertEquals(second, store().actions().single().dueDate)

            // A save that leaves the date alone keeps it.
            assertNull(store().saveAction(Action("a1", "Send invoice", priority = Priority.MUST, dueDate = second)))
            assertEquals(second, store().actions().single().dueDate)

            assertNull(store().saveAction(Action("a1", "Send invoice", priority = Priority.MUST, dueDate = null)))
            assertNull(store().actions().single().dueDate)
        }
}
