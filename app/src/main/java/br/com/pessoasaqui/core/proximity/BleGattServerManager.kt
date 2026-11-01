package br.com.pessoasaqui.core.proximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.nio.ByteBuffer
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Gerenciador do Servidor Bluetooth GATT (FASE 3C).
 *
 * Responsável por:
 * - Inicializar e publicar o serviço e características GATT do PessoasAqui.
 * - Receber fragmentos de pacotes escritos por clientes remotos (CHAR_RX).
 * - Encaminhar fragmentos para reconstrução pelo [BlePacketFramingEngine].
 * - Decifrar mensagens completas e validar sequências com [BleCryptoHandshakeEngine] e [BleSessionTokenManager].
 * - Enviar confirmações de entrega autenticadas (MSG_ACK) e pacotes de resposta via notificações (CHAR_TX).
 * - Controlar limites de conexões simultâneas e liberar recursos de rádio.
 * - Manter a sessão lógica intacta mesmo diante de desconexões físicas do canal GATT.
 */
class BleGattServerManager(
    private val context: Context? = null,
    val framingEngine: BlePacketFramingEngine = BlePacketFramingEngine(),
    val sessionManager: BleSessionTokenManager = BleSessionTokenManager(),
    customDriver: BleGattServerDriver? = null,
    private val maxConnections: Int = DEFAULT_MAX_CONNECTIONS
) {

    companion object {
        val SERVICE_UUID: UUID = BleConstants.SERVICE_UUID // 0000FA01-0000-1000-8000-00805F9B34FB (Preserva descoberta do Radar)
        val CHAR_RX_UUID: UUID = UUID.fromString("FA010002-4D2A-4C3D-9E1F-72A0168E29A1") // 128-bit custom proprietary RX
        val CHAR_TX_UUID: UUID = UUID.fromString("FA010003-4D2A-4C3D-9E1F-72A0168E29A1") // 128-bit custom proprietary TX
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB") // Bluetooth SIG Standard CCCD (0x2902)

        const val DEFAULT_MAX_CONNECTIONS = 4
        const val GATT_SUCCESS = 0
        const val GATT_FAILURE = 257
    }

    /**
     * Estado de um cliente remoto conectado ao servidor GATT.
     */
    data class ConnectedClient(
        val deviceAddress: String,
        var mtu: Int = 23,
        var notificationsEnabled: Boolean = false,
        var lastActiveMs: Long = System.currentTimeMillis(),
        var boundSessionId: String? = null
    )

    private val driver: BleGattServerDriver = customDriver ?: DefaultAndroidGattServerDriver(context)

    private val connectedClients = ConcurrentHashMap<String, ConnectedClient>()

    var isServerRunning: Boolean = false
        private set

    var activeSessionId: String? = null
    var localIdentityKeyPair: KeyPair? = null
    var localIdentityHash: String? = null

    /**
     * Associa explicitamente um endereço de dispositivo a uma sessão lógica autenticada.
     */
    fun bindSession(deviceAddress: String, sessionId: String): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        if (session.isExpired(System.currentTimeMillis())) return false
        val client = connectedClients[deviceAddress] ?: return false
        client.boundSessionId = sessionId
        return true
    }

    /**
     * Retorna o identificador de sessão associado a uma conexão ativa.
     */
    fun getBoundSessionId(deviceAddress: String): String? = connectedClients[deviceAddress]?.boundSessionId

    // Ouvintes de eventos da camada superior
    var onMessageReceivedListener: ((sessionId: String, msgId: Int, plaintext: ByteArray) -> Unit)? = null
    var onClientConnectedListener: ((deviceAddress: String) -> Unit)? = null
    var onClientDisconnectedListener: ((deviceAddress: String) -> Unit)? = null
    var onErrorListener: ((errorMessage: String) -> Unit)? = null

    private val serverCallback = object : BleGattServerCallback {
        override fun onConnectionStateChange(deviceAddress: String, status: Int, newState: Int) {
            if (status == GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                if (connectedClients.size >= maxConnections) {
                    onErrorListener?.invoke("Limite de conexões simultâneas atingido ($maxConnections)")
                    return
                }
                val client = ConnectedClient(deviceAddress)
                connectedClients[deviceAddress] = client
                onClientConnectedListener?.invoke(deviceAddress)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connectedClients.remove(deviceAddress)
                onClientDisconnectedListener?.invoke(deviceAddress)
            }
        }

        override fun onCharacteristicWriteRequest(
            deviceAddress: String,
            requestId: Int,
            characteristicUuid: UUID,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            if (responseNeeded) {
                driver.sendResponse(deviceAddress, requestId, GATT_SUCCESS, offset, null)
            }

            if (characteristicUuid == CHAR_RX_UUID) {
                processIncomingPacket(deviceAddress, value)
            }
        }

        override fun onNotificationSent(deviceAddress: String, status: Int) {
            // Confirmação de envio de notificação no rádio físico
        }

        override fun onMtuChanged(deviceAddress: String, mtu: Int) {
            connectedClients[deviceAddress]?.let {
                it.mtu = mtu
                it.lastActiveMs = System.currentTimeMillis()
            }
        }
    }

    /**
     * Inicializa o servidor GATT e registra os serviços e características do PessoasAqui.
     */
    @Synchronized
    fun startServer(): Boolean {
        if (isServerRunning) return true

        val started = driver.openServer(SERVICE_UUID, CHAR_RX_UUID, CHAR_TX_UUID, CCCD_UUID, serverCallback)
        if (started) {
            isServerRunning = true
        } else {
            onErrorListener?.invoke("Falha ao abrir servidor GATT ou registrar serviço de presença")
        }
        return started
    }

    /**
     * Encerra o servidor GATT e libera todos os recursos de rádio.
     */
    @Synchronized
    fun stopServer() {
        if (!isServerRunning) return
        isServerRunning = false
        connectedClients.clear()
        driver.close()
    }

    /**
     * Processa um fragmento recebido na característica CHAR_RX.
     */
    fun processIncomingPacket(deviceAddress: String, packet: ByteArray) {
        val client = connectedClients[deviceAddress]
        val nowMs = System.currentTimeMillis()
        client?.lastActiveMs = nowMs

        // Tratamento de Handshake Inicial (FRAME_TYPE_HANDSHAKE_INIT = 0x03)
        if (packet.size >= 7 &&
            packet[0] == BlePacketFramingEngine.MAGIC_BYTE &&
            packet[1] == BlePacketFramingEngine.FRAME_TYPE_HANDSHAKE_INIT
        ) {
            val result = framingEngine.receivePacket(packet, "handshake-$deviceAddress", nowMs)
            if (result is BlePacketFramingEngine.ReassemblyResult.Completed) {
                handleHandshakeInit(deviceAddress, result.payload)
            } else if (result is BlePacketFramingEngine.ReassemblyResult.Rejected) {
                onErrorListener?.invoke("Handshake rejeitado na remontagem: ${result.reason}")
            }
            return
        }

        // Tratamento especial para SESSION_RESUME rápido (20 bytes)
        if (packet.size == 20 &&
            packet[0] == BlePacketFramingEngine.MAGIC_BYTE &&
            packet[1] == BlePacketFramingEngine.FRAME_TYPE_SESSION_RESUME
        ) {
            val resumedSession = sessionManager.processResumePacket(
                packet = packet,
                direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
                nowMs = nowMs
            )
            if (resumedSession != null) {
                client?.boundSessionId = resumedSession.sessionId
            } else {
                onErrorListener?.invoke("Pacote SESSION_RESUME rejeitado para $deviceAddress: autenticação inválida ou sessão expirada")
            }
            return
        }

        // Validação estrita de autenticação: conexão DEVE possuir uma sessão lógica autenticada
        val boundSessionId = client?.boundSessionId ?: activeSessionId
        if (boundSessionId == null) {
            onErrorListener?.invoke("Pacote rejeitado: conexão $deviceAddress não autenticada por handshake ou resume")
            return
        }

        val session = sessionManager.getSession(boundSessionId)
        if (session == null || session.isExpired(nowMs)) {
            if (session != null && session.isExpired(nowMs)) {
                sessionManager.terminateSession(boundSessionId)
            }
            client?.boundSessionId = null
            onErrorListener?.invoke("Pacote rejeitado: sessão $boundSessionId expirada ou inexistente")
            return
        }

        val result = framingEngine.receivePacket(packet, boundSessionId, nowMs)

        when (result) {
            is BlePacketFramingEngine.ReassemblyResult.Completed -> {
                handleCompletedEnvelope(deviceAddress, boundSessionId, result)
            }
            is BlePacketFramingEngine.ReassemblyResult.Rejected -> {
                onErrorListener?.invoke("Pacote rejeitado pelo motor de fragmentação: ${result.reason}")
            }
            is BlePacketFramingEngine.ReassemblyResult.InProgress -> {
                // Aguardando fragmentos subsequentes
            }
        }
    }

    private fun handleHandshakeInit(deviceAddress: String, payload: ByteArray) {
        val initData = BleCryptoHandshakeEngine.decodeHandshakeInit(payload)
        if (initData == null) {
            onErrorListener?.invoke("Payload de HandshakeInit inválido de $deviceAddress")
            return
        }

        val nowMs = System.currentTimeMillis()
        if (Math.abs(nowMs - initData.timestampMs) > BleCryptoHandshakeEngine.MAX_HANDSHAKE_DRIFT_MS) {
            onErrorListener?.invoke("Timestamp de HandshakeInit fora da janela de tolerância de 30s")
            return
        }

        val initIdentityPubKey = try {
            BleCryptoHandshakeEngine.decodePublicKey(initData.initiatorIdentityPubKeyBytes)
        } catch (_: Exception) {
            onErrorListener?.invoke("Chave pública de identidade do iniciador inválida")
            return
        }

        val transcriptHash = BleCryptoHandshakeEngine.buildTranscriptHash(
            version = initData.version,
            role = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            initiatorIdHash = initData.initiatorIdHash,
            responderIdHash = initData.responderIdHash,
            initiatorEphemeralPubKey = initData.initiatorEphemeralPubKeyBytes,
            responderEphemeralPubKey = ByteArray(0),
            initiatorNonce = initData.initiatorNonce,
            responderNonce = ByteArray(0),
            timestampMs = initData.timestampMs
        )

        val sigValid = BleCryptoHandshakeEngine.verifyTranscriptSignature(
            identityPublicKey = initIdentityPubKey,
            transcriptHash = transcriptHash,
            signature = initData.signature
        )

        if (!sigValid) {
            onErrorListener?.invoke("Assinatura do transcript de HandshakeInit inválida")
            return
        }

        val respEphemeral = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val respNonce = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.NONCE_SIZE_BYTES)

        val initEphemeralPubKey = try {
            BleCryptoHandshakeEngine.decodePublicKey(initData.initiatorEphemeralPubKeyBytes)
        } catch (_: Exception) {
            onErrorListener?.invoke("Chave efêmera do iniciador inválida")
            return
        }

        val sharedSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(respEphemeral.private, initEphemeralPubKey)
        val derived = BleCryptoHandshakeEngine.deriveSessionKeys(
            ecdhSecret = sharedSecret,
            initiatorNonce = initData.initiatorNonce,
            responderNonce = respNonce,
            initiatorPubKey = initData.initiatorEphemeralPubKeyBytes,
            responderPubKey = respEphemeral.public.encoded
        )

        val confirmMac = BleCryptoHandshakeEngine.computeSessionConfirmMac(derived.macKey, derived.sessionId)

        val respTranscriptHash = BleCryptoHandshakeEngine.buildTranscriptHash(
            version = BleCryptoHandshakeEngine.PROTOCOL_VERSION,
            role = BleCryptoHandshakeEngine.ROLE_RESPONDER,
            initiatorIdHash = initData.initiatorIdHash,
            responderIdHash = initData.responderIdHash,
            initiatorEphemeralPubKey = initData.initiatorEphemeralPubKeyBytes,
            responderEphemeralPubKey = respEphemeral.public.encoded,
            initiatorNonce = initData.initiatorNonce,
            responderNonce = respNonce,
            timestampMs = nowMs
        )

        val myKeyPair = localIdentityKeyPair ?: BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val respSig = BleCryptoHandshakeEngine.signTranscript(myKeyPair.private, respTranscriptHash)

        val session = BleSessionTokenManager.BleProximitySession(
            sessionId = derived.sessionId,
            sessionToken = derived.sessionToken,
            localIdentityHash = localIdentityHash ?: "SERVER",
            peerIdentityHash = initData.initiatorIdHash,
            localRole = BleCryptoHandshakeEngine.ROLE_RESPONDER,
            encKey = derived.encKey,
            macKey = derived.macKey,
            sessionSalt = derived.sessionSalt,
            createdAtMs = nowMs,
            lastActiveMs = nowMs
        )
        sessionManager.registerSession(session)
        bindSession(deviceAddress, session.sessionId)

        val respData = BleCryptoHandshakeEngine.HandshakeRespData(
            version = BleCryptoHandshakeEngine.PROTOCOL_VERSION,
            responderNonce = respNonce,
            responderIdentityPubKeyBytes = myKeyPair.public.encoded,
            responderEphemeralPubKeyBytes = respEphemeral.public.encoded,
            signature = respSig,
            sessionConfirmMac = confirmMac
        )
        val respPayload = BleCryptoHandshakeEngine.encodeHandshakeResp(respData)
        val mtu = connectedClients[deviceAddress]?.mtu ?: BlePacketFramingEngine.MIN_MTU
        val fragments = framingEngine.fragment(
            payload = respPayload,
            mtu = mtu,
            msgId = 0,
            frameType = BlePacketFramingEngine.FRAME_TYPE_HANDSHAKE_RESP
        )
        for (frag in fragments) {
            notifyClient(deviceAddress, frag)
        }
    }

    private fun handleCompletedEnvelope(
        deviceAddress: String,
        sessionId: String,
        result: BlePacketFramingEngine.ReassemblyResult.Completed
    ) {
        val session = sessionManager.getSession(sessionId)
        if (session == null) {
            onErrorListener?.invoke("Envelope recebido sem sessão lógica ativa associada")
            return
        }

        if (session.isExpired(System.currentTimeMillis())) {
            sessionManager.terminateSession(sessionId)
            onErrorListener?.invoke("Mensagem descartada: sessão lógica expirada")
            return
        }

        if (result.frameType == BlePacketFramingEngine.FRAME_TYPE_DATA) {
            try {
                // Extrai número de sequência do IV nos primeiros 12 bytes do envelope
                val envelope = result.payload
                require(envelope.size >= 12 + 16) { "Envelope criptográfico truncado" }
                val seqBytes = ByteBuffer.wrap(envelope, 4, 8)
                val sequence = seqBytes.long

                // Validação de unicidade e proteção contra repetição
                if (!session.validateAndRecordRxSequence(sequence)) {
                    onErrorListener?.invoke("Mensagem com sequência repetida ou antiga rejeitada")
                    return
                }

                val decrypted = BleCryptoHandshakeEngine.decryptMessage(
                    envelope = envelope,
                    encKey = session.encKey,
                    sessionToken = session.sessionToken,
                    msgId = result.msgId,
                    direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
                    sequence = sequence
                )

                session.markMessageProcessed(result.msgId, System.currentTimeMillis())
                onMessageReceivedListener?.invoke(sessionId, result.msgId, decrypted)

                // Emite ACK autenticado de volta para o cliente via notificação
                sendAckToClient(deviceAddress, session, result.msgId, statusCode = 0x00.toByte())

            } catch (e: Exception) {
                onErrorListener?.invoke("Falha ao decifrar mensagem AES-GCM: ${e.message}")
            }
        }
    }

    /**
     * Envia confirmação autenticada de recebimento da mensagem completa (MSG_ACK).
     */
    fun sendAckToClient(
        deviceAddress: String,
        session: BleSessionTokenManager.BleProximitySession,
        msgId: Int,
        statusCode: Byte
    ): Boolean {
        val ackHmac = BleCryptoHandshakeEngine.computeAckHmac(
            macKey = session.macKey,
            sessionId = session.sessionId,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            msgId = msgId,
            statusCode = statusCode
        )

        val ackPacket = ByteArray(13)
        ackPacket[0] = BlePacketFramingEngine.MAGIC_BYTE
        ackPacket[1] = BlePacketFramingEngine.FRAME_TYPE_ACK
        ackPacket[2] = ((msgId ushr 8) and 0xFF).toByte()
        ackPacket[3] = (msgId and 0xFF).toByte()
        ackPacket[4] = statusCode
        System.arraycopy(ackHmac, 0, ackPacket, 5, 8)

        return driver.notifyCharacteristicChanged(deviceAddress, CHAR_TX_UUID, confirm = false, value = ackPacket)
    }

    /**
     * Envia um pacote de dados ou controle para o cliente conectado via notificação.
     */
    fun notifyClient(deviceAddress: String, packet: ByteArray): Boolean {
        return driver.notifyCharacteristicChanged(deviceAddress, CHAR_TX_UUID, confirm = false, value = packet)
    }

    /**
     * Envia mensagem cifrada para o cliente conectado via notificação na característica CHAR_TX.
     */
    fun sendEncryptedNotification(
        sessionId: String,
        payload: ByteArray,
        msgId: Int
    ): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        val address = connectedClients.entries.firstOrNull { it.value.boundSessionId == sessionId }?.key ?: return false
        if (session.isExpired(System.currentTimeMillis())) return false

        val sequence = session.nextTxSequence()
        val envelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext = payload,
            encKey = session.encKey,
            sessionSalt = session.sessionSalt,
            sessionToken = session.sessionToken,
            msgId = msgId,
            direction = BleCryptoHandshakeEngine.ROLE_RESPONDER,
            sequence = sequence
        )

        val mtu = connectedClients[address]?.mtu ?: BlePacketFramingEngine.MIN_MTU
        val fragments = framingEngine.fragment(
            payload = envelope,
            mtu = mtu,
            msgId = msgId,
            frameType = BlePacketFramingEngine.FRAME_TYPE_DATA
        )

        for (frag in fragments) {
            val sent = notifyClient(address, frag)
            if (!sent) return false
        }
        return true
    }

    fun getConnectedClientCount(): Int = connectedClients.size

    fun getClient(deviceAddress: String): ConnectedClient? = connectedClients[deviceAddress]

    /**
     * Interface de abstração do rádio Bluetooth GATT Server para viabilizar testes unitários e produção.
     */
    interface BleGattServerDriver {
        fun openServer(
            serviceUuid: UUID,
            rxCharUuid: UUID,
            txCharUuid: UUID,
            cccdUuid: UUID,
            callback: BleGattServerCallback
        ): Boolean

        fun sendResponse(
            deviceAddress: String,
            requestId: Int,
            status: Int,
            offset: Int,
            value: ByteArray?
        ): Boolean

        fun notifyCharacteristicChanged(
            deviceAddress: String,
            characteristicUuid: UUID,
            confirm: Boolean,
            value: ByteArray
        ): Boolean

        fun close()
    }

    /**
     * Interface de retorno dos eventos de hardware do servidor GATT.
     */
    interface BleGattServerCallback {
        fun onConnectionStateChange(deviceAddress: String, status: Int, newState: Int)
        fun onCharacteristicWriteRequest(
            deviceAddress: String,
            requestId: Int,
            characteristicUuid: UUID,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        )
        fun onNotificationSent(deviceAddress: String, status: Int)
        fun onMtuChanged(deviceAddress: String, mtu: Int)
    }

    /**
     * Driver concreto que utiliza a API nativa Android Bluetooth no runtime físico.
     */
    private class DefaultAndroidGattServerDriver(private val context: Context?) : BleGattServerDriver {
        private var gattServer: BluetoothGattServer? = null
        private var rxChar: BluetoothGattCharacteristic? = null
        private var txChar: BluetoothGattCharacteristic? = null

        @SuppressLint("MissingPermission")
        override fun openServer(
            serviceUuid: UUID,
            rxCharUuid: UUID,
            txCharUuid: UUID,
            cccdUuid: UUID,
            callback: BleGattServerCallback
        ): Boolean {
            if (context == null) return false
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false

            val nativeCallback = object : BluetoothGattServerCallback() {
                override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
                    device?.address?.let { callback.onConnectionStateChange(it, status, newState) }
                }

                override fun onCharacteristicWriteRequest(
                    device: BluetoothDevice?,
                    requestId: Int,
                    characteristic: BluetoothGattCharacteristic?,
                    preparedWrite: Boolean,
                    responseNeeded: Boolean,
                    offset: Int,
                    value: ByteArray?
                ) {
                    if (device != null && characteristic != null && value != null) {
                        callback.onCharacteristicWriteRequest(
                            device.address, requestId, characteristic.uuid, preparedWrite, responseNeeded, offset, value
                        )
                    }
                }

                override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
                    device?.address?.let { callback.onNotificationSent(it, status) }
                }

                override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
                    device?.address?.let { callback.onMtuChanged(it, mtu) }
                }
            }

            try {
                gattServer = bm.openGattServer(context, nativeCallback) ?: return false

                val service = BluetoothGattService(serviceUuid, BluetoothGattService.SERVICE_TYPE_PRIMARY)

                val rx = BluetoothGattCharacteristic(
                    rxCharUuid,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                    BluetoothGattCharacteristic.PERMISSION_WRITE
                )
                rxChar = rx

                val tx = BluetoothGattCharacteristic(
                    txCharUuid,
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE,
                    BluetoothGattCharacteristic.PERMISSION_READ
                )
                val cccd = BluetoothGattDescriptor(cccdUuid, BluetoothGattDescriptor.PERMISSION_WRITE or BluetoothGattDescriptor.PERMISSION_READ)
                tx.addDescriptor(cccd)
                txChar = tx

                service.addCharacteristic(rx)
                service.addCharacteristic(tx)

                return gattServer?.addService(service) == true
            } catch (_: Exception) {
                return false
            }
        }

        @SuppressLint("MissingPermission")
        override fun sendResponse(
            deviceAddress: String,
            requestId: Int,
            status: Int,
            offset: Int,
            value: ByteArray?
        ): Boolean {
            val bm = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val device = bm?.adapter?.getRemoteDevice(deviceAddress) ?: return false
            return try {
                gattServer?.sendResponse(device, requestId, status, offset, value) == true
            } catch (_: Exception) {
                false
            }
        }

        @SuppressLint("MissingPermission")
        override fun notifyCharacteristicChanged(
            deviceAddress: String,
            characteristicUuid: UUID,
            confirm: Boolean,
            value: ByteArray
        ): Boolean {
            val bm = context?.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            val device = bm?.adapter?.getRemoteDevice(deviceAddress) ?: return false
            if (characteristicUuid != CHAR_TX_UUID) return false
            val charToNotify = txChar ?: return false
            return try {
                charToNotify.value = value
                gattServer?.notifyCharacteristicChanged(device, charToNotify, confirm) == true
            } catch (_: Exception) {
                false
            }
        }

        @SuppressLint("MissingPermission")
        override fun close() {
            try {
                gattServer?.clearServices()
                gattServer?.close()
            } catch (_: Exception) {}
            gattServer = null
        }
    }
}
