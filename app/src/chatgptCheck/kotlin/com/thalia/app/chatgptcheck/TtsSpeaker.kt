package com.thalia.app.chatgptcheck

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** A voice as the engine reports it, reduced to what choosing needs. */
internal data class VoiceChoice(
    val name: String,
    val locale: String,
    val quality: Int,
    val needsNetwork: Boolean,
    val installed: Boolean,
)

internal object VoiceSelection {
    /** Offline, installed voices in [language], best quality first. A voice that needs a network is never offered: it would send the text away. */
    fun usable(
        voices: List<VoiceChoice>,
        language: String = "en",
    ): List<VoiceChoice> =
        voices
            .filter { !it.needsNetwork && it.installed && it.locale.lowercase().startsWith(language) }
            .sortedWith(compareByDescending<VoiceChoice> { it.quality }.thenBy { it.name })
}

/**
 * Local spoken replies. The platform's `TextToSpeech`, offline voices only. Lines are queued one after another; [stop] drops
 * everything. It speaks only what it is handed, only in the foreground, and reports start, end and error with timing so the
 * audition can record how long the first sound took.
 */
internal class TtsSpeaker(
    context: Context,
    private val onEvent: (String) -> Unit,
) {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var requestedAt = 0L

    init {
        tts =
            TextToSpeech(context.applicationContext) { status ->
                ready = status == TextToSpeech.SUCCESS
                onEvent(if (ready) "tts ready" else "tts failed to start")
                if (ready) {
                    tts?.setAudioAttributes(
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build(),
                    )
                    tts?.setOnUtteranceProgressListener(
                        object : UtteranceProgressListener() {
                            override fun onStart(utteranceId: String) =
                                onEvent("started $utteranceId after ${System.currentTimeMillis() - requestedAt} ms")

                            override fun onDone(utteranceId: String) = onEvent("done $utteranceId")

                            @Deprecated("Deprecated in Java")
                            override fun onError(utteranceId: String) = onEvent("error $utteranceId")

                            override fun onError(
                                utteranceId: String,
                                errorCode: Int,
                            ) = onEvent("error $utteranceId code $errorCode")
                        },
                    )
                }
            }
    }

    val isReady get() = ready

    fun voices(): List<VoiceChoice> =
        runCatching {
            tts
                ?.voices
                .orEmpty()
                .map {
                    VoiceChoice(
                        it.name,
                        it.locale.toLanguageTag(),
                        it.quality,
                        it.isNetworkConnectionRequired,
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features,
                    )
                }
        }.getOrDefault(emptyList())

    /** Chooses [name] if it is an offline installed voice; otherwise the best one in English, or the engine's default. */
    fun choose(name: String?) {
        val usable = VoiceSelection.usable(voices())
        val pick = usable.firstOrNull { it.name == name } ?: usable.firstOrNull() ?: return
        tts?.voices?.firstOrNull { it.name == pick.name }?.let { tts?.voice = it }
        if (tts?.voice == null) tts?.language = Locale.US
    }

    fun speak(lines: List<String>) {
        if (!ready || lines.isEmpty()) return
        requestedAt = System.currentTimeMillis()
        lines.forEachIndexed { i, line ->
            tts?.speak(line, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "u$i")
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
    }
}
