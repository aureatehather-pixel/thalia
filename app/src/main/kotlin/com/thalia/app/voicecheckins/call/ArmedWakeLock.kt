package com.thalia.app.voicecheckins.call

import android.content.Context
import android.os.PowerManager
import kotlin.time.Duration

/**
 * Keeps the CPU awake while a development test call is armed, so its timer can fire
 * with the screen off.
 *
 * A coroutine delay counts uptime, and a phone with a dark screen and nothing holding it
 * awake suspends, which stops uptime. Without this, a ten second delay started just
 * before locking the phone could fire minutes late, when something else next woke the
 * device, and the test would say nothing about locked-screen ringing.
 *
 * Every hold has a timeout, so a bug elsewhere can leave the phone awake for seconds and
 * never for longer. It is released explicitly the moment the armed window ends.
 */
internal class ArmedWakeLock(
    context: Context,
) {
    private val lock: PowerManager.WakeLock? =
        context
            .getSystemService(PowerManager::class.java)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
            ?.apply { setReferenceCounted(false) }

    /** Hold the CPU awake for at most [timeout]. Calling again restarts the timeout. */
    fun hold(timeout: Duration) {
        lock?.acquire(timeout.inWholeMilliseconds)
    }

    /** Release the hold. Safe when nothing is held. */
    fun release() {
        lock?.takeIf { it.isHeld }?.release()
    }

    private companion object {
        const val TAG = "thalia:test-ring-armed"
    }
}
