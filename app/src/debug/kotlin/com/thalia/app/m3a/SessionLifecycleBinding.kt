package com.thalia.app.m3a

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner

/**
 * A session runs only while the M3A screen is visible. When the screen stops, because it was
 * left, covered, or the app went to the background, the session ends as `ENDED_BY_SYSTEM`.
 * Destroying the screen cancels its scope, and with it the session's collector, whose cleanup
 * releases everything the session holds.
 */
class SessionLifecycleBinding(
    private val controller: LiveTestController,
) : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        controller.refresh()
    }

    override fun onStop(owner: LifecycleOwner) {
        controller.endBySystem()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        controller.endBySystem()
    }
}
