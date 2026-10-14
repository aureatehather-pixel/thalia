package com.thalia.app.voicecheckins.call

import android.content.Context
import android.media.AudioAttributes
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The sound and vibration of an incoming call, repeating until [stop].
 *
 * The notification channel is deliberately silent and this class is the only thing that
 * makes noise. Two reasons. A notification sound plays once and then stops, which does
 * not read as a ringing phone. And a sound owned by the notification can only be
 * silenced by Android's notification service reacting to an update or a cancel, where a
 * sound owned here is silenced by the app calling [stop], directly and synchronously.
 * For a call that must go quiet the instant it is answered, declined or timed out, the
 * second is the one that can be relied on and tested.
 *
 * Playback uses the ringtone usage, so the ringer switch and Do Not Disturb apply to it
 * the way they apply to a phone call. Whether the phone rings is the device's decision.
 *
 * [start] and [stop] are both safe to call repeatedly and in any order.
 */
internal class CallRingAlert(
    private val context: Context,
) {
    private var ringtone: Ringtone? = null
    private var vibrating = false

    fun start() {
        startSound()
        startVibration()
    }

    fun stop() {
        ringtone?.stop()
        ringtone = null
        if (vibrating) {
            vibrator()?.cancel()
            vibrating = false
        }
    }

    private fun startSound() {
        if (ringtone != null) return
        ringtone =
            RingtoneManager
                .getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                ?.apply {
                    audioAttributes =
                        AudioAttributes
                            .Builder()
                            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    isLooping = true
                    play()
                }
    }

    private fun startVibration() {
        if (vibrating) return
        val vibrator = vibrator() ?: return
        // Ring, pause, ring. Repeats from index 0 until cancelled, the way a call does.
        vibrator.vibrate(VibrationEffect.createWaveform(RING_PATTERN, 0))
        vibrating = true
    }

    private fun vibrator(): Vibrator? = context.getSystemService(VibratorManager::class.java)?.defaultVibrator

    private companion object {
        val RING_PATTERN = longArrayOf(0, 800, 700)
    }
}
