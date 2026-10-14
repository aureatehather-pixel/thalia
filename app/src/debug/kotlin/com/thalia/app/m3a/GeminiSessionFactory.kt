package com.thalia.app.m3a

import android.content.Context
import android.util.Log
import com.thalia.app.secret.ApiKeyStore
import com.thalia.voicecheckins.domain.CheckInType
import com.thalia.voicecheckins.gemini.GeminiApiKey
import com.thalia.voicecheckins.gemini.GeminiLiveVoiceSessionClient
import com.thalia.voicecheckins.voice.VoiceSessionConfig

/**
 * Builds one Gemini Live session for the M3A screen: the saved key, the Android audio and the
 * fixed, synthetic content ADR 0008 decision 5 allows. Building it opens nothing.
 */
internal class GeminiSessionFactory(
    context: Context,
    private val apiKeys: ApiKeyStore,
) : LiveSessionFactory {
    private val appContext = context.applicationContext

    override fun prepare(
        sessionId: String,
        callbacks: AudioCallbacks,
    ): Preparation {
        val apiKey =
            try {
                apiKeys.load()?.let(::GeminiApiKey)
            } catch (e: IllegalArgumentException) {
                null
            } ?: return Preparation.Refused(StartRefusal.KEY_UNREADABLE)
        val client =
            GeminiLiveVoiceSessionClient(
                apiKey = apiKey,
                audioIo = AndroidAudioIo(AndroidAudioPlatform(appContext), callbacks.onFocusLost, callbacks.onOpened),
                systemInstruction = SYSTEM_INSTRUCTION,
                openingPrompt = OPENING_PROMPT,
                // Every line the client logs is already redacted, and carries no audio or payload.
                log = { line -> Log.i(LOG_TAG, line) },
            )
        val config =
            VoiceSessionConfig(
                occurrenceId = sessionId,
                checkInType = CheckInType.OPENING,
                contextNotes = listOf(SYNTHETIC_CONTEXT),
            )
        return Preparation.Ready(client.start(config))
    }

    companion object {
        const val SYSTEM_INSTRUCTION: String =
            "You are Thalia, a voice assistant. This session is a connection test of live voice, not a real " +
                "check-in. Reply in one or two short sentences. Do not ask for, and do not encourage the user " +
                "to share, any personal information."

        /** Fixed and synthetic. It holds no goal, plan, calendar entry, memory or other personal data. */
        const val SYNTHETIC_CONTEXT: String =
            "Synthetic test context: it is a weekday morning and the user is testing the voice connection."

        const val OPENING_PROMPT: String = "Please greet me briefly and ask if you can hear me well."

        private const val LOG_TAG = "ThaliaM3A"
    }
}
