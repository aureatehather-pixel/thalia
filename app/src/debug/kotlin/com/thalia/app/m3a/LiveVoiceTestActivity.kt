package com.thalia.app.m3a

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.thalia.app.secret.ApiKeyStore
import com.thalia.app.ui.ThaliaTheme

/**
 * The M3A spike's screen: a manual, two-way Gemini Live session. Debug builds only.
 *
 * It exists only in the debug source set and the debug manifest, so a release build has no
 * trace of it. A session runs only while this screen is visible; see [SessionLifecycleBinding].
 */
class LiveVoiceTestActivity : ComponentActivity() {
    private lateinit var controller: LiveTestController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val graph = M3aGraph.get(this)
        controller =
            LiveTestController(
                scope = lifecycleScope,
                inputs = { readGateInputs(this, graph) },
                ledger = graph.ledger,
                sessions = GeminiSessionFactory(this, graph.apiKeys),
                clock = System::currentTimeMillis,
            )
        lifecycle.addObserver(SessionLifecycleBinding(controller))

        setContent {
            ThaliaTheme {
                val state by controller.state.collectAsStateWithLifecycle()
                var consent by remember { mutableStateOf(graph.consent.current()) }
                var keySaved by remember { mutableStateOf(graph.apiKeys.hasKey()) }
                var microphoneGranted by remember { mutableStateOf(hasMicrophone()) }
                var keyMessage by remember { mutableStateOf<String?>(null) }

                LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
                    consent = graph.consent.current()
                    keySaved = graph.apiKeys.hasKey()
                    microphoneGranted = hasMicrophone()
                }

                val requestMicrophone =
                    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                        microphoneGranted = hasMicrophone()
                    }

                LiveVoiceTestScreen(
                    state = state,
                    consent = consent,
                    keySaved = keySaved,
                    microphoneGranted = microphoneGranted,
                    keyMessage = keyMessage,
                    onConsentChange = { accepted ->
                        if (accepted) {
                            graph.consent.accept(System.currentTimeMillis())
                        } else {
                            graph.consent.withdraw()
                            // Consent withdrawn mid-session ends the session.
                            controller.endByUser()
                        }
                        consent = graph.consent.current()
                    },
                    onSaveKey = { entry ->
                        keyMessage =
                            when (val result = graph.apiKeys.save(entry)) {
                                ApiKeyStore.SaveResult.Saved -> "Key saved."
                                is ApiKeyStore.SaveResult.Invalid -> result.message
                                ApiKeyStore.SaveResult.EncryptionFailed -> "The key could not be encrypted, so it was not saved."
                            }
                        keySaved = graph.apiKeys.hasKey()
                    },
                    onClearKey = {
                        keyMessage =
                            when (graph.apiKeys.clear()) {
                                ApiKeyStore.ClearResult.Cleared -> "Key cleared."
                                ApiKeyStore.ClearResult.Failed -> "The key file is deleted, but the Keystore key could not be."
                            }
                        keySaved = graph.apiKeys.hasKey()
                    },
                    onRequestMicrophone = { requestMicrophone.launch(Manifest.permission.RECORD_AUDIO) },
                    onStart = controller::start,
                    onEnd = controller::endByUser,
                )
            }
        }
    }

    private fun hasMicrophone(): Boolean = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
}
