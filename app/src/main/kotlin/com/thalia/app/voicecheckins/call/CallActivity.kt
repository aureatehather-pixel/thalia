package com.thalia.app.voicecheckins.call

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thalia.app.ThaliaApplication
import com.thalia.app.conversation.CallHandoff
import com.thalia.app.ui.ThaliaTheme
import com.thalia.app.voicecheckins.call.ui.CallSurface
import com.thalia.voicecheckins.domain.CheckInType

/**
 * The incoming-call surface, and the active call that follows it.
 *
 * This is the activity Android raises from the notification's full-screen intent, so it
 * has to be able to appear over the keyguard on a sleeping phone. `showWhenLocked` and
 * `turnScreenOn` are set both in the manifest and here: the manifest attributes cover
 * the launch, and the programmatic calls cover a relaunch into an existing instance.
 */
class CallActivity : ComponentActivity() {
    private val coordinator: CheckInCallCoordinator
        get() = (application as ThaliaApplication).callCoordinator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()
        handleAnswerRequest(intent)

        setContent {
            ThaliaTheme {
                val state by coordinator.state.collectAsStateWithLifecycle()
                CallSurface(
                    state = state,
                    onAnswer = ::answer,
                    onDecline = {
                        coordinator.decline()
                        finishAndRemoveTask()
                    },
                    onEndCall = { coordinator.endCall() },
                    onDismiss = {
                        coordinator.dismiss()
                        finishAndRemoveTask()
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        showOverLockScreen()
        handleAnswerRequest(intent)
    }

    private fun handleAnswerRequest(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_ANSWER, false) == true) answer()
    }

    /** True while a request to unlock is open, so a second tap or a repeated delivery cannot start another. */
    private var unlockPending = false

    private fun answer() {
        val ringing = coordinator.state.value as? CallUiState.Ringing ?: return
        if (!ringing.handoff) {
            coordinator.answer()
            dismissKeyguard()
            return
        }
        handOff(ringing.checkInType)
    }

    /**
     * Slice 4C. The conversation shows planning content, so the phone is unlocked first. The call is answered only after the
     * unlock succeeds: a cancelled or failed unlock leaves the call ringing, opens nothing and sends nothing.
     */
    private fun handOff(type: CheckInType) {
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard?.isKeyguardLocked != true) {
            completeHandoff(type)
            return
        }
        if (unlockPending) return
        unlockPending = true
        keyguard.requestDismissKeyguard(
            this,
            object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    unlockPending = false
                    completeHandoff(type)
                }

                override fun onDismissCancelled() {
                    unlockPending = false
                }

                override fun onDismissError() {
                    unlockPending = false
                }
            },
        )
    }

    private fun completeHandoff(type: CheckInType) {
        if (!coordinator.answerWithHandoff()) return
        startActivity(
            CallHandoff.intent(
                this,
                type,
                com.thalia.app.ai
                    .aiUsable((application as com.thalia.app.ThaliaApplication).aiServices),
            ),
        )
        coordinator.dismiss()
        finishAndRemoveTask()
    }

    private fun showOverLockScreen() {
        setShowWhenLocked(true)
        setTurnScreenOn(true)
    }

    /**
     * Ask for the keyguard once the call is answered.
     *
     * The call screen itself shows over the lock screen without unlocking, which is
     * correct: answering a call should not expose the rest of the phone. This only asks
     * once the user has chosen to answer, and on a secured device it prompts rather than
     * unlocking anything by itself.
     */
    private fun dismissKeyguard() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(KeyguardManager::class.java)
                ?.requestDismissKeyguard(this, null)
        }
    }

    companion object {
        private const val EXTRA_ANSWER = "answer_immediately"

        fun intent(
            context: Context,
            answerImmediately: Boolean = false,
        ): Intent =
            Intent(context, CallActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_ANSWER, answerImmediately)
    }
}
