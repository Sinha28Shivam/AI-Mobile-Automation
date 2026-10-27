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
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jarvis.assistant.onboarding.OnboardingScreen
import com.jarvis.assistant.ui.HomeScreen
import com.jarvis.assistant.ui.HomeViewModel
import com.jarvis.assistant.ui.ModelSetupScreen
import com.jarvis.assistant.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                val viewModel: HomeViewModel = viewModel()
                val settings by viewModel.settings.collectAsStateWithLifecycle()
                val state by viewModel.serviceState.collectAsStateWithLifecycle()
                val modelState by viewModel.modelState.collectAsStateWithLifecycle()
                val lastExchange by viewModel.lastExchange.collectAsStateWithLifecycle()
                val notice by viewModel.notice.collectAsStateWithLifecycle()
                var showModelSetup by rememberSaveable { mutableStateOf(false) }
                var showSettings by rememberSaveable { mutableStateOf(false) }

                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    val contentModifier = Modifier.padding(padding)
                    val current = settings
                    when {
                        current == null -> Box(contentModifier.fillMaxSize())
                        !current.onboardingDone -> OnboardingScreen(
                            onContinue = viewModel::completeOnboarding,
                            modifier = contentModifier,
                        )
                        showModelSetup -> {
                            BackHandler { showModelSetup = false }
                            ModelSetupScreen(
                                spec = viewModel.modelSpec,
                                state = modelState,
                                onDownload = viewModel::downloadModel,
                                onImport = viewModel::importModel,
                                onCancel = viewModel::cancelModelInstall,
                                onDelete = viewModel::deleteModel,
                                onBack = { showModelSetup = false },
                                modifier = contentModifier,
                            )
                        }
                        showSettings -> {
                            BackHandler { showSettings = false }
                            val historyCount by viewModel.historyCount.collectAsStateWithLifecycle()
                            LaunchedEffect(Unit) { viewModel.refreshHistoryCount() }
                            SettingsScreen(
                                historyEnabled = current.historyEnabled,
                                historyCount = historyCount,
                                onHistoryEnabledChange = viewModel::setHistoryEnabled,
                                onClearHistory = viewModel::clearHistory,
                                onOpenModelSetup = {
                                    viewModel.clearModelError()
                                    showModelSetup = true
                                },
                                onBack = { showSettings = false },
                                modifier = contentModifier,
                            )
                        }
                        else -> HomeScreen(
                            state = state,
                            modelState = modelState,
                            wakeSensitivity = current.wakeSensitivity,
                            historyEnabled = current.historyEnabled,
                            lastExchange = lastExchange,
                            notice = notice,
                            onStart = viewModel::startListening,
                            onStop = viewModel::stopListening,
                            onTapToTalk = viewModel::tapToTalk,
                            onPermissionDenied = viewModel::onMicrophonePermissionDenied,
                            onWakeSensitivityChange = viewModel::setWakeSensitivity,
                            onOpenModelSetup = {
                                viewModel.clearModelError()
                                showModelSetup = true
                            },
                            onOpenSettings = { showSettings = true },
                            modifier = contentModifier,
                        )
                    }
                }
            }
        }
    }
}
