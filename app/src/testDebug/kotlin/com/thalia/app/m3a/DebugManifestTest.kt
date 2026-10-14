package com.thalia.app.m3a

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The debug build's merged manifest: the M3A spike's permissions and screen, and nothing that
 * would let a session outlive its screen. The release build's side is in `src/testRelease`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class DebugManifestTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager get() = context.packageManager

    private fun requested(): Set<String> =
        assertNotNull(pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions).toSet()

    @Test
    fun `the debug build requests the spike's permissions`() {
        val requested = requested()

        assertTrue("android.permission.INTERNET" in requested)
        assertTrue("android.permission.RECORD_AUDIO" in requested)
        assertTrue("android.permission.ACCESS_NETWORK_STATE" in requested)
        assertTrue("android.permission.MODIFY_AUDIO_SETTINGS" in requested)
    }

    @Test
    fun `nor does it request a microphone foreground service or any telephony permission`() {
        val requested = requested()

        listOf(
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.READ_PHONE_STATE",
            "android.permission.CALL_PHONE",
            "android.permission.MANAGE_OWN_CALLS",
            "android.permission.USE_EXACT_ALARM",
        ).forEach { assertFalse(it in requested, "$it must not be requested") }
        val services = pm.getPackageInfo(context.packageName, PackageManager.GET_SERVICES).services.orEmpty()
        services.forEach { service ->
            val microphone = service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            assertEquals(0, microphone, "${service.name} must not be a microphone service")
        }
    }

    @Test
    fun `the live test screen is declared, with its own launcher entry`() {
        val info = pm.getActivityInfo(ComponentName(context, LiveVoiceTestActivity::class.java), 0)
        assertTrue(info.exported)

        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val launchers = pm.queryIntentActivities(launcher, 0)
        assertTrue(launchers.any { it.activityInfo.name == LiveVoiceTestActivity::class.java.name })
        assertTrue(launchers.any { it.activityInfo.name == "com.thalia.app.MainActivity" })
    }
}
