package com.thalia.chatgptplan

/** Removes anything that looks like a credential before a line is written or shown. A proof record holds no token, ever. */
object Redactor {
    private val jwt = Regex("""eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]*""")
    private val bearer = Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]{8,}""")
    private val longToken = Regex("""[A-Za-z0-9_-]{40,}""")
    private val query = Regex("""(?i)(code|state|nonce|code_verifier|refresh_token|access_token|id_token)=[^&\s"]+""")

    fun scrub(text: String): String =
        text
            .replace(jwt, "[jwt]")
            .replace(bearer, "[bearer]")
            .replace(query) { "${it.groupValues[1]}=[redacted]" }
            .replace(longToken, "[token]")
}

/** One line of evidence: what was asked, how it ended, and how it scored. Synthetic content only; tokens never. */
data class ProofRecord(
    val atMillis: Long,
    val kind: String,
    val subject: String,
    val outcome: String,
    val httpStatus: Int? = null,
    val latencyMillis: Long? = null,
    val verdict: Verdict? = null,
    val detail: String = "",
    val promptTokens: Int? = null,
    val outputTokens: Int? = null,
    /** Most lines are short on purpose. A full case report sets this higher so nothing of a synthetic reply is cut. */
    val detailLimit: Int = MAX_DETAIL,
) {
    fun line(): String =
        buildString {
            append('{')
            append("\"t\":$atMillis,\"kind\":${q(kind)},\"subject\":${q(subject)},\"outcome\":${q(outcome)}")
            httpStatus?.let { append(",\"status\":$it") }
            latencyMillis?.let { append(",\"ms\":$it") }
            verdict?.let { append(",\"verdict\":${q(it.name)}") }
            promptTokens?.let { append(",\"in\":$it") }
            outputTokens?.let { append(",\"out\":$it") }
            if (detail.isNotEmpty()) append(",\"detail\":${q(detail.take(detailLimit))}")
            append('}')
        }

    private fun q(s: String) =
        "\"" +
            Redactor
                .scrub(s)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\""

    companion object {
        const val MAX_DETAIL = 700

        /** Room for a whole synthetic reply, every call and its arguments. */
        const val FULL_DETAIL = 8_000
    }
}
