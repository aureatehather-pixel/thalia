package com.thalia.app.voicecheckins.devtools

import com.thalia.app.voicecheckins.call.CheckInCallCoordinator
import com.thalia.app.voicecheckins.call.needsForegroundService
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Whether a delayed test call is waiting to ring. */
sealed interface DevTestRingState {
    /** Nothing is armed. */
    data object Off : DevTestRingState

    /** One test call will ring at [fireAtMillis], on the clock the timer was given. */
    data class Armed(
        val checkInType: CheckInType,
        val fireAtMillis: Long,
    ) : DevTestRingState
}

/**
 * A development-only, one-shot, in-process delay before a test call rings.
 *
 * It exists for one reason: the M1 question is whether a *locked* phone raises the
 * full-screen call, and the Ring buttons cannot ask it. Tapping one rings immediately,
 * while the screen is on, so locking the phone afterwards tests a call that started
 * unlocked. This lets the tester arm a call, lock the phone, and have it start while the
 * phone is already locked and dark.
 *
 * What it is not:
 *
 * - **Not scheduling.** It is one coroutine delay of ten seconds, held in memory. There is
 *   no AlarmManager, no WorkManager, no persistence, no recurrence, and it does not
 *   survive the process. M2's scheduling is a different thing built a different way, and
 *   nothing here is a step towards it.
 * - **Not self-sufficient.** A coroutine delay does not fire in a process that Android has
 *   frozen or a device that has gone to sleep. What keeps this reliable with the screen
 *   off is [com.thalia.app.voicecheckins.call.CheckInCallService], which holds the process
 *   in the foreground and the CPU awake for exactly the armed window. This class only
 *   knows when to ring.
 *
 * At most one test call can be armed. Arming again while armed, or while a call is already
 * ringing or in progress, does nothing, so test calls cannot stack.
 *
 * @param delay how long after arming the call rings.
 * @param clock milliseconds, only used to tell the UI and the notification when the call
 *   will ring. Injected so tests can run on virtual time.
 */
class DevTestRing(
    private val scope: CoroutineScope,
    private val coordinator: CheckInCallCoordinator,
    private val delay: Duration = DEFAULT_DELAY,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val _state = MutableStateFlow<DevTestRingState>(DevTestRingState.Off)
    val state: StateFlow<DevTestRingState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Arm one test call. Returns false, and changes nothing, if one is already armed or
     * if a call is already ringing or in progress.
     */
    fun arm(checkInType: CheckInType): Boolean {
        if (_state.value is DevTestRingState.Armed) return false
        if (coordinator.state.value.needsForegroundService) return false

        _state.value = DevTestRingState.Armed(checkInType, clock() + delay.inWholeMilliseconds)
        job =
            scope.launch {
                delay(delay)
                fire(checkInType)
            }
        return true
    }

    /** Disarm. Does nothing if nothing is armed, and never touches a call already ringing. */
    fun cancel() {
        job?.cancel()
        job = null
        _state.value = DevTestRingState.Off
    }

    private fun fire(checkInType: CheckInType) {
        job = null
        // Ring first, then clear. The service is carrying the armed window, and it stops
        // itself when it sees nothing left to carry. Clearing first would show it a moment
        // with neither an armed call nor a ringing one, and it would shut down before the
        // phone could ring.
        coordinator.ring(checkInType)
        _state.value = DevTestRingState.Off
    }

    companion object {
        /** Long enough to press the power button and put the phone down. */
        val DEFAULT_DELAY: Duration = 10.seconds
    }
}
