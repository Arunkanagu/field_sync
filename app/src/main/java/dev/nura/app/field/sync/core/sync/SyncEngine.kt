package dev.nura.app.field.sync.core.sync

import dev.nura.app.field.sync.core.security.HandshakeResult
import dev.nura.app.field.sync.core.security.LocalIdentity
import dev.nura.app.field.sync.core.transport.MessageChannel
import dev.nura.app.field.sync.core.transport.ProtocolViolationException
import dev.nura.app.field.sync.core.transport.RemoteAbortException
import dev.nura.app.field.sync.data.dto.MessageType
import dev.nura.app.field.sync.data.dto.SyncRequestPayload
import dev.nura.app.field.sync.data.dto.TransferCompletePayload
import dev.nura.app.field.sync.domain.model.SyncEntityType
import dev.nura.app.field.sync.domain.model.SyncPolicy
import javax.inject.Inject
import javax.inject.Singleton

/** What the local user chose to do in this session. */
data class TransferOptions(
    val sendTypes: Set<SyncEntityType>,
    val acceptIncoming: Boolean,
)

data class TransferStats(
    val sent: Int = 0,
    val received: Int = 0,
    val conflicts: Int = 0,
    val rejected: Int = 0,
    val rejectedReasons: List<String> = emptyList(),
)

data class TransferProgress(
    val stage: String,
    val stats: TransferStats,
)

/**
 * Exchanges data over an authenticated, encrypted [MessageChannel]. Both directions run in one
 * connection: the client sends first, then the server. Each direction is stop-and-wait:
 *
 * ```
 * sender: SYNC_REQUEST(types) → receiver: SYNC_ACCEPT(types it accepts)
 * sender: DATA_BATCH → receiver commits → BATCH_ACK → sender advances its cursor … repeat
 * sender: TRANSFER_COMPLETE
 * ```
 * Finally the client sends SESSION_CLOSE so the server knows every byte arrived before either
 * side tears down the Wi-Fi Direct group.
 */
@Singleton
class SyncEngine @Inject constructor(
    private val batchBuilder: BatchBuilder,
    private val batchProcessor: BatchProcessor,
) {

    suspend fun exchange(
        channel: MessageChannel,
        handshake: HandshakeResult,
        local: LocalIdentity,
        isClient: Boolean,
        options: TransferOptions,
        onProgress: (TransferProgress) -> Unit,
    ): TransferStats {
        var stats = TransferStats()
        val report: (String, TransferStats) -> Unit = { stage, updated ->
            stats = updated
            onProgress(TransferProgress(stage, updated))
        }
        if (isClient) {
            stats = sendPhase(channel, handshake, local, options, stats, report)
            stats = receivePhase(channel, handshake, local, options, stats, report)
            channel.send(channel.message(MessageType.SESSION_CLOSE))
        } else {
            stats = receivePhase(channel, handshake, local, options, stats, report)
            stats = sendPhase(channel, handshake, local, options, stats, report)
            channel.expect(MessageType.SESSION_CLOSE)
        }
        return stats
    }

    private suspend fun sendPhase(
        channel: MessageChannel,
        handshake: HandshakeResult,
        local: LocalIdentity,
        options: TransferOptions,
        initial: TransferStats,
        report: (String, TransferStats) -> Unit,
    ): TransferStats {
        var stats = initial
        val peerId = handshake.peer.deviceId
        val offered = SyncEntityType.entries.filter { it in options.sendTypes && SyncPolicy.canSend(local.role, it) }
        channel.send(channel.message(MessageType.SYNC_REQUEST).copy(sync = SyncRequestPayload(offered.map { it.name })))
        val acceptedNames = channel.expect(MessageType.SYNC_ACCEPT).sync?.entityTypes.orEmpty()
        val accepted = offered.filter { it.name in acceptedNames }

        var batches = 0
        var records = 0
        for (type in accepted) {
            batchBuilder.markStarted(peerId, type)
            var success = false
            try {
                while (true) {
                    val batch = batchBuilder.nextBatch(local.deviceId, peerId, type) ?: break
                    report("Sending ${type.displayName.lowercase()}…", stats)
                    channel.send(channel.message(MessageType.DATA_BATCH).copy(batch = batch.payload))
                    val ack = channel.expect(MessageType.BATCH_ACK).ack
                    if (ack == null || ack.batchId != batch.payload.batchId) {
                        throw ProtocolViolationException("acknowledgement for the wrong batch")
                    }
                    // Only now, after the receiver committed, are these records considered synced.
                    batchBuilder.acknowledge(peerId, type, batch.lastSeq)
                    batches++
                    records += batch.recordCount
                    stats = stats.copy(
                        sent = stats.sent + (ack.accepted ?: 0),
                        rejected = stats.rejected + (ack.rejected ?: 0),
                        rejectedReasons = stats.rejectedReasons + ack.rejectedReasons.orEmpty().map { "Sent ${type.displayName}: $it" },
                    )
                    report("Sending ${type.displayName.lowercase()}…", stats)
                }
                success = true
            } finally {
                batchBuilder.markFinished(peerId, type, success)
            }
        }
        channel.send(channel.message(MessageType.TRANSFER_COMPLETE).copy(complete = TransferCompletePayload(batches, records)))
        return stats
    }

    private suspend fun receivePhase(
        channel: MessageChannel,
        handshake: HandshakeResult,
        local: LocalIdentity,
        options: TransferOptions,
        initial: TransferStats,
        report: (String, TransferStats) -> Unit,
    ): TransferStats {
        var stats = initial
        val offered = channel.expect(MessageType.SYNC_REQUEST).sync?.entityTypes.orEmpty()
            .mapNotNull { name -> SyncEntityType.entries.firstOrNull { it.name == name } }
        val accepted = if (options.acceptIncoming) {
            offered.filter { SyncPolicy.canReceive(handshake.peer.role, it) }
        } else {
            emptyList()
        }
        channel.send(channel.message(MessageType.SYNC_ACCEPT).copy(sync = SyncRequestPayload(accepted.map { it.name })))
        report("Receiving…", stats)

        while (true) {
            val message = channel.receive()
            when (message.messageType) {
                MessageType.DATA_BATCH -> {
                    val batch = message.batch ?: throw ProtocolViolationException("DATA_BATCH without payload")
                    if (accepted.none { it.name == batch.entityType }) {
                        throw ProtocolViolationException("batch of a type that was not accepted")
                    }
                    val ack = batchProcessor.process(handshake.peer, local.role, handshake.transferId, batch)
                    channel.send(channel.message(MessageType.BATCH_ACK).copy(ack = ack))
                    stats = stats.copy(
                        received = stats.received + (ack.accepted ?: 0),
                        conflicts = stats.conflicts + (ack.conflicts ?: 0),
                        rejected = stats.rejected + (ack.rejected ?: 0),
                        rejectedReasons = stats.rejectedReasons + ack.rejectedReasons.orEmpty().map { "Received: $it" },
                    )
                    report("Receiving ${batch.entityType?.lowercase()}…", stats)
                }
                MessageType.TRANSFER_COMPLETE -> return stats
                MessageType.CANCEL -> throw RemoteAbortException("The other device cancelled the transfer.")
                MessageType.TRANSFER_ERROR -> throw RemoteAbortException(
                    "The other device reported an error: ${message.error?.message ?: "unknown error"}",
                )
                else -> throw ProtocolViolationException("unexpected ${message.messageType} while receiving")
            }
        }
    }
}
