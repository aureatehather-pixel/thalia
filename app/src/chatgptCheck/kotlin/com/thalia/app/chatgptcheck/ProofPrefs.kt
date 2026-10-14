package com.thalia.app.chatgptcheck

import android.annotation.SuppressLint
import android.content.Context
import androidx.core.content.edit
import com.thalia.chatgptplan.PlanGates

/**
 * This proof's own switches, kept on the phone: consent, the chosen model, the mute switch, and a **hard request count** that
 * survives restarts and is only ever reset by reinstalling. Plan usage is not unlimited, so the count is the proof's own
 * ceiling. A request is counted before it is sent, so an uncertain one stays counted.
 */
internal class ProofPrefs(
    context: Context,
    private val maxDiscovery: Int = MAX_DISCOVERY,
    private val maxInference: Int = MAX_INFERENCE,
    private val maxProposal: Int = MAX_PROPOSAL,
    private val modelRequestsEnabled: Boolean = MODEL_REQUESTS_ENABLED,
) : PlanGates {
    private val prefs = context.getSharedPreferences("chatgpt_proof", Context.MODE_PRIVATE)

    var consent: Boolean
        get() = prefs.getBoolean("consent", false)
        set(value) = prefs.edit { putBoolean("consent", value) }

    /**
     * The owner's own statement that they opened ChatGPT Settings, Usage, after signing in and found credits off for this app. It
     * is an attestation, not a control: the app cannot read that setting. Cleared on sign-out, and required for every request.
     */
    var creditsChecked: Boolean
        get() = prefs.getBoolean("credits_checked", false)
        set(value) = prefs.edit { putBoolean("credits_checked", value) }

    var model: String?
        get() = prefs.getString("model", null)
        set(value) = prefs.edit { putString("model", value) }

    var muted: Boolean
        get() = prefs.getBoolean("muted", false)
        set(value) = prefs.edit { putBoolean("muted", value) }

    var voice: String?
        get() = prefs.getString("voice", null)
        set(value) = prefs.edit { putString("voice", value) }

    /** Requests of each kind that have been counted. A request is counted before it is sent, so an uncertain one stays counted. */
    val discoveryUsed: Int get() = prefs.getInt("discovery_used", 0)

    val inferenceUsed: Int get() = prefs.getInt("inference_used", 0)

    val used: Int get() = discoveryUsed + inferenceUsed

    val remaining: Int get() = (maxDiscovery - discoveryUsed).coerceAtLeast(0) + (maxInference - inferenceUsed).coerceAtLeast(0)

    override fun consented() = modelRequestsEnabled && consent && creditsChecked

    /** Phase B2 attempts counted so far. Separate from the discovery and inference counters, which stay as they were. */
    val proposalUsed: Int get() = prefs.getInt("proposal_used", 0)

    /** The proposal cases that have been started. A case is recorded here before its request is made and is never sent again. */
    val proposalAttempted: Set<String> get() = prefs.getStringSet("proposal_attempted", emptySet()).orEmpty().toSet()

    @SuppressLint("UseKtx") // commit() is wanted: the case must be on disk before its request leaves.
    @Synchronized
    fun markProposalAttempted(
        caseId: String,
    ): Boolean = prefs.edit().putStringSet("proposal_attempted", proposalAttempted + caseId).commit()

    /** One Phase B2 inference attempt, counted on disk before it is sent. The provider calls this through [ProposalGates]. */
    @Synchronized
    fun tryReserveProposal(): Boolean = reserve("proposal_used", maxProposal)

    /** The one model-discovery request. Not an inference, and not evidence that inference is accepted. */
    @Synchronized
    fun tryReserveDiscovery(): Boolean = reserve("discovery_used", maxDiscovery)

    /** The one inference request. The provider calls this before every inference it sends, so no second one can leave. */
    @Synchronized
    override fun tryReserve(): Boolean = reserve("inference_used", maxInference)

    /** The separate connection trial's counters. Their own keys: the discovery, inference and proposal counters are never read or changed. */
    val trialSignInUsed: Int get() = prefs.getInt("trial_signin_used", 0)

    val trialInferenceUsed: Int get() = prefs.getInt("trial_inference_used", 0)

    /** The one trial sign-in attempt, counted on disk before the browser opens. A sign-in sends no model request. */
    @Synchronized
    fun tryReserveTrialSignIn(): Boolean = reserve("trial_signin_used", TRIAL_MAX_SIGN_IN)

    /** The one trial inference, counted on disk before it is sent. The provider calls this through [TrialGates]. */
    @Synchronized
    fun tryReserveTrialInference(): Boolean = reserve("trial_inference_used", TRIAL_MAX_INFERENCE)

    // commit, not apply: the count must be on disk before the request leaves.
    @SuppressLint("UseKtx") // commit() is wanted: its result says whether the count reached the disk.
    private fun reserve(
        key: String,
        max: Int,
    ): Boolean {
        val n = prefs.getInt(key, 0)
        if (n >= max) return false
        return prefs.edit().putInt(key, n + 1).commit()
    }

    companion object {
        /**
         * Phase B1: model requests are allowed, but only through the gates above (consent, the owner's balance attestation and
         * the two counters). Phase A had this false. Every request path asks [consented] first.
         */
        const val MODEL_REQUESTS_ENABLED = true

        /**
         * Phase B1: model-discovery requests in total, and one inference request, until the app is reinstalled. Raised from 1 to 2
         * after the first discovery returned HTTP 200 and no usable list, so that a second can be read with better diagnostics.
         * The count already used is kept; this only raises the ceiling.
         */
        const val MAX_DISCOVERY = 2
        const val MAX_INFERENCE = 1

        /** Phase B2: inference attempts for the ten proposal cases, in total, with no automatic retry. */
        const val MAX_PROPOSAL = 10

        /** The connection trial: one sign-in attempt and one inference, with no discovery request built for it. */
        const val TRIAL_MAX_SIGN_IN = 1
        const val TRIAL_MAX_INFERENCE = 1
    }
}

/**
 * What the provider asks before each proposal-case request: the same consent and record as everywhere else, and a count that is
 * its own. The exhausted discovery and inference counters are neither read nor changed.
 */
internal class ProposalGates(
    private val prefs: ProofPrefs,
) : PlanGates {
    override fun consented() = prefs.consented()

    override fun tryReserve() = prefs.tryReserveProposal()
}

/**
 * What the provider asks before the trial's one inference: the same consent and record as everywhere else, and a count that is its
 * own. The exhausted discovery, inference and proposal counters are neither read nor changed.
 */
internal class TrialGates(
    private val prefs: ProofPrefs,
) : PlanGates {
    override fun consented() = prefs.consented()

    override fun tryReserve() = prefs.tryReserveTrialInference()
}
