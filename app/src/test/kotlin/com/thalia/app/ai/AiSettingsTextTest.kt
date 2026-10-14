package com.thalia.app.ai

import com.thalia.app.conversation.aiNotice
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiSettingsTextTest {
    @TempDir
    lateinit var dir: File

    private fun summary(
        spent: Long,
        held: Long = 0,
        heldCount: Int = 0,
    ) = BudgetSummary("2026-10", 5_000_000, spent - held, held, heldCount, 0, 0)

    @Test
    fun `warnings appear at 70 and 90 percent and when the budget is used up`() {
        assertNull(budgetWarning(summary(3_000_000)))
        assertEquals("70% of the budget is used.", budgetWarning(summary(3_600_000)))
        assertEquals("90% of the budget is used.", budgetWarning(summary(4_600_000)))
        assertTrue("used up" in budgetWarning(summary(5_000_000))!!)
    }

    @Test
    fun `the budget text says it is an estimate and names held requests`() {
        val text = budgetText(summary(1_500_000, held = 100_000, heldCount = 2))
        assertTrue("USD 1.50 of USD 5.00" in text)
        assertTrue("held for 2 request(s)" in text)
        assertTrue("stays counted until the month ends" in text)
    }

    @Test
    fun `the screens say plainly what Send can reach, and a check build never claims to reach Google`() {
        assertTrue("no connection" in aiNotice(AiConnection.NONE, consent = true, key = true))
        assertTrue("AI is off" in aiNotice(AiConnection.FAKE, consent = false, key = true))
        assertTrue("no key" in aiNotice(AiConnection.LIVE, consent = true, key = false))
        val fake = aiNotice(AiConnection.FAKE, consent = true, key = true)
        assertTrue("CHECK BUILD" in fake && "fake transport" in fake && "not to Google" in fake && "Nothing leaves the phone" in fake)
        assertFalse("shares a snapshot" in fake)
        assertTrue("shares a snapshot" in aiNotice(AiConnection.LIVE, consent = true, key = true))

        val settingsFake = connectionText(AiConnection.FAKE)
        assertTrue("fake transport" in settingsFake && "not to Google" in settingsFake && "no internet permission" in settingsFake)
        assertFalse("can send requests" in settingsFake || "can reach" in settingsFake)
        assertTrue("no connection" in connectionText(AiConnection.NONE))
    }

    @Test
    fun `consent counts only for the wording that was accepted`() {
        val file = File(dir, "consent.txt")
        AiConsentStore(file, "old-wording").accept(5)
        assertFalse(AiConsentStore(file).isAccepted())
        assertTrue(AiConsentStore(file, "old-wording").isAccepted())
        val store = AiConsentStore(file)
        store.accept(9)
        assertEquals(9, assertNotNull(store.current()).acceptedAtMillis)
        store.withdraw()
        assertFalse(store.isAccepted())
    }

    @Test
    fun `the wording names the provider, the model and what leaves the phone`() {
        val t = AiConsentWording.TEXT
        listOf(
            "Google's Gemini",
            "gemini-3.8-flash",
            "Inbox captures are never included",
            "up to 5",
            "400 characters",
            "Free-tier",
            "not Google's",
        ).forEach { assertTrue(it in t, it) }
    }
}
