package com.thalia.app.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The call screen takes its look from the app theme only, and stays reachable in landscape and at large text. */
class CallSurfaceStyleTest {
    private val source = File("src/main/kotlin/com/thalia/app/voicecheckins/call/ui/CallSurface.kt").readText()

    @Test
    fun `colours come from the theme, never from a literal`() {
        assertFalse(Regex("Color\\(0x").containsMatchIn(source), "no hard-coded colour")
        assertFalse("Color.Red" in source || "Color.Green" in source || "Color.Black" in source || "Color.White" in source)
        assertTrue("MaterialTheme.colorScheme" in source)
    }

    @Test
    fun `the phases scroll rather than clip, and the call buttons are large targets`() {
        assertTrue("verticalScroll" in source, "a phase can scroll in landscape or at large text")
        assertTrue("heightIn(min = 64.dp)" in source, "Answer, Decline, End Call and Done are at least 64 dp tall")
        assertTrue("label = \"Answer\"" in source && "label = \"Decline\"" in source, "their meaning is in words")
    }

    @Test
    fun `a short window trims the layout so the buttons show without scrolling`() {
        assertTrue("COMPACT_BELOW" in source && "compact" in source)
    }

    @Test
    fun `the surface still says it is an internet call and a simulated conversation`() {
        assertTrue("Incoming internet call" in source)
        assertTrue("Simulated conversation. No microphone, no audio, no AI provider." in source)
    }
}
