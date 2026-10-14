package com.thalia.app.chatgptcheck

/** What the owner is told before anything is sent, and the checks to make in ChatGPT itself. Plain text, so it can be tested. */
internal object ChatGptCheckWording {
    const val CONSENT =
        "This proof sends synthetic text to OpenAI, using your ChatGPT plan through Sign in with ChatGPT. Plan usage is shared " +
            "with ChatGPT and your other apps. It is not unlimited and can run out. This app has no API key and never switches to a " +
            "paid route. It sends at most ${ProofPrefs.MAX_DISCOVERY} model-discovery requests in total and ${ProofPrefs.MAX_INFERENCE} " +
            "inference request, ever, then up to ${ProofPrefs.MAX_PROPOSAL} proposal-case attempts, and retries nothing."

    const val AFTER_SIGN_IN =
        "This app cannot read or change your ChatGPT billing settings, and the box below is only your own record, not a " +
            "protection. What the help page says is the protection: app credit use is off unless you opt in, it needs available " +
            "credits, automatic purchases are a separate setting, and a failure does not switch to another paid option. Before " +
            "ticking: your credit balance is zero and automatic recharge is off. Until you tick, every request is refused."

    const val PHASE = "Phase B2: proposal behavior, up to 10 attempts"

    const val PHASE_NOTE =
        "The discovery and single-inference allowances are used up and stay so. This run sends the ten fixed synthetic cases, one " +
            "request each, through the real parser and validator, with at most ${ProofPrefs.MAX_PROPOSAL} attempts in total and " +
            "no retry. " +
            "Nothing is applied, not even a plainly stated outcome, until you tap Approve; approving writes only to an in-memory fake " +
            "store. Nothing real is written."

    const val TRIAL_PROMPT = "Reply with exactly: Thalia connection test."

    const val TRIAL_EXPECTED = "Thalia connection test."

    const val TRIAL_NOTE =
        "Connection trial: one sign-in attempt (only if signed out) and one inference, counted on their own. The prompt is fixed, " +
            "carries no planning data and no tools, and nothing it returns is applied. No discovery request, no retry. A reply " +
            "shows acceptance, not how the plan was metered."

    const val CREDITS_CHECKED = "My credit balance is zero and automatic recharge is off."

    const val DISCOVERY_NOT_ACCEPTANCE = "Discovery only. This does not show that inference is accepted."

    const val INFERENCE_NOT_METERING = "A reply shows the request was accepted. It does not show how the plan was metered."

    const val FEATURE_OFF = "Disabled in this build."

    const val USING_PLAN = "Using ChatGPT plan"
    const val MANAGE_USAGE = "Manage usage: chatgpt.com/settings/usage"

    const val SIGNATURE_NOTE = "The ID token's signature is verified against OpenAI's published keys, then its claims are checked."

    const val SIMULATION = "Simulated. No network, no sign-in, fake tokens. Nothing real is touched."

    fun limitMessage() = "Usage limit reached. Review your plan or this app's limit in ChatGPT settings. Nothing else was tried."
}
