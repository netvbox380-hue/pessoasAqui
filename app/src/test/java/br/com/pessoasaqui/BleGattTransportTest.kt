package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.core.proximity.BleCryptoHandshakeEngine
import br.com.pessoasaqui.core.proximity.BleGattClientManager
import br.com.pessoasaqui.core.proximity.BleGattClientManager.BleGattClientCallback
import br.com.pessoasaqui.core.proximity.BleGattClientManager.BleGattClientDriver
import br.com.pessoasaqui.core.proximity.BleGattServerManager
import br.com.pessoasaqui.core.proximity.BleGattServerManager.BleGattServerCallback
import br.com.pessoasaqui.core.proximity.BleGattServerManager.BleGattServerDriver
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine
import br.com.pessoasaqui.core.proximity.BleRoleArbitrator
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager.BleProximitySession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID

/**
 * Suíte de Testes Obrigatórios da FASE 3C:
 * Transporte Bluetooth GATT Server/Client e Arbitragem de Conexões.
 *
 * Valida os 18 requisitos formais estabelecidos na autorização:
 * 1. Inicialização do servidor GATT.
 * 2. Descoberta de serviços.
 * 3. Estabelecimento de conexão.
 * 4. Negociação de MTU.
 * 5. Envio de fragmentos.
 * 6. Recepção de fragmentos.
 * 7. Confirmação de mensagens completas (MSG_ACK).
 * 8. Desconexões inesperadas.
 * 9. Reconexões.
 * 10. Arbitragem entre aparelhos (BleRoleArbitrator).
 * 11. Falhas de permissão.
 * 12. Liberação de recursos (close/stop).
 * 13. Limites de conexões simultâneas.
 * 14. Preservação da sessão lógica após desconexão física.
 * 15. Rejeição de pacotes não autenticados.
 * 16. Independência do Modo B.
 * 17. Identificador de 32 bits com bit mais significativo ativado (0x98765432.toInt()).
 * 18. Aplicação correta de limites personalizados de expiração.
 */
class BleGattTransportTest {

    private lateinit var framingEngine: BlePacketFramingEngine
    private lateinit var sessionManager: BleSessionTokenManager

    private lateinit var mockServerDriver: TestGattServerDriver
    private lateinit var mockClientDriver: TestGattClientDriver

    private lateinit var serverManager: BleGattServerManager
    private lateinit var clientManager: BleGattClientManager

    @Before
    fun setUp() {
        framingEngine = BlePacketFramingEngine()
        sessionManager = BleSessionTokenManager()

        mockServerDriver = TestGattServerDriver()
        mockClientDriver = TestGattClientDriver()

        serverManager = BleGattServerManager(
            framingEngine = framingEngine,
            sessionManager = sessionManager,
            customDriver = mockServerDriver,
            maxConnections = 2
        )

        clientManager = BleGattClientManager(
            framingEngine = framingEngine,
            sessionManager = sessionManager,
            customDriver = mockClientDriver,
            inactivityTimeoutMs = 15000L
        )

        // Conecta o loopback lógico entre cliente e servidor nos drivers de teste
        mockClientDriver.targetServer = mockServerDriver
        mockServerDriver.targetClient = mockClientDriver
    }

    // -------------------------------------------------------------------------
    // 1. Inicialização do Servidor GATT
    // -------------------------------------------------------------------------
    @Test
    fun test01_GattServerInitialization() {
        assertFalse(serverManager.isServerRunning)
        val started = serverManager.startServer()
        assertTrue("Servidor GATT deve iniciar com sucesso", started)
        assertTrue(serverManager.isServerRunning)

        serverManager.stopServer()
        assertFalse("Servidor GATT deve ser encerrado", serverManager.isServerRunning)
    }

    // -------------------------------------------------------------------------
    // 2. Descoberta de Serviços
    // -------------------------------------------------------------------------
    @Test
    fun test02_ServiceDiscovery() {
        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:01")

        assertEquals(BleGattClientManager.ConnectionState.CONNECTED, clientManager.connectionState)
        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))

        // Deve transicionar para SERVICES_DISCOVERED e em seguida para READY com o MTU
        assertEquals(BleGattClientManager.ConnectionState.READY, clientManager.connectionState)
    }

    // -------------------------------------------------------------------------
    // 3. Estabelecimento de Conexão
    // -------------------------------------------------------------------------
    @Test
    fun test03_ConnectionEstablishment() {
        serverManager.startServer()
        val connected = clientManager.connect("AA:BB:CC:DD:EE:02")
        assertTrue("Chamada de conexão deve retornar true", connected)

        assertEquals(1, serverManager.getConnectedClientCount())
        assertNotNull(serverManager.getClient("AA:BB:CC:DD:EE:02"))
    }

    // -------------------------------------------------------------------------
    // 4. Negociação de MTU
    // -------------------------------------------------------------------------
    @Test
    fun test04_MtuNegotiation() {
        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:03")

        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))
        mockClientDriver.triggerMtuChange(247)

        assertEquals(247, clientManager.currentMtu)
        assertEquals(247, serverManager.getClient("AA:BB:CC:DD:EE:03")?.mtu)
    }

    // -------------------------------------------------------------------------
    // 5. Envio de Fragmentos
    // -------------------------------------------------------------------------
    @Test
    fun test05_FragmentTransmissionQueue() {
        val session = registerTestSession("session-trans-05")
        clientManager.activeSessionId = session.sessionId

        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:05")
        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))

        val text = "Mensagem que gera múltiplos fragmentos no transporte BLE GATT"
        val sent = clientManager.sendEncryptedMessage(session.sessionId, text.toByteArray(StandardCharsets.UTF_8), msgId = 501)
        assertTrue("Mensagem deve ser enfileirada e enviada", sent)

        // Fila deve ser processada à medida que as escritas são confirmadas
        assertEquals(0, clientManager.getQueuedPacketCount())
        assertTrue("Driver deve ter registrado escritas", mockClientDriver.writeHistory.isNotEmpty())
    }

    // -------------------------------------------------------------------------
    // 6. Recepção de Fragmentos
    // -------------------------------------------------------------------------
    @Test
    fun test06_FragmentReassemblyOnServer() {
        val session = registerTestSession("session-trans-06")
        clientManager.activeSessionId = session.sessionId
        serverManager.activeSessionId = session.sessionId

        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:06")
        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))

        var receivedMessage: String? = null
        serverManager.onMessageReceivedListener = { _, _, plaintext ->
            receivedMessage = String(plaintext, StandardCharsets.UTF_8)
        }

        val originalText = "Texto de teste recebido e montado pelo servidor GATT"
        clientManager.sendEncryptedMessage(session.sessionId, originalText.toByteArray(StandardCharsets.UTF_8), msgId = 601)

        assertNotNull("Servidor deve ter recebido e decifrado a mensagem", receivedMessage)
        assertEquals(originalText, receivedMessage)
    }

    // -------------------------------------------------------------------------
    // 7. Confirmação de Mensagens Completas (MSG_ACK)
    // -------------------------------------------------------------------------
    @Test
    fun test07_CompleteMessageDeliveryConfirmation() {
        val session = registerTestSession("session-trans-07")
        clientManager.activeSessionId = session.sessionId
        serverManager.activeSessionId = session.sessionId

        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:07")
        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))

        var acknowledgedMsgId: Int? = null
        clientManager.onDeliveryConfirmedListener = { msgId ->
            acknowledgedMsgId = msgId
        }

        val text = "Mensagem com solicitação de confirmação de entrega completa"
        clientManager.sendEncryptedMessage(session.sessionId, text.toByteArray(StandardCharsets.UTF_8), msgId = 701)

        assertEquals("Cliente deve receber confirmação ACK autenticada para o msgId 701", 701, acknowledgedMsgId)
    }

    // -------------------------------------------------------------------------
    // 8. Desconexões Inesperadas
    // -------------------------------------------------------------------------
    @Test
    fun test08_UnexpectedDisconnectionHandling() {
        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:08")

        assertEquals(BleGattClientManager.ConnectionState.CONNECTED, clientManager.connectionState)

        // Simula queda abrupta do link de rádio
        mockClientDriver.triggerDisconnection()

        assertEquals(BleGattClientManager.ConnectionState.DISCONNECTED, clientManager.connectionState)
        assertEquals(0, clientManager.getQueuedPacketCount())
    }

    // -------------------------------------------------------------------------
    // 9. Reconexões
    // -------------------------------------------------------------------------
    @Test
    fun test09_ReconnectionAndQueueProcessing() {
        val session = registerTestSession("session-trans-09")
        clientManager.activeSessionId = session.sessionId

        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:09")
        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))

        // Desconecta
        clientManager.disconnect()
        assertEquals(BleGattClientManager.ConnectionState.DISCONNECTED, clientManager.connectionState)

        // Reconecta
        val reconnected = clientManager.connect("AA:BB:CC:DD:EE:09")
        assertTrue(reconnected)
        mockClientDriver.triggerServicesDiscovered(listOf(BleGattServerManager.SERVICE_UUID))

        assertEquals(BleGattClientManager.ConnectionState.READY, clientManager.connectionState)
    }

    // -------------------------------------------------------------------------
    // 10. Arbitragem entre Aparelhos (BleRoleArbitrator)
    // -------------------------------------------------------------------------
    @Test
    fun test10_RoleArbitrationBetweenDevices() {
        val capsBoth = BleRoleArbitrator.DeviceCapabilities(canServe = true, canClient = true)
        val capsClientOnly = BleRoleArbitrator.DeviceCapabilities(canServe = false, canClient = true)
        val capsNeither = BleRoleArbitrator.DeviceCapabilities(canServe = false, canClient = false)

        // 1. Simetria: menor hash vira Central/Client, maior hash vira Peripheral/Server
        val roleA = BleRoleArbitrator.arbitrate("HASH_AAA", "HASH_BBB", capsBoth, capsBoth)
        val roleB = BleRoleArbitrator.arbitrate("HASH_BBB", "HASH_AAA", capsBoth, capsBoth)
        assertEquals(BleRoleArbitrator.Role.CENTRAL_CLIENT, roleA)
        assertEquals(BleRoleArbitrator.Role.PERIPHERAL_SERVER, roleB)

        // 2. Assimetria: aparelho local não consegue ser servidor -> força CENTRAL_CLIENT
        val roleAsymClient = BleRoleArbitrator.arbitrate("HASH_ZZZ", "HASH_AAA", capsClientOnly, capsBoth)
        assertEquals(BleRoleArbitrator.Role.CENTRAL_CLIENT, roleAsymClient)

        // 3. Assimetria: parceiro remoto não consegue ser servidor -> aparelho local vira PERIPHERAL_SERVER
        val roleAsymServer = BleRoleArbitrator.arbitrate("HASH_AAA", "HASH_ZZZ", capsBoth, capsClientOnly)
        assertEquals(BleRoleArbitrator.Role.PERIPHERAL_SERVER, roleAsymServer)

        // 4. Incompatibilidade: nenhum suporta servidor
        val roleIncompat = BleRoleArbitrator.arbitrate("HASH_AAA", "HASH_BBB", capsClientOnly, capsNeither)
        assertEquals(BleRoleArbitrator.Role.INCOMPATIBLE, roleIncompat)
    }

    // -------------------------------------------------------------------------
    // 11. Falhas de Permissão
    // -------------------------------------------------------------------------
    @Test
    fun test11_PermissionFailureHandling() {
        mockServerDriver.failNextOpen = true
        var errorMessage: String? = null
        serverManager.onErrorListener = { errorMessage = it }

        val started = serverManager.startServer()
        assertFalse("Não deve iniciar com falha de permissão no driver", started)
        assertFalse(serverManager.isServerRunning)
        assertNotNull(errorMessage)
    }

    // -------------------------------------------------------------------------
    // 12. Liberação de Recursos (Close / Stop)
    // -------------------------------------------------------------------------
    @Test
    fun test12_ResourceReleaseOnClose() {
        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:12")

        clientManager.close()
        serverManager.stopServer()

        assertTrue(mockClientDriver.closed)
        assertTrue(mockServerDriver.closed)
        assertEquals(0, serverManager.getConnectedClientCount())
    }

    // -------------------------------------------------------------------------
    // 13. Limites de Conexões Simultâneas
    // -------------------------------------------------------------------------
    @Test
    fun test13_ConnectionLimitEnforcement() {
        serverManager.startServer() // maxConnections = 2 configurado no setUp

        // Cliente 1
        mockServerDriver.simulateIncomingConnection("CLIENT_01")
        assertEquals(1, serverManager.getConnectedClientCount())

        // Cliente 2
        mockServerDriver.simulateIncomingConnection("CLIENT_02")
        assertEquals(2, serverManager.getConnectedClientCount())

        // Cliente 3 (ultrapassa limite de 2)
        var errorTriggered = false
        serverManager.onErrorListener = { errorTriggered = true }
        mockServerDriver.simulateIncomingConnection("CLIENT_03")

        assertEquals("Contagem deve permanecer no limite máximo de 2", 2, serverManager.getConnectedClientCount())
        assertTrue("Deve emitir erro de limite atingido", errorTriggered)
    }

    // -------------------------------------------------------------------------
    // 14. Preservação da Sessão Lógica após Desconexão Física
    // -------------------------------------------------------------------------
    @Test
    fun test14_LogicalSessionPreservedAfterPhysicalDisconnect() {
        val session = registerTestSession("session-trans-14")
        clientManager.activeSessionId = session.sessionId

        serverManager.startServer()
        clientManager.connect("AA:BB:CC:DD:EE:14")

        // Desconecta a ligação física GATT (ex: inatividade de 15s)
        clientManager.disconnect()

        // A sessão lógica em memória RAM continua 100% ativa com suas chaves intactas
        val inMemorySession = sessionManager.getSession("session-trans-14")
        assertNotNull(inMemorySession)
        assertEquals(session.sessionToken, inMemorySession?.sessionToken)
        assertFalse(inMemorySession!!.isExpired(System.currentTimeMillis()))
    }

    // -------------------------------------------------------------------------
    // 15. Rejeição de Pacotes não Autenticados
    // -------------------------------------------------------------------------
    @Test
    fun test15_RejectionOfUnauthenticatedPackets() {
        serverManager.startServer()
        var errorReported = false
        serverManager.onErrorListener = { errorReported = true }

        // Envia pacote forjado sem sessão associada
        val randomPacket = ByteArray(25) { 0x01 }
        randomPacket[0] = BlePacketFramingEngine.MAGIC_BYTE
        randomPacket[1] = BlePacketFramingEngine.FRAME_TYPE_DATA

        serverManager.processIncomingPacket("UNKNOWN_DEVICE", randomPacket)
        assertTrue("Pacote forjado sem sessão válida deve acionar erro/rejeição", errorReported)
    }

    // -------------------------------------------------------------------------
    // 16. Independência do Modo B
    // -------------------------------------------------------------------------
    @Test
    fun test16_IndependenceOfModoBInternet() {
        // Valida que o motor E2EE persistente para a nuvem/Internet não sofre interferência do transporte GATT
        val idA = CryptoIdentityManager()
        val idB = CryptoIdentityManager()

        val plaintext = "Conversa permanente segura no Modo B"
        val (cipher, iv) = E2eeCryptoEngine.encrypt(plaintext, idA.getTechnicalIdentity(), idB.getTechnicalIdentity())
        val decrypted = E2eeCryptoEngine.decrypt(cipher, iv, idA.getTechnicalIdentity(), idB.getTechnicalIdentity())

        assertEquals(plaintext, decrypted)
    }

    // -------------------------------------------------------------------------
    // 17. Identificador de 32 bits com Bit Mais Significativo Ativado (0x98765432.toInt())
    // -------------------------------------------------------------------------
    @Test
    fun test17_IdentifierWithMostSignificantBitActive() {
        // Testa explicitamente o valor 0x98765432.toInt() (bit MSB ativado / número negativo em Int signed)
        val msbToken = 0x98765432.toInt()
        assertTrue("Bit mais significativo deve estar ativado (valor negativo em Int)", msbToken < 0)

        val session = BleProximitySession(
            sessionId = "session-msb-test",
            sessionToken = msbToken,
            localIdentityHash = "ALICE",
            peerIdentityHash = "BOB",
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32) { 0x07 },
            macKey = ByteArray(32) { 0x08 },
            sessionSalt = byteArrayOf(0x01, 0x02, 0x03),
            createdAtMs = System.currentTimeMillis(),
            lastActiveMs = System.currentTimeMillis()
        )
        sessionManager.registerSession(session)

        // 1. Cifragem e decifragem com AAD utilizando o token MSB
        val plaintext = "Teste de token com bit mais significativo ativo".toByteArray(StandardCharsets.UTF_8)
        val envelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext, session.encKey, session.sessionSalt, msbToken, msgId = 17,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR, sequence = 1L
        )

        val decrypted = BleCryptoHandshakeEngine.decryptMessage(
            envelope, session.encKey, msbToken, msgId = 17,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR, sequence = 1L
        )
        assertArrayEquals(plaintext, decrypted)

        // 2. Pacote SESSION_RESUME com token MSB
        val nonce = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.RESUME_NONCE_SIZE_BYTES)
        val mac = BleCryptoHandshakeEngine.computeResumeMac(session.macKey, msbToken, nonce, 0x01)
        val resumePacket = BleCryptoHandshakeEngine.buildResumePacket(msbToken, nonce, mac)

        val resumed = sessionManager.processResumePacket(resumePacket, direction = 0x01)
        assertNotNull("Retomada com token MSB ativado deve ser validada perfeitamente", resumed)
        assertEquals(msbToken, resumed?.sessionToken)
    }

    // -------------------------------------------------------------------------
    // 18. Aplicação Correta de Limites Personalizados de Expiração
    // -------------------------------------------------------------------------
    @Test
    fun test18_CustomExpirationLimitsAppliedCorrectly() {
        // Cria gerenciador com limites customizados: 10s carência, 20s expiração definitiva
        val customManager = BleSessionTokenManager(
            gracePeriodMs = 10000L,
            expirationTimeoutMs = 20000L
        )

        val session = BleProximitySession(
            sessionId = "session-custom-limits",
            sessionToken = 12345,
            localIdentityHash = "USER_A",
            peerIdentityHash = "USER_B",
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32) { 0x01 },
            macKey = ByteArray(32) { 0x02 },
            sessionSalt = byteArrayOf(0x01, 0x02, 0x03),
            createdAtMs = 1000L,
            lastActiveMs = 1000L
        )
        customManager.registerSession(session)

        // t = 1000 + 5s (5000ms): Ativa
        assertFalse(session.isGracePeriod(6000L, graceMs = 10000L, timeoutMs = 20000L))
        assertFalse(session.isExpired(6000L, timeoutMs = 20000L))

        // t = 1000 + 15s (16000ms): Em carência customizada (10s a 20s)
        assertTrue(session.isGracePeriod(16000L, graceMs = 10000L, timeoutMs = 20000L))
        assertFalse(session.isExpired(16000L, timeoutMs = 20000L))

        // t = 1000 + 25s (26000ms): Expirada com limite customizado de 20s
        assertTrue(session.isExpired(26000L, timeoutMs = 20000L))

        val expiredList = customManager.cleanupExpiredSessions(26000L)
        assertEquals(listOf("session-custom-limits"), expiredList)
        assertNull(customManager.getSession("session-custom-limits"))
    }

    private fun registerTestSession(sessionId: String): BleProximitySession {
        val session = BleProximitySession(
            sessionId = sessionId,
            sessionToken = sessionId.hashCode(),
            localIdentityHash = "ALICE",
            peerIdentityHash = "BOB",
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32) { 0x11 },
            macKey = ByteArray(32) { 0x22 },
            sessionSalt = byteArrayOf(0x01, 0x02, 0x03),
            createdAtMs = System.currentTimeMillis(),
            lastActiveMs = System.currentTimeMillis()
        )
        sessionManager.registerSession(session)
        return session
    }

    // =========================================================================
    // Drivers de Teste (Simulação Determinística de Loopback em JVM)
    // =========================================================================

    class TestGattServerDriver : BleGattServerDriver {
        var callback: BleGattServerCallback? = null
        var targetClient: TestGattClientDriver? = null
        var isOpened = false
        var closed = false
        var failNextOpen = false

        override fun openServer(serviceUuid: UUID, rxCharUuid: UUID, txCharUuid: UUID, cccdUuid: UUID, callback: BleGattServerCallback): Boolean {
            if (failNextOpen) return false
            this.callback = callback
            isOpened = true
            return true
        }

        override fun sendResponse(deviceAddress: String, requestId: Int, status: Int, offset: Int, value: ByteArray?): Boolean = true

        override fun notifyCharacteristicChanged(deviceAddress: String, characteristicUuid: UUID, confirm: Boolean, value: ByteArray): Boolean {
            targetClient?.callback?.onCharacteristicChanged(characteristicUuid, value)
            return true
        }

        override fun close() {
            closed = true
            isOpened = false
        }

        fun simulateIncomingConnection(deviceAddress: String) {
            callback?.onConnectionStateChange(deviceAddress, BleGattServerManager.GATT_SUCCESS, 2 /* STATE_CONNECTED */)
        }
    }

    class TestGattClientDriver : BleGattClientDriver {
        var callback: BleGattClientCallback? = null
        var targetServer: TestGattServerDriver? = null
        var connectedAddress: String? = null
        var closed = false
        val writeHistory = mutableListOf<ByteArray>()

        override fun connect(deviceAddress: String, autoConnect: Boolean, callback: BleGattClientCallback): Boolean {
            this.callback = callback
            this.connectedAddress = deviceAddress
            // Simula conexão com sucesso
            callback.onConnectionStateChange(BleGattClientManager.GATT_SUCCESS, 2 /* STATE_CONNECTED */)
            targetServer?.callback?.onConnectionStateChange(deviceAddress, BleGattServerManager.GATT_SUCCESS, 2 /* STATE_CONNECTED */)
            return true
        }

        override fun discoverServices(): Boolean = true

        override fun requestMtu(mtu: Int): Boolean {
            callback?.onMtuChanged(mtu, BleGattClientManager.GATT_SUCCESS)
            targetServer?.callback?.onMtuChanged(connectedAddress ?: "", mtu)
            return true
        }

        override fun setCharacteristicNotification(charUuid: UUID, enabled: Boolean): Boolean = true

        override fun writeDescriptor(descUuid: UUID, value: ByteArray): Boolean = true

        override fun writeCharacteristic(charUuid: UUID, value: ByteArray, writeType: Int): Boolean {
            writeHistory.add(value)
            // Entrega imediatamente no servidor
            targetServer?.callback?.onCharacteristicWriteRequest(
                connectedAddress ?: "CLIENT", 1, charUuid, false, false, 0, value
            )
            // Confirma escrita no cliente
            callback?.onCharacteristicWrite(charUuid, BleGattClientManager.GATT_SUCCESS)
            return true
        }

        override fun disconnect() {
            callback?.onConnectionStateChange(BleGattClientManager.GATT_SUCCESS, 0 /* STATE_DISCONNECTED */)
            targetServer?.callback?.onConnectionStateChange(connectedAddress ?: "", BleGattServerManager.GATT_SUCCESS, 0 /* STATE_DISCONNECTED */)
        }

        override fun close() {
            closed = true
            disconnect()
        }

        fun triggerServicesDiscovered(services: List<UUID>) {
            callback?.onServicesDiscovered(BleGattClientManager.GATT_SUCCESS, services)
        }

        fun triggerMtuChange(mtu: Int) {
            callback?.onMtuChanged(mtu, BleGattClientManager.GATT_SUCCESS)
            targetServer?.callback?.onMtuChanged(connectedAddress ?: "", mtu)
        }

        fun triggerDisconnection() {
            callback?.onConnectionStateChange(BleGattClientManager.GATT_FAILURE, 0 /* STATE_DISCONNECTED */)
            targetServer?.callback?.onConnectionStateChange(connectedAddress ?: "", BleGattServerManager.GATT_FAILURE, 0 /* STATE_DISCONNECTED */)
        }
    }
}
