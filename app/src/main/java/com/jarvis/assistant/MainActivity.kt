package com.jarvis.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.onboarding.OnboardingScreen
import com.jarvis.assistant.ui.HomeScreen
import com.jarvis.assistant.ui.HomeViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val viewModel: HomeViewModel = viewModel()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val state by viewModel.serviceState.collectAsStateWithLifecycle()

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val contentModifier = Modifier.padding(padding)
                    val current = settings
                    when {
                        current == null -> Box(contentModifier.fillMaxSize())
                        !current.onboardingDone -> OnboardingScreen(
                            onContinue = viewModel::completeOnboarding,
                            modifier = contentModifier,
                        )
                        else -> HomeScreen(
                            state = state,
                            onStart = viewModel::startListening,
                            onStop = viewModel::stopListening,
                            onPermissionDenied = viewModel::onMicrophonePermissionDenied,
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}
