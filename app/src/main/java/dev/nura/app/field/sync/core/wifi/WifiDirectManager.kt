package dev.nura.app.field.sync.core.wifi

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.InetAddress
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class WifiDirectException(message: String) : Exception(message)

enum class PeerStatus(val label: String) {
    CONNECTED("Connected"),
    INVITED("Invited"),
    FAILED("Failed"),
    AVAILABLE("Available"),
    UNAVAILABLE("Unavailable"),
}

data class NearbyPeer(val name: String, val address: String, val status: PeerStatus)

/** Group information for an established Wi-Fi Direct connection. */
data class P2pConnection(val isGroupOwner: Boolean, val groupOwnerAddress: InetAddress?)

data class WifiDirectState(
    val isSupported: Boolean,
    val isActive: Boolean = false,
    val isEnabled: Boolean = false,
    val isDiscovering: Boolean = false,
    val peers: List<NearbyPeer> = emptyList(),
    val connection: P2pConnection? = null,
    val channelLost: Boolean = false,
)

/**
 * Wraps [WifiP2pManager]: discovery, connection and group information, exposed as a [StateFlow].
 * [start] registers for Wi-Fi Direct broadcasts while the transfer feature is in use; [stop]
 * releases the channel. Callers must hold [WifiDirectPermissions.required] before discovering
 * or connecting.
 */
@Singleton
class WifiDirectManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val manager: WifiP2pManager? = context.getSystemService(WifiP2pManager::class.java)
    private var channel: WifiP2pManager.Channel? = null
    private var receiver: BroadcastReceiver? = null

    private val _state = MutableStateFlow(
        WifiDirectState(
            isSupported = manager != null &&
                context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT),
        ),
    )
    val state: StateFlow<WifiDirectState> = _state.asStateFlow()

    fun start() {
        val manager = manager ?: return
        if (receiver != null) return
        channel = manager.initialize(context, Looper.getMainLooper()) {
            // The framework dropped the channel (for example Wi-Fi was reset). Start again on next use.
            channel = null
            _state.update { it.copy(channelLost = true, isDiscovering = false, peers = emptyList(), connection = null) }
        }
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)
        }
        val newReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = handleBroadcast(intent)
        }
        // System broadcasts are still delivered to non-exported receivers.
        ContextCompat.registerReceiver(context, newReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiver = newReceiver
        _state.update { it.copy(isActive = true, channelLost = false) }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        channel?.let { ch ->
            manager?.stopPeerDiscovery(ch, null)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) ch.close()
        }
        channel = null
        _state.update { it.copy(isActive = false, isDiscovering = false, peers = emptyList()) }
    }

    @SuppressLint("MissingPermission")
    suspend fun discoverPeers() {
        requirePermissions()
        val (manager, channel) = requireChannel()
        action { manager.discoverPeers(channel, it) }
        _state.update { it.copy(isDiscovering = true) }
    }

    suspend fun stopDiscovery() {
        val (manager, channel) = requireChannel()
        action { manager.stopPeerDiscovery(channel, it) }
        _state.update { it.copy(isDiscovering = false) }
    }

    /** Sends a connection invitation; the other phone's user must accept it in the system dialog. */
    @SuppressLint("MissingPermission")
    suspend fun connect(deviceAddress: String) {
        requirePermissions()
        val (manager, channel) = requireChannel()
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            @Suppress("DEPRECATION")
            wps.setup = WpsInfo.PBC
        }
        action { manager.connect(channel, config, it) }
    }

    suspend fun cancelConnect() {
        val (manager, channel) = requireChannel()
        runCatching { action { manager.cancelConnect(channel, it) } }
    }

    /** Leaves the Wi-Fi Direct group, releasing the connection on both phones. */
    suspend fun disconnect() {
        val manager = manager ?: return
        val channel = channel ?: return
        runCatching { action { manager.removeGroup(channel, it) } }
        _state.update { it.copy(connection = null) }
    }

    private fun handleBroadcast(intent: Intent) {
        when (intent.action) {
            WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) == WifiP2pManager.WIFI_P2P_STATE_ENABLED
                _state.update {
                    if (enabled) it.copy(isEnabled = true) else it.copy(isEnabled = false, isDiscovering = false, peers = emptyList())
                }
            }
            WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                val list = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_P2P_DEVICE_LIST, WifiP2pDeviceList::class.java)
                _state.update { it.copy(peers = list?.deviceList.orEmpty().map(::toPeer).sortedBy { peer -> peer.name }) }
            }
            WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                val info = IntentCompat.getParcelableExtra(intent, WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                val connection = info?.takeIf { it.groupFormed }?.let {
                    P2pConnection(isGroupOwner = it.isGroupOwner, groupOwnerAddress = it.groupOwnerAddress)
                }
                _state.update { it.copy(connection = connection) }
            }
            WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION -> {
                val started = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, -1) ==
                    WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED
                _state.update { it.copy(isDiscovering = started) }
            }
        }
    }

    private fun toPeer(device: WifiP2pDevice) = NearbyPeer(
        name = device.deviceName?.takeIf { it.isNotBlank() } ?: device.deviceAddress,
        address = device.deviceAddress,
        status = when (device.status) {
            WifiP2pDevice.CONNECTED -> PeerStatus.CONNECTED
            WifiP2pDevice.INVITED -> PeerStatus.INVITED
            WifiP2pDevice.FAILED -> PeerStatus.FAILED
            WifiP2pDevice.AVAILABLE -> PeerStatus.AVAILABLE
            else -> PeerStatus.UNAVAILABLE
        },
    )

    private fun requirePermissions() {
        if (!WifiDirectPermissions.hasRequired(context)) throw WifiDirectException("Nearby-device permission is required.")
    }

    private fun requireChannel(): Pair<WifiP2pManager, WifiP2pManager.Channel> {
        val manager = manager ?: throw WifiDirectException("This phone does not support Wi-Fi Direct.")
        if (channel == null) start()
        val channel = channel ?: throw WifiDirectException("Wi-Fi Direct is not available right now.")
        return manager to channel
    }

    private suspend fun action(block: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            block(
                object : WifiP2pManager.ActionListener {
                    override fun onSuccess() {
                        if (continuation.isActive) continuation.resume(Unit)
                    }

                    override fun onFailure(reason: Int) {
                        if (continuation.isActive) continuation.resumeWithException(WifiDirectException(reasonMessage(reason)))
                    }
                },
            )
        }

    private fun reasonMessage(reason: Int) = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "Wi-Fi Direct is not supported on this phone."
        WifiP2pManager.BUSY -> "Wi-Fi Direct is busy. Try again in a moment."
        WifiP2pManager.NO_SERVICE_REQUESTS -> "No service requests."
        else -> "Wi-Fi Direct reported an error. Check that Wi-Fi is on."
    }
}
