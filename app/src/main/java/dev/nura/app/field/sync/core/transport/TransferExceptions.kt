package dev.nura.app.field.sync.core.transport

import java.io.IOException

/** Base class for failures of a transfer session. [message] is shown to the user. */
open class TransferException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** The peer sent something that violates the protocol: malformed, oversized, unexpected or out of order. */
class ProtocolViolationException(message: String, cause: Throwable? = null) :
    TransferException("Protocol error: $message", cause)

/** The peer could not prove its identity, or a message failed its integrity check. */
class AuthenticationFailedException(message: String) : TransferException(message)

/** The peer cancelled or reported an error. */
class RemoteAbortException(message: String) : TransferException(message)

/** A user declined (or did not confirm) pairing. */
class PairingRejectedException(message: String) : TransferException(message)
