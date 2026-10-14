package com.thalia.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The release build has no trace of the M3A Gemini spike: none of its permissions, not its
 * screen, and neither its provider module nor the network library on the classpath. ADR 0008.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class ReleaseIsolationTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val pm: PackageManager get() = context.packageManager

    @Test
    fun `the release build requests none of the spike's permissions, and the microphone only for on-device speech`() {
        val requested = assertNotNull(pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions).toSet()

        listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.MODIFY_AUDIO_SETTINGS",
            "android.permission.FOREGROUND_SERVICE_MICROPHONE",
            "android.permission.READ_PHONE_STATE",
        ).forEach { assertFalse(it in requested, "$it must not be requested by a release build") }
        // Slice 4B: the one microphone use in a release build is the conversation screen's on-device speech, asked at runtime.
        assertTrue("android.permission.RECORD_AUDIO" in requested)
    }

    @Test
    fun `the only launcher entry is the main screen`() {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName)
        val launchers = pm.queryIntentActivities(launcher, 0)

        assertEquals(listOf(MainActivity::class.java.name), launchers.map { it.activityInfo.name })
    }

    @Test
    fun `no alternative conversation route is installed in a release build, and the plan provider module is not on its classpath`() {
        assertNull(com.thalia.app.ai.ConversationRoutes.installed)
        assertNull(com.thalia.app.ai.AiTransportOverride.transport)
        assertFailsWith<ClassNotFoundException> { Class.forName("com.thalia.chatgptplan.ChatGptPlanProvider") }
        assertFailsWith<ClassNotFoundException> { Class.forName("com.thalia.chatgptplan.ChatGptPlanConnection") }
    }

    @Test
    fun `no spike code, provider or network library is on the release classpath`() {
        listOf(
            "com.thalia.app.m3a.LiveVoiceTestActivity",
            "com.thalia.app.m3a.ReservationLedger",
            "com.thalia.voicecheckins.gemini.GeminiLiveVoiceSessionClient",
            "okhttp3.OkHttpClient",
            // Slice 6 feasibility proof: only the chatgptCheck build has it.
            "com.thalia.chatgptplan.ChatGptPlanProvider",
            "com.thalia.speech.SpokenReplyPolicy",
            "com.thalia.app.chatgptcheck.ChatGptCheckActivity",
        ).forEach { name -> assertFailsWith<ClassNotFoundException>(name) { Class.forName(name) } }
    }
}
