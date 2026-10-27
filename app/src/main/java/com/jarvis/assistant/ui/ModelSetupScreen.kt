package com.jarvis.assistant.ui

import android.content.ActivityNotFoundException
import android.net.Uri
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.ai.InstallProgress
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.ai.ModelSpec
import com.jarvis.assistant.ai.ModelState
import java.util.Locale

@Composable
fun ModelSetupScreen(
    spec: ModelSpec,
    state: ModelState,
    onDownload: () -> Unit,
    onImport: (Uri) -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val manager = remember(context) { LocalModelManager.get(context) }
    val progress by manager.progress.collectAsStateWithLifecycle()
    val notice by manager.notice.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }
    var importUnavailable by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImport(uri)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Speech model", style = MaterialTheme.typography.headlineMedium)
        Text(
            "JARVIS needs a speech model to recognize \"Hey Jarvis\" and your commands on this phone. " +
                "It is not included in the app.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
        Text(
            "Download size: ${formatMegabytes(spec.downloadBytes)} - installed size about " +
                formatMegabytes(spec.installedBytes),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("License: ${spec.license}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Downloading is a one-time setup step that uses the internet over HTTPS from ${spec.source} " +
                "(consider Wi-Fi). This is the only time JARVIS uses the network: after installation, " +
                "recognition works offline and audio is never uploaded.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Integrity: the file must be exactly ${spec.downloadBytes} bytes and match SHA-256 " +
                "${spec.sha256.take(16)}..." +
                (spec.md5?.let { " and the MD5 ${it.take(12)}... published by upstream" } ?: "") +
                ". It is unpacked into a temporary folder, checked for all model files and test-loaded. " +
                "An installed model is only replaced after the new one passes. Needs about " +
                "${formatMegabytes(spec.downloadBytes + spec.installedBytes)} free space during install.",
            style = MaterialTheme.typography.bodySmall,
        )

        Spacer(Modifier.height(4.dp))
        ModelStatus(state, progress)
        notice?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(4.dp))

        when (state) {
            is ModelState.Downloading, ModelState.Verifying -> {
                OutlinedButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
                Text(
                    "Cancelling keeps any model that is already installed.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            is ModelState.Ready -> {
                OutlinedButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Delete model")
                }
                OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text("Re-download / repair")
                }
                Text(
                    "Re-download keeps the current model until the new copy passes all checks.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            ModelState.Missing, is ModelState.Error -> {
                Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state is ModelState.Error) "Try download again" else "Download (${formatMegabytes(spec.downloadBytes)})")
                }
                OutlinedButton(
                    onClick = {
                        try {
                            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
                        } catch (_: ActivityNotFoundException) {
                            importUnavailable = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Import zip from this phone") }
                Text(
                    "Import: download ${spec.id}.zip from ${spec.source} on any device, copy it to this phone, " +
                        "then pick it here. Only that exact file is accepted.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onBack) { Text("Back") }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
            title = { Text("Delete speech model?") },
            text = {
                Text(
                    "Listening stops first; the files are deleted only after the listening service has " +
                        "released the model. Listening stays unavailable until the model is installed again.",
                )
            },
        )
    }
    if (importUnavailable) {
        AlertDialog(
            onDismissRequest = { importUnavailable = false },
            confirmButton = { TextButton(onClick = { importUnavailable = false }) { Text("OK") } },
            title = { Text("No file picker") },
            text = { Text("This phone has no app that can pick files. Use Download instead.") },
        )
    }
}

@Composable
private fun ModelStatus(state: ModelState, progress: InstallProgress?) {
    when (state) {
        ModelState.Missing -> Text("Status: Not installed", style = MaterialTheme.typography.titleSmall)
        is ModelState.Downloading -> {
            val fraction = state.progress
            if (fraction != null) {
                Text("Status: Downloading ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                Text("Status: Downloading...", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        ModelState.Verifying -> {
            val stage = progress?.stage?.label ?: "Verifying and installing"
            val fraction = progress?.fraction
            if (fraction != null) {
                Text("Status: $stage ${(fraction * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text("Status: $stage...", style = MaterialTheme.typography.titleSmall)
                }
            }
        }
        is ModelState.Ready -> Text(
            "Status: Ready (${formatMegabytes(state.sizeBytes)} on this phone). Checksum verified and model test-loaded.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        is ModelState.Error -> Text(
            "Status: Error: ${state.message}",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

internal fun formatMegabytes(bytes: Long): String =
    String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
