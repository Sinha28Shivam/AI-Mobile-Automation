package com.jarvis.assistant.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.tools.AndroidNotificationReader
import com.jarvis.assistant.tts.SpeechOutput

@Composable
fun SettingsScreen(
    historyEnabled: Boolean,
    historyCount: Int,
    onHistoryEnabledChange: (Boolean) -> Unit,
    onClearHistory: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val notificationAccess = AndroidNotificationReader(context).isAccessEnabled()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) { Text("‹ Back") }
        Text("Settings & help", style = MaterialTheme.typography.headlineMedium)

        Section("How to use") {
            Body(
                "Say \"Hey Jarvis\", wait for the status to show \"Heard wake word\", then say your command. " +
                    "Or tap \"Tap to talk\" to skip the wake word. Say \"stop listening\" or tap Stop to end.\n\n" +
                    "The wake word is spotted by a small local recognizer limited to \"hey jarvis\". It uses more " +
                    "battery than a hardware wake-word chip and may react to similar words; lower the sensitivity " +
                    "if that happens. Audio stays in memory on this phone and is never saved or uploaded.",
            )
        }

        Section("Command history") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Keep local history (last 20)", modifier = Modifier.weight(1f))
                Switch(checked = historyEnabled, onCheckedChange = onHistoryEnabledChange)
            }
            Body("Off by default. Stored only in app-private storage on this phone, never backed up or uploaded.")
            OutlinedButton(onClick = onClearHistory, enabled = historyCount > 0) {
                Text(if (historyCount > 0) "Clear history ($historyCount)" else "History is empty")
            }
        }

        Section("Notification access (optional)") {
            Body(
                "Needed only for \"read my latest notification\". JARVIS keeps notification text in memory, " +
                    "never logs or stores it. Status: " + if (notificationAccess) "enabled." else "disabled.",
            )
            OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text("Open notification access settings")
            }
        }

        Section("Voice output") {
            Body(
                "JARVIS speaks with your phone's text-to-speech engine. If replies are silent, install or " +
                    "download an offline voice for your language in the TTS settings.",
            )
            OutlinedButton(onClick = { SpeechOutput.openTtsSettings(context) }) { Text("Open voice (TTS) settings") }
        }

        Section("Speech model") {
            Body("Offline speech recognition needs a local model (download once or import a file).")
            OutlinedButton(onClick = onOpenModelSetup) { Text("Set up speech model") }
        }

        Section("Battery & autostart (optional, OS-dependent)") {
            Body(
                "Some phones (e.g. Xiaomi MIUI/HyperOS) stop background apps aggressively. If listening stops " +
                    "on its own, you may optionally: set JARVIS battery saver to \"No restrictions\", enable " +
                    "Autostart in the Security app, and lock JARVIS in Recents. Menu names differ by OS version " +
                    "and some options may not exist. JARVIS cannot keep running after a force-stop or reboot.",
            )
            OutlinedButton(onClick = { open(context, PermissionManager.appSettingsIntent(context)) }) {
                Text("Open JARVIS app info")
            }
        }

        Section("Permissions") {
            Body("Review or revoke microphone, notification and other permissions in Android settings.")
            OutlinedButton(onClick = { open(context, PermissionManager.appSettingsIntent(context)) }) {
                Text("Manage app permissions")
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Spacer(Modifier.height(8.dp))
    HorizontalDivider()
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
}

@Composable
private fun Body(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium)
}

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Not available on this OS build.
    } catch (_: SecurityException) {
        // Blocked by the OS.
    }
}
