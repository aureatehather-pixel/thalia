package com.thalia.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import com.thalia.app.planning.PlanningActivity
import com.thalia.app.voicecheckins.schedule.ReconcileTrigger
import kotlinx.coroutines.launch

/**
 * The launcher entry. It shows nothing: it does what starting the app has always done, then hands over to the four-tab shell
 * ([PlanningActivity] hosts Today, Plan and Life; Thalia is the conversation) and finishes.
 *
 * Starting the app is one of the moments the stored occurrences are brought back into line with Android's alarms. It never
 * rings a call. The opening-call retry and the reminder reconcile follow in the same coroutine, so each reads its state only
 * after the reconcile before it has returned. The old development screen is [ToolsActivity], reached from Settings.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thalia = application as ThaliaApplication
        thalia.ioScope.launch {
            thalia.reconcile(ReconcileTrigger.APP_LAUNCH)
            thalia.retryOpeningCalls()
            thalia.reconcileReminders()
        }
        startActivity(Intent(this, PlanningActivity::class.java))
        finish()
    }
}
