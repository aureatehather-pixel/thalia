package com.thalia.voicecheckins.gemini

import java.net.URLEncoder

/**
 * Removes one secret from any text before it leaves the provider.
 *
 * Every log line and every failure message the client produces passes through [redact],
 * including text that came from elsewhere: an exception's message, or the reason a server gave
 * for closing. The key is sent as the `key` query parameter, as Gemini's WebSocket guide
 * documents, so a rendered URL is the likeliest place for it to leak. A `key` or `access_token`
 * query value is removed whatever it holds, and so is the secret itself, raw or URL-encoded,
 * wherever it appears.
 */
internal class SecretRedactor(
    secret: String,
) {
    private val forms: List<String> =
        listOf(secret, URLEncoder.encode(secret, Charsets.UTF_8))
            .filter { it.isNotEmpty() }
            .distinct()

    fun redact(text: String?): String {
        if (text == null) return ""
        var out: String = text
        for (form in forms) out = out.replace(form, REDACTED)
        return SECRET_QUERY_VALUE.replace(out) { it.groupValues[1] + REDACTED }
    }

    companion object {
        const val REDACTED = "[redacted]"

        private val SECRET_QUERY_VALUE = Regex("""([?&](?:key|access_token)=)[^&#\s]*""", RegexOption.IGNORE_CASE)
    }
}
