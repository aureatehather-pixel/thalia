package com.thalia.openai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Why a spoken reply could not be made. None of these carries text from the service. */
enum class SpeechFailure {
    /** No key saved, so no request was made. */
    NO_KEY,

    /** The text was empty or longer than [CartesiaSpeechClient.MAX_CHARACTERS]. Nothing was sent. */
    BAD_TEXT,

    NETWORK,

    /** 401 or 403: the key was not accepted. */
    AUTH,

    /** 402 or 429: credits used up, or rate limited. */
    LIMITED,

    /** 400 or 422: the request was refused as malformed (for example the voice or model). */
    REJECTED,
    SERVER,

    /** The reply had no audio. */
    UNREADABLE,
}

sealed interface SpeechResult {
    /** All audio was handed on. [characters] were submitted; [firstAudioMillis] is request start to the first audio chunk. */
    class Streamed(
        val characters: Int,
        val firstAudioMillis: Long,
    ) : SpeechResult

    /** The listener asked to stop, so the connection was closed early. */
    class Stopped(
        val characters: Int,
    ) : SpeechResult

    data class Failed(
        val failure: SpeechFailure,
        val httpStatus: Int? = null,
    ) : SpeechResult
}

/**
 * Cartesia text to speech, model `sonic-3.6`, voice Ella (Caring Scout), as one `POST /tts/bytes` whose response body is read
 * while it arrives: raw 16 bit little-endian mono PCM at 24 kHz, handed chunk by chunk to [onAudio] so playback can begin before
 * the reply is fully generated. The key goes in the Authorization header only. One network attempt per call, no resend.
 * [onAudio] returns false to stop (the connection is closed).
 *
 * Shapes follow docs.cartesia.ai (read 2026-10-12): `/tts/bytes`, `Cartesia-Version: 2026-08-14`, `output_format` raw
 * `pcm_s16le`. Exercised here against a local fake server only.
 */
class CartesiaSpeechClient(
    private val apiKey: () -> String?,
    private val client: OkHttpClient = defaultClient(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val model: String = DEFAULT_MODEL,
    private val voiceId: String = DEFAULT_VOICE_ID,
    private val now: () -> Long = System::nanoTime,
) {
    suspend fun synthesize(
        text: String,
        onAudio: (ByteArray, Int) -> Boolean,
    ): SpeechResult {
        val clean = text.trim()
        if (clean.isEmpty() || clean.length > MAX_CHARACTERS) return SpeechResult.Failed(SpeechFailure.BAD_TEXT)
        val key = apiKey() ?: return SpeechResult.Failed(SpeechFailure.NO_KEY)
        val body =
            buildJsonObject {
                put("model_id", model)
                put("transcript", clean)
                putJsonObject("voice") { put("id", voiceId) }
                putJsonObject("output_format") {
                    put("container", "raw")
                    put("encoding", "pcm_s16le")
                    put("sample_rate", SAMPLE_RATE)
                }
                put("language", "en")
            }
        val http =
            Request
                .Builder()
                .url("$baseUrl/tts/bytes")
                .header("Authorization", "Bearer $key")
                .header("Cartesia-Version", API_VERSION)
                .post(body.toString().toRequestBody(JSON))
                .build()
        val attempts = AtomicInteger()
        val call =
            client
                .newBuilder()
                .retryOnConnectionFailure(false)
                .followRedirects(false)
                .followSslRedirects(false)
                .addNetworkInterceptor { chain ->
                    if (attempts.incrementAndGet() > 1) throw IOException("a second attempt for one request was refused")
                    chain.proceed(chain.request())
                }.build()
                .newCall(http)
        return withContext(Dispatchers.IO) {
            suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { call.cancel() }
                continuation.resumeWith(Result.success(read(call, clean.length, onAudio)))
            }
        }
    }

    private fun read(
        call: okhttp3.Call,
        characters: Int,
        onAudio: (ByteArray, Int) -> Boolean,
    ): SpeechResult {
        val started = now()
        var first = -1L
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) return failed(response.code)
                val source = response.body.byteStream()
                val buffer = ByteArray(CHUNK)
                var carry = -1
                var total = 0L
                while (true) {
                    val offset = if (carry >= 0) 1 else 0
                    if (carry >= 0) buffer[0] = carry.toByte()
                    val n = source.read(buffer, offset, buffer.size - offset)
                    if (n < 0) break
                    val have = offset + n
                    val usable = have - (have % 2)
                    carry = if (have % 2 == 1) buffer[have - 1].toInt() and 0xFF else -1
                    if (usable == 0) continue
                    if (first < 0) first = (now() - started) / 1_000_000
                    total += usable
                    if (!onAudio(buffer.copyOf(usable), usable)) {
                        call.cancel()
                        return SpeechResult.Stopped(characters)
                    }
                }
                return if (total == 0L) SpeechResult.Failed(SpeechFailure.UNREADABLE) else SpeechResult.Streamed(characters, first)
            }
        } catch (e: IOException) {
            return if (call.isCanceled()) SpeechResult.Stopped(characters) else SpeechResult.Failed(SpeechFailure.NETWORK)
        }
    }

    private fun failed(code: Int): SpeechResult.Failed =
        SpeechResult.Failed(
            when {
                code == 401 || code == 403 -> SpeechFailure.AUTH
                code == 402 || code == 429 -> SpeechFailure.LIMITED
                code in 500..599 -> SpeechFailure.SERVER
                else -> SpeechFailure.REJECTED
            },
            code,
        )

    companion object {
        const val DEFAULT_MODEL = "sonic-3.6"

        /** Ella, Caring Scout. */
        const val DEFAULT_VOICE_ID = "2a12b36c-7f9b-4c3a-9f7a-72731b15323a"
        const val DEFAULT_BASE_URL = "https://api.cartesia.ai"
        const val API_VERSION = "2026-08-14"
        const val SAMPLE_RATE = 24_000

        /** Replies are brief by design; a longer text is never sent. */
        const val MAX_CHARACTERS = 500
        private const val CHUNK = 8192
        private val JSON = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
