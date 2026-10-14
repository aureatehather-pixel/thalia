package com.thalia.app.ai

import kotlin.test.Test
import kotlin.test.assertNotNull

/** The debug build, which is the real app, carries the everyday route's clients. Loading builds them; it sends nothing. */
class LiveRoutePresentTest {
    @Test
    fun `the live route loads and builds both services without a request`() {
        val route = assertNotNull(LiveRoute.load())
        assertNotNull(route.reasoning { null })
        assertNotNull(route.speech { null })
    }
}
