package com.thalia.app.conversation

import com.thalia.conversation.ProviderFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Every failure has its own plain words, and an unknown or setup-related status is never called a server fault. */
class ProviderFailureTextTest {
    @Test
    fun `every failure has words, and each is different`() {
        val texts = ProviderFailure.entries.associateWith { providerFailureText(it) }
        assertTrue(texts.values.all { it.isNotBlank() })
        assertEquals(texts.size, texts.values.toSet().size)
    }

    @Test
    fun `not found, too large and unknown statuses are not described as a service problem`() {
        val server = providerFailureText(ProviderFailure.SERVER)
        listOf(ProviderFailure.NOT_FOUND, ProviderFailure.REQUEST_TOO_LARGE, ProviderFailure.UNEXPECTED_STATUS).forEach {
            val t = providerFailureText(it)
            assertFalse(t == server || "had a problem" in t, it.name)
        }
        assertTrue("model or address" in providerFailureText(ProviderFailure.NOT_FOUND))
        assertTrue("too large" in providerFailureText(ProviderFailure.REQUEST_TOO_LARGE))
        // A sent request is never described as unsent, and may still count against the budget.
        assertFalse("Nothing was sent" in providerFailureText(ProviderFailure.REQUEST_TOO_LARGE))
        assertTrue("budget" in providerFailureText(ProviderFailure.UNEXPECTED_STATUS))
    }
}
