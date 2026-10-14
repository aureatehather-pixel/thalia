package com.thalia.voicecheckins.gemini

/**
 * What one Gemini Live session has reported about its usage, kept exactly as received.
 *
 * **Nothing here is summed, and nothing here is a cost.** The Live API reference describes
 * `usageMetadata` only as "usage metadata about the response(s)", and as of 2026-10-02 it does
 * not say whether successive reports are cumulative for the session or incremental. Summing
 * them could double count, and taking the last could undercount, so ADR 0008 decision 7 forbids
 * either until the official semantics are known. Every report is kept in arrival order instead,
 * so the right reading can be applied afterwards.
 *
 * Token counts are [Long]. Any later cost arithmetic must use exact decimal or integer
 * arithmetic, never floating point.
 */
data class LiveUsage(
    /** Every readable `usageMetadata` object, in the order the server sent them. */
    val reports: List<UsageReport> = emptyList(),
    /** `usageMetadata` values that were present but not an object, so nothing could be read. */
    val unreadableReports: Int = 0,
    /** Microphone audio handed to the socket. A diagnostic only, never a cost. */
    val audioBytesSent: Long = 0,
    /** Model audio received and handed to playback. A diagnostic only, never a cost. */
    val audioBytesReceived: Long = 0,
) {
    /** The most recent readable report, if any. Not a total. */
    val latest: UsageReport? get() = reports.lastOrNull()
}

/**
 * One `usageMetadata` object.
 *
 * A count is null when the server left it out, or sent something that is not a non-negative
 * whole number. A field that was present but unreadable is also named in [invalidFields], so an
 * unexpected shape is visible rather than silently treated as zero.
 */
data class UsageReport(
    val promptTokenCount: Long?,
    val cachedContentTokenCount: Long?,
    val responseTokenCount: Long?,
    val toolUsePromptTokenCount: Long?,
    val thoughtsTokenCount: Long?,
    val totalTokenCount: Long?,
    val promptTokensDetails: List<ModalityTokenCount>,
    val cacheTokensDetails: List<ModalityTokenCount>,
    val responseTokensDetails: List<ModalityTokenCount>,
    val toolUsePromptTokensDetails: List<ModalityTokenCount>,
    val invalidFields: List<String>,
)

/** One modality's share of a count, such as `AUDIO` or `TEXT`. Either part may be missing. */
data class ModalityTokenCount(
    val modality: String?,
    val tokenCount: Long?,
)
