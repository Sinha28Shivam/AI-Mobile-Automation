package com.jarvis.assistant.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lifecycle state of the listening pipeline, shown in the UI and notification. */
sealed interface ServiceState {
    data object Stopped : ServiceState

    /** Foreground service is up and loading the local speech models. */
    data object Starting : ServiceState
    data object Listening : ServiceState
    data object HeardWakeWord : ServiceState
    data object Processing : ServiceState
    data object Speaking : ServiceState
    data object PermissionRequired : ServiceState
    data class Error(val message: String) : ServiceState

    /** True while the foreground service owns the microphone pipeline. */
    val isActive: Boolean
        get() = when (this) {
            Starting, Listening, HeardWakeWord, Processing, Speaking -> true
            Stopped, PermissionRequired, is Error -> false
        }

    val label: String
        get() = when (this) {
            Stopped -> "Stopped"
            Starting -> "Starting (loading speech model)"
            Listening -> "Listening for \"Hey Jarvis\""
            HeardWakeWord -> "Heard wake word"
            Processing -> "Processing"
            Speaking -> "Speaking"
            PermissionRequired -> "Microphone permission required"
            is Error -> "Error: $message"
        }
}

/**
 * Process-wide single source of truth for [ServiceState]. The service writes,
 * the UI observes [state].
 */
object ServiceStateRepository {
    private val _state = MutableStateFlow<ServiceState>(ServiceState.Stopped)
    val state: StateFlow<ServiceState> = _state.asStateFlow()

    fun update(newState: ServiceState) {
        _state.value = newState
    }

    /**
     * Moves to [newState] only if the current state is active. Used by pipeline
     * stages so a late callback cannot resurrect a stopped service.
     */
    fun updateIfActive(newState: ServiceState): Boolean {
        while (true) {
            val current = _state.value
            if (!current.isActive) return false
            if (_state.compareAndSet(current, newState)) return true
        }
    }

    fun reset() {
        _state.value = ServiceState.Stopped
    }
}

/** Non-fatal, user-visible notices (e.g. voice output unavailable). Never contains user speech. */
object ServiceNoticeRepository {
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun post(message: String) {
        _notice.value = message
    }

    fun clear() {
        _notice.value = null
    }
}

/** The most recent command and reply. Only populated when local history is enabled; memory only. */
data class LastExchange(val command: String, val reply: String?)

object LastExchangeRepository {
    private val _last = MutableStateFlow<LastExchange?>(null)
    val last: StateFlow<LastExchange?> = _last.asStateFlow()

    fun set(exchange: LastExchange?) {
        _last.value = exchange
    }

    fun clear() {
        _last.value = null
    }
}
