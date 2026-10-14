package com.thalia.app.planning

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.SaverScope
import com.thalia.planning.ActiveDay
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The Plan tab's choices must come back exactly after a configuration change. Nothing here approves or saves anything. */
class PlanDraftSaversTest {
    private val scope = SaverScope { true }

    private fun <T, S : Any> Saver<T, S>.roundTrip(value: T): T? = restore(with(this) { scope.save(value)!! })

    @Test
    fun `selected actions come back as the same set`() {
        val picked = setOf("b", "a", "c")
        assertEquals(picked, StringSetSaver.roundTrip(picked))
        assertEquals(emptySet(), StringSetSaver.roundTrip(emptySet()))
    }

    @Test
    fun `pin texts come back exactly, including a half-typed one`() {
        val pins = mapOf("a" to "14:00", "b" to "9:", "c" to "")
        assertEquals(pins, StringMapSaver.roundTrip(pins))
        assertEquals(emptyMap(), StringMapSaver.roundTrip(emptyMap()))
    }

    @Test
    fun `the day being planned comes back, and no day stays none`() {
        val day = ActiveDay(LocalDate.of(2026, 10, 13))
        assertEquals(day, ActiveDaySaver.roundTrip(day))
        assertNull(ActiveDaySaver.roundTrip(null))
    }
}
