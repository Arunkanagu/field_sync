package dev.nura.app.field.sync.feature.sync_history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.nura.app.field.sync.core.security.PairingManager
import dev.nura.app.field.sync.core.sync.ConflictResolver
import dev.nura.app.field.sync.core.util.runSuspendCatching
import dev.nura.app.field.sync.data.repository.SyncStatusRepository
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.TransferRecord
import dev.nura.app.field.sync.domain.model.TrustedDevice
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SyncHistoryUiState(
    val devices: List<TrustedDevice> = emptyList(),
    val history: List<TransferRecord> = emptyList(),
    val openConflicts: Int = 0,
    val canRevoke: Boolean = false,
    val confirmRevoke: TrustedDevice? = null,
    val error: String? = null,
)

@HiltViewModel
class SyncHistoryViewModel @Inject constructor(
    private val pairingManager: PairingManager,
    syncStatusRepository: SyncStatusRepository,
    conflictResolver: ConflictResolver,
    sessionManager: SessionManager,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncHistoryUiState(canRevoke = sessionManager.hasPermission(Permission.RECEIVE_DATA)))
    val uiState: StateFlow<SyncHistoryUiState> = _uiState.asStateFlow()

    init {
        runCatching {
            combine(
                pairingManager.observeDevices(),
                syncStatusRepository.observeHistory(),
                conflictResolver.observeOpenCount(),
            ) { devices, history, conflicts -> Triple(devices, history, conflicts) }
                .onEach { (devices, history, conflicts) ->
                    _uiState.update { it.copy(devices = devices, history = history, openConflicts = conflicts) }
                }
                .catch { e -> _uiState.update { it.copy(error = e.message) } }
                .launchIn(viewModelScope)
        }.onFailure { e -> _uiState.update { it.copy(error = e.message) } }
    }

    fun requestRevoke(device: TrustedDevice) = _uiState.update { it.copy(confirmRevoke = device) }
    fun cancelRevoke() = _uiState.update { it.copy(confirmRevoke = null) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun revoke() {
        val device = _uiState.value.confirmRevoke ?: return
        _uiState.update { it.copy(confirmRevoke = null) }
        viewModelScope.launch {
            runSuspendCatching { pairingManager.revoke(device.deviceId) }
                .onFailure { e -> _uiState.update { it.copy(error = e.message) } }
        }
    }
}
