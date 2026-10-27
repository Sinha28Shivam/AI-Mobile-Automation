package com.jarvis.assistant.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.ai.LocalModelManager
import com.jarvis.assistant.ai.ModelSpec
import com.jarvis.assistant.ai.ModelState
import com.jarvis.assistant.data.JarvisSettings
import com.jarvis.assistant.data.LocalHistoryRepository
import com.jarvis.assistant.service.ErrorRecovery
import com.jarvis.assistant.service.ServiceNoticeRepository
import com.jarvis.assistant.service.VoiceMetrics
import com.jarvis.assistant.service.VoiceMetricsSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.jarvis.assistant.data.PreferencesRepository
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.LastExchange
import com.jarvis.assistant.service.LastExchangeRepository
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.service.ServiceStateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferencesRepository(application)
    private val modelManager = LocalModelManager.get(application)

    val serviceState: StateFlow<ServiceState> = ServiceStateRepository.state
    val modelState: StateFlow<ModelState> = modelManager.state
    val modelSpec: ModelSpec = modelManager.spec
    val lastExchange: StateFlow<LastExchange?> = LastExchangeRepository.last
    val notice: StateFlow<String?> = ServiceNoticeRepository.notice
    val diagnostics: StateFlow<VoiceMetricsSnapshot> = VoiceMetrics.shared.snapshot

    private val history = LocalHistoryRepository.get(application)
    private val _historyCount = MutableStateFlow(0)
    val historyCount: StateFlow<Int> = _historyCount.asStateFlow()

    /** null while the first DataStore read is pending, to avoid flashing onboarding. */
    val settings: StateFlow<JarvisSettings?> = preferences.settings
        .map<JarvisSettings, JarvisSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun completeOnboarding() {
        viewModelScope.launch { preferences.setOnboardingDone(true) }
    }

    fun startListening() {
        if (!canListen()) return
        JarvisForegroundService.start(getApplication())
    }

    fun tapToTalk() {
        if (!canListen()) return
        JarvisForegroundService.tapToTalk(getApplication())
    }

    private fun canListen(): Boolean {
        val app = getApplication<Application>()
        if (!PermissionManager.hasMicrophonePermission(app)) {
            ServiceStateRepository.update(ServiceState.PermissionRequired)
            return false
        }
        if (modelState.value !is ModelState.Ready) {
            ServiceStateRepository.update(
                ServiceState.Error(JarvisForegroundService.MODEL_MISSING_MESSAGE, ErrorRecovery.OpenModelSetup),
            )
            return false
        }
        return true
    }

    fun stopListening() {
        JarvisForegroundService.stop(getApplication())
    }

    fun onMicrophonePermissionDenied() {
        ServiceStateRepository.update(ServiceState.PermissionRequired)
    }

    fun setHistoryEnabled(enabled: Boolean) {
        viewModelScope.launch {
            preferences.setHistoryEnabled(enabled)
            refreshHistoryCount()
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            history.clear()
            LastExchangeRepository.clear()
            refreshHistoryCount()
        }
    }

    fun refreshHistoryCount() {
        viewModelScope.launch { _historyCount.value = history.entries().size }
    }

    fun setWakeSensitivity(value: Float) {
        viewModelScope.launch { preferences.setWakeSensitivity(value) }
    }

    fun downloadModel() = modelManager.download()

    fun importModel(uri: Uri) = modelManager.importFrom(uri)

    fun cancelModelInstall() = modelManager.cancel()

    fun deleteModel() {
        // The running service holds the model open; stop it before deleting the files.
        if (serviceState.value.isActive) stopListening()
        modelManager.delete()
    }

    fun clearModelError() {
        val current = ServiceStateRepository.state.value
        if (current is ServiceState.Error && current.recovery == ErrorRecovery.OpenModelSetup) {
            ServiceStateRepository.update(ServiceState.Stopped)
        }
    }

    /** Clears a permission/error state after the user has acted on it (e.g. returned from settings). */
    fun dismissError() {
        val current = ServiceStateRepository.state.value
        if (current is ServiceState.Error || current == ServiceState.PermissionRequired) {
            ServiceStateRepository.update(ServiceState.Stopped)
        }
    }
}
