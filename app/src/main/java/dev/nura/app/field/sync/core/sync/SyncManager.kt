package dev.nura.app.field.sync.core.sync

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.nura.app.field.sync.core.database.dao.SyncDao
import dev.nura.app.field.sync.core.database.entity.TransferHistoryEntity
import dev.nura.app.field.sync.core.device.DeviceSettingsRepository
import dev.nura.app.field.sync.core.security.LocalIdentity
import dev.nura.app.field.sync.core.security.PairingManager
import dev.nura.app.field.sync.core.security.PairingRequest
import dev.nura.app.field.sync.core.security.SessionAuthenticator
import dev.nura.app.field.sync.core.transport.MessageChannel
import dev.nura.app.field.sync.core.transport.TcpConnector
import dev.nura.app.field.sync.core.transport.TransferException
import dev.nura.app.field.sync.core.wifi.P2pConnection
import dev.nura.app.field.sync.core.wifi.WifiDirectManager
import dev.nura.app.field.sync.data.local.AuditLogger
import dev.nura.app.field.sync.domain.auth.SessionManager
import dev.nura.app.field.sync.domain.model.Permission
import dev.nura.app.field.sync.domain.model.TransferStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TransferPhase {
    data object Idle : TransferPhase
    data class Connecting(val message: String) : TransferPhase
    data class AwaitingApproval(val request: PairingRequest) : TransferPhase
    data class InProgress(val peerName: String, val stage: String, val stats: TransferStats) : TransferPhase
    data class Completed(val peerName: String, val stats: TransferStats, val newlyPaired: Boolean) : TransferPhase
    data class Failed(val message: String) : TransferPhase
}

/**
 * Runs one transfer session whenever a Wi-Fi Direct group forms while the transfer screen is
 * open ([attach]). The Group Owner listens and the other phone connects; then the devices
 * authenticate (pairing with user approval if needed), exchange data and leave the group.
 *
 * Sessions outlive the screen: a [TransferService] keeps the process in the foreground until
 * the session ends.
 */
@Singleton
class SyncManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val wifi: WifiDirectManager,
    private val engine: SyncEngine,
    private val pairingManager: PairingManager,
    private val syncDao: SyncDao,
    private val audit: AuditLogger,
    private val sessionManager: SessionManager,
    private val deviceSettings: DeviceSettingsRepository,
    private val gson: Gson,
    private val clock: Clock,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _phase = MutableStateFlow<TransferPhase>(TransferPhase.Idle)
    val phase: StateFlow<TransferPhase> = _phase.asStateFlow()

    /** Non-null while the transfer screen is open; a new connection only starts a session then. */
    @Volatile
    private var options: TransferOptions? = null

    @Volatile
    private var sessionJob: Job? = null

    @Volatile
    private var handledConnection: P2pConnection? = null

    @Volatile
    private var pendingApproval: CompletableDeferred<Boolean>? = null

    private val openResources = CopyOnWriteArrayList<Closeable>()

    val isSessionActive: Boolean get() = sessionJob?.isActive == true

    init {
        scope.launch { syncDao.failInterruptedTransfers("The app was closed during the transfer.") }
        scope.launch {
            wifi.state.map { it.connection }.distinctUntilChanged().collect { connection ->
                if (connection == null) {
                    handledConnection = null
                } else {
                    maybeStartSession(connection)
                }
            }
        }
        scope.launch {
            sessionManager.currentUser.collect { user ->
                if (user == null) {
                    cancel()
                    detach()
                }
            }
        }
    }

    fun attach(transferOptions: TransferOptions) {
        options = transferOptions
        wifi.start()
        wifi.state.value.connection?.let(::maybeStartSession)
    }

    fun updateOptions(transferOptions: TransferOptions) {
        if (options != null) options = transferOptions
    }

    /** The transfer screen closed. Releases Wi-Fi Direct unless a session is still running. */
    fun detach() {
        options = null
        if (!isSessionActive) scope.launch { releaseWifi() }
    }

    fun respondToPairing(approved: Boolean) {
        pendingApproval?.complete(approved)
    }

    fun retry() {
        if (isSessionActive) return
        val connection = wifi.state.value.connection
        if (connection == null) {
            _phase.value = TransferPhase.Failed("Not connected. Connect to the other phone first.")
            return
        }
        handledConnection = connection
        startSession(connection)
    }

    fun cancel() {
        pendingApproval?.complete(false)
        sessionJob?.cancel()
        closeResources()
    }

    fun dismissResult() {
        if (!isSessionActive) _phase.value = TransferPhase.Idle
    }

    private fun maybeStartSession(connection: P2pConnection) {
        if (options == null || handledConnection != null || isSessionActive) return
        handledConnection = connection
        startSession(connection)
    }

    private fun startSession(connection: P2pConnection) {
        val requested = options ?: return
        sessionJob = scope.launch { runSession(connection, requested) }
    }

    private suspend fun runSession(connection: P2pConnection, requested: TransferOptions) {
        val user = sessionManager.currentUser.value ?: return
        val sessionOptions = TransferOptions(
            sendTypes = if (user.can(Permission.SEND_DATA)) requested.sendTypes else emptySet(),
            acceptIncoming = requested.acceptIncoming && user.can(Permission.RECEIVE_DATA),
        )
        val deviceId = deviceSettings.getOrCreateDeviceId()
        val local = LocalIdentity(
            deviceId = deviceId,
            name = deviceSettings.deviceName.first().take(DeviceSettingsRepository.MAX_DEVICE_NAME_LENGTH),
            role = deviceSettings.deviceRole.first() ?: user.role,
        )
        var history = TransferHistoryEntity(
            id = UUID.randomUUID().toString(),
            transferId = null,
            peerDeviceId = null,
            peerName = "Unknown device",
            peerRole = null,
            startedAt = clock.millis(),
            finishedAt = null,
            status = TransferStatus.IN_PROGRESS,
            sentCount = 0,
            receivedCount = 0,
            conflictCount = 0,
            rejectedCount = 0,
            errorMessage = null,
            initiatedBy = user.username,
        )
        syncDao.insertHistory(history)
        TransferService.start(context)

        var channel: MessageChannel? = null
        var latestStats = TransferStats()
        try {
            _phase.value = TransferPhase.Connecting(
                if (connection.isGroupOwner) "Waiting for the other phone to connect…" else "Connecting to the other phone…",
            )
            val socket = if (connection.isGroupOwner) {
                TcpConnector.accept(timeoutMs = ACCEPT_TIMEOUT_MS, onOpened = openResources::add)
            } else {
                val host = connection.groupOwnerAddress ?: throw TransferException("The other phone's address is unknown.")
                TcpConnector.connect(host, timeoutMs = CONNECT_TIMEOUT_MS, onOpened = openResources::add)
            }
            openResources.add(socket)
            socket.soTimeout = READ_TIMEOUT_MS
            val openChannel = MessageChannel(socket, gson, clock, deviceId).also { channel = it }

            _phase.value = TransferPhase.Connecting("Verifying the other device…")
            val authenticator = SessionAuthenticator(local, pairingManager, ::awaitApproval, normalReadTimeoutMs = READ_TIMEOUT_MS)
            val handshake = if (connection.isGroupOwner) {
                authenticator.runAsServer(openChannel)
            } else {
                authenticator.runAsClient(openChannel)
            }
            history = history.copy(
                transferId = handshake.transferId,
                peerDeviceId = handshake.peer.deviceId,
                peerName = handshake.peer.name,
                peerRole = handshake.peer.role,
            )
            syncDao.updateHistory(history)

            val stats = engine.exchange(openChannel, handshake, local, isClient = !connection.isGroupOwner, sessionOptions) { progress ->
                latestStats = progress.stats
                _phase.value = TransferPhase.InProgress(handshake.peer.name, progress.stage, progress.stats)
            }
            val now = clock.millis()
            syncDao.updateHistory(history.withStats(stats).copy(finishedAt = now, status = TransferStatus.COMPLETED))
            syncDao.markDeviceSynced(handshake.peer.deviceId, now)
            audit.log(
                "TRANSFER_COMPLETED",
                "DEVICE",
                handshake.peer.deviceId,
                "${handshake.peer.name}: sent ${stats.sent}, received ${stats.received}, " +
                    "conflicts ${stats.conflicts}, rejected ${stats.rejected}",
                actor = user.username,
            )
            _phase.value = TransferPhase.Completed(handshake.peer.name, stats, handshake.newlyPaired)
            openChannel.close()
            wifi.disconnect()
        } catch (e: Exception) {
            val cancelled = e is CancellationException || !currentCoroutineContext().isActive
            val message = if (cancelled) "Transfer cancelled." else userMessage(e)
            if (!cancelled) channel?.trySendError("TRANSFER_FAILED", message)
            withContext(NonCancellable) {
                syncDao.updateHistory(
                    history.withStats(latestStats).copy(
                        finishedAt = clock.millis(),
                        status = if (cancelled) TransferStatus.CANCELLED else TransferStatus.FAILED,
                        errorMessage = message,
                    ),
                )
                audit.log("TRANSFER_FAILED", "DEVICE", history.peerDeviceId, "${history.peerName}: $message", actor = user.username)
            }
            _phase.value = TransferPhase.Failed(message)
            if (e is CancellationException) throw e
        } finally {
            closeResources()
            pendingApproval = null
            TransferService.stop(context)
            if (options == null) withContext(NonCancellable) { releaseWifi() }
        }
    }

    private suspend fun awaitApproval(request: PairingRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingApproval = deferred
        _phase.value = TransferPhase.AwaitingApproval(request)
        val approved = withTimeoutOrNull(PAIRING_APPROVAL_TIMEOUT_MS) { deferred.await() } ?: false
        pendingApproval = null
        _phase.value = TransferPhase.Connecting(
            if (approved) "Waiting for the other phone to confirm the code…" else "Pairing was not approved.",
        )
        return approved
    }

    private suspend fun releaseWifi() {
        wifi.disconnect()
        wifi.stop()
    }

    private fun closeResources() {
        openResources.forEach { runCatching { it.close() } }
        openResources.clear()
    }

    private fun TransferHistoryEntity.withStats(stats: TransferStats) = copy(
        sentCount = stats.sent,
        receivedCount = stats.received,
        conflictCount = stats.conflicts,
        rejectedCount = stats.rejected,
    )

    private fun userMessage(e: Exception): String = when (e) {
        is TransferException -> e.message ?: "Transfer failed."
        is SocketTimeoutException -> "The other phone stopped responding. Keep both phones close and try again."
        is IOException -> "The connection was lost. Uncommitted data will be resent when you retry."
        else -> e.message ?: "Transfer failed."
    }

    private companion object {
        const val ACCEPT_TIMEOUT_MS = 60_000
        const val CONNECT_TIMEOUT_MS = 60_000L
        const val READ_TIMEOUT_MS = 30_000
        const val PAIRING_APPROVAL_TIMEOUT_MS = 120_000L
    }
}
