package com.thalia.app.m3a

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Handler
import android.os.Looper
import com.thalia.voicecheckins.gemini.AudioUnavailableException

/**
 * [AudioPlatform] on [AudioManager], [AudioRecord], [AudioTrack] and [AcousticEchoCanceler].
 *
 * Routing uses the communication device API, which from Android 12 replaces the speakerphone
 * switch. This app's request is the only routing change made, so withdrawing it restores exactly
 * the routing that was in place before; the speakerphone switch itself is never touched.
 */
internal class AndroidAudioPlatform(
    context: Context,
) : AudioPlatform {
    private val appContext = context.applicationContext
    private val audioManager: AudioManager =
        appContext.getSystemService(AudioManager::class.java) ?: throw AudioUnavailableException("No audio service.")
    private val mainHandler = Handler(Looper.getMainLooper())

    private val voiceAttributes: AudioAttributes =
        AudioAttributes
            .Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

    override fun requestTransientFocus(onLoss: () -> Unit): FocusHold? {
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(voiceAttributes)
                .setAcceptsDelayedFocusGain(false)
                .setWillPauseWhenDucked(false)
                .setOnAudioFocusChangeListener({ change ->
                    // A conversation cannot be paused and resumed, so any loss ends it.
                    if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) onLoss()
                }, mainHandler)
                .build()
        if (audioManager.requestAudioFocus(request) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) return null
        return FocusHold { audioManager.abandonAudioFocusRequest(request) }
    }

    override fun mode(): Int = audioManager.mode

    override fun setMode(mode: Int) {
        audioManager.mode = mode
    }

    override fun routeToSpeaker(): Boolean {
        val speaker =
            audioManager.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?: return false
        return audioManager.setCommunicationDevice(speaker)
    }

    override fun clearRoute() {
        audioManager.clearCommunicationDevice()
    }

    // The permission is checked just above the call, which lint cannot see through the early throw.
    @SuppressLint("MissingPermission")
    override fun openRecorder(): Recorder {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw AudioUnavailableException("Microphone permission is not granted.")
        }
        val minimum = AudioRecord.getMinBufferSize(CAPTURE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw AudioUnavailableException("The microphone does not support 16 kHz mono PCM.")
        val record =
            AudioRecord
                .Builder()
                .setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(CAPTURE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setBufferSizeInBytes(maxOf(minimum * 2, PlatformAudioSession.CAPTURE_CHUNK_BYTES * 4))
                .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw AudioUnavailableException("The microphone could not be initialised.")
        }
        return object : Recorder {
            override val audioSessionId: Int get() = record.audioSessionId

            override fun start() {
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw AudioUnavailableException("The microphone did not start recording.")
                }
            }

            override fun read(buffer: ByteArray): Int = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)

            override fun stop() {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            }

            override fun release() = record.release()
        }
    }

    override fun openPlayer(): Player {
        val minimum = AudioTrack.getMinBufferSize(PLAYBACK_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minimum <= 0) throw AudioUnavailableException("Playback does not support 24 kHz mono PCM.")
        val track =
            AudioTrack
                .Builder()
                .setAudioAttributes(voiceAttributes)
                .setAudioFormat(
                    AudioFormat
                        .Builder()
                        .setSampleRate(PLAYBACK_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                ).setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(maxOf(minimum, PlatformAudioSession.PLAYBACK_SLICE_BYTES * 4))
                .build()
        if (track.state != AudioTrack.STATE_INITIALIZED) {
            track.release()
            throw AudioUnavailableException("Playback could not be initialised.")
        }
        return object : Player {
            override fun play() = track.play()

            override fun write(
                bytes: ByteArray,
                offset: Int,
                length: Int,
            ): Int = track.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING)

            override fun pause() = track.pause()

            override fun flush() = track.flush()

            override fun stop() {
                if (track.playState != AudioTrack.PLAYSTATE_STOPPED) track.stop()
            }

            override fun release() = track.release()
        }
    }

    override fun attachEchoCanceler(audioSessionId: Int): AutoCloseable? {
        if (!AcousticEchoCanceler.isAvailable()) return null
        val canceler = AcousticEchoCanceler.create(audioSessionId) ?: return null
        if (canceler.setEnabled(true) != AcousticEchoCanceler.SUCCESS) {
            canceler.release()
            return null
        }
        return AutoCloseable { canceler.release() }
    }

    private companion object {
        const val CAPTURE_RATE = 16_000
        const val PLAYBACK_RATE = 24_000
    }
}
