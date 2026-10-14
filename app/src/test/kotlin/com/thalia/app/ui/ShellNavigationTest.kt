package com.thalia.app.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.thalia.app.conversation.ConversationActivity
import com.thalia.app.planning.PlanningActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The four tabs, their order, and where each one goes. Moving between tabs writes nothing and carries no data. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ShellNavigationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the tabs are Today, Plan, Life and Thalia in that order`() {
        assertEquals(listOf("Today", "Plan", "Life", "Thalia"), ThaliaTab.entries.map { it.label })
    }

    @Test
    fun `Today, Plan and Life go to the planning host and Thalia goes to the conversation`() {
        listOf(ThaliaTab.TODAY, ThaliaTab.PLAN, ThaliaTab.LIFE).forEach {
            assertEquals(PlanningActivity::class.java.name, tabIntent(context, it).component?.className)
        }
        assertEquals(ConversationActivity::class.java.name, tabIntent(context, ThaliaTab.THALIA).component?.className)
    }

    @Test
    fun `a tab intent brings an open screen back, keeping the ones behind it alive, and names its tab`() {
        ThaliaTab.entries.forEach { tab ->
            val intent = tabIntent(context, tab)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
            assertEquals(tab, ThaliaTab.from(intent))
        }
    }

    @Test
    fun `an intent with no tab, or an unknown one, names none`() {
        assertNull(ThaliaTab.from(Intent()))
        assertNull(ThaliaTab.from(null))
        assertNull(ThaliaTab.from(Intent().putExtra(ThaliaTab.EXTRA, "NOWHERE")))
    }

    @Test
    fun `the appearance choices are kept, shared, and fall back to the defaults`() {
        context
            .getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        val prefs = AppearancePrefs(context)
        assertEquals(ThemeChoice.SYSTEM, prefs.theme)
        assertEquals(Accent.TEAL, prefs.accent)
        prefs.chooseTheme(ThemeChoice.DARK)
        prefs.chooseAccent(Accent.BLUE)
        val reopened = AppearancePrefs(context)
        assertEquals(ThemeChoice.DARK, reopened.theme)
        assertEquals(Accent.BLUE, reopened.accent)
    }
}
