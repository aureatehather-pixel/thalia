package com.thalia.app.m3a

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.thalia.app.ThaliaApplication
import com.thalia.app.voicecheckins.call.CallUiState

/** Reads the gate's inputs from Android and the M3A stores. Asks for nothing and writes nothing. */
internal fun readGateInputs(
    context: Context,
    graph: M3aGraph,
): GateInputs {
    val call = (context.applicationContext as ThaliaApplication).callCoordinator.state.value
    return GateInputs(
        consentAccepted = graph.consent.isAccepted(),
        keySaved = graph.apiKeys.hasKey(),
        microphoneGranted = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
        networkValidated = hasValidatedNetwork(context),
        checkInCallInProgress = call is CallUiState.Ringing || call is CallUiState.Active,
        attemptsUsed = graph.ledger.attemptsUsed(),
    )
}

private fun hasValidatedNetwork(context: Context): Boolean {
    val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val network = connectivity.activeNetwork ?: return false
    val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}
