package com.thalia.app.conversation

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Slice 4B. Speech recognition through the platform's explicitly on-device recognizer.
 *
 * It never uses `SpeechRecognizer.createSpeechRecognizer`, which may be a cloud service, and it never calls
 * `triggerModelDownload`: a missing language pack is reported and left to the person. The language is always the exact tag
 * the person selected, and its support is queried again for that request before the microphone opens. It keeps no audio and
 * writes nothing; the one recognised phrase is handed to the caller and dropped. Everything runs on the main thread, as
 * `SpeechRecognizer` requires.
 */
internal class OnDeviceSpeechSource(
    private val context: Context,
) : SpeechSource {
    override val deviceTag: String = Locale.getDefault().toLanguageTag()
    private var recognizer: SpeechRecognizer? = null
    private var cancelling = false
    private var current: (() -> Unit)? = null

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun query(
        recognizer: SpeechRecognizer,
        tag: String,
        onResult: (List<String>?) -> Unit,
    ) = recognizer.checkRecognitionSupport(
        speechIntent(tag),
        context.mainExecutor,
        object : RecognitionSupportCallback {
            override fun onSupportResult(recognitionSupport: RecognitionSupport) = onResult(recognitionSupport.installedOnDeviceLanguages)

            override fun onError(error: Int) = onResult(null)
        },
    )

    override suspend fun readiness(): SpeechReadiness {
        val sdk = Build.VERSION.SDK_INT
        // Below Android 13 the installed-language query does not exist, so on-device cannot be confirmed.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return SpeechReadiness.Unavailable(voiceUnavailableReason(sdk, false, emptyList()).orEmpty())
        }
        val available = SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        voiceUnavailableReason(sdk, available, emptyList())?.let { return SpeechReadiness.Unavailable(it) }
        val created = recognizer ?: SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also { recognizer = it }
        val installed =
            suspendCancellableCoroutine<List<String>?> { continuation ->
                query(created, deviceTag) { if (continuation.isActive) continuation.resume(it) }
            }
        voiceUnavailableReason(sdk, true, installed)?.let { return SpeechReadiness.Unavailable(it) }
        return SpeechReadiness.Available(installed.orEmpty())
    }

    override fun listen(
        languageTag: String,
        onDone: (SpeechOutcome) -> Unit,
    ) {
        val active = recognizer
        // A recognizer exists only after readiness() passed, which needs Android 13; the check also tells lint so.
        if (active == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            onDone(SpeechOutcome.Failed("Voice input is not ready."))
            return
        }
        cancelling = false
        var finished = false

        fun finish(outcome: SpeechOutcome) {
            if (finished) return
            finished = true
            current = null
            onDone(outcome)
        }
        current = { finish(SpeechOutcome.Cancelled) }
        // Validate support for this exact request first. The microphone opens only if the selected tag is installed.
        query(active, languageTag) { installed ->
            when {
                finished -> Unit
                cancelling -> finish(SpeechOutcome.Cancelled)
                !languageInstalled(installed, languageTag) ->
                    finish(
                        SpeechOutcome.Failed("The on-device speech language ($languageTag) is not installed. Thalia does not download it."),
                    )
                else -> {
                    active.setRecognitionListener(listener(::finish))
                    active.startListening(speechIntent(languageTag))
                }
            }
        }
    }

    private fun listener(finish: (SpeechOutcome) -> Unit) =
        object : RecognitionListener {
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                finish(
                    if (text.isNullOrBlank()) {
                        SpeechOutcome.Failed(speechErrorMessage(SpeechRecognizer.ERROR_NO_MATCH))
                    } else {
                        SpeechOutcome.Heard(text)
                    },
                )
            }

            override fun onError(error: Int) =
                finish(if (cancelling) SpeechOutcome.Cancelled else SpeechOutcome.Failed(speechErrorMessage(error)))

            override fun onReadyForSpeech(params: Bundle?) = Unit

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onPartialResults(partialResults: Bundle?) = Unit

            override fun onEvent(
                eventType: Int,
                params: Bundle?,
            ) = Unit
        }

    override fun cancel() {
        cancelling = true
        recognizer?.cancel()
        // cancel() does not promise a callback, and the screen must not stay on "listening", so settle it here.
        current?.invoke()
    }

    override fun release() {
        recognizer?.destroy()
        recognizer = null
    }
}
