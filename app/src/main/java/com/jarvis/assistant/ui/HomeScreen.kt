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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.service.ServiceState

@Composable
fun HomeScreen(
    state: ServiceState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPermissionDenied: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var showHelp by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        // Notification denial is tolerated; the microphone is required.
        if (results[PermissionManager.MICROPHONE] == true) onStart() else onPermissionDenied()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
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
        Spacer(Modifier.height(48.dp))

        if (state.isActive) {
            Button(
                onClick = onStop,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            ) { Text("Stop Listening", fontSize = 22.sp) }
        } else {
            Button(
                onClick = {
                    val ready = PermissionManager.hasMicrophonePermission(context) &&
                        PermissionManager.hasNotificationPermission(context)
                    if (ready) onStart() else permissionLauncher.launch(PermissionManager.listeningPermissions())
                },
                modifier = Modifier.fillMaxWidth().height(96.dp),
            ) { Text("Start Listening", fontSize = 22.sp) }
        }

        Spacer(Modifier.height(24.dp))
        Text("Status: ${state.label}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)

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

        Spacer(Modifier.height(48.dp))
        OutlinedButton(onClick = { showHelp = true }) { Text("Settings & help") }
    }

    if (showHelp) {
        AlertDialog(
            onDismissRequest = { showHelp = false },
            confirmButton = { TextButton(onClick = { showHelp = false }) { Text("Close") } },
            title = { Text("Settings & help") },
            text = {
                Text(
                    "While listening, a notification stays visible. Tap Stop there or here to stop " +
                        "immediately. Audio stays in memory on this phone and is never saved or uploaded. " +
                        "More settings will appear here in later versions.",
                )
            },
        )
    }
}
