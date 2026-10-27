package com.jarvis.assistant.ui

import android.content.ActivityNotFoundException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import com.jarvis.assistant.service.LastExchange
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.tts.SpeechOutput

@Composable
fun HomeScreen(
    state: ServiceState,
    modelState: ModelState,
    wakeSensitivity: Float,
    historyEnabled: Boolean,
    lastExchange: LastExchange?,
    notice: String?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onTapToTalk: () -> Unit,
    onPermissionDenied: () -> Unit,
    onWakeSensitivityChange: (Float) -> Unit,
    onOpenModelSetup: () -> Unit,
    onOpenSettings: () -> Unit,
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
            ) { Text("Stop Listening", fontSize = 22.sp) }
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
            enabled = modelReady && (state == ServiceState.Listening || !state.isActive),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) { Text("Tap to talk") }

        Spacer(Modifier.height(24.dp))
        Text("Status: ${state.label}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)

        if (!modelReady) {
            Spacer(Modifier.height(12.dp))
            Text(
                when (modelState) {
                    is ModelState.Downloading, ModelState.Verifying -> "Speech model is being installedâ€¦"
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
    }
}

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
