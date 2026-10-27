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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.jarvis.assistant.data.JarvisSettings
import com.jarvis.assistant.data.LocalHistoryRepository
import com.jarvis.assistant.data.PreferencesRepository
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.tools.AndroidNotificationReader
import com.jarvis.assistant.tools.AndroidReminderTool
import com.jarvis.assistant.tools.NotificationPrivacy
import com.jarvis.assistant.tts.SpeechOutput
import kotlinx.coroutines.launch

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
    val scope = rememberCoroutineScope()
    val preferences = remember(context) { PreferencesRepository(context) }
    val settings by preferences.settings.collectAsStateWithLifecycle(initialValue = JarvisSettings())

    // Re-checked on every resume: the user may grant or revoke access in system settings.
    var notificationAccess by remember { mutableStateOf(AndroidNotificationReader(context).isAccessEnabled()) }
    var reminderLimitation by remember { mutableStateOf(AndroidReminderTool.notificationLimitation(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = AndroidNotificationReader(context).isAccessEnabled()
                reminderLimitation = AndroidReminderTool.notificationLimitation(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var confirmEnableHistory by remember { mutableStateOf(false) }
    var excludedText by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(settings.notificationExcludedPackages) {
        if (excludedText == null) excludedText = settings.notificationExcludedPackages.sorted().joinToString(", ")
    }

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
                Text("Keep local history (last ${LocalHistoryRepository.MAX_ENTRIES})", modifier = Modifier.weight(1f))
                Switch(
                    checked = historyEnabled,
                    onCheckedChange = { enable -> if (enable) confirmEnableHistory = true else onHistoryEnabledChange(false) },
                )
            }
            Body(
                "Off by default. When on, JARVIS saves the text of your last ${LocalHistoryRepository.MAX_ENTRIES} " +
                    "commands and its replies in app-private storage on this phone. Notification text is never " +
                    "saved. History is never backed up or uploaded.",
            )
            OutlinedButton(onClick = onClearHistory, enabled = historyCount > 0) {
                Text(if (historyCount > 0) "Clear history ($historyCount)" else "History is empty")
            }
        }

        Section("Notification access (optional)") {
            Body(
                "Needed only for \"read my latest notification\". Notification access lets an app see the " +
                    "content of all your notifications, which can include message previews, one-time " +
                    "passwords (OTPs), banking alerts and other private information. Turn it on only if you " +
                    "want this feature.\n\nJARVIS keeps at most the last 5 notifications in memory for up to " +
                    "10 minutes, never writes them to storage or logs, and clears them when access is turned " +
                    "off. Status: " + if (notificationAccess) "enabled." else "disabled.",
            )
            OutlinedButton(onClick = { open(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                Text(if (notificationAccess) "Turn off notification access" else "Open notification access settings")
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "Never read notifications from messaging, email, banking, payment or authenticator apps, or ones that look like OTPs",
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = settings.notificationBlockSensitiveApps,
                    onCheckedChange = { value -> scope.launch { preferences.setNotificationBlockSensitiveApps(value) } },
                )
            }
            OutlinedTextField(
                value = excludedText.orEmpty(),
                onValueChange = { excludedText = it },
                label = { Text("Also never read these apps (package names, comma separated)") },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = {
                val parsed = NotificationPrivacy.parsePackageList(excludedText.orEmpty())
                excludedText = parsed.sorted().joinToString(", ")
                scope.launch { preferences.setNotificationExcludedPackages(parsed) }
            }) { Text("Save excluded apps") }
            Body("Example: com.example.chat. Find a package name in the app's Android app-info page or store link.")
        }

        Section("Reminders") {
            Body(
                "Reminders use Android's battery-friendly inexact alarms, so they can arrive several minutes " +
                    "late (longer in battery saver or Doze). They are not exact alarms. Delays from 1 minute to " +
                    "24 hours are supported. Pending reminders are saved on this phone and set again after a " +
                    "restart; a reminder that came due while the phone was off is shown as \"Missed reminder\" " +
                    "(up to 24 hours late). Restarting only re-sets reminders; it never starts listening.",
            )
            reminderLimitation?.let { Body("Currently unavailable: ${it.message}") }
            OutlinedButton(onClick = { open(context, notificationSettingsIntent(context)) }) {
                Text("Open JARVIS notification settings")
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
                    "and some options may not exist. Listening does not restart after a force-stop or reboot.",
            )
            OutlinedButton(onClick = { open(context, PermissionManager.appSettingsIntent(context)) }) {
                Text("Open JARVIS app info")
            }
        }

        Section("Backups & privacy") {
            Body(
                "JARVIS opts out of Android cloud backup and device-to-device transfer, so settings, history, " +
                    "reminders and the speech model stay on this phone only.",
            )
        }

        Section("Permissions") {
            Body("Review or revoke microphone, notification and other permissions in Android settings.")
            OutlinedButton(onClick = { open(context, PermissionManager.appSettingsIntent(context)) }) {
                Text("Manage app permissions")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    if (confirmEnableHistory) {
        AlertDialog(
            onDismissRequest = { confirmEnableHistory = false },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnableHistory = false
                    onHistoryEnabledChange(true)
                }) { Text("Turn on") }
            },
            dismissButton = { TextButton(onClick = { confirmEnableHistory = false }) { Text("Cancel") } },
            title = { Text("Keep command history?") },
            text = {
                Text(
                    "JARVIS will save the text of what you say after the wake word and its replies (for " +
                        "example \"remind me to call mum in 10 minutes\"), with the time, for the last " +
                        "${LocalHistoryRepository.MAX_ENTRIES} commands. Notification text and audio are never " +
                        "saved. It stays in app-private storage on this phone, is not backed up or uploaded, and " +
                        "you can clear it at any time.",
                )
            },
        )
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

private fun notificationSettingsIntent(context: Context): Intent =
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        // Not available on this OS build.
    } catch (_: SecurityException) {
        // Blocked by the OS.
    }
}
