package dev.nura.app.field.sync.data.dto

/** Message types of the FieldSync transfer protocol (project plan, section 8). */
enum class MessageType {
    /** Plain text: identity, nonce and ephemeral ECDH key. */
    HELLO,

    /** Plain text: proves possession of the pairing secret for an already-trusted peer. */
    AUTH_RESPONSE,

    /** Plain text: at least one side does not trust the other; start user-approved pairing. */
    PAIR_REQUEST,

    /** Plain text: sent after the local user confirmed the pairing code; proves the ECDH secret. */
    PAIR_CONFIRM,

    // Everything below is sent only over the encrypted channel.
    SYNC_REQUEST,
    SYNC_ACCEPT,
    DATA_BATCH,
    BATCH_ACK,
    TRANSFER_COMPLETE,
    SESSION_CLOSE,
    TRANSFER_ERROR,
    CANCEL,
}

/**
 * One protocol message. Exactly one payload field is set, matching [messageType].
 * Fields are nullable because Gson ignores Kotlin nullability; [dev.nura.app.field.sync.core.transport.MessageChannel]
 * validates the header and the receiving code validates payloads.
 */
data class Envelope(
    val protocolVersion: Int? = null,
    val messageType: MessageType? = null,
    val transferId: String? = null,
    val senderDeviceId: String? = null,
    val receiverDeviceId: String? = null,
    val createdAt: Long? = null,
    val hello: HelloPayload? = null,
    val auth: AuthPayload? = null,
    val sync: SyncRequestPayload? = null,
    val batch: DataBatchPayload? = null,
    val ack: BatchAckPayload? = null,
    val complete: TransferCompletePayload? = null,
    val error: ErrorPayload? = null,
)

data class HelloPayload(
    val deviceName: String? = null,
    val role: String? = null,
    /** Base64, 32 random bytes. */
    val nonce: String? = null,
    /** Base64 X.509-encoded P-256 public key, fresh for every session. */
    val ephemeralKey: String? = null,
    /** Server only: whether the server already trusts the client. */
    val trustsPeer: Boolean? = null,
)

data class AuthPayload(
    /** Base64 HMAC-SHA256 over the handshake transcript. */
    val mac: String? = null,
)

data class SyncRequestPayload(
    val entityTypes: List<String>? = null,
)

data class DataBatchPayload(
    val batchId: String? = null,
    val entityType: String? = null,
    val employees: List<EmployeeDto>? = null,
    val attendance: List<AttendanceDto>? = null,
    val jobs: List<JobDto>? = null,
)

data class BatchAckPayload(
    val batchId: String? = null,
    val accepted: Int? = null,
    val ignored: Int? = null,
    val conflicts: Int? = null,
    val rejected: Int? = null,
    val rejectedReasons: List<String>? = null,
)

data class TransferCompletePayload(
    val batchCount: Int? = null,
    val recordCount: Int? = null,
)

data class ErrorPayload(
    val code: String? = null,
    val message: String? = null,
)
