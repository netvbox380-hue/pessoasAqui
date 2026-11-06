package br.com.pessoasaqui

import android.bluetooth.BluetoothProfile
import br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager
import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.core.moderation.ContentModerationManager
import br.com.pessoasaqui.core.proximity.BleCryptoHandshakeEngine
import br.com.pessoasaqui.core.proximity.BleDirectTransportCoordinator
import br.com.pessoasaqui.core.proximity.BleGattClientManager
import br.com.pessoasaqui.core.proximity.BleGattClientManager.BleGattClientCallback
import br.com.pessoasaqui.core.proximity.BleGattClientManager.BleGattClientDriver
import br.com.pessoasaqui.core.proximity.BleGattServerManager
import br.com.pessoasaqui.core.proximity.BleGattServerManager.BleGattServerCallback
import br.com.pessoasaqui.core.proximity.BleGattServerManager.BleGattServerDriver
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager.BleProximitySession
import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Suíte de Testes da FASE 3D: Integração do Transporte Bluetooth GATT ao Modo A (Conversas Temporárias).
 *
 * Valida integralmente os 18 requisitos formais estabelecidos na autorização:
 * 1. Seleção exclusiva do transporte Bluetooth para o Modo A.
 * 2. Seleção exclusiva do transporte Internet para o Modo B.
 * 3. Primeiro contato entre participantes desconhecidos (Handshake Inicial).
 * 4. Autenticação criptográfica mútua (ECDH + ECDSA + SessionConfirmMac).
 * 5. Envio de mensagem temporária criptografada (AES-256-GCM + IV determinístico).
 * 6. Recepção de mensagem temporária com entrega na UI em memória.
 * 7. Fragmentação e remontagem transparente no transporte GATT.
 * 8. Confirmação de entrega física (MSG_ACK).
 * 9. Rejeição de pacotes antigos ou com repetição de IV (Anti-Replay).
 * 10. Rejeição de sessões expiradas (> 45s de ausência).
 * 11. Reconexão rápida após oscilação Bluetooth usando SESSION_RESUME (20 bytes).
 * 12. Preservação estrita das mensagens temporárias em memória RAM.
 * 13. Ausência de persistência em disco das conversas temporárias.
 * 14. Isolamento estrito entre 3 ou mais participantes simultâneos.
 * 15. Falha de rádio Bluetooth sem fallback para o Render.
 * 16. Comunicação permanente funcionando normalmente mesmo com participantes lado a lado.
 * 17. Opção A: Promoção controlada de conversa temporária para persistente na conexão mútua.
 * 18. Preservação das regras de exclusão voluntária e revogação.
 */
class BleTemporaryChatIntegrationTest {

    private lateinit var framingEngineA: BlePacketFramingEngine
    private lateinit var framingEngineB: BlePacketFramingEngine
    private lateinit var sessionManagerA: BleSessionTokenManager
    private lateinit var sessionManagerB: BleSessionTokenManager

    private lateinit var serverDriverA: IntegrationGattServerDriver
    private lateinit var clientDriverA: IntegrationGattClientDriver

    private lateinit var serverDriverB: IntegrationGattServerDriver
    private lateinit var clientDriverB: IntegrationGattClientDriver

    private lateinit var coordinatorA: BleDirectTransportCoordinator
    private lateinit var coordinatorB: BleDirectTransportCoordinator

    private val peerAddressB = "BB:BB:BB:BB:BB:02"
    private val peerAddressA = "AA:AA:AA:AA:AA:01"

    @Before
    fun setUp() {
        framingEngineA = BlePacketFramingEngine()
        framingEngineB = BlePacketFramingEngine()
        sessionManagerA = BleSessionTokenManager()
        sessionManagerB = BleSessionTokenManager()

        serverDriverA = IntegrationGattServerDriver()
        clientDriverA = IntegrationGattClientDriver()

        serverDriverB = IntegrationGattServerDriver()
        clientDriverB = IntegrationGattClientDriver()

        // Interliga Cliente A ao Servidor B
        clientDriverA.targetServer = serverDriverB
        serverDriverB.targetClient = clientDriverA

        // Interliga Cliente B ao Servidor A
        clientDriverB.targetServer = serverDriverA
        serverDriverA.targetClient = clientDriverB

        coordinatorA = BleDirectTransportCoordinator(
            context = null,
            framingEngine = framingEngineA,
            sessionManager = sessionManagerA,
            serverDriver = serverDriverA,
            clientDriver = clientDriverA
        )

        coordinatorB = BleDirectTransportCoordinator(
            context = null,
            framingEngine = framingEngineB,
            sessionManager = sessionManagerB,
            serverDriver = serverDriverB,
            clientDriver = clientDriverB
        )

        coordinatorA.start()
        coordinatorB.start()
    }

    // -------------------------------------------------------------------------
    // 1. Seleção Exclusiva do Transporte Bluetooth para o Modo A
    // -------------------------------------------------------------------------
    @Test
    fun test01_ExclusiveBluetoothTransportSelectionForModoA() {
        var bleSent = false
        var internetSent = false

        val testCoordinator = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                bleSent = true
                return true
            }
        }

        val testNetworkClient = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                internetSent = true
                return true
            }
        }

        val repo = PessoasAquiRepository(
            injectedBleTransportCoordinator = testCoordinator,
            networkClient = testNetworkClient
        )

        val temporaryPeerId = "7B09F14A"
        assertFalse("Peer temporário não deve ser permanente", repo.isPeerPermanent(temporaryPeerId))

        // Envia mensagem privada para peer temporário (Modo A)
        repo.sendPrivateMessage(temporaryPeerId, "Olá no Modo A via Bluetooth!")

        assertTrue("Mensagem do Modo A deve ser roteada via Bluetooth BLE", bleSent)
        assertFalse("Mensagem do Modo A NUNCA deve ser enviada via Internet/Render", internetSent)
    }

    // -------------------------------------------------------------------------
    // 2. Seleção Exclusiva do Transporte Internet para o Modo B
    // -------------------------------------------------------------------------
    @Test
    fun test02_ExclusiveInternetTransportSelectionForModoB() {
        var bleSent = false
        var internetSent = false

        val testCoordinator = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                bleSent = true
                return true
            }
        }

        val testNetworkClient = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                internetSent = true
                return true
            }
        }

        val repo = PessoasAquiRepository(
            injectedBleTransportCoordinator = testCoordinator,
            networkClient = testNetworkClient
        )

        val peerManager = CryptoIdentityManager()
        val permanentPeerId = peerManager.getTechnicalIdentity()
        E2eeCryptoEngine.registerPeerPublicKey(permanentPeerId, peerManager.getPublicKeyBase64())
        // Estabelece conexão mútua legítima (Modo B)
        repo.setMarkPerson(permanentPeerId, mark = true, explicitMutual = true)
        repo.confirmMutualConnectionFromServer(permanentPeerId)
        assertTrue("Peer marcado mutuamente deve ser permanente (Modo B)", repo.isPeerPermanent(permanentPeerId))

        // Envia mensagem privada para conexão permanente (Modo B)
        repo.sendPrivateMessage(permanentPeerId, "Olá no Modo B via Internet!")

        assertTrue("Mensagem do Modo B deve ser enviada via Internet/WebSocket", internetSent)
        assertFalse("Mensagem do Modo B NUNCA deve ser roteada por Bluetooth BLE", bleSent)
    }

    // -------------------------------------------------------------------------
    // 3. Primeiro Contato entre Participantes Desconhecidos (Handshake Inicial)
    // -------------------------------------------------------------------------
    @Test
    fun test03_FirstContactUnknownParticipantsHandshake() {
        val aliceId = coordinatorA.cryptoIdentityManager.getTechnicalIdentity()
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()

        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(aliceId, peerAddressA)

        // Inicialmente nenhum deles possui sessão lógica em memória
        assertNull(sessionManagerA.getSessionByPeer(bobId))
        assertNull(sessionManagerB.getSessionByPeer(aliceId))

        // Cliente A conecta e inicia handshake mútuo com Bob
        var handshakeCompleted = false
        var sharedSessionId: String? = null

        coordinatorA.clientManager.connect(peerAddressB)
        assertEquals(BleGattClientManager.ConnectionState.READY, coordinatorA.clientManager.connectionState)

        val started = coordinatorA.clientManager.startHandshake(
            peerIdentityHash = bobId,
            localIdHash = aliceId,
            localKeyPair = coordinatorA.cryptoIdentityManager.getActiveKeyPair()!!,
            onComplete = { success, sId ->
                handshakeCompleted = success
                sharedSessionId = sId
            }
        )

        assertTrue("Handshake deve ser disparado com sucesso", started)
        assertTrue("Handshake deve ser concluído com sucesso mútuo", handshakeCompleted)
        assertNotNull("Deve existir um sessionId compartilhado", sharedSessionId)

        // Ambas as partes devem possuir a sessão registrada em suas memórias RAM
        val sessionAlice = sessionManagerA.getSession(sharedSessionId!!)
        val sessionBob = sessionManagerB.getSession(sharedSessionId!!)

        assertNotNull(sessionAlice)
        assertNotNull(sessionBob)
        assertEquals(sessionAlice!!.sessionToken, sessionBob!!.sessionToken)
        assertArrayEquals("Chaves de criptografia devem ser idênticas", sessionAlice.encKey, sessionBob.encKey)
        assertArrayEquals("Chaves de MAC devem ser idênticas", sessionAlice.macKey, sessionBob.macKey)
    }

    // -------------------------------------------------------------------------
    // 4. Autenticação Criptográfica Mútua (ECDH + ECDSA + SessionConfirmMac)
    // -------------------------------------------------------------------------
    @Test
    fun test04_MutualAuthenticatedHandshakeSecurity() {
        val ephemA = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val ephemB = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val nonceA = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.NONCE_SIZE_BYTES)
        val nonceB = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.NONCE_SIZE_BYTES)

        val ecdhSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(ephemB.private, ephemA.public)
        val derived = BleCryptoHandshakeEngine.deriveSessionKeys(
            ecdhSecret = ecdhSecret,
            initiatorNonce = nonceA,
            responderNonce = nonceB,
            initiatorPubKey = ephemA.public.encoded,
            responderPubKey = ephemB.public.encoded
        )

        val validMac = BleCryptoHandshakeEngine.computeSessionConfirmMac(derived.macKey, derived.sessionId)
        assertTrue("MAC válido deve passar", BleCryptoHandshakeEngine.verifySessionConfirmMac(derived.macKey, derived.sessionId, validMac))

        // Adulteração do MAC
        val tamperedMac = validMac.copyOf()
        tamperedMac[0] = (tamperedMac[0].toInt() xor 0xFF).toByte()
        assertFalse("MAC adulterado DEVE ser rejeitado", BleCryptoHandshakeEngine.verifySessionConfirmMac(derived.macKey, derived.sessionId, tamperedMac))
    }

    // -------------------------------------------------------------------------
    // 5. Envio de Mensagem Temporária Criptografada (AES-256-GCM + IV Determinístico)
    // -------------------------------------------------------------------------
    @Test
    fun test05_EncryptedTemporaryMessageSending() {
        val sessionToken = 0x55AA1122
        val encKey = ByteArray(32) { 0x01 }
        val sessionSalt = ByteArray(4) { 0x02 }
        val plaintext = "Mensagem secreta Bluetooth".toByteArray(StandardCharsets.UTF_8)
        val sequence = 1L
        val msgId = 100

        val envelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext = plaintext,
            encKey = encKey,
            sessionSalt = sessionSalt,
            sessionToken = sessionToken,
            msgId = msgId,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            sequence = sequence
        )

        // Envelope deve ter cabeçalho de 12 bytes IV + ciphertext + 16 bytes tag
        assertTrue("Envelope deve ser maior que o plaintext devido ao IV e Tag GCM", envelope.size == 12 + plaintext.size + 16)

        // Plaintext não deve estar em claro nos dados cifrados
        val envelopeString = String(envelope, StandardCharsets.ISO_8859_1)
        assertFalse("Envelope não deve conter plaintext em claro", envelopeString.contains("Mensagem secreta"))

        // Decifra com sucesso
        val decrypted = BleCryptoHandshakeEngine.decryptMessage(
            envelope = envelope,
            encKey = encKey,
            sessionToken = sessionToken,
            msgId = msgId,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            sequence = sequence
        )

        assertEquals("Mensagem secreta Bluetooth", String(decrypted, StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // 6. Recepção de Mensagem Temporária com Entrega na UI em Memória
    // -------------------------------------------------------------------------
    @Test
    fun test06_ReceptionOfTemporaryMessageInRam() {
        val aliceId = coordinatorA.cryptoIdentityManager.getTechnicalIdentity()
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()

        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(aliceId, peerAddressA)

        var receivedText: String? = null
        var receivedPeer: String? = null

        coordinatorB.onTemporaryMessageReceived = { peer, text, _ ->
            receivedPeer = peer
            receivedText = text
        }

        // Executa handshake inicial
        coordinatorA.clientManager.connect(peerAddressB)
        coordinatorA.clientManager.startHandshake(
            peerIdentityHash = bobId,
            localIdHash = aliceId,
            localKeyPair = coordinatorA.cryptoIdentityManager.getActiveKeyPair()!!,
            onComplete = { success, sessionId ->
                assertTrue(success)
                val sent = coordinatorA.clientManager.sendEncryptedMessage(
                    sessionId = sessionId!!,
                    plaintext = "Olá Bob, mensagem direta em RAM!".toByteArray(StandardCharsets.UTF_8),
                    msgId = 42
                )
                assertTrue(sent)
            }
        )

        assertEquals("Olá Bob, mensagem direta em RAM!", receivedText)
        assertEquals(aliceId, receivedPeer)
    }

    // -------------------------------------------------------------------------
    // 7. Fragmentação e Remontagem Transparente no Transporte GATT
    // -------------------------------------------------------------------------
    @Test
    fun test07_PacketFragmentationAndReassembly() {
        val largeMessage = "PessoasAqui BLE Long Message ".repeat(100) // ~2900 bytes
        val largeBytes = largeMessage.toByteArray(StandardCharsets.UTF_8)

        // Fragmenta usando MTU mínimo de 23 bytes (chunk de 18 bytes)
        val fragments = framingEngineA.fragment(
            payload = largeBytes,
            mtu = 23,
            msgId = 77,
            frameType = BlePacketFramingEngine.FRAME_TYPE_DATA
        )

        assertTrue("Deve gerar mais de 100 fragmentos para MTU 23", fragments.size > 100)

        // Remonta todos os fragmentos no destino
        var completedResult: BlePacketFramingEngine.ReassemblyResult? = null
        for (frag in fragments) {
            completedResult = framingEngineB.receivePacket(frag, "session-test-mtu23", System.currentTimeMillis())
        }

        assertTrue("Remontagem deve ser Completed", completedResult is BlePacketFramingEngine.ReassemblyResult.Completed)
        val completed = completedResult as BlePacketFramingEngine.ReassemblyResult.Completed
        assertEquals(77, completed.msgId)
        assertArrayEquals("Payload remontado deve ser idêntico ao original", largeBytes, completed.payload)
    }

    // -------------------------------------------------------------------------
    // 8. Confirmação de Entrega Física (MSG_ACK)
    // -------------------------------------------------------------------------
    @Test
    fun test08_DeliveryConfirmationViaMsgAck() {
        val aliceId = coordinatorA.cryptoIdentityManager.getTechnicalIdentity()
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()

        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(aliceId, peerAddressA)

        var confirmedMsgId: Int? = null
        coordinatorA.onDeliveryConfirmed = { peer, msgId ->
            confirmedMsgId = msgId
        }

        // Handshake + Envio com confirmação automática pelo servidor
        coordinatorA.clientManager.connect(peerAddressB)
        coordinatorA.clientManager.startHandshake(
            peerIdentityHash = bobId,
            localIdHash = aliceId,
            localKeyPair = coordinatorA.cryptoIdentityManager.getActiveKeyPair()!!,
            onComplete = { success, sessionId ->
                coordinatorA.clientManager.sendEncryptedMessage(
                    sessionId = sessionId!!,
                    plaintext = "Test ACK".toByteArray(StandardCharsets.UTF_8),
                    msgId = 888
                )
            }
        )

        assertEquals(888, confirmedMsgId)
    }

    // -------------------------------------------------------------------------
    // 9. Rejeição de Pacotes Antigos ou com Repetição de IV (Anti-Replay)
    // -------------------------------------------------------------------------
    @Test
    fun test09_RejectionOfOldOrReplayedPackets() {
        val session = BleProximitySession(
            sessionId = "session-replay-test",
            sessionToken = 0x12345678,
            localIdentityHash = "ALICE",
            peerIdentityHash = "BOB",
            localRole = BleCryptoHandshakeEngine.ROLE_RESPONDER,
            encKey = ByteArray(32),
            macKey = ByteArray(32),
            sessionSalt = ByteArray(4)
        )

        // Primeira sequência: 5
        assertTrue(session.validateAndRecordRxSequence(5L))
        // Sequência repetida: 5
        assertFalse("Sequência repetida DEVE ser rejeitada", session.validateAndRecordRxSequence(5L))
        // Sequência antiga: 3
        assertFalse("Sequência mais antiga DEVE ser rejeitada", session.validateAndRecordRxSequence(3L))
        // Sequência mais nova: 6
        assertTrue("Sequência progressiva deve ser aceita", session.validateAndRecordRxSequence(6L))
    }

    // -------------------------------------------------------------------------
    // 10. Rejeição de Sessões Expiradas (> 45s de ausência)
    // -------------------------------------------------------------------------
    @Test
    fun test10_RejectionOfExpiredSessions() {
        val now = 1000000L
        val session = BleProximitySession(
            sessionId = "session-exp-test",
            sessionToken = 0x12345678,
            localIdentityHash = "ALICE",
            peerIdentityHash = "BOB",
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32),
            macKey = ByteArray(32),
            sessionSalt = ByteArray(4),
            createdAtMs = now,
            lastActiveMs = now
        )

        assertFalse("Recém criada não está expirada", session.isExpired(now + 10000))
        assertFalse("Aos 30s (grace period) ainda válida", session.isExpired(now + 30000))
        assertFalse("Aos 44.9s ainda válida", session.isExpired(now + 44900))
        assertTrue("Aos 45.1s DEVE estar expirada", session.isExpired(now + 45100))

        sessionManagerA.registerSession(session)
        val purged = sessionManagerA.cleanupExpiredSessions(now + 46000)
        assertEquals(listOf("session-exp-test"), purged)
        assertNull(sessionManagerA.getSession("session-exp-test"))
    }

    // -------------------------------------------------------------------------
    // 11. Reconexão Rápida após Oscilação Bluetooth usando SESSION_RESUME (20 bytes)
    // -------------------------------------------------------------------------
    @Test
    fun test11_GattReconnectWithFastSessionResume() {
        val aliceId = coordinatorA.cryptoIdentityManager.getTechnicalIdentity()
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()

        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(aliceId, peerAddressA)

        var establishedSessionId: String? = null
        coordinatorA.clientManager.connect(peerAddressB)
        coordinatorA.clientManager.startHandshake(
            peerIdentityHash = bobId,
            localIdHash = aliceId,
            localKeyPair = coordinatorA.cryptoIdentityManager.getActiveKeyPair()!!,
            onComplete = { success, sId ->
                establishedSessionId = sId
            }
        )
        assertNotNull(establishedSessionId)

        // Simula queda de rádio físico (desconexão)
        coordinatorA.clientManager.disconnect()
        assertEquals(BleGattClientManager.ConnectionState.DISCONNECTED, coordinatorA.clientManager.connectionState)

        // Reconecta fisicamente
        coordinatorA.clientManager.connect(peerAddressB)
        assertEquals(BleGattClientManager.ConnectionState.READY, coordinatorA.clientManager.connectionState)

        // Executa SESSION_RESUME de 20 bytes para reautenticar sem novo handshake ECDH
        val resumed = coordinatorA.clientManager.sendSessionResume(establishedSessionId!!)
        assertTrue("SESSION_RESUME deve ser enviado com sucesso", resumed)
        assertEquals(establishedSessionId, coordinatorB.serverManager.getBoundSessionId(peerAddressB))
    }

    // -------------------------------------------------------------------------
    // 12. Preservação Estrita das Mensagens Temporárias em Memória RAM
    // -------------------------------------------------------------------------
    @Test
    fun test12_PreservationOfTemporaryMessagesInRam() {
        val repo = PessoasAquiRepository()
        val tempPeerId = "PEER_TEMP_RAM"

        repo.proximitySimulator.receivePrivateMessage(tempPeerId, "Pessoa Próxima", "Mensagem em RAM 1")
        repo.proximitySimulator.receivePrivateMessage(tempPeerId, "Pessoa Próxima", "Mensagem em RAM 2")

        val chats = repo.proximitySimulator.privateChats.value[tempPeerId]
        assertNotNull(chats)
        assertEquals(2, chats!!.size)
        assertEquals("Mensagem em RAM 1", chats[0].text)
        assertEquals("Mensagem em RAM 2", chats[1].text)
    }

    // -------------------------------------------------------------------------
    // 13. Ausência de Persistência em Disco das Conversas Temporárias
    // -------------------------------------------------------------------------
    @Test
    fun test13_ZeroPersistenceOfTemporaryChatOnDisk() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_pessoasaqui_zero_disk_" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val persistence = ChatMessagePersistenceManager(baseDir = tempDir)
            val repo = PessoasAquiRepository(injectedChatPersistence = persistence)

            val tempPeerId = "TEMP_NO_DISK"
            assertFalse(repo.isPeerPermanent(tempPeerId))

            // Envia e recebe mensagens temporárias
            repo.sendPrivateMessage(tempPeerId, "Esta mensagem temporária nunca deve ir ao disco!")

            // Verifica armazenamento físico
            val onDisk = persistence.loadAllPrivateChats()
            assertTrue("Nenhuma conversa temporária pode ser persistida no disco", onDisk.isEmpty() || !onDisk.containsKey(tempPeerId))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 14. Isolamento Estrito entre 3 ou Mais Participantes Simultâneos
    // -------------------------------------------------------------------------
    @Test
    fun test14_IsolationBetweenThreeOrMoreParticipants() {
        val sessionAB = BleProximitySession(
            sessionId = "session-A-B",
            sessionToken = 0x11111111,
            localIdentityHash = "ALICE",
            peerIdentityHash = "BOB",
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32) { 0x0A },
            macKey = ByteArray(32) { 0x0A },
            sessionSalt = ByteArray(4) { 0x01 }
        )

        val sessionAC = BleProximitySession(
            sessionId = "session-A-C",
            sessionToken = 0x22222222,
            localIdentityHash = "ALICE",
            peerIdentityHash = "CAROL",
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32) { 0x0C },
            macKey = ByteArray(32) { 0x0C },
            sessionSalt = ByteArray(4) { 0x02 }
        )

        // Alice criptografa mensagem destinada a Bob
        val envelopeForBob = BleCryptoHandshakeEngine.encryptMessage(
            plaintext = "Segredo Alice-Bob".toByteArray(StandardCharsets.UTF_8),
            encKey = sessionAB.encKey,
            sessionSalt = sessionAB.sessionSalt,
            sessionToken = sessionAB.sessionToken,
            msgId = 1,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            sequence = 1L
        )

        // Carol tenta decifrar a mensagem destinada a Bob usando sua própria chave
        var carolDecryptionFailed = false
        try {
            BleCryptoHandshakeEngine.decryptMessage(
                envelope = envelopeForBob,
                encKey = sessionAC.encKey,
                sessionToken = sessionAC.sessionToken,
                msgId = 1,
                direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
                sequence = 1L
            )
        } catch (_: Exception) {
            carolDecryptionFailed = true
        }

        assertTrue("Carol NÃO deve conseguir decifrar envelope de Bob", carolDecryptionFailed)
    }

    // -------------------------------------------------------------------------
    // 15. Falha de Rádio Bluetooth sem Fallback para o Render
    // -------------------------------------------------------------------------
    @Test
    fun test15_BluetoothFailureWithoutFallbackToRender() {
        var internetAttempted = false

        val testCoordinator = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                // Simula falha catastrófica de Bluetooth (fora de alcance / rádio desligado)
                return false
            }
        }

        val testNetworkClient = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                internetAttempted = true
                return true
            }
        }

        val repo = PessoasAquiRepository(
            injectedBleTransportCoordinator = testCoordinator,
            networkClient = testNetworkClient
        )

        val tempPeer = "DISCONNECTED_PEER"
        assertFalse(repo.isPeerPermanent(tempPeer))

        // Tentativa de envio falha no rádio
        repo.sendPrivateMessage(tempPeer, "Tentativa offline")

        assertFalse("Em hipótese alguma deve ocorrer fallback para o Render na falha do Bluetooth", internetAttempted)
    }

    // -------------------------------------------------------------------------
    // 16. Comunicação Permanente Funcionando Normalmente Mesmo Próximos Fisicamente
    // -------------------------------------------------------------------------
    @Test
    fun test16_PermanentCommunicationIndependentOfBluetooth() {
        var internetSent = false
        var bleSent = false

        val testCoordinator = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                bleSent = true
                return true
            }
        }

        val testNetworkClient = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                internetSent = true
                return true
            }
        }

        val repo = PessoasAquiRepository(
            injectedBleTransportCoordinator = testCoordinator,
            networkClient = testNetworkClient
        )

        val peerManager = CryptoIdentityManager()
        val permanentPeer = peerManager.getTechnicalIdentity()
        E2eeCryptoEngine.registerPeerPublicKey(permanentPeer, peerManager.getPublicKeyBase64())
        repo.setMarkPerson(permanentPeer, mark = true, explicitMutual = true)
        repo.confirmMutualConnectionFromServer(permanentPeer)
        assertTrue(repo.isPeerPermanent(permanentPeer))

        // Ambos estão fisicamente a 1.5 metros (adjacentes)
        repo.sendPrivateMessage(permanentPeer, "Olá mesmo estando lado a lado!")

        assertTrue("Contatos permanentes usam exclusivamente a Internet com E2EE", internetSent)
        assertFalse("Contatos permanentes nunca são rebaixados para Bluetooth temporário", bleSent)
    }

    // -------------------------------------------------------------------------
    // 17. Opção A: Promoção Controlada de Conversa Temporária para Persistente na Conexão Mútua
    // -------------------------------------------------------------------------
    @Test
    fun test17_OptionAPromotionFromTemporaryToPermanent() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_pessoasaqui_option_a_" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val persistence = ChatMessagePersistenceManager(baseDir = tempDir)
            val repo = PessoasAquiRepository(injectedChatPersistence = persistence)

            val peerId = "TEMP_TO_MUTUAL"
            assertFalse(repo.isPeerPermanent(peerId))

            // 1. Conversa temporária existe em RAM
            repo.proximitySimulator.receivePrivateMessage(peerId, "Pessoa Próxima", "Msg RAM 1")
            repo.proximitySimulator.receivePrivateMessage(peerId, "Pessoa Próxima", "Msg RAM 2")

            // Não existe em disco ainda
            var onDisk = persistence.loadAllPrivateChats()[peerId]
            assertTrue(onDisk.isNullOrEmpty())

            // 2. Estabelece Conexão Mútua legítima
            repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
            repo.confirmMutualConnectionFromServer(peerId)
            assertTrue(repo.isPeerPermanent(peerId))

            // 3. Executa Promoção Opção A (preserva histórico em memória no disco criptografado)
            repo.promoteTemporarySessionToMutual(peerId)

            onDisk = persistence.loadAllPrivateChats()[peerId] ?: persistence.loadAllPrivateChats()[peerId.lowercase()]
            assertNotNull("Histórico temporário deve ser promovido para o disco criptografado", onDisk)
            assertTrue("Deve conter a primeira mensagem temporária promovida", onDisk!!.any { it.text == "Msg RAM 1" })
            assertTrue("Deve conter a segunda mensagem temporária promovida", onDisk.any { it.text == "Msg RAM 2" })
            assertTrue("Deve preservar todas as mensagens (mínimo 2)", onDisk.size >= 2)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    // -------------------------------------------------------------------------
    // 18. Preservação das Regras de Exclusão Voluntária e Revogação
    // -------------------------------------------------------------------------
    @Test
    fun test18_PreservationOfExclusionAndRevocationRules() {
        val repo = PessoasAquiRepository()
        val peerId = "REVOCATION_TEST_PEER"

        // Conecta mutuamente
        repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
        repo.confirmMutualConnectionFromServer(peerId)
        assertTrue(repo.isPeerPermanent(peerId))

        // Revoga conexão mútua
        repo.setMarkPerson(peerId, mark = false)
        assertFalse("Após revogação, o peer não é mais permanente", repo.isPeerPermanent(peerId))

        // Não deve ser considerado permanente no repositório
        assertFalse(repo.isPeerPermanent(peerId))
    }

    // =========================================================================
    // Drivers de Loopback de Teste Integrado
    // =========================================================================

    class IntegrationGattServerDriver : BleGattServerDriver {
        var callback: BleGattServerCallback? = null
        var targetClient: IntegrationGattClientDriver? = null
        val targetClients = ConcurrentHashMap<String, IntegrationGattClientDriver>()
        var isOpened = false

        override fun openServer(serviceUuid: UUID, rxCharUuid: UUID, txCharUuid: UUID, cccdUuid: UUID, callback: BleGattServerCallback): Boolean {
            this.callback = callback
            isOpened = true
            return true
        }

        override fun sendResponse(deviceAddress: String, requestId: Int, status: Int, offset: Int, value: ByteArray?): Boolean = true

        override fun notifyCharacteristicChanged(deviceAddress: String, characteristicUuid: UUID, confirm: Boolean, value: ByteArray): Boolean {
            val client = targetClients[deviceAddress] ?: targetClient
            client?.callback?.onCharacteristicChanged(characteristicUuid, value)
            callback?.onNotificationSent(deviceAddress, BleGattServerManager.GATT_SUCCESS)
            return true
        }

        override fun close() {
            isOpened = false
        }
    }

    class IntegrationGattClientDriver : BleGattClientDriver {
        var callback: BleGattClientCallback? = null
        var targetServer: IntegrationGattServerDriver? = null
        var connectedAddress: String? = null

        override fun connect(deviceAddress: String, autoConnect: Boolean, callback: BleGattClientCallback): Boolean {
            this.callback = callback
            this.connectedAddress = deviceAddress
            targetServer?.targetClients?.put(deviceAddress, this)
            // No loopback síncrono, o Peripheral precisa registrar o cliente antes
            // de o Central descobrir serviços e ativar CCCD (isso ocorre
            // imediatamente dentro do callback CONNECTED simulado).
            // Usar o MESMO endereço para conexão, descriptor e fragmentos.
            targetServer?.callback?.onConnectionStateChange(deviceAddress, BleGattServerManager.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            callback.onConnectionStateChange(BleGattClientManager.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            return true
        }

        override fun discoverServices(): Boolean {
            callback?.onServicesDiscovered(BleGattClientManager.GATT_SUCCESS, listOf(BleGattServerManager.SERVICE_UUID))
            return true
        }

        override fun requestMtu(mtu: Int): Boolean {
            callback?.onMtuChanged(mtu, BleGattClientManager.GATT_SUCCESS)
            targetServer?.callback?.onMtuChanged(connectedAddress ?: "", mtu)
            return true
        }

        override fun setCharacteristicNotification(charUuid: UUID, enabled: Boolean): Boolean = true

        override fun writeDescriptor(descUuid: UUID, value: ByteArray): Boolean {
            targetServer?.callback?.onDescriptorWriteRequest(
                connectedAddress ?: "CLIENT", 2, descUuid, false, true, 0, value
            )
            callback?.onDescriptorWrite(descUuid, BleGattClientManager.GATT_SUCCESS)
            return true
        }

        override fun writeCharacteristic(charUuid: UUID, value: ByteArray, writeType: Int): Boolean {
            targetServer?.callback?.onCharacteristicWriteRequest(
                connectedAddress ?: "CLIENT", 1, charUuid, false, false, 0, value
            )
            callback?.onCharacteristicWrite(charUuid, BleGattClientManager.GATT_SUCCESS)
            return true
        }

        override fun disconnect() {
            callback?.onConnectionStateChange(BleGattClientManager.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
            targetServer?.callback?.onConnectionStateChange(connectedAddress ?: "", BleGattServerManager.GATT_SUCCESS, BluetoothProfile.STATE_DISCONNECTED)
        }

        override fun close() {
            disconnect()
        }
    }
}
