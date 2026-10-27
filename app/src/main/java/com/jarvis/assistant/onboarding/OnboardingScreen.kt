package com.jarvis.assistant.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val onboardingPoints = listOf(
    "Microphone" to "JARVIS uses the microphone only while you have turned listening on, so it can hear you speak to it.",
    "Visible while active" to "Listening runs as a foreground service. A notification is shown the whole time, with a Stop button.",
    "On-device" to "Wake-word detection (\"Hey Jarvis\") and speech recognition run locally on this phone using the open-source Vosk engine. Audio is kept in memory and never saved or uploaded.",
    "Optional features" to "Features like reading notifications will ask for extra permissions only when you turn them on.",
    "You stay in control" to "Tap Stop in the app or notification at any time. You can revoke permissions in Android Settings > Apps > JARVIS > Permissions.",
    "Speech model" to "Listening needs a one-time download of the Vosk small English model (about 39 MB, Apache 2.0, from alphacephei.com). This is the only time the internet is used; you can also import the file yourself. It is checked before installing and can be deleted at any time.",
)

/**
 * Plain-language explanation shown on first launch. It never requests a
 * permission itself; the microphone permission is requested only when the
 * user taps Start Listening on the home screen.
 */
@Composable
fun OnboardingScreen(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Text("Welcome to JARVIS", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(16.dp))
        onboardingPoints.forEach { (title, body) ->
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = onContinue, modifier = Modifier.fillMaxWidth()) {
            Text("Got it")
        }
    }
}
