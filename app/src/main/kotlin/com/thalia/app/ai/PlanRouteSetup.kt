package com.thalia.app.ai

import java.io.File

/**
 * DRAFT wording for the ChatGPT-plan route. It is not approved terms: eligibility, the plan's terms, metering and revocation are
 * unresolved, so nothing offers it for acceptance. Changing a word means changing [VERSION], which withdraws any consent given
 * to the earlier wording, as with [AiConsentWording].
 */
internal object PlanRouteConsentWording {
    const val VERSION: String = "chatgpt-plan-draft-1"

    const val TEXT: String =
        "DRAFT, not approved. This describes a route that is off. If it is ever turned on, pressing Send would share a snapshot " +
            "of your planning data and up to the last 10 lines of the conversation with ChatGPT, using your own sign-in. " +
            "Nothing would be applied without your tap."
}

/** The route's own consent file, apart from the Gemini consent. Acceptance is not offered while the wording is a draft. */
internal fun planRouteConsentStore(directory: File): AiConsentStore =
    AiConsentStore(File(directory, "chatgpt-plan-consent.txt"), PlanRouteConsentWording.VERSION)

/**
 * What the route has to work with. A null consent, model or credentials source means the build has no production store for it,
 * which is unavailable and never ready. [credentialsStored] says only that a sign-in can be opened from storage. [signInVerified]
 * is the separate question of whether one has been shown to work; null means this build has no way to show it, so the route
 * cannot be ready. Today no build has such a way.
 */
internal class PlanRouteSources(
    val consent: AiConsentStore?,
    val model: (() -> String?)?,
    val credentialsStored: (() -> Boolean)?,
    val signInVerified: (() -> Boolean)? = null,
) {
    fun readiness(): PlanRouteReadiness {
        if (consent == null || model == null || credentialsStored == null) return PlanRouteReadiness.UNAVAILABLE
        return when {
            !consent.isAccepted() -> PlanRouteReadiness.CONSENT_NEEDED
            model() == null -> PlanRouteReadiness.MODEL_NEEDED
            !credentialsStored() -> PlanRouteReadiness.SIGN_IN_NEEDED
            signInVerified?.invoke() != true -> PlanRouteReadiness.SIGN_IN_UNVERIFIED
            else -> PlanRouteReadiness.READY
        }
    }

    /** What is set up, in words that claim nothing about eligibility, cost, quota or renewal. */
    fun settingsLines(): List<String> =
        buildList {
            add("ChatGPT plan route: OFF. Nothing is sent to ChatGPT, and this build does not install it.")
            add("Status: " + planRouteNotice(readiness()).removePrefix("ChatGPT plan route: "))
            add("Setup:")
            add(if (consent == null) "- agreement store: none in this build" else "- agreement: not offered, the wording is a draft")
            add(
                if (model == null) {
                    "- model: no way to choose one in this build"
                } else {
                    "- model: " + (model.invoke() ?: "none chosen") + " (chosen, not checked to exist)"
                },
            )
            add(
                if (credentialsStored == null) {
                    "- sign-in: no way to sign in in this build"
                } else {
                    "- sign-in: " + if (credentialsStored.invoke()) "stored, not shown to work" else "none stored"
                },
            )
            add("Draft wording (version ${PlanRouteConsentWording.VERSION}): ${PlanRouteConsentWording.TEXT}")
        }
}
