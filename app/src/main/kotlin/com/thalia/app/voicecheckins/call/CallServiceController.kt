package com.thalia.app.voicecheckins.call

import com.thalia.app.voicecheckins.devtools.DevTestRing
import com.thalia.app.voicecheckins.devtools.DevTestRingState
import com.thalia.voicecheckins.domain.CheckInType
import kotlinx.coroutines.flow.merge

/** What the foreground service was asked to do. */
internal sealed interface CallServiceCommand {
    /** Start ringing for one occurrence. A button leaves the id at the manual-trigger default. */
    data class Ring(
        val checkInType: CheckInType,
        val occurrenceId: String = CheckInCallCoordinator.MANUAL_TRIGGER_OCCURRENCE_ID,
    ) : CallServiceCommand

    data object Decline : CallServiceCommand

    data object EndCall : CallServiceCommand

    /** Development only: arm one delayed test call. See [DevTestRing]. */
    data class ArmTestRing(
        val checkInType: CheckInType,
    ) : CallServiceCommand

    /** Development only: disarm the delayed test call. */
    data object CancelTestRing : CallServiceCommand

    /** An intent this service does not recognise. It still counts as a start. */
    data object Unrecognised : CallServiceCommand
}

/**
 * The Android side effects the service performs, and nothing else.
 *
 * Everything the service does to the outside world is one of these six calls, which is
 * what lets [CallServiceController] decide *when* to do them without Android present.
 *
 * Each `show` call also settles the CPU: [showArmed] holds it awake for the armed window,
 * and [showRinging], [showOngoing] and [shutDown] release that hold.
 */
internal interface CallServiceHost {
    /** Enter, or update, the foreground notification while a test call is armed. */
    fun showArmed(
        checkInType: CheckInType,
        fireAtMillis: Long,
    )

    /** Enter, or update, the foreground notification for a ringing call. */
    fun showRinging(checkInType: CheckInType)

    /** Enter, or update, the foreground notification for an answered call. */
    fun showOngoing(checkInType: CheckInType)

    /** Begin the repeating ringtone and vibration. */
    fun startRingAlert()

    /** Silence the ringtone and vibration. Safe when nothing is playing. */
    fun stopRingAlert()

    /** Remove the foreground notification and stop the service. Safe to repeat. */
    fun shutDown()
}

/**
 * Decides what the call service presents, and when it stops.
 *
 * This class is the single owner of the notification, the ring alert and the service's
 * lifetime. The service forwards commands and state to it and does nothing else, so
 * there is exactly one place where those decisions are made and one place to test them.
 *
 * Three rules exist because of how Android creates a service, and all are tested:
 *
 * - **Nothing is presented before the first command.** A service is created, and starts
 *   observing state, before its start command has been delivered. At that moment the
 *   coordinator is still idle, or still showing the previous call's ended screen, and
 *   reading that as "the call is over" stopped the service before it could ring.
 *   Coordinator state is therefore ignored until a command has been handled, and the
 *   command itself is what first establishes the presentation.
 * - **A presentation is applied once, when it changes.** Answering a call and every
 *   transcript line after it produce new state, but only the first ongoing state changes
 *   what the notification shows. Re-posting an unchanged notification is what made the
 *   old service ring twice and restart its own call timer.
 * - **An armed test call keeps the service alive until it rings.** The service is what
 *   holds the process and the CPU through a locked, dark screen, so it must not stop in
 *   the moment between the timer firing and the call starting to ring. A ringing or
 *   active call always outranks an armed one, and the timer rings before it clears.
 */
internal class CallServiceController(
    private val coordinator: CheckInCallCoordinator,
    private val testRing: DevTestRing,
    private val host: CallServiceHost,
) {
    private var armed = false

    /** What is currently on screen. Null until the first command has been handled. */
    private var presented: CallPresentation? = null

    /**
     * What the command being handled has already caused. Coordinator state can reach
     * [onStateChanged] synchronously, from inside the coordinator call a command makes, so
     * the command's own follow-up must ask what has happened rather than assume.
     */
    private var stopRequestedByThisCommand = false
    private var enteredForegroundByThisCommand = false

    /**
     * Watch the call and the armed test call, and re-present whenever either changes.
     *
     * The service and the tests both run exactly this, so the wiring that is tested is the
     * wiring that ships.
     */
    suspend fun observe() {
        merge(coordinator.state, testRing.state).collect { onStateChanged() }
    }

    /** Apply one start command from Android, then present whatever state results. */
    fun onCommand(command: CallServiceCommand) {
        armed = true
        stopRequestedByThisCommand = false
        enteredForegroundByThisCommand = false
        when (command) {
            is CallServiceCommand.Ring -> coordinator.ring(command.checkInType, command.occurrenceId)
            CallServiceCommand.Decline -> coordinator.decline()
            CallServiceCommand.EndCall -> coordinator.endCall()
            is CallServiceCommand.ArmTestRing -> testRing.arm(command.checkInType)
            CallServiceCommand.CancelTestRing -> testRing.cancel()
            CallServiceCommand.Unrecognised -> Unit
        }
        val presentation = currentPresentation()
        present(presentation, afterCommand = true)

        // Every startForegroundService() call must be answered by a startForeground(),
        // or Android kills the process. A repeat Ring or Arm changes no state, so nothing
        // above answered it. The notifications are silent and posted only-alert-once, so
        // re-posting one is harmless.
        val startedForeground = command is CallServiceCommand.Ring || command is CallServiceCommand.ArmTestRing
        if (startedForeground && !enteredForegroundByThisCommand) reassertForeground(presentation)
    }

    /** The call or the armed test call changed. Ignored until a command has armed the controller. */
    fun onStateChanged() {
        if (armed) present(currentPresentation(), afterCommand = false)
    }

    /**
     * The service is being destroyed, by us or by the system.
     *
     * Silences the alert unconditionally, because a ringtone that outlives its service is
     * the worst possible failure for this feature. An armed test call is cancelled, since
     * nothing is left to carry it and it would otherwise ring into a service-less process.
     * If the call is somehow still ringing or active, nothing is carrying it any more, so
     * it is ended rather than left claiming to be in progress.
     */
    fun onDestroy() {
        // Disarm first: ending the call below produces state, and a service that is
        // already being destroyed has nothing left to present or to stop.
        armed = false
        host.stopRingAlert()
        testRing.cancel()
        if (coordinator.state.value.needsForegroundService) coordinator.interrupt()
        presented = null
    }

    /** A call that is ringing or active outranks an armed test call. */
    private fun currentPresentation(): CallPresentation {
        val call = coordinator.state.value.toPresentation()
        if (call != CallPresentation.None) return call
        return when (val test = testRing.state.value) {
            is DevTestRingState.Armed -> CallPresentation.Armed(test.checkInType, test.fireAtMillis)
            DevTestRingState.Off -> CallPresentation.None
        }
    }

    private fun present(
        next: CallPresentation,
        afterCommand: Boolean,
    ) {
        // A command that leaves nothing to carry must always end the service, even if the
        // presentation was already "nothing": a later start command can cancel an earlier
        // stop request. Once per command is enough, so a stop that the command's own state
        // change has already caused is not asked for a second time.
        val repeatShutDown = afterCommand && next == CallPresentation.None && !stopRequestedByThisCommand
        if (next == presented && !repeatShutDown) return
        presented = next

        when (next) {
            CallPresentation.None -> {
                host.stopRingAlert()
                host.shutDown()
                stopRequestedByThisCommand = true
            }

            is CallPresentation.Armed -> {
                host.showArmed(next.checkInType, next.fireAtMillis)
                enteredForegroundByThisCommand = true
            }

            is CallPresentation.Ringing -> {
                host.showRinging(next.checkInType)
                host.startRingAlert()
                enteredForegroundByThisCommand = true
            }

            is CallPresentation.Ongoing -> {
                host.stopRingAlert()
                host.showOngoing(next.checkInType)
                enteredForegroundByThisCommand = true
            }
        }
    }

    private fun reassertForeground(presentation: CallPresentation) {
        when (presentation) {
            CallPresentation.None -> Unit
            is CallPresentation.Armed -> host.showArmed(presentation.checkInType, presentation.fireAtMillis)
            is CallPresentation.Ringing -> host.showRinging(presentation.checkInType)
            is CallPresentation.Ongoing -> host.showOngoing(presentation.checkInType)
        }
    }
}
