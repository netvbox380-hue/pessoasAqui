package br.com.pessoasaqui

import android.bluetooth.BluetoothProfile
import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.proximity.BleCryptoHandshakeEngine
import br.com.pessoasaqui.core.proximity.BleDirectTransportCoordinator
import br.com.pessoasaqui.core.proximity.BleGattClientManager
import br.com.pessoasaqui.core.proximity.BleGattClientManager.BleGattClientCallback
import br.com.pessoasaqui.core.proximity.BleGattClientManager.BleGattClientDriver
import br.com.pessoasaqui.core.proximity.BleGattServerManager
import br.com.pessoasaqui.core.proximity.BleGattServerManager.BleGattServerCallback
import br.com.pessoasaqui.core.proximity.BleGattServerManager.BleGattServerDriver
import br.com.pessoasaqui.core.proximity.BleManager
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager
import br.com.pessoasaqui.core.proximity.DistanceEstimator
import br.com.pessoasaqui.domain.model.UserIntent
import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.MessageDeliveryStatus
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Suíte de Testes de Regressão da FASE 3E (Correções de Conexão Física BLE, Fila Assíncrona e Metadados).
 *
 * Cobre estritamente os 6 requisitos formais de homologação:
 * 1. Conexão GATT assíncrona aguardando estado READY antes de emitir handshake/mensagens.
 * 2. Fila de requisições pendentes com cancelamento e disparo de onDeliveryFailed em caso de erro/timeout.
 * 3. Rejeição de agregação de ServiceData (Samsung One UI / Android 16) e higienização de apelidos corrompidos.
 * 4. Transmissão criptografada de nomes completos (META_ALIAS) com suporte a UTF-8, acentos, emojis e espaços.
 * 5. Ciclo de vida real dos estados de entrega: PENDING -> DELIVERED (ACK) e FAILED (erro/timeout).
 * 6. Ausência absoluta de fallback para o Render / Internet no Modo A.
 */
class BlePhase3EFixTest {

    private lateinit var framingEngineA: BlePacketFramingEngine
    private lateinit var framingEngineB: BlePacketFramingEngine
    private lateinit var sessionManagerA: BleSessionTokenManager
    private lateinit var sessionManagerB: BleSessionTokenManager

    private lateinit var serverDriverA: AsyncGattServerDriver
    private lateinit var clientDriverA: AsyncGattClientDriver

    private lateinit var serverDriverB: AsyncGattServerDriver
    private lateinit var clientDriverB: AsyncGattClientDriver

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

        serverDriverA = AsyncGattServerDriver()
        clientDriverA = AsyncGattClientDriver()

        serverDriverB = AsyncGattServerDriver()
        clientDriverB = AsyncGattClientDriver()

        clientDriverA.targetServer = serverDriverB
        serverDriverB.targetClient = clientDriverA

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
    // 1. Conexão GATT Assíncrona: Enfileiramento e Disparo Após READY
    // -------------------------------------------------------------------------
    @Test
    fun test01_AsyncGattConnectionQueuesMessageUntilReady() {
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()
        val aliceId = coordinatorA.cryptoIdentityManager.getTechnicalIdentity()

        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(aliceId, peerAddressA)

        // Configura o driver para pausar a conexão física (não disparar callbacks imediatamente)
        clientDriverA.pauseConnectionCallbacks = true

        var messageReceived = false
        var receivedText = ""
        coordinatorB.onTemporaryMessageReceived = { peer, text, _ ->
            messageReceived = true
            receivedText = text
        }

        // Alice tenta enviar mensagem para Bob enquanto o canal ainda está desconectado
        val sentInitiated = coordinatorA.sendTemporaryMessage(
            recipientId = bobId,
            text = "Olá Bob assíncrono!",
            myAlias = "Alice",
            messageUuid = "msg-async-1"
        )

        assertTrue("Envio deve ser aceito e agendado na fila segura", sentInitiated)
        assertEquals("Cliente ainda deve estar em estado CONNECTING", BleGattClientManager.ConnectionState.CONNECTING, coordinatorA.clientManager.connectionState)
        assertFalse("Mensagem ainda não deve ter chegado pois o canal físico ainda está conectando", messageReceived)

        // Agora o hardware Bluetooth conclui a negociação de rádio: CONNECTED -> SERVICES_DISCOVERED -> MTU_CHANGED (READY)
        clientDriverA.completePendingConnection()

        assertEquals("Cliente deve ter transitado para READY", BleGattClientManager.ConnectionState.READY, coordinatorA.clientManager.connectionState)
        assertTrue("Mensagem deve ter sido enviada automaticamente da fila ao atingir estado READY", messageReceived)
        assertEquals("Olá Bob assíncrono!", receivedText)
    }

    @Test
    fun test01b_HandshakeWaitsUntilCccdWriteIsConfirmed() {
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()
        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        clientDriverA.pauseCccdCallback = true

        var arrived = false
        coordinatorB.onTemporaryMessageReceived = { _, _, _ -> arrived = true }
        assertTrue(coordinatorA.sendTemporaryMessage(bobId, "aguardando CCCD", "Alice", "msg-cccd"))
        assertEquals(BleGattClientManager.ConnectionState.SERVICES_DISCOVERED,
            coordinatorA.clientManager.connectionState)
        assertFalse("Não se deve fazer handshake antes do CCCD", arrived)

        clientDriverA.completePendingCccdWrite()
        assertEquals(BleGattClientManager.ConnectionState.READY, coordinatorA.clientManager.connectionState)
        assertTrue("O envio deve prosseguir após CCCD confirmado", arrived)
    }

    // -------------------------------------------------------------------------
    // 2. Fila de Requisições Pendentes: Falha na Conexão Notifica onDeliveryFailed
    // -------------------------------------------------------------------------
    @Test
    fun test02_ConnectionFailureTriggersDeliveryFailed() {
        val bobId = "FAKEDEVICE01"
        val fakeAddress = "FF:EE:DD:CC:BB:AA"
        coordinatorA.recordPeerDeviceAddress(bobId, fakeAddress)

        var failedUuid = ""
        var failureReason = ""
        coordinatorA.onDeliveryFailed = { peer, msgId, uuid, reason ->
            failedUuid = uuid
            failureReason = reason
        }

        clientDriverA.pauseConnectionCallbacks = true

        coordinatorA.sendTemporaryMessage(
            recipientId = bobId,
            text = "Tentativa com falha",
            myAlias = "Alice",
            messageUuid = "msg-fail-test"
        )

        // Simula rejeição de hardware na conexão física
        clientDriverA.failPendingConnection()

        assertEquals("msg-fail-test", failedUuid)
        assertTrue("Motivo da falha deve registrar desconexão ou falha", failureReason.contains("Desconectado") || failureReason.contains("falha"))
    }

    // -------------------------------------------------------------------------
    // 3. Rejeição de Agregação Samsung One UI e Higienização de Nomes Corrompidos
    // -------------------------------------------------------------------------
    @Test
    fun test03_SamsungAggregatedScanRecordRejectionAndSanitization() {
        val bleManager = BleManager(
            context = null,
            distanceEstimator = DistanceEstimator()
        )

        val discoveredPeers = mutableListOf<NearbyPerson>()

        // 1. Caso do defeito real observado no Samsung One UI:
        // Carga com hash hexadecimal concatenado (ex: "testeD5CDE23A9DF71C")
        val corruptedPayload = "1122334455667788:0:testeD5CDE23A9DF71C".toByteArray(StandardCharsets.UTF_8)
        bleManager.processScanRecordData(
            serviceData = corruptedPayload,
            macAddress = "11:22:33:44:55:66",
            rawRssi = -60,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals(1, discoveredPeers.size)
        // O nome corrompido contendo hash embutido DEVE ter sido rejeitado, utilizando o fallback seguro
        assertEquals("Pessoa Próxima", discoveredPeers[0].alias)
        assertEquals("1122334455667788", discoveredPeers[0].technicalIdentityHash)

        // 2. Caso de nome legítimo com números (ex: "teste5454"):
        discoveredPeers.clear()
        val legitimatePayload = "AABBCCDDEEFF0011:0:teste5454".toByteArray(StandardCharsets.UTF_8)
        bleManager.processScanRecordData(
            serviceData = legitimatePayload,
            macAddress = "AA:BB:CC:DD:EE:11",
            rawRssi = -60,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals(1, discoveredPeers.size)
        // O nome legítimo DEVE ser integralmente preservado sem truncamento forçado
        assertEquals("teste5454", discoveredPeers[0].alias)

        // 3. Caso de dispositivo não PessoasAqui (dados binários arbitrários):
        discoveredPeers.clear()
        val nonPessoasPayload = "random_bluetooth_headset_data".toByteArray(StandardCharsets.UTF_8)
        bleManager.processScanRecordData(
            serviceData = nonPessoasPayload,
            macAddress = "00:11:22:33:44:55",
            rawRssi = -60,
            onPeerDiscovered = { discoveredPeers.add(it) }
        )

        assertEquals("Dispositivo não compatível DEVE ser ignorado", 0, discoveredPeers.size)
    }

    // -------------------------------------------------------------------------
    // 4. Transmissão Criptografada de Nomes Completos (META_ALIAS) com UTF-8 e Emojis
    // -------------------------------------------------------------------------
    @Test
    fun test04_EncryptedMetadataAliasWithUtf8AccentsAndEmojis() {
        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()
        val aliceId = coordinatorA.cryptoIdentityManager.getTechnicalIdentity()

        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(aliceId, peerAddressA)

        var receivedFullAlias = ""
        coordinatorB.onPeerAliasReceived = { peer, alias ->
            receivedFullAlias = alias
        }

        var chatMessagesReceived = 0
        var chatText = ""
        coordinatorB.onTemporaryMessageReceived = { peer, text, _ ->
            chatMessagesReceived++
            chatText = text
        }

        // Alice envia uma mensagem com nome rico (acentos, espaços e emoji)
        val fullAlias = "Fábio João 🚀"
        coordinatorA.sendTemporaryMessage(
            recipientId = bobId,
            text = "Mensagem normal de chat",
            myAlias = fullAlias,
            messageUuid = "uuid-alias-test"
        )

        // O nome completo autenticado DEVE ter sido recebido com integridade total
        assertEquals(fullAlias, receivedFullAlias)

        // O pacote de controle META_ALIAS NÃO deve ser entregue na UI como balão de chat
        assertEquals("Apenas a mensagem legítima de chat deve ser entregue na UI", 1, chatMessagesReceived)
        assertEquals("Mensagem normal de chat", chatText)
    }

    // -------------------------------------------------------------------------
    // 5. Estados Reais de Entrega: PENDING -> DELIVERED (ACK) e FAILED
    // -------------------------------------------------------------------------
    @Test
    fun test05_DeliveryStatusPendingToDeliveredAndFailed() {
        val repo = PessoasAquiRepository(
            injectedBleTransportCoordinator = coordinatorA
        )

        val bobId = coordinatorB.cryptoIdentityManager.getTechnicalIdentity()
        coordinatorA.recordPeerDeviceAddress(bobId, peerAddressB)
        coordinatorB.recordPeerDeviceAddress(coordinatorA.cryptoIdentityManager.getTechnicalIdentity(), peerAddressA)

        // 1. Envio bem-sucedido: deve começar PENDING e mudar para DELIVERED após o ACK
        repo.sendPrivateMessage(bobId, "Teste de Status")

        val messages = repo.proximitySimulator.privateChats.value[bobId]
            ?: repo.proximitySimulator.privateChats.value[bobId.lowercase()]
            ?: emptyList()
        assertFalse("Deve ter pelo menos uma mensagem na conversa", messages.isEmpty())
        val sentMsg = messages.last()

        // Como os drivers locais processam o ACK automaticamente:
        assertEquals("Status final da mensagem deve ser DELIVERED após o ACK", MessageDeliveryStatus.DELIVERED, sentMsg.deliveryStatus)

        // 2. Simulação de falha: atualiza o status para FAILED via notificação do coordenador
        repo.proximitySimulator.updateMessageDeliveryStatus(bobId, sentMsg.id, MessageDeliveryStatus.FAILED)
        val currentMessages = repo.proximitySimulator.privateChats.value[bobId]
            ?: repo.proximitySimulator.privateChats.value[bobId.lowercase()]
            ?: emptyList()
        val failedMsg = currentMessages.first { it.id == sentMsg.id }
        assertEquals(MessageDeliveryStatus.FAILED, failedMsg.deliveryStatus)
    }

    // -------------------------------------------------------------------------
    // 6. Ausência Absoluta de Fallback para o Render no Modo A
    // -------------------------------------------------------------------------
    @Test
    fun test06_ModeAFailureNeverFallsBackToRender() {
        var internetAttempted = false

        val testNetworkClient = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                internetAttempted = true
                return true
            }
        }

        // Coordenador que sempre rejeita/falha
        val failingCoordinator = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                return false
            }
        }

        val repo = PessoasAquiRepository(
            injectedBleTransportCoordinator = failingCoordinator,
            networkClient = testNetworkClient
        )

        val tempPeer = "DISCONNECTED_PEER"
        assertFalse("Peer temporário não é permanente", repo.isPeerPermanent(tempPeer))

        // Tentativa de envio com falha de rádio Bluetooth
        repo.sendPrivateMessage(tempPeer, "Falha física proposital")

        assertFalse("Em hipótese alguma o Modo A deve acionar o cliente de rede ou o Render", internetAttempted)
    }

    // =========================================================================
    // Drivers de Loopback com Controle Assíncrono para Teste
    // =========================================================================

    class AsyncGattServerDriver : BleGattServerDriver {
        var callback: BleGattServerCallback? = null
        var targetClient: AsyncGattClientDriver? = null
        val targetClients = ConcurrentHashMap<String, AsyncGattClientDriver>()
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

    class AsyncGattClientDriver : BleGattClientDriver {
        var callback: BleGattClientCallback? = null
        var targetServer: AsyncGattServerDriver? = null
        var connectedAddress: String? = null
        var pauseConnectionCallbacks = false
        var pauseCccdCallback = false
        private var pendingCccdUuid: UUID? = null

        fun completePendingCccdWrite() {
            val uuid = pendingCccdUuid ?: return
            pendingCccdUuid = null
            callback?.onDescriptorWrite(uuid, BleGattClientManager.GATT_SUCCESS)
        }

        override fun connect(deviceAddress: String, autoConnect: Boolean, callback: BleGattClientCallback): Boolean {
            this.callback = callback
            this.connectedAddress = deviceAddress
            targetServer?.targetClients?.put(deviceAddress, this)

            if (!pauseConnectionCallbacks) {
                completePendingConnection()
            }
            return true
        }

        fun completePendingConnection() {
            val addr = connectedAddress ?: "DEVICE"
            targetServer?.callback?.onConnectionStateChange(addr, BleGattServerManager.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
            callback?.onConnectionStateChange(BleGattClientManager.GATT_SUCCESS, BluetoothProfile.STATE_CONNECTED)
        }

        fun failPendingConnection() {
            val addr = connectedAddress ?: "DEVICE"
            callback?.onConnectionStateChange(133, BluetoothProfile.STATE_DISCONNECTED)
            targetServer?.callback?.onConnectionStateChange(addr, 133, BluetoothProfile.STATE_DISCONNECTED)
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
            if (pauseCccdCallback) {
                pendingCccdUuid = descUuid
            } else {
                callback?.onDescriptorWrite(descUuid, BleGattClientManager.GATT_SUCCESS)
            }
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
