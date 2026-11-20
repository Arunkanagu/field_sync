package dev.nura.app.field.sync.core.security

import dev.nura.app.field.sync.core.serialization.GsonProvider
import dev.nura.app.field.sync.core.transport.AuthenticationFailedException
import dev.nura.app.field.sync.core.transport.MessageChannel
import dev.nura.app.field.sync.core.transport.PairingRejectedException
import dev.nura.app.field.sync.core.transport.TransferException
import dev.nura.app.field.sync.data.dto.MessageType
import dev.nura.app.field.sync.data.dto.SyncRequestPayload
import dev.nura.app.field.sync.domain.model.Role
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.time.Clock
import java.util.UUID

/** Runs the real handshake over loopback TCP sockets. */
class SessionAuthenticatorTest {

    private val admin = LocalIdentity(UUID.randomUUID().toString(), "Admin Phone", Role.ADMIN)
    private val manager = LocalIdentity(UUID.randomUUID().toString(), "Manager Phone", Role.MANAGER)
    private val adminStore = InMemoryTrustStore()
    private val managerStore = InMemoryTrustStore()
    private val gson = GsonProvider.create()
    private val sockets = mutableListOf<Socket>()

    @After
    fun closeSockets() = sockets.forEach { it.close() }

    @Test
    fun firstContact_pairsWithMatchingCodes_thenExchangesEncryptedMessages() = runBlocking {
        val codes = mutableListOf<String>()
        val (server, client) = channels()

        val serverResult = async(Dispatchers.IO) {
            SessionAuthenticator(admin, adminStore, { codes += it.code; true }).runAsServer(server)
        }
        val clientResult = withContext(Dispatchers.IO) {
            SessionAuthenticator(manager, managerStore, { codes += it.code; true }).runAsClient(client)
        }
        val serverHandshake = serverResult.await()

        assertTrue(clientResult.newlyPaired)
        assertEquals(manager.deviceId, serverHandshake.peer.deviceId)
        assertEquals(Role.ADMIN, clientResult.peer.role)
        assertEquals(2, codes.size)
        assertEquals("Both phones must show the same code", codes[0], codes[1])
        assertNotNull(adminStore.trustedSecret(manager.deviceId))
        assertTrue(adminStore.trustedSecret(manager.deviceId)!!.contentEquals(managerStore.trustedSecret(admin.deviceId)))

        // The channel is now encrypted end to end.
        withContext(Dispatchers.IO) {
            client.send(client.message(MessageType.SYNC_REQUEST).copy(sync = SyncRequestPayload(listOf("ATTENDANCE"))))
            assertEquals(listOf("ATTENDANCE"), server.expect(MessageType.SYNC_REQUEST).sync?.entityTypes)
        }
    }

    @Test
    fun secondContact_authenticatesWithoutAskingTheUser() = runBlocking {
        pairOnce()
        val (server, client) = channels()
        val neverAsked: suspend (PairingRequest) -> Boolean = { fail("should not ask"); false }

        val serverResult = async(Dispatchers.IO) { SessionAuthenticator(admin, adminStore, neverAsked).runAsServer(server) }
        val clientResult = withContext(Dispatchers.IO) { SessionAuthenticator(manager, managerStore, neverAsked).runAsClient(client) }

        assertFalse(clientResult.newlyPaired)
        assertFalse(serverResult.await().newlyPaired)
    }

    @Test
    fun rejectedPairing_storesNoTrust() = runBlocking {
        val (server, client) = channels()
        val serverResult = async(Dispatchers.IO) {
            runCatching { SessionAuthenticator(admin, adminStore, { false }).runAsServer(server) }
        }
        val clientError = withContext(Dispatchers.IO) {
            runCatching { SessionAuthenticator(manager, managerStore, { true }).runAsClient(client) }
        }.exceptionOrNull()

        assertTrue(serverResult.await().exceptionOrNull() is PairingRejectedException)
        assertTrue(clientError is PairingRejectedException)
        assertEquals(null, adminStore.trustedSecret(manager.deviceId))
        assertEquals(null, managerStore.trustedSecret(admin.deviceId))
    }

    @Test
    fun impostorWithWrongSecret_failsAuthentication() = runBlocking {
        pairOnce()
        // An attacker claims the Manager's device ID but holds a different secret.
        val impostorStore = InMemoryTrustStore().apply {
            secrets[admin.deviceId] = SessionCrypto.randomBytes(32)
        }
        val (server, client) = channels()
        val serverResult = async(Dispatchers.IO) {
            runCatching { SessionAuthenticator(admin, adminStore, { true }).runAsServer(server) }.also { server.close() }
        }
        withContext(Dispatchers.IO) {
            runCatching { SessionAuthenticator(manager, impostorStore, { true }).runAsClient(client) }
        }
        val error = serverResult.await().exceptionOrNull()
        assertTrue("was $error", error is AuthenticationFailedException)
    }

    @Test
    fun unknownDeviceSendingAuth_isRejected() = runBlocking {
        // The client believes it is paired but the server has no record (e.g. revoked).
        pairOnce()
        adminStore.secrets.clear()
        val (server, client) = channels()
        val serverResult = async(Dispatchers.IO) {
            runCatching { SessionAuthenticator(admin, adminStore, { false }).runAsServer(server) }
        }
        val clientError = withContext(Dispatchers.IO) {
            runCatching { SessionAuthenticator(manager, managerStore, { false }).runAsClient(client) }
        }.exceptionOrNull()
        // The server says it does not trust the client, so the client falls back to pairing,
        // which the users decline.
        assertTrue(clientError is TransferException)
        assertTrue(serverResult.await().isFailure)
        assertEquals(null, adminStore.trustedSecret(manager.deviceId))
    }

    private suspend fun pairOnce() = coroutineScope {
        val (server, client) = channels()
        val s = async(Dispatchers.IO) { SessionAuthenticator(admin, adminStore, { true }).runAsServer(server) }
        withContext(Dispatchers.IO) { SessionAuthenticator(manager, managerStore, { true }).runAsClient(client) }
        s.await()
    }

    private fun channels(): Pair<MessageChannel, MessageChannel> {
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { listener ->
            val clientSocket = Socket(InetAddress.getLoopbackAddress(), listener.localPort)
            val serverSocket = listener.accept()
            listOf(clientSocket, serverSocket).forEach { it.soTimeout = 5_000 }
            sockets += clientSocket
            sockets += serverSocket
            val clock = Clock.systemUTC()
            return MessageChannel(serverSocket, gson, clock, admin.deviceId) to
                MessageChannel(clientSocket, gson, clock, manager.deviceId)
        }
    }

    private class InMemoryTrustStore : PeerTrustStore {
        val secrets = mutableMapOf<String, ByteArray>()
        override suspend fun trustedSecret(deviceId: String): ByteArray? = secrets[deviceId]
        override suspend fun saveTrustedPeer(peer: PeerIdentity, secret: ByteArray) {
            secrets[peer.deviceId] = secret
        }
    }
}
