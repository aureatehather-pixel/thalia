package com.thalia.chatgptplan

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface AccessResult {
    data class Ok(
        val accessToken: String,
    ) : AccessResult

    /** No usable sign-in: never signed in, signed out, or the server rejected the refresh token (revoked or lapsed). */
    data class SignedOut(
        val revoked: Boolean,
    ) : AccessResult

    /** Signed in, but the grant does not include plan usage. Nothing is requested. */
    data object NoPlanScope : AccessResult

    /** Renewal could not be decided (network, 5xx, 429). Signed in still, and tokens are kept. */
    data object Transient : AccessResult
}

/**
 * The signed-in state, and renewal. One refresh at a time: a second caller waits and then uses the first one's result, because
 * the refresh token rotates and cannot be spent twice. A refreshed pair is saved before the new access token is used. If the
 * save fails the pair is kept in memory for this process, so the sign-in is not lost to a storage error while the process lives.
 */
class PlanSession(
    private val store: CredentialStore,
    private val tokens: TokenClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val skewMillis: Long = 60_000,
) {
    private val lock = Mutex()
    private var unsaved: PlanCredentials? = null

    /** Set when the last renewal could not be written, for display. */
    @Volatile var storeFailed = false
        private set

    private fun current(): PlanCredentials? = unsaved ?: store.load()

    fun signedIn(): PlanCredentials? = current()

    suspend fun accessToken(): AccessResult =
        lock.withLock {
            val credentials = current() ?: return@withLock AccessResult.SignedOut(revoked = false)
            if (!credentials.mayUsePlan) return@withLock AccessResult.NoPlanScope
            if (credentials.expiresAtMillis - skewMillis > clock()) return@withLock AccessResult.Ok(credentials.accessToken)
            renew(credentials)
        }

    /** Renews now, whatever the expiry says: used once after a 401. Not repeated if another caller already renewed. */
    suspend fun renewAfterRejection(rejectedToken: String): AccessResult =
        lock.withLock {
            val credentials = current() ?: return@withLock AccessResult.SignedOut(revoked = false)
            if (credentials.accessToken != rejectedToken) return@withLock AccessResult.Ok(credentials.accessToken)
            renew(credentials)
        }

    private suspend fun renew(credentials: PlanCredentials): AccessResult =
        when (val result = tokens.refresh(credentials.clientId, credentials.refreshToken)) {
            is TokenResult.Ok -> {
                val fresh =
                    credentials.copy(
                        accessToken = result.tokens.accessToken,
                        // The refresh token rotates: a new one replaces the old. If none came back, the old one is all there is.
                        refreshToken = result.tokens.refreshToken ?: credentials.refreshToken,
                        idToken = result.tokens.idToken ?: credentials.idToken,
                        scopes = result.tokens.scopes ?: credentials.scopes,
                        expiresAtMillis = clock() + result.tokens.expiresInSeconds * 1000,
                        savedAtMillis = clock(),
                    )
                try {
                    store.save(fresh)
                    unsaved = null
                    storeFailed = false
                } catch (e: RuntimeException) {
                    unsaved = fresh
                    storeFailed = true
                }
                if (fresh.mayUsePlan) AccessResult.Ok(fresh.accessToken) else AccessResult.NoPlanScope
            }
            is TokenResult.Rejected ->
                if (result.error in TERMINAL_REFRESH_ERRORS) {
                    signOut()
                    AccessResult.SignedOut(revoked = true)
                } else {
                    // invalid_client, invalid_request or an unnamed refusal is a configuration or protocol problem, not proof that
                    // the refresh token is dead. Keep the tokens and report it; erasing them could throw away a working sign-in.
                    AccessResult.Transient
                }
            is TokenResult.Transient -> AccessResult.Transient
        }

    /** Forgets the tokens on this phone. Revoking access is done in ChatGPT settings; OpenAI does not tell the app. */
    fun signOut() {
        unsaved = null
        store.clear()
    }

    private companion object {
        /** The refresh errors developers.openai.com/siwc/token-sharing-open-source/errors-and-recovery says to clear tokens for. */
        val TERMINAL_REFRESH_ERRORS =
            setOf(
                "invalid_grant",
                "invalid_refresh_token",
                "token_expired",
                "refresh_token_expired",
                "refresh_token_invalidated",
                "refresh_token_reused",
            )
    }
}
