package com.thalia.app.geminicheck

/**
 * The live check's own consent, shown instead of production's in this build only (ADR 0013). It is a different version, so
 * production's consent, and any consent given to an earlier wording, never counts here. Changing a word means changing
 * [VERSION].
 */
internal object GeminiCheckWording {
    const val VERSION: String = "ai-text-live-check-free-tier-2026-10-08"

    const val ESTIMATE_NOTE: String =
        "Amounts in this budget are paid-equivalent estimates, worked out on this phone at Gemini's paid-tier prices. They are " +
            "not charges: Google bills a free-tier project nothing. Whether a project is on the free tier is for you to " +
            "confirm in Google AI Studio; Thalia cannot see it."

    const val TEXT: String =
        "LIVE CHECK BUILD. This build is intended for a key from a free-tier Gemini API project, and for invented text only. " +
            "Thalia does not verify billing status or which tier a key is on; that is for you to confirm in Google AI Studio.\n\n" +
            "Pressing Send sends the following to Google's Gemini (model gemini-3.8-flash, through the Gemini Developer API), " +
            "an external service, using the key you save on this screen:\n" +
            "- a fixed instruction telling the model its job and limits;\n" +
            "- a snapshot of this app's planning data, which is all invented;\n" +
            "- up to the last 10 lines of the conversation, including whatever you type;\n" +
            "- a list of the function names the model may suggest;\n" +
            "- only when you use Triage with Thalia, up to 5 invented Inbox captures, each cut to 400 characters.\n\n" +
            "How Google may use it. Google's Gemini API terms, read on 2026-10-08, say that on unpaid services Google uses the " +
            "content you submit to improve its products, and that human reviewers may read, annotate and process your input " +
            "and output, after it is detached from your account, key and project. They say not to submit sensitive, " +
            "confidential or personal information to unpaid services. On paid services content is not used that way. You must be " +
            "18 or older, and in the EEA, Switzerland and the UK Google's terms allow paid services only.\n\n" +
            "So: type invented text only in the conversation. No real names, plans, places, contacts, accounts or secrets.\n\n" +
            "Limits of this check: at most 8 Sends and USD 0.42 of estimated reservations in total, no automatic retry, and no " +
            "further Send after the first failure or reply without usage. Google may refuse a free-tier request for quota. " +
            "Thalia does not store the conversation. Anything the model suggests changes nothing in the invented data until " +
            "you approve it. Turning AI off stops all requests."
}
