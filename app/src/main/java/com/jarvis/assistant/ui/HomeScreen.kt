package com.jarvis.assistant.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.ai.ModelState
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.service.ErrorRecovery
import com.jarvis.assistant.service.LastExchange
import com.jarvis.assistant.service.LatencyStats
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.service.VoiceMetricsSnapshot
import com.jarvis.assistant.tts.SpeechOutput

@Composable
fun HomeScreen(
    state: ServiceState,
    modelState: ModelState,
    wakeSensitivity: Float,
    historyEnabled: Boolean,
    lastExchange: LastExchange?,
    notice: String?,
    diagnostics: VoiceMetricsSnapshot,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTapToTalk: () -> Unit,
    onPermissionDenied: () -> Unit,
    onWakeSensitivityChange: (Float) -> Unit,
    onOpenModelSetup: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissError: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var pendingTapToTalk by remember { mutableStateOf(false) }
    val modelReady = modelState is ModelState.Ready

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Notification denial is tolerated; the microphone is required.
        if (results[PermissionManager.MICROPHONE] == true) {
            if (pendingTapToTalk) onTapToTalk() else onStart()
        } else {
            onPermissionDenied()
        }
        pendingTapToTalk = false
    }

    fun withPermissions(tapToTalk: Boolean, action: () -> Unit) {
        val ready = PermissionManager.hasMicrophonePermission(context) &&
            PermissionManager.hasNotificationPermission(context)
        if (ready) {
            action()
        } else {
            pendingTapToTalk = tapToTalk
            permissionLauncher.launch(PermissionManager.listeningPermissions())
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("JARVIS", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "All processing on-device",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(32.dp))

        if (state.isActive) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text(if (state == ServiceState.Starting) "Cancel start" else "Stop Listening", fontSize = 22.sp) }
        } else if (state == ServiceState.Stopping) {
            Button(
                onClick = {},
                enabled = false,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            ) { Text("Stopping…", fontSize = 22.sp) }
        } else {
            Button(
                onClick = { withPermissions(tapToTalk = false, action = onStart) },
                enabled = modelReady,
                modifier = Modifier.fillMaxWidth().height(96.dp),
            ) { Text("Start Listening", fontSize = 22.sp) }
        }
        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = { withPermissions(tapToTalk = true, action = onTapToTalk) },
            enabled = modelReady && (state == ServiceState.Listening || (!state.isActive && state != ServiceState.Stopping)),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Tap to talk") }

        Spacer(Modifier.height(24.dp))
        Text("Status: ${statusText(state)}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)

        if (state == ServiceState.MicrophoneSilenced) {
            Spacer(Modifier.height(8.dp))
            Text(
                "Another app (for example a call or voice recorder) is using the microphone. " +
                    "JARVIS will resume automatically when it is free.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
        }

        if (state is ServiceState.Error) {
            ErrorRecoveryActions(
                state = state,
                onRetry = { onDismissError(); withPermissions(tapToTalk = false, action = onStart) },
                onOpenAppSettings = {
                    onDismissError()
                    try {
                        context.startActivity(PermissionManager.appSettingsIntent(context))
                    } catch (_: ActivityNotFoundException) {
                        // No settings app available; nothing else to do.
                    }
                },
                onOpenModelSetup = onOpenModelSetup,
                onDismiss = onDismissError,
            )
        }

        if (!PermissionManager.hasNotificationPermission(context)) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Notifications are off for JARVIS. Listening still works, but the status notification " +
                    "(with its Stop button) and reminder alerts may be hidden. Use the Stop button here, " +
                    "or allow notifications in app settings.",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = {
                try {
                    context.startActivity(PermissionManager.appSettingsIntent(context))
                } catch (_: ActivityNotFoundException) {
                    // No settings app available; nothing else to do.
                }
            }) { Text("Allow notifications") }
        }

        if (!modelReady) {
            Spacer(Modifier.height(12.dp))
            Text(
                when (modelState) {
                    is ModelState.Downloading, ModelState.Verifying -> "Speech model is being installed..."
                    is ModelState.Error -> "Speech model problem: ${modelState.message}"
                    else -> "Speech model not installed. Set it up to start listening."
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenModelSetup) { Text("Set up speech model") }
        }

        if (state is ServiceState.PermissionRequired) {
            Spacer(Modifier.height(12.dp))
            Text(
                "JARVIS needs microphone access to listen. You can grant it from Android settings.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = {
                try {
                    context.startActivity(PermissionManager.appSettingsIntent(context))
                } catch (_: ActivityNotFoundException) {
                    // No settings app available; nothing else to do.
                }
            }) { Text("Open app settings") }
        }

        if (notice != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                notice,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = { SpeechOutput.openTtsSettings(context) }) { Text("Open voice settings") }
        }

        if (historyEnabled && lastExchange != null) {
            Spacer(Modifier.height(16.dp))
            Text("Last command: ${lastExchange.command}", style = MaterialTheme.typography.bodyMedium)
            lastExchange.reply?.let { Text("Result: $it", style = MaterialTheme.typography.bodyMedium) }
        }

        Spacer(Modifier.height(24.dp))
        WakeSensitivity(wakeSensitivity, onWakeSensitivityChange)

        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onOpenModelSetup) { Text("Speech model") }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenSettings) { Text("Settings & help") }

        Spacer(Modifier.height(24.dp))
        Diagnostics(diagnostics)
    }
}

private fun statusText(state: ServiceState): String = when (state) {
    is ServiceState.Error -> "Error"
    else -> state.label
}

@Composable
private fun ErrorRecoveryActions(
    state: ServiceState.Error,
    onRetry: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onDismiss: () -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(
        state.message,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        when (state.recovery) {
            ErrorRecovery.Retry -> Button(onClick = onRetry) { Text("Try again") }
            ErrorRecovery.OpenAppSettings -> Button(onClick = onOpenAppSettings) { Text("Open app settings") }
            ErrorRecovery.OpenModelSetup -> Button(onClick = onOpenModelSetup) { Text("Set up speech model") }
        }
        TextButton(onClick = onDismiss) { Text("Dismiss") }
    }
}

/** On-device counters only (no transcripts); reset when the app process restarts. */
@Composable
private fun Diagnostics(snapshot: VoiceMetricsSnapshot) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "Hide diagnostics" else "Show diagnostics")
        }
        if (!expanded) return@Column
        val style = MaterialTheme.typography.bodySmall
        Text("Counts since app start (no speech content is stored).", style = style)
        Text("Wake detections: ${snapshot.wakeDetections}   Tap-to-talk: ${snapshot.tapToTalkSessions}", style = style)
        Text("Command sessions: ${snapshot.commandSessions}", style = style)
        Text(
            "No speech: ${snapshot.noSpeech}   Low confidence: ${snapshot.lowConfidence}   " +
                "Handler errors: ${snapshot.handlerFailures}",
            style = style,
        )
        Text(
            "Voice output failures: ${snapshot.speechOutputFailures}   Mic errors: ${snapshot.captureErrors}   " +
                "Mic taken by other app: ${snapshot.microphoneSilenced}",
            style = style,
        )
        Text("Model load: ${snapshot.lastModelLoadMs?.let { "$it ms" } ?: "n/a"}", style = style)
        Text("Wake to command start: ${latency(snapshot.wakeLatency)}", style = style)
        Text("End of speech to reply: ${latency(snapshot.responseLatency)}", style = style)
    }
}

private fun latency(stats: LatencyStats): String =
    if (stats.count == 0) "n/a" else "avg ${stats.averageMs} / median ${stats.medianMs} / p95 ${stats.p95Ms} ms (n=${stats.count})"

@Composable
private fun WakeSensitivity(value: Float, onChange: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Wake word sensitivity: ${(local * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local) },
            valueRange = 0f..1f,
        )
        Text(
            "Lower = fewer accidental triggers. Higher = reacts to quieter or less clear speech.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
