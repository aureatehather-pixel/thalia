package com.thalia.app.ai

import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanRouteSetupTest {
    @TempDir
    lateinit var dir: File

    private fun accepted(version: String = PlanRouteConsentWording.VERSION): AiConsentStore =
        AiConsentStore(File(dir, "c.txt"), version).also { it.accept(1) }

    private fun none() = AiConsentStore(File(dir, "none.txt"), PlanRouteConsentWording.VERSION)

    private fun sources(
        consent: AiConsentStore,
        model: String?,
        stored: Boolean,
        verified: Boolean? = null,
    ) = PlanRouteSources(consent, { model }, { stored }, verified?.let { v -> { v } })

    @Test
    fun `a missing production store is unavailable, never ready, whatever the others say`() {
        val ok = accepted()
        val model: () -> String? = { "m" }
        val stored: () -> Boolean = { true }
        val verified: () -> Boolean = { true }
        assertEquals(PlanRouteReadiness.UNAVAILABLE, PlanRouteSources(null, model, stored, verified).readiness())
        assertEquals(PlanRouteReadiness.UNAVAILABLE, PlanRouteSources(ok, null, stored, verified).readiness())
        assertEquals(PlanRouteReadiness.UNAVAILABLE, PlanRouteSources(ok, model, null, verified).readiness())
        assertEquals(PlanRouteReadiness.UNAVAILABLE, PlanRouteSources(null, null, null).readiness())
    }

    @Test
    fun `with every source present the first missing piece is named in the provider's order`() {
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, sources(none(), null, false).readiness())
        assertEquals(PlanRouteReadiness.MODEL_NEEDED, sources(accepted(), null, false).readiness())
        assertEquals(PlanRouteReadiness.SIGN_IN_NEEDED, sources(accepted(), "m", false, true).readiness())
        assertEquals(PlanRouteReadiness.SIGN_IN_UNVERIFIED, sources(accepted(), "m", true, false).readiness())
        assertEquals(PlanRouteReadiness.READY, sources(accepted(), "m", true, true).readiness())
    }

    @Test
    fun `stored credentials alone are never ready, with no way to show a sign-in works or with a failed one`() {
        assertEquals(PlanRouteReadiness.SIGN_IN_UNVERIFIED, sources(accepted(), "m", true, null).readiness())
        assertEquals(PlanRouteReadiness.SIGN_IN_UNVERIFIED, sources(accepted(), "m", true, false).readiness())
        assertTrue("not been shown to work" in planRouteNotice(PlanRouteReadiness.SIGN_IN_UNVERIFIED))
    }

    @Test
    fun `readiness follows the stores as they change`() {
        var model: String? = null
        var stored = false
        val consent = none()
        val sources = PlanRouteSources(consent, { model }, { stored })
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, sources.readiness())
        consent.accept(1)
        assertEquals(PlanRouteReadiness.MODEL_NEEDED, sources.readiness())
        model = "m"
        assertEquals(PlanRouteReadiness.SIGN_IN_NEEDED, sources.readiness())
        stored = true
        assertEquals(PlanRouteReadiness.SIGN_IN_UNVERIFIED, sources.readiness())
        consent.withdraw()
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, sources.readiness())
    }

    @Test
    fun `consent given to an earlier wording version counts as none`() {
        val file = File(dir, "plan.txt")
        AiConsentStore(file, "chatgpt-plan-draft-0").accept(1)
        val current = AiConsentStore(file, PlanRouteConsentWording.VERSION)
        assertEquals(PlanRouteReadiness.CONSENT_NEEDED, sources(current, "m", true, true).readiness())
        assertEquals(PlanRouteReadiness.READY, sources(AiConsentStore(file, "chatgpt-plan-draft-0"), "m", true, true).readiness())
    }

    @Test
    fun `the route's consent is a separate file from the Gemini consent`() {
        val gemini = AiConsentStore(File(dir, "consent.txt"))
        gemini.accept(1)
        assertFalse(planRouteConsentStore(dir).isAccepted())
        planRouteConsentStore(dir).accept(2)
        gemini.withdraw()
        assertTrue(planRouteConsentStore(dir).isAccepted())
        assertFalse(gemini.isAccepted())
    }

    @Test
    fun `the settings section says the route is off and names what is missing in a build with no stores`() {
        val text = PlanRouteSources(planRouteConsentStore(dir), null, null).settingsLines().joinToString("\n")
        listOf(
            "OFF",
            "does not install it",
            "not available in this build",
            "no way to choose one",
            "no way to sign in",
            "DRAFT, not approved",
            "chatgpt-plan-draft-1",
        ).forEach { assertTrue(it in text, it) }
    }

    @Test
    fun `the settings section separates a stored sign-in from a working one and a chosen model from an available one`() {
        val none = PlanRouteSources(accepted(), { null }, { false }).settingsLines().joinToString("\n")
        assertTrue("model: none chosen" in none && "none stored" in none && "not offered" in none && "OFF" in none)
        val some = sources(accepted(), "m", true).settingsLines().joinToString("\n")
        assertTrue("model: m (chosen, not checked to exist)" in some)
        assertTrue("stored, not shown to work" in some)
        assertTrue("OFF" in some && "ready" !in some.lowercase())
    }

    @Test
    fun `nothing in the settings section or the draft claims eligibility, cost, usage or renewal`() {
        val all =
            listOf(PlanRouteSources(null, null, null), sources(accepted(), "m", true, true), sources(accepted(), "m", true))
                .flatMap { it.settingsLines() }
                .joinToString("\n")
                .lowercase()
                .replace("not approved", "")
        listOf("eligible", "free", "unlimited", "included", "metered", "quota", "billing", "budget", "renew", "approved terms")
            .forEach { assertFalse(it in all, it) }
    }

    @Test
    fun `the draft wording is labelled as a draft`() {
        assertTrue(PlanRouteConsentWording.TEXT.startsWith("DRAFT, not approved"))
    }
}
