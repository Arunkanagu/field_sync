package dev.nura.app.field.sync.feature.transfer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import dev.nura.app.field.sync.core.sync.SyncManager
import dev.nura.app.field.sync.core.sync.TransferOptions
import dev.nura.app.field.sync.core.sync.TransferPhase
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.core.wifi.NearbyPeer
import dev.nura.app.field.sync.core.wifi.WifiDirectManager
import dev.nura.app.field.sync.core.wifi.WifiDirectState
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.Role
import dev.nura.app.field.sync.domain.model.SyncEntityType
import dev.nura.app.field.sync.domain.model.SyncPolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TransferSelection(
    val sendable: List<SyncEntityType> = emptyList(),
    val selected: Set<SyncEntityType> = emptySet(),
    val acceptIncoming: Boolean = false,
    val canSend: Boolean = false,
    val canReceive: Boolean = false,
    val message: String? = null,
)

data class TransferUiState(
    val wifi: WifiDirectState,
    val phase: TransferPhase,
    val selection: TransferSelection,
)

@HiltViewModel
class TransferViewModel @Inject constructor(
    private val wifiDirect: WifiDirectManager,
    private val syncManager: SyncManager,
    sessionManager: SessionManager,
    deviceSettings: DeviceSettingsRepository,
) : ViewModel() {

    private val selection = MutableStateFlow(TransferSelection())

    val uiState: StateFlow<TransferUiState> = combine(wifiDirect.state, syncManager.phase, selection, ::TransferUiState)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TransferUiState(wifiDirect.state.value, syncManager.phase.value, selection.value))

    init {
        val user = sessionManager.currentUser.value
        viewModelScope.launch {
            val role = deviceSettings.deviceRole.first() ?: user?.role ?: Role.MANAGER
            val canSend = user?.can(Permission.SEND_DATA) == true
            val canReceive = user?.can(Permission.RECEIVE_DATA) == true
            val sendable = if (canSend) SyncPolicy.sendableTypes(role) else emptyList()
            selection.value = TransferSelection(
                sendable = sendable,
                selected = sendable.toSet(),
                acceptIncoming = canReceive,
                canSend = canSend,
                canReceive = canReceive,
            )
            syncManager.attach(selection.value.toOptions())
        }
    }

    fun findDevices() = wifiAction { wifiDirect.discoverPeers() }

    fun connect(peer: NearbyPeer) = wifiAction {
        selection.update { it.copy(message = "Invitation sent to ${peer.name}. Accept it on the other phone.") }
        wifiDirect.connect(peer.address)
    }

    fun disconnect() = wifiAction {
        syncManager.cancel()
        wifiDirect.disconnect()
    }

    fun toggleType(type: SyncEntityType) = updateSelection { current ->
        current.copy(selected = if (type in current.selected) current.selected - type else current.selected + type)
    }

    fun setAcceptIncoming(accept: Boolean) = updateSelection { it.copy(acceptIncoming = accept && it.canReceive) }

    fun respondToPairing(approved: Boolean) = syncManager.respondToPairing(approved)
    fun cancelTransfer() = syncManager.cancel()
    fun retry() = syncManager.retry()
    fun dismissResult() = syncManager.dismissResult()
    fun dismissMessage() = selection.update { it.copy(message = null) }

    private fun updateSelection(transform: (TransferSelection) -> TransferSelection) {
        selection.update(transform)
        syncManager.updateOptions(selection.value.toOptions())
    }

    private fun wifiAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runSuspendCatching { block() }.onFailure { e -> selection.update { it.copy(message = e.message) } }
        }
    }

    private fun TransferSelection.toOptions() = TransferOptions(sendTypes = selected, acceptIncoming = acceptIncoming)

    override fun onCleared() {
        syncManager.detach()
    }
}
