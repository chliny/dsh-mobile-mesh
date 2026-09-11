package dev.dsh.mobile.mesh.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.dsh.mobile.mesh.connection.AppSettings
import dev.dsh.mobile.mesh.connection.ConnectionManager
import dev.dsh.mobile.mesh.connection.ConnectionUiState
import dev.dsh.mobile.mesh.connection.HostsStore
import dev.dsh.mobile.mesh.update.AvailableUpdate
import dev.dsh.mobile.mesh.update.UpdateChecker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(
    hostsStore: HostsStore,
    private val connectionManager: ConnectionManager,
    private val updateChecker: UpdateChecker,
) : ViewModel() {

    val settings: StateFlow<AppSettings> = hostsStore.settings.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        AppSettings(),
    )

    val connectionState: StateFlow<ConnectionUiState> = connectionManager.state

    /** A newer release to offer, or null. See [UpdateChecker]. */
    val availableUpdate: StateFlow<AvailableUpdate?> = updateChecker.available

    fun checkForUpdate(currentVersion: String) {
        viewModelScope.launch { updateChecker.checkOnce(currentVersion) }
    }

    fun dismissUpdate(version: String) {
        viewModelScope.launch { updateChecker.dismiss(version) }
    }

    fun reconnect() {
        connectionManager.reconnectIfNeeded()
    }
}
