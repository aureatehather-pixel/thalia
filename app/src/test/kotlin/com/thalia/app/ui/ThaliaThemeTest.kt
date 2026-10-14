package com.thalia.app.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The approved palette must stay readable. Each text and background pair the screens use is held to the design's own bar (4.5 to 1 for
 * body text), in light and dark and for every accent, so a later colour tweak cannot quietly make something unreadable.
 */
class ThaliaThemeTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Double {
        val hi = maxOf(a.luminance(), b.luminance()) + 0.05
        val lo = minOf(a.luminance(), b.luminance()) + 0.05
        return hi / lo
    }

    @Test
    fun `every text pair reads at 4_5 to 1 in light and dark for every accent`() {
        val failures = mutableListOf<String>()
        for (dark in listOf(false, true)) {
            for (accent in Accent.entries) {
                val c = thaliaColorScheme(dark, accent)
                val pairs =
                    mapOf(
                        "onPrimary/primary" to (c.onPrimary to c.primary),
                        "onSurface/surface" to (c.onSurface to c.surface),
                        "onSurface/card" to (c.onSurface to c.surfaceContainerLowest),
                        "onSurfaceVariant/surface" to (c.onSurfaceVariant to c.surface),
                        "onSurfaceVariant/card" to (c.onSurfaceVariant to c.surfaceContainerLowest),
                        "primary/surface" to (c.primary to c.surface),
                        "primary/card" to (c.primary to c.surfaceContainerLowest),
                        "onPrimaryContainer/primaryContainer" to (c.onPrimaryContainer to c.primaryContainer),
                        "onSecondaryContainer/secondaryContainer" to (c.onSecondaryContainer to c.secondaryContainer),
                        "onTertiaryContainer/tertiaryContainer" to (c.onTertiaryContainer to c.tertiaryContainer),
                        "onErrorContainer/errorContainer" to (c.onErrorContainer to c.errorContainer),
                        "error/surface" to (c.error to c.surface),
                    )
                pairs.forEach { (name, pair) ->
                    val ratio = contrast(pair.first, pair.second)
                    if (ratio < 4.5) failures += "${if (dark) "dark" else "light"} ${accent.key} $name = ${"%.2f".format(ratio)}"
                }
            }
        }
        assertTrue(failures.isEmpty(), "pairs below 4.5 to 1: $failures")
    }

    @Test
    fun `the default accent is the design's teal and the four accents are distinct`() {
        assertEquals(Accent.TEAL, Accent.of(null))
        assertEquals(Accent.TEAL, Accent.of("not an accent"))
        assertEquals(
            4,
            Accent.entries
                .map { it.light }
                .toSet()
                .size,
        )
        assertEquals(
            4,
            Accent.entries
                .map { it.dark }
                .toSet()
                .size,
        )
    }

    @Test
    fun `an unknown theme falls back to following the system`() {
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of(null))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of("sepia"))
        assertEquals(ThemeChoice.DARK, ThemeChoice.of("dark"))
    }
}
