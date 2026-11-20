package dev.nura.app.field.sync.core.transport

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException

/**
 * Length-prefixed framing: `[4-byte big-endian length][payload]`. TCP is a byte stream, so every
 * message is framed; the reader enforces a maximum size and reads exactly the declared bytes.
 */
object MessageFramer {

    const val DEFAULT_MAX_FRAME_BYTES = 4 * 1024 * 1024

    fun write(output: DataOutputStream, payload: ByteArray, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES) {
        require(payload.isNotEmpty() && payload.size <= maxFrameBytes) { "Frame size ${payload.size} is out of range." }
        output.writeInt(payload.size)
        output.write(payload)
        output.flush()
    }

    fun read(input: DataInputStream, maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES): ByteArray {
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            throw TransferException("The other device closed the connection.", e)
        }
        if (length <= 0 || length > maxFrameBytes) {
            throw ProtocolViolationException("frame length $length is out of range")
        }
        val payload = ByteArray(length)
        try {
            input.readFully(payload)
        } catch (e: EOFException) {
            throw ProtocolViolationException("connection closed in the middle of a message", e)
        }
        return payload
    }
}
