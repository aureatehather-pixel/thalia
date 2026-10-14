package com.thalia.app.voicecheckins.schedule

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.thalia.app.ThaliaApplication
import kotlinx.coroutines.launch

/**
 * Brings the stored occurrences and Android's alarms back into agreement when the system says
 * something happened that can have upset them.
 *
 * That is a restart, an update of the app, a change of the clock or the time zone, and the exact
 * alarm permission being granted. Each is a trigger for the same [Reconciler], which never rings
 * a call. This receiver starts nothing else, in particular no foreground service, so the limits
 * on starting one from a boot broadcast do not apply.
 *
 * It is not exported. The broadcasts it handles are protected: only the system can send them.
 *
 * It listens for the boot broadcast that follows the first unlock, not the earlier locked one,
 * because the database lives in storage that is not available until then. A phone that restarts
 * and is not unlocked has no alarms until it is, and that is a known limit of this slice.
 */
class SystemEventReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        val trigger = ReconcileTrigger.fromAction(intent.action) ?: return
        val app = context.applicationContext as ThaliaApplication
        val pending = goAsync()
        app.ioScope.launch {
            try {
                app.reconcile(trigger)
                app.reconcileReminders()
            } catch (e: Exception) {
                Log.e(LOG_TAG, "reconciling after $trigger failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val LOG_TAG = "ThaliaAlarm"
    }
}
