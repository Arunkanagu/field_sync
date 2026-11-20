package dev.nura.app.field.sync.core.security

import dev.nura.app.field.sync.core.transport.AuthenticationFailedException
import dev.nura.app.field.sync.core.transport.MessageChannel
import dev.nura.app.field.sync.core.transport.PairingRejectedException
import dev.nura.app.field.sync.core.transport.ProtocolViolationException
import dev.nura.app.field.sync.data.dto.AuthPayload
import dev.nura.app.field.sync.data.dto.Envelope
import dev.nura.app.field.sync.data.dto.HelloPayload
import dev.nura.app.field.sync.data.dto.MessageType
import dev.nura.app.field.sync.domain.model.Role
import java.security.PublicKey
import java.util.Base64
import java.util.UUID

data class LocalIdentity(val deviceId: String, val name: String, val role: Role)

data class PeerIdentity(val deviceId: String, val name: String, val role: Role)

/** Shown to the user, who must compare [code] with the code on the other phone. */
data class PairingRequest(val peer: PeerIdentity, val code: String)

data class HandshakeResult(
    val peer: PeerIdentity,
    val transferId: String,
    /** True when this session created (or replaced) the trust relationship. */
    val newlyPaired: Boolean,
)

/** Registry of trusted peers and their pairing secrets. */
interface PeerTrustStore {
    /** The pairing secret for a currently trusted peer, or null if the peer is unknown or revoked. */
    suspend fun trustedSecret(deviceId: String): ByteArray?

    suspend fun saveTrustedPeer(peer: PeerIdentity, secret: ByteArray)
}

/**
 * Establishes an authenticated, encrypted session over a fresh connection (project plan, section 11).
 *
 * ```
 * client                                   server
 *   HELLO(nonceC, ephemeral key C)  ──▶
 *                                   ◀──  HELLO(nonceS, ephemeral key S, trustsPeer)
 * if both sides trust each other:
 *   AUTH_RESPONSE(mac "client")     ──▶
 *                                   ◀──  AUTH_RESPONSE(mac "server")
 * otherwise (user-approved pairing; both screens show the same 6-digit code):
 *   PAIR_REQUEST                    ──▶
 *   PAIR_CONFIRM(mac) after approval ◀─▶ PAIR_CONFIRM(mac) after approval
 * ```
 *
 * Session keys mix the long-term pairing secret with the per-session ECDH secret and the
 * transcript, then the channel switches to [SessionCipher]. A device name or MAC address is
 * never treated as identity: only possession of the pairing secret is.
 */
class SessionAuthenticator(
    private val local: LocalIdentity,
    private val trustStore: PeerTrustStore,
    /** Suspends until the local user approves (true) or rejects (false) the pairing. */
    private val approvePairing: suspend (PairingRequest) -> Boolean,
    /** Socket read timeout while waiting for the other user to confirm the pairing code. */
    private val pairingReadTimeoutMs: Int = 150_000,
    private val normalReadTimeoutMs: Int = 30_000,
) {

    private val encoder = Base64.getEncoder()
    private val decoder = Base64.getDecoder()

    suspend fun runAsClient(channel: MessageChannel): HandshakeResult {
        channel.transferId = UUID.randomUUID().toString()
        val ephemeral = SessionCrypto.generateEphemeralKeyPair()
        val myHello = HelloPayload(
            deviceName = local.name,
            role = local.role.name,
            nonce = encoder.encodeToString(SessionCrypto.randomBytes(SessionCrypto.NONCE_BYTES)),
            ephemeralKey = encoder.encodeToString(ephemeral.public.encoded),
        )
        channel.send(channel.message(MessageType.HELLO).copy(hello = myHello))

        val reply = channel.expect(MessageType.HELLO)
        val peerHello = parseHello(reply)
        channel.peerDeviceId = peerHello.identity.deviceId

        val transcript = transcript(local.deviceId, myHello, peerHello.identity.deviceId, reply.hello!!)
        val shared = SessionCrypto.ecdh(ephemeral.private, peerHello.publicKey)
        val knownSecret = trustStore.trustedSecret(peerHello.identity.deviceId)

        val (secret, newlyPaired) = if (knownSecret != null && reply.hello.trustsPeer == true) {
            val keys = SessionCrypto.deriveSessionKeys(knownSecret, shared, transcript)
            channel.send(channel.message(MessageType.AUTH_RESPONSE).copy(auth = mac(keys.authKey, CLIENT_LABEL, transcript)))
            verifyMac(channel.expect(MessageType.AUTH_RESPONSE).auth, keys.authKey, SERVER_LABEL, transcript)
            knownSecret to false
        } else {
            channel.send(channel.message(MessageType.PAIR_REQUEST))
            pair(channel, peerHello.identity, shared, transcript, ownLabel = CLIENT_LABEL, peerLabel = SERVER_LABEL) to true
        }

        val keys = SessionCrypto.deriveSessionKeys(secret, shared, transcript)
        channel.enableEncryption(SessionCipher(sendKey = keys.clientToServerKey, receiveKey = keys.serverToClientKey))
        return HandshakeResult(peerHello.identity, requireNotNull(channel.transferId), newlyPaired)
    }

    suspend fun runAsServer(channel: MessageChannel): HandshakeResult {
        val hello = channel.expect(MessageType.HELLO)
        val transferId = hello.transferId?.takeIf(::isUuid) ?: throw ProtocolViolationException("invalid transfer ID")
        val peerHello = parseHello(hello)
        channel.transferId = transferId
        channel.peerDeviceId = peerHello.identity.deviceId

        val knownSecret = trustStore.trustedSecret(peerHello.identity.deviceId)
        val ephemeral = SessionCrypto.generateEphemeralKeyPair()
        val myHello = HelloPayload(
            deviceName = local.name,
            role = local.role.name,
            nonce = encoder.encodeToString(SessionCrypto.randomBytes(SessionCrypto.NONCE_BYTES)),
            ephemeralKey = encoder.encodeToString(ephemeral.public.encoded),
            trustsPeer = knownSecret != null,
        )
        channel.send(channel.message(MessageType.HELLO).copy(hello = myHello))

        val transcript = transcript(peerHello.identity.deviceId, hello.hello!!, local.deviceId, myHello)
        val shared = SessionCrypto.ecdh(ephemeral.private, peerHello.publicKey)

        val next = channel.receive()
        val (secret, newlyPaired) = when (next.messageType) {
            MessageType.AUTH_RESPONSE -> {
                if (knownSecret == null) throw AuthenticationFailedException("Unknown device. Pair the devices first.")
                val keys = SessionCrypto.deriveSessionKeys(knownSecret, shared, transcript)
                verifyMac(next.auth, keys.authKey, CLIENT_LABEL, transcript)
                channel.send(channel.message(MessageType.AUTH_RESPONSE).copy(auth = mac(keys.authKey, SERVER_LABEL, transcript)))
                knownSecret to false
            }
            MessageType.PAIR_REQUEST ->
                pair(channel, peerHello.identity, shared, transcript, ownLabel = SERVER_LABEL, peerLabel = CLIENT_LABEL) to true
            MessageType.CANCEL -> throw PairingRejectedException("The other device cancelled the connection.")
            else -> throw ProtocolViolationException("unexpected ${next.messageType} during handshake")
        }

        val keys = SessionCrypto.deriveSessionKeys(secret, shared, transcript)
        channel.enableEncryption(SessionCipher(sendKey = keys.serverToClientKey, receiveKey = keys.clientToServerKey))
        return HandshakeResult(peerHello.identity, transferId, newlyPaired)
    }

    private suspend fun pair(
        channel: MessageChannel,
        peer: PeerIdentity,
        shared: ByteArray,
        transcript: ByteArray,
        ownLabel: String,
        peerLabel: String,
    ): ByteArray {
        val approved = approvePairing(PairingRequest(peer, SessionCrypto.pairingCode(shared, transcript)))
        if (!approved) {
            channel.trySendError("PAIRING_REJECTED", "Pairing was rejected.")
            throw PairingRejectedException("Pairing was not approved on this device.")
        }
        val confirmKey = SessionCrypto.pairingConfirmKey(shared, transcript)
        channel.send(channel.message(MessageType.PAIR_CONFIRM).copy(auth = mac(confirmKey, ownLabel, transcript)))

        channel.setReadTimeout(pairingReadTimeoutMs)
        val reply = try {
            channel.receive()
        } finally {
            channel.setReadTimeout(normalReadTimeoutMs)
        }
        when (reply.messageType) {
            MessageType.PAIR_CONFIRM -> verifyMac(reply.auth, confirmKey, peerLabel, transcript)
            MessageType.CANCEL -> throw PairingRejectedException("Pairing was rejected on the other device.")
            else -> throw ProtocolViolationException("unexpected ${reply.messageType} during pairing")
        }

        val secret = SessionCrypto.pairingSecret(shared, transcript)
        trustStore.saveTrustedPeer(peer, secret)
        return secret
    }

    private class ParsedHello(val identity: PeerIdentity, val publicKey: PublicKey)

    private fun parseHello(envelope: Envelope): ParsedHello {
        val hello = envelope.hello ?: throw ProtocolViolationException("HELLO without payload")
        val deviceId = envelope.senderDeviceId?.takeIf(::isUuid) ?: throw ProtocolViolationException("invalid device ID")
        if (deviceId == local.deviceId) throw ProtocolViolationException("the other device reported this device's ID")
        val role = Role.entries.firstOrNull { it.name == hello.role } ?: throw ProtocolViolationException("invalid role")
        val name = hello.deviceName?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_NAME }
            ?: throw ProtocolViolationException("invalid device name")
        val nonce = decode(hello.nonce)
        if (nonce.size != SessionCrypto.NONCE_BYTES) throw ProtocolViolationException("invalid nonce")
        val publicKey = try {
            SessionCrypto.decodePublicKey(decode(hello.ephemeralKey))
        } catch (e: IllegalArgumentException) {
            throw ProtocolViolationException("invalid key", e)
        }
        return ParsedHello(PeerIdentity(deviceId, name, role), publicKey)
    }

    /** Binds both identities, nonces and ephemeral keys, so neither side's claims can be swapped. */
    private fun transcript(
        clientId: String,
        clientHello: HelloPayload,
        serverId: String,
        serverHello: HelloPayload,
    ): ByteArray {
        fun HelloPayload.parts() = listOf(
            deviceName.orEmpty().toByteArray(Charsets.UTF_8),
            role.orEmpty().toByteArray(Charsets.UTF_8),
            decode(nonce),
            decode(ephemeralKey),
        )
        val parts = listOf(clientId.toByteArray(), serverId.toByteArray()) +
            clientHello.parts() + serverHello.parts()
        return SessionCrypto.transcriptHash(*parts.toTypedArray())
    }

    private fun mac(key: ByteArray, label: String, transcript: ByteArray) =
        AuthPayload(encoder.encodeToString(SessionCrypto.hmac(key, label.toByteArray() + transcript)))

    private fun verifyMac(payload: AuthPayload?, key: ByteArray, label: String, transcript: ByteArray) {
        val actual = payload?.mac?.let { runCatching { decoder.decode(it) }.getOrNull() }
        val expected = SessionCrypto.hmac(key, label.toByteArray() + transcript)
        if (!SessionCrypto.macMatches(expected, actual)) {
            throw AuthenticationFailedException(
                "The other device could not prove its identity. If it was reset, remove it from trusted devices and pair again.",
            )
        }
    }

    private fun decode(value: String?): ByteArray =
        value?.let { runCatching { decoder.decode(it) }.getOrNull() }
            ?: throw ProtocolViolationException("invalid encoding")

    private fun isUuid(value: String): Boolean = runCatching { UUID.fromString(value) }.isSuccess && value.length == 36

    private companion object {
        const val CLIENT_LABEL = "fieldsync client"
        const val SERVER_LABEL = "fieldsync server"
        const val MAX_NAME = 64
    }
}
