package com.jarvis.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jarvis.assistant.data.JarvisSettings
import com.jarvis.assistant.data.PreferencesRepository
import com.jarvis.assistant.permissions.PermissionManager
import com.jarvis.assistant.service.JarvisForegroundService
import com.jarvis.assistant.service.ServiceState
import com.jarvis.assistant.service.ServiceStateRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val preferences = PreferencesRepository(application)

    val serviceState: StateFlow<ServiceState> = ServiceStateRepository.state

    /** null while the first DataStore read is pending, to avoid flashing onboarding. */
    val settings: StateFlow<JarvisSettings?> = preferences.settings
        .map<JarvisSettings, JarvisSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun completeOnboarding() {
        viewModelScope.launch { preferences.setOnboardingDone(true) }
    }

    fun startListening() {
        val app = getApplication<Application>()
        if (!PermissionManager.hasMicrophonePermission(app)) {
            ServiceStateRepository.update(ServiceState.PermissionRequired)
            return
        }
        JarvisForegroundService.start(app)
    }

    fun stopListening() {
        JarvisForegroundService.stop(getApplication())
    }

    fun onMicrophonePermissionDenied() {
        ServiceStateRepository.update(ServiceState.PermissionRequired)
    }
}
