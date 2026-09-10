package dev.dsh.mobile.mesh.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.mesh.connection.AppSettings
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HostsStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * App-language choices: the 11 shipped locales, plus following the system.
 *
 * A null [tag] clears the override. Without it the picker is a one-way door — once a language is
 * chosen there is no way back to whatever the device is set to.
 */
data class LanguageOption(val tag: String?, val label: String?, val labelRes: Int? = null)

val LanguageOptions = listOf(
    LanguageOption(null, null, dev.dsh.mobile.mesh.R.string.settings_language_system),
    LanguageOption("en", "English"),
    LanguageOption("zh", "中文"),
    LanguageOption("hi", "हिन्दी"),
    LanguageOption("es", "Español"),
    LanguageOption("fr", "Français"),
    LanguageOption("ar", "العربية"),
    LanguageOption("bn", "বাংলা"),
    LanguageOption("pt", "Português"),
    LanguageOption("ru", "Русский"),
    LanguageOption("ur", "اردو"),
    LanguageOption("th", "ไทย"),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val hostsStore: HostsStore,
    private val connectionManager: ConnectionManager,
) : ViewModel() {

    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state.asStateFlow()

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ConnectionUiState()
    )

    init {
        viewModelScope.launch {
            hostsStore.settings.collect { _state.value = it }
        }
    }

    fun set(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch {
            hostsStore.setSetting(transform)
        }
    }

    fun disconnect() {
        connectionManager.disconnect()
    }

    /**
     * Forget every remembered harness; the connect screen starts from discovery again.
     *
     */
    fun forgetHosts(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            hostsStore.hosts.first().forEach { hostsStore.removeHost(it.id) }
            onDone()
        }
    }

    /** Forget which session to reopen per harness; the app lands on the newest one next time. */
    fun clearLastSessions(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            hostsStore.clearLastSessions()
            onDone()
        }
    }
}
