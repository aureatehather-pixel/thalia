package com.thalia.app.planning

import com.thalia.planning.PlanningWindow
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals

/** A fresh Plan a day editor starts from the default window, never from another day's override. */
class PlanDayWindowFieldsTest {
    @Test
    fun `fresh editor text is the default window`() {
        val default = PlanningWindow(LocalTime.of(12, 0), LocalTime.of(22, 0))
        assertEquals("12:00" to "22:00", defaultWindowTexts(default))
    }

    @Test
    fun `before setup the editor stays empty so setup can seed it`() {
        assertEquals("" to "", defaultWindowTexts(null))
    }
}
