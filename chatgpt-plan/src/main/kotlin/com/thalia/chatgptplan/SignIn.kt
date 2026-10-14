package com.thalia.chatgptplan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How a sign-in attempt ended. [problem] is a short code, never a token, code or body. */
sealed interface SignInResult {
    data class Signed(
        val credentials: PlanCredentials,
    ) : SignInResult

    data class Failed(
        val problem: String,
    ) : SignInResult
}

/**
 * Completes a sign-in once the browser has returned: checks the callback against the attempt, exchanges the code, verifies the ID
 * token's signature ([IdTokenVerifier]) and checks its claims, requires the plan scope, and saves. Nothing is saved unless every
 * check passed, so a failed sign-in leaves the previous state alone.
 */
class SignIn(
    private val tokens: TokenClient,
    private val store: CredentialStore,
    private val endpoints: PlanEndpoints,
    private val verifier: IdTokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun complete(
        attempt: AuthorizationAttempt,
        callback: CallbackResult,
    ): SignInResult {
        val code =
            when (callback) {
                is CallbackResult.Code -> callback
                is CallbackResult.Denied -> return SignInResult.Failed("denied:${callback.error}")
                CallbackResult.TimedOut -> return SignInResult.Failed("timed_out")
            }
        // A new registration must come back with its issued client id; a reauthorization must not change it.
        val clientId =
            if (attempt.registering) {
                code.clientId?.takeIf { it.isNotBlank() } ?: return SignInResult.Failed("registration_incomplete")
            } else {
                if (code.clientId != null && code.clientId != attempt.clientId) return SignInResult.Failed("client_id_changed")
                attempt.clientId
            }
        val result = tokens.exchange(clientId, code.code, attempt.verifier, attempt.redirectUri)
        val granted =
            when (result) {
                is TokenResult.Ok -> result.tokens
                is TokenResult.Rejected -> return SignInResult.Failed("exchange_rejected:${result.error ?: result.status}")
                is TokenResult.Transient -> return SignInResult.Failed("exchange_unavailable")
            }
        val idToken = granted.idToken ?: return SignInResult.Failed("no_id_token")
        if (!verifier.verify(idToken)) return SignInResult.Failed("id_token_signature")
        val claims = IdTokenClaims.parse(idToken) ?: return SignInResult.Failed("no_id_token")
        claims.problem(endpoints.issuer, clientId, attempt.nonce, clock() / 1000)?.let { return SignInResult.Failed("id_token_$it") }
        val refresh = granted.refreshToken ?: return SignInResult.Failed("no_refresh_token")
        val scopes = granted.scopes ?: code.scope?.split(' ')?.filter { it.isNotBlank() } ?: emptyList()
        val credentials =
            PlanCredentials(
                issuer = endpoints.issuer,
                subject = claims.subject.orEmpty(),
                clientId = clientId,
                hostId = attempt.hostId,
                idToken = granted.idToken,
                accessToken = granted.accessToken,
                refreshToken = refresh,
                scopes = scopes,
                expiresAtMillis = clock() + granted.expiresInSeconds * 1000,
                savedAtMillis = clock(),
            )
        // The plan scope is checked before use, not before saving: a grant without it is shown as such rather than hidden.
        store.rememberClientId(clientId)
        store.save(credentials)
        return SignInResult.Signed(credentials)
    }
}

/** Why a model-list response produced the models it did. Nothing here is a value from the body except the short, sanitized names. */
enum class DiscoveryOutcome {
    /** At least one entry with `visibility: "list"` and a `slug`. */
    MODELS,

    /** Not JSON at all. */
    INVALID_JSON,

    /** JSON, but not an object with a `models` array, which is the documented shape. */
    UNSUPPORTED_SHAPE,

    /** A `models` array with no entries. */
    EMPTY,

    /** Entries exist, none has `visibility: "list"`. */
    ALL_FILTERED_BY_VISIBILITY,

    /** Entries with `visibility: "list"` exist, none has a usable `slug`. */
    MISSING_IDENTIFIERS,
}

data class ModelDiscovery(
    val slugs: List<String>,
    val outcome: DiscoveryOutcome,
    /** Type, key names, counts and rejection counts only. Never a model name, header, credential or any part of the body. */
    val summary: String,
)

/**
 * The account's model list, read the way developers.openai.com/siwc/token-sharing-open-source/models-and-inference documents it
 * (read 2026-10-10): `GET /v1/models`, a response with a `models` array, entries kept when `visibility == "list"`, each chosen by
 * `slug`. That page gives no example body and names no other field or top-level key, so nothing else is guessed: a top-level array,
 * a `data` key, an `id` in place of `slug` or an entry with no `visibility` are all reported as what they are, not accepted.
 * The [DiscoveryOutcome] and [ModelDiscovery.summary] say which way an empty result came about.
 */
object ModelCatalog {
    fun parse(json: String): List<String> = analyze(json).slugs

    fun analyze(
        json: String,
        contentType: String? = null,
        truncated: Boolean = false,
    ): ModelDiscovery {
        val head = "content-type=${mediaType(contentType)} bytes=${json.length}${if (truncated) " truncated=true" else ""}"
        val root =
            runCatching { Json.parseToJsonElement(json) }.getOrNull()
                ?: return ModelDiscovery(emptyList(), DiscoveryOutcome.INVALID_JSON, "$head type=invalid_json")
        val type = typeOf(root)
        val keys =
            (root as? JsonObject)
                ?.keys
                ?.take(MAX_NAMES)
                ?.joinToString(",") { safe(it) }
                .orEmpty()
        val models = (root as? JsonObject)?.get("models")
        val items =
            models as? JsonArray
                ?: return ModelDiscovery(
                    emptyList(),
                    DiscoveryOutcome.UNSUPPORTED_SHAPE,
                    "$head type=$type keys=[$keys] models=${models?.let { typeOf(it) } ?: "absent"}",
                )
        val objects = items.filterIsInstance<JsonObject>()

        fun visibility(o: JsonObject) = (o["visibility"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val listed = objects.filter { visibility(it) == "list" }
        val slugs = listed.mapNotNull { o -> (o["slug"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } }
        val visibilityValues =
            objects
                .mapNotNull { visibility(it) }
                .groupingBy { safeValue(it) }
                .eachCount()
                .entries
                .take(MAX_NAMES)
                .joinToString(",") { "${it.key}:${it.value}" }
        val entryKeys =
            objects
                .firstOrNull()
                ?.keys
                ?.take(MAX_NAMES)
                ?.joinToString(",") { safe(it) }
                .orEmpty()
        val summary =
            "$head type=$type keys=[$keys] models=array entries=${items.size} nonObject=${items.size - objects.size} " +
                "visibility=[$visibilityValues] visibilityMissing=${objects.count { visibility(it) == null }} listed=${listed.size} " +
                "listedWithoutSlug=${listed.size - slugs.size} firstEntryKeys=[$entryKeys]"
        val outcome =
            when {
                items.isEmpty() -> DiscoveryOutcome.EMPTY
                listed.isEmpty() -> DiscoveryOutcome.ALL_FILTERED_BY_VISIBILITY
                slugs.isEmpty() -> DiscoveryOutcome.MISSING_IDENTIFIERS
                else -> DiscoveryOutcome.MODELS
            }
        return ModelDiscovery(slugs, outcome, summary)
    }

    private const val MAX_NAMES = 12

    private fun typeOf(e: JsonElement): String =
        when (e) {
            is JsonObject -> "object"
            is JsonArray -> "array"
            JsonNull -> "null"
            is JsonPrimitive -> if (e.isString) "string" else "primitive"
        }

    /**
     * A key name, kept only if it is short lower snake_case like every documented field. Anything else, including anything that
     * could be a token or a model name, is shown as a placeholder.
     */
    private fun safe(name: String): String = if (KEY.matches(name)) name else "?"

    /** An enum-like value such as `list` or `hide`: letters and underscores only, so an identifier or secret never matches. */
    private fun safeValue(value: String): String = if (VALUE.matches(value)) value else "?"

    private val KEY = Regex("[a-z][a-z0-9_]{0,23}")
    private val VALUE = Regex("[a-z_]{1,16}")

    /** The media type only, without parameters, and only if it looks like one. */
    private fun mediaType(header: String?): String =
        header
            ?.substringBefore(';')
            ?.trim()
            ?.lowercase()
            ?.takeIf { Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}").matches(it) } ?: "none"
}

/**
 * The whole browser sign-in: start the loopback listener first, hand the authorization URL to [openBrowser], wait for the
 * callback (state-checked), and complete. The listener is closed whatever happens. The platform supplies only how to open a URL.
 */
class SignInFlow(
    private val store: CredentialStore,
    private val tokens: TokenClient,
    private val endpoints: PlanEndpoints,
    private val verifier: IdTokenVerifier,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(
        openBrowser: (String) -> Unit,
        timeoutMillis: Long = 180_000,
    ): SignInResult =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            LoopbackListener().use { listener ->
                val attempt = AuthorizationAttempt.begin(store.issuedClientId(), store.hostId(), listener.port)
                openBrowser(attempt.url(endpoints))
                val callback = listener.await(attempt.state, timeoutMillis)
                SignIn(tokens, store, endpoints, verifier, clock).complete(attempt, callback)
            }
        }
}
