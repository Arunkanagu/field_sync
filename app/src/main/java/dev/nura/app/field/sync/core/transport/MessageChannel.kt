package dev.nura.app.field.sync.core.transport

import com.google.gson.Gson
import com.google.gson.JsonParseException
import dev.nura.app.field.sync.core.security.SessionCipher
import dev.nura.app.field.sync.data.dto.Envelope
import dev.nura.app.field.sync.data.dto.ErrorPayload
import dev.nura.app.field.sync.data.dto.MessageType
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.time.Clock

/**
 * Sends and receives [Envelope]s over a socket using [MessageFramer]. After [enableEncryption]
 * every frame is sealed with AES-GCM; before that only handshake messages may be exchanged.
 *
 * Blocking: call from an IO dispatcher. Not thread-safe.
 */
class MessageChannel(
    private val socket: Socket,
    private val gson: Gson,
    private val clock: Clock,
    val localDeviceId: String,
    private val maxFrameBytes: Int = MessageFramer.DEFAULT_MAX_FRAME_BYTES,
) : Closeable {

    private val input = DataInputStream(BufferedInputStream(socket.getInputStream()))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream()))
    private var cipher: SessionCipher? = null

    /** Set by the client when it opens the session, and by the server from the first HELLO. */
    var transferId: String? = null

    /** Set once the peer's HELLO has been received; later messages must come from this device. */
    var peerDeviceId: String? = null

    val isEncrypted: Boolean get() = cipher != null

    fun enableEncryption(sessionCipher: SessionCipher) {
        cipher = sessionCipher
    }

    fun setReadTimeout(millis: Int) {
        socket.soTimeout = millis
    }

    /** A header for a new outgoing message; add the payload with `copy(...)`. */
    fun message(type: MessageType) = Envelope(
        protocolVersion = PROTOCOL_VERSION,
        messageType = type,
        transferId = transferId,
        senderDeviceId = localDeviceId,
        receiverDeviceId = peerDeviceId,
        createdAt = clock.millis(),
    )

    fun send(envelope: Envelope) {
        val type = requireNotNull(envelope.messageType)
        check(isEncrypted || type in PLAINTEXT_TYPES) { "$type must be sent encrypted" }
        val json = gson.toJson(envelope).toByteArray(Charsets.UTF_8)
        MessageFramer.write(output, cipher?.seal(json) ?: json, maxFrameBytes)
    }

    fun receive(): Envelope {
        val frame = MessageFramer.read(input, maxFrameBytes)
        val json = cipher?.open(frame) ?: frame
        val envelope = try {
            gson.fromJson(String(json, Charsets.UTF_8), Envelope::class.java)
        } catch (e: JsonParseException) {
            throw ProtocolViolationException("malformed message", e)
        } ?: throw ProtocolViolationException("empty message")
        validateHeader(envelope)
        return envelope
    }

    /** Receives the next message and requires it to be [type]. Peer cancellations and errors become exceptions. */
    fun expect(type: MessageType): Envelope {
        val envelope = receive()
        return when (envelope.messageType) {
            type -> envelope
            MessageType.CANCEL -> throw RemoteAbortException("The other device cancelled the transfer.")
            MessageType.TRANSFER_ERROR -> throw RemoteAbortException(
                "The other device reported an error: ${envelope.error?.message ?: "unknown error"}",
            )
            else -> throw ProtocolViolationException("expected $type but received ${envelope.messageType}")
        }
    }

    /** Best effort: tells the peer why the session is ending. Never throws. */
    fun trySendError(code: String, message: String) {
        runCatching {
            val type = if (isEncrypted) MessageType.TRANSFER_ERROR else MessageType.CANCEL
            send(message(type).copy(error = ErrorPayload(code, message.take(200))))
        }
    }

    private fun validateHeader(envelope: Envelope) {
        val type = envelope.messageType ?: throw ProtocolViolationException("missing or unknown message type")
        if (envelope.protocolVersion != PROTOCOL_VERSION) {
            throw ProtocolViolationException("unsupported protocol version ${envelope.protocolVersion}")
        }
        if (!isEncrypted && type !in PLAINTEXT_TYPES) {
            throw ProtocolViolationException("$type received before the session was authenticated")
        }
        val expectedTransfer = transferId
        if (expectedTransfer != null && envelope.transferId != expectedTransfer) {
            throw ProtocolViolationException("message belongs to a different transfer")
        }
        val expectedPeer = peerDeviceId
        if (expectedPeer != null && envelope.senderDeviceId != expectedPeer) {
            throw ProtocolViolationException("message came from an unexpected device")
        }
        if (expectedPeer != null && envelope.receiverDeviceId != localDeviceId) {
            throw ProtocolViolationException("message was addressed to a different device")
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        const val PROTOCOL_VERSION = 1

        private val PLAINTEXT_TYPES = setOf(
            MessageType.HELLO,
            MessageType.AUTH_RESPONSE,
            MessageType.PAIR_REQUEST,
            MessageType.PAIR_CONFIRM,
            MessageType.CANCEL,
        )
    }
}
