package com.thalia.app.voicecheckins.call

import com.thalia.voicecheckins.voice.VoiceSessionClient
import com.thalia.voicecheckins.voice.VoiceSessionConfig
import com.thalia.voicecheckins.voice.VoiceSessionHandle
import com.thalia.voicecheckins.voice.fake.FakeVoiceSessionClient

/**
 * A client that remembers what it was asked to open, then behaves like the fake.
 *
 * For tests that need to see which occurrence a session was started for.
 */
internal class RecordingVoiceSessionClient(
    private val delegate: VoiceSessionClient = FakeVoiceSessionClient(),
) : VoiceSessionClient {
    val configs = mutableListOf<VoiceSessionConfig>()

    override fun start(config: VoiceSessionConfig): VoiceSessionHandle {
        configs += config
        return delegate.start(config)
    }
}
