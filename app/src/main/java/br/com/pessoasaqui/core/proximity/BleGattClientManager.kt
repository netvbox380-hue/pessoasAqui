package br.com.pessoasaqui.core.proximity

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.nio.ByteBuffer
import java.security.KeyPair
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gerenciador do Cliente Bluetooth GATT (FASE 3C).
 *
 * Responsável por:
 * - Estabelecer conexão com servidores GATT remotos (GAP Central / GATT Client).
 * - Realizar a descoberta de serviços e características do PessoasAqui.
 * - Negociar MTU estendido (até 517 bytes) para alta performance.
 * - Habilitar notificações na característica CHAR_TX via descriptor CCCD.
 * - Gerenciar a fila sequencial de escrita de fragmentos em CHAR_RX com controle de confirmação.
 * - Processar confirmações autenticadas de entrega (MSG_ACK) e notificações recebidas.
 * - Encerrar conexões físicas ociosas (após 15s) preservando a sessão lógica na memória RAM.
 * - Executar reconexão rápida via pacote SESSION_RESUME de 20 bytes.
 */
class BleGattClientManager(
    private val context: Context? = null,
    val framingEngine: BlePacketFramingEngine = BlePacketFramingEngine(),
    val sessionManager: BleSessionTokenManager = BleSessionTokenManager(),
    customDriver: BleGattClientDriver? = null,
    private val inactivityTimeoutMs: Long = DEFAULT_INACTIVITY_TIMEOUT_MS
) {

    companion object {
        val SERVICE_UUID: UUID = BleGattServerManager.SERVICE_UUID
        val CHAR_RX_UUID: UUID = BleGattServerManager.CHAR_RX_UUID
        val CHAR_TX_UUID: UUID = BleGattServerManager.CHAR_TX_UUID
        val CCCD_UUID: UUID = BleGattServerManager.CCCD_UUID

        const val DEFAULT_INACTIVITY_TIMEOUT_MS = 15000L
        const val MAX_WRITE_RETRIES = 3
        const val DEFAULT_MTU = 23
        const val TARGET_MTU = 517

        const val GATT_SUCCESS = 0
        const val GATT_FAILURE = 257
    }

    enum class ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        SERVICES_DISCOVERED,
        READY
    }

    private val driver: BleGattClientDriver = customDriver ?: DefaultAndroidGattClientDriver(context)

    var connectionState: ConnectionState = ConnectionState.DISCONNECTED
        private set

    var currentMtu: Int = DEFAULT_MTU
        private set

    var connectedDeviceAddress: String? = null
        private set

    var activeSessionId: String? = null
    var localIdentityKeyPair: KeyPair? = null
    var localIdentityHash: String? = null

    var onHandshakeCompletedListener: ((success: Boolean, sessionId: String?) -> Unit)? = null
    private var pendingHandshakeEphemeral: KeyPair? = null
    private var pendingHandshakeNonce: ByteArray? = null
    private var pendingHandshakeTimestamp: Long = 0L
    private var pendingPeerIdHash: String? = null

    // Fila sequencial de pacotes para transmissão pelo rádio
    private val transmissionQueue = ConcurrentLinkedQueue<ByteArray>()
    private val isWriting = AtomicBoolean(false)
    private var currentRetryCount = 0

    var lastActivityMs: Long = System.currentTimeMillis()
        private set

    // Ouvintes da camada de aplicação
    var onDeliveryConfirmedListener: ((msgId: Int) -> Unit)? = null
    var onMessageReceivedListener: ((sessionId: String, msgId: Int, plaintext: ByteArray) -> Unit)? = null
    var onStateChangedListener: ((newState: ConnectionState) -> Unit)? = null
    var onErrorListener: ((errorMessage: String) -> Unit)? = null

    private val clientCallback = object : BleGattClientCallback {
        override fun onConnectionStateChange(status: Int, newState: Int) {
            lastActivityMs = System.currentTimeMillis()
            if (status == GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                transitionTo(ConnectionState.CONNECTED)
                driver.discoverServices()
            } else {
                transitionTo(ConnectionState.DISCONNECTED)
                connectedDeviceAddress = null
                transmissionQueue.clear()
                isWriting.set(false)
            }
        }

        override fun onServicesDiscovered(status: Int, serviceUuids: List<UUID>) {
            lastActivityMs = System.currentTimeMillis()
            if (status == GATT_SUCCESS && serviceUuids.contains(SERVICE_UUID)) {
                transitionTo(ConnectionState.SERVICES_DISCOVERED)
                // Solicita negociação de MTU estendido imediatamente
                val requested = driver.requestMtu(TARGET_MTU)
                if (!requested) {
                    // MTU é opcional, CCCD não. READY somente após confirmação do descritor.
                    enableNotifications()
                }
            } else {
                onErrorListener?.invoke("Serviço PessoasAqui não encontrado no servidor remoto")
                disconnect()
            }
        }

        override fun onMtuChanged(mtu: Int, status: Int) {
            lastActivityMs = System.currentTimeMillis()
            if (status == GATT_SUCCESS) {
                currentMtu = mtu.coerceIn(BlePacketFramingEngine.MIN_MTU, BlePacketFramingEngine.MAX_MTU)
            }
            enableNotifications()
        }

        override fun onDescriptorWrite(descUuid: UUID, status: Int) {
            if (descUuid != CCCD_UUID) return
            if (status == GATT_SUCCESS && connectionState == ConnectionState.SERVICES_DISCOVERED) {
                transitionTo(ConnectionState.READY)
                processNextQueuedPacket()
            } else if (status != GATT_SUCCESS) {
                onErrorListener?.invoke("Habilitação das notificações GATT falhou: $status")
                disconnect()
            }
        }

        override fun onCharacteristicWrite(charUuid: UUID, status: Int) {
            lastActivityMs = System.currentTimeMillis()
            if (charUuid == CHAR_RX_UUID) {
                if (status == GATT_SUCCESS) {
                    currentRetryCount = 0
                    transmissionQueue.poll() // Remove pacote transmitido com sucesso
                    isWriting.set(false)
                    processNextQueuedPacket()
                } else {
                    if (currentRetryCount < MAX_WRITE_RETRIES) {
                        currentRetryCount++
                        // Retenta envio do pacote no topo da fila
                        isWriting.set(false)
                        processNextQueuedPacket()
                    } else {
                        currentRetryCount = 0
                        transmissionQueue.clear()
                        isWriting.set(false)
                        onErrorListener?.invoke("Falha definitiva na escrita física do fragmento GATT")
                    }
                }
            }
        }

        override fun onCharacteristicChanged(charUuid: UUID, value: ByteArray) {
            lastActivityMs = System.currentTimeMillis()
            if (charUuid == CHAR_TX_UUID) {
                processIncomingNotification(value)
            }
        }
    }

    private fun enableNotifications() {
        if (!driver.setCharacteristicNotification(CHAR_TX_UUID, true) ||
            !driver.writeDescriptor(CCCD_UUID, byteArrayOf(0x01, 0x00))) {
            onErrorListener?.invoke("Não foi possível habilitar CCCD: handshake BLE bloqueado")
            disconnect()
        }
    }

    private fun transitionTo(newState: ConnectionState) {
        connectionState = newState
        onStateChangedListener?.invoke(newState)
    }

    /**
     * Conecta a um servidor GATT de dispositivo remoto.
     */
    @Synchronized
    fun connect(deviceAddress: String, autoConnect: Boolean = false): Boolean {
        if (connectionState != ConnectionState.DISCONNECTED) {
            if (connectedDeviceAddress == deviceAddress &&
                (connectionState == ConnectionState.READY ||
                 connectionState == ConnectionState.CONNECTING ||
                 connectionState == ConnectionState.CONNECTED ||
                 connectionState == ConnectionState.SERVICES_DISCOVERED)
            ) {
                // Já conectado ou com conexão em andamento para o mesmo dispositivo
                return true
            }
            disconnect()
        }

        connectedDeviceAddress = deviceAddress
        transitionTo(ConnectionState.CONNECTING)
        val success = driver.connect(deviceAddress, autoConnect, clientCallback)
        if (!success) {
            transitionTo(ConnectionState.DISCONNECTED)
            onErrorListener?.invoke("Falha ao iniciar chamada connectGatt para $deviceAddress")
        }
        return success
    }

    /**
     * Encerra a conexão GATT física sem destruir a sessão lógica na memória RAM.
     */
    @Synchronized
    fun disconnect() {
        driver.disconnect()
        transitionTo(ConnectionState.DISCONNECTED)
    }

    /**
     * Fecha e libera os recursos do cliente Bluetooth.
     */
    @Synchronized
    fun close() {
        disconnect()
        driver.close()
        transmissionQueue.clear()
        isWriting.set(false)
    }

    /**
     * Enfileira uma mensagem cifrada e autenticada para transmissão fragmentada via rádio.
     */
    @Synchronized
    fun sendEncryptedMessage(
        sessionId: String,
        plaintext: ByteArray,
        msgId: Int
    ): Boolean {
        val session = sessionManager.getSession(sessionId)
        if (session == null) {
            onErrorListener?.invoke("Tentativa de envio sem sessão lógica ativa em memória")
            return false
        }

        if (session.isExpired(System.currentTimeMillis())) {
            sessionManager.terminateSession(sessionId)
            onErrorListener?.invoke("Sessão lógica expirada: mensagem não pode ser enviada")
            return false
        }

        val sequence = session.nextTxSequence()

        val envelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext = plaintext,
            encKey = session.encKey,
            sessionSalt = session.sessionSalt,
            sessionToken = session.sessionToken,
            msgId = msgId,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            sequence = sequence
        )

        val fragments = framingEngine.fragment(
            payload = envelope,
            mtu = currentMtu,
            msgId = msgId,
            frameType = BlePacketFramingEngine.FRAME_TYPE_DATA
        )

        transmissionQueue.addAll(fragments)
        processNextQueuedPacket()
        return true
    }

    /**
     * Envia um pacote de controle ou pacote pré-formatado (ex: SESSION_RESUME).
     */
    @Synchronized
    fun sendRawPacket(packet: ByteArray) {
        transmissionQueue.add(packet)
        processNextQueuedPacket()
    }

    /**
     * Envia um pacote de retomada de sessão criptográfica (SESSION_RESUME de 20 bytes).
     * Autentica o canal GATT físico utilizando a chave MAC da sessão em memória.
     */
    @Synchronized
    fun sendSessionResume(sessionId: String): Boolean {
        val session = sessionManager.getSession(sessionId) ?: return false
        if (session.isExpired(System.currentTimeMillis())) {
            sessionManager.terminateSession(sessionId)
            onErrorListener?.invoke("Tentativa de retomada para sessão expirada: $sessionId")
            return false
        }

        val nonce = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.RESUME_NONCE_SIZE_BYTES)
        val mac = BleCryptoHandshakeEngine.computeResumeMac(
            macKey = session.macKey,
            sessionToken = session.sessionToken,
            resumeNonce = nonce,
            direction = BleCryptoHandshakeEngine.ROLE_INITIATOR
        )

        val resumePacket = BleCryptoHandshakeEngine.buildResumePacket(
            sessionToken = session.sessionToken,
            resumeNonce = nonce,
            resumeMac = mac
        )

        activeSessionId = sessionId
        sendRawPacket(resumePacket)
        return true
    }

    /**
     * Inicia o handshake criptográfico mútuo com um dispositivo desconhecido pelo canal GATT (FRAME_TYPE_HANDSHAKE_INIT).
     */
    @Synchronized
    fun startHandshake(
        peerIdentityHash: String,
        localIdHash: String,
        localKeyPair: KeyPair,
        onComplete: ((success: Boolean, sessionId: String?) -> Unit)? = null
    ): Boolean {
        if (connectionState != ConnectionState.READY) return false
        onHandshakeCompletedListener = onComplete
        pendingPeerIdHash = peerIdentityHash
        localIdentityHash = localIdHash
        localIdentityKeyPair = localKeyPair

        val ephem = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        pendingHandshakeEphemeral = ephem
        val nonce = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.NONCE_SIZE_BYTES)
        pendingHandshakeNonce = nonce
        val nowMs = System.currentTimeMillis()
        pendingHandshakeTimestamp = nowMs

        val transcriptHash = BleCryptoHandshakeEngine.buildTranscriptHash(
            version = BleCryptoHandshakeEngine.PROTOCOL_VERSION,
            role = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            initiatorIdHash = localIdHash,
            responderIdHash = peerIdentityHash,
            initiatorEphemeralPubKey = ephem.public.encoded,
            responderEphemeralPubKey = ByteArray(0),
            initiatorNonce = nonce,
            responderNonce = ByteArray(0),
            timestampMs = nowMs
        )
        val signature = BleCryptoHandshakeEngine.signTranscript(localKeyPair.private, transcriptHash)

        val initData = BleCryptoHandshakeEngine.HandshakeInitData(
            version = BleCryptoHandshakeEngine.PROTOCOL_VERSION,
            initiatorIdHash = localIdHash,
            responderIdHash = peerIdentityHash,
            timestampMs = nowMs,
            initiatorNonce = nonce,
            initiatorIdentityPubKeyBytes = localKeyPair.public.encoded,
            initiatorEphemeralPubKeyBytes = ephem.public.encoded,
            signature = signature
        )
        val payload = BleCryptoHandshakeEngine.encodeHandshakeInit(initData)
        val fragments = framingEngine.fragment(
            payload = payload,
            mtu = currentMtu,
            msgId = 0,
            frameType = BlePacketFramingEngine.FRAME_TYPE_HANDSHAKE_INIT
        )
        for (frag in fragments) {
            sendRawPacket(frag)
        }
        return true
    }

    private fun handleHandshakeResp(payload: ByteArray) {
        val respData = BleCryptoHandshakeEngine.decodeHandshakeResp(payload)
        if (respData == null) {
            onHandshakeCompletedListener?.invoke(false, null)
            return
        }
        val ephem = pendingHandshakeEphemeral ?: return
        val nonce = pendingHandshakeNonce ?: return
        val localHash = localIdentityHash ?: return
        val peerHash = pendingPeerIdHash ?: return

        val respIdentityPubKey = try {
            BleCryptoHandshakeEngine.decodePublicKey(respData.responderIdentityPubKeyBytes)
        } catch (_: Exception) {
            onHandshakeCompletedListener?.invoke(false, null)
            return
        }

        val respEphemeralPubKey = try {
            BleCryptoHandshakeEngine.decodePublicKey(respData.responderEphemeralPubKeyBytes)
        } catch (_: Exception) {
            onHandshakeCompletedListener?.invoke(false, null)
            return
        }

        // MAC ECDH autentica apenas a sessão, não o nome/identidade do outro aparelho.
        // Exigir assinatura ECDSA do respondedor sobre o mesmo transcript.
        if (respData.version != BleCryptoHandshakeEngine.PROTOCOL_VERSION ||
            !br.com.pessoasaqui.core.crypto.TrustedIdentityKeys.isTrustedPeer(
                peerHash, respData.responderIdentityPubKeyBytes)) {
            onErrorListener?.invoke("BLE: chave pública não corresponde ao respondedor anunciado")
            onHandshakeCompletedListener?.invoke(false, null)
            return
        }
        val transcriptHash = BleCryptoHandshakeEngine.buildTranscriptHash(
            version = respData.version,
            role = BleCryptoHandshakeEngine.ROLE_RESPONDER,
            initiatorIdHash = localHash,
            responderIdHash = peerHash,
            initiatorEphemeralPubKey = ephem.public.encoded,
            responderEphemeralPubKey = respData.responderEphemeralPubKeyBytes,
            initiatorNonce = nonce,
            responderNonce = respData.responderNonce,
            timestampMs = pendingHandshakeTimestamp
        )
        if (!BleCryptoHandshakeEngine.verifyTranscriptSignature(
                respIdentityPubKey, transcriptHash, respData.signature)) {
            onErrorListener?.invoke("BLE: assinatura do respondedor inválida")
            onHandshakeCompletedListener?.invoke(false, null)
            return
        }

        val sharedSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(ephem.private, respEphemeralPubKey)
        val derived = BleCryptoHandshakeEngine.deriveSessionKeys(
            ecdhSecret = sharedSecret,
            initiatorNonce = nonce,
            responderNonce = respData.responderNonce,
            initiatorPubKey = ephem.public.encoded,
            responderPubKey = respData.responderEphemeralPubKeyBytes
        )

        val macValid = BleCryptoHandshakeEngine.verifySessionConfirmMac(derived.macKey, derived.sessionId, respData.sessionConfirmMac)
        if (!macValid) {
            onErrorListener?.invoke("SessionConfirmMac inválido do servidor")
            onHandshakeCompletedListener?.invoke(false, null)
            return
        }

        val session = BleSessionTokenManager.BleProximitySession(
            sessionId = derived.sessionId,
            sessionToken = derived.sessionToken,
            localIdentityHash = localHash,
            peerIdentityHash = peerHash,
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = derived.encKey,
            macKey = derived.macKey,
            sessionSalt = derived.sessionSalt,
            createdAtMs = System.currentTimeMillis(),
            lastActiveMs = System.currentTimeMillis()
        )
        sessionManager.registerSession(session)
        activeSessionId = session.sessionId

        onHandshakeCompletedListener?.invoke(true, session.sessionId)
    }

    @Synchronized
    private fun processNextQueuedPacket() {
        if (connectionState != ConnectionState.READY) return
        if (isWriting.get()) return

        val nextPacket = transmissionQueue.peek() ?: return
        if (isWriting.compareAndSet(false, true)) {
            val written = driver.writeCharacteristic(
                charUuid = CHAR_RX_UUID,
                value = nextPacket,
                writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            )
            if (!written) {
                isWriting.set(false)
                onErrorListener?.invoke("Erro imediato na chamada writeCharacteristic")
            }
        }
    }

    /**
     * Processa notificações recebidas do servidor na característica CHAR_TX.
     */
    fun processIncomingNotification(packet: ByteArray) {
        if (packet.size < 2) return
        if (packet[0] != BlePacketFramingEngine.MAGIC_BYTE) return

        val frameType = packet[1]

        // 1. Tratamento de Confirmação de Entrega (MSG_ACK)
        if (frameType == BlePacketFramingEngine.FRAME_TYPE_ACK && packet.size >= 13) {
            val msgId = ((packet[2].toInt() and 0xFF) shl 8) or (packet[3].toInt() and 0xFF)
            val statusCode = packet[4]
            val receivedHmac = packet.copyOfRange(5, 13)

            val session = activeSessionId?.let { sessionManager.getSession(it) }
            if (session != null) {
                val validHmac = BleCryptoHandshakeEngine.verifyAckHmac(
                    macKey = session.macKey,
                    sessionId = session.sessionId,
                    direction = BleCryptoHandshakeEngine.ROLE_INITIATOR,
                    msgId = msgId,
                    statusCode = statusCode,
                    receivedHmac = receivedHmac
                )
                if (validHmac && statusCode == 0x00.toByte()) {
                    onDeliveryConfirmedListener?.invoke(msgId)
                } else {
                    onErrorListener?.invoke("ACK recebido com HMAC inválido ou código de erro: $statusCode")
                }
            }
            return
        }

        // 2. Fragmento de Resposta de Handshake Inicial (FRAME_TYPE_HANDSHAKE_RESP = 0x04)
        if (frameType == BlePacketFramingEngine.FRAME_TYPE_HANDSHAKE_RESP) {
            val result = framingEngine.receivePacket(packet, "handshake-client", System.currentTimeMillis())
            if (result is BlePacketFramingEngine.ReassemblyResult.Completed) {
                handleHandshakeResp(result.payload)
            }
            return
        }

        // 3. Fragmento de Dados recebido do servidor
        val sessionId = activeSessionId ?: "session-client-unknown"
        val result = framingEngine.receivePacket(packet, sessionId, System.currentTimeMillis())
        if (result is BlePacketFramingEngine.ReassemblyResult.Completed &&
            result.frameType == BlePacketFramingEngine.FRAME_TYPE_DATA
        ) {
            val session = sessionManager.getSession(sessionId)
            if (session != null && !session.isExpired(System.currentTimeMillis())) {
                try {
                    val envelope = result.payload
                    val seqBytes = ByteBuffer.wrap(envelope, 4, 8)
                    val sequence = seqBytes.long

                    if (session.validateAndRecordRxSequence(sequence)) {
                        val decrypted = BleCryptoHandshakeEngine.decryptMessage(
                            envelope = envelope,
                            encKey = session.encKey,
                            sessionToken = session.sessionToken,
                            msgId = result.msgId,
                            direction = BleCryptoHandshakeEngine.ROLE_RESPONDER,
                            sequence = sequence
                        )
                        onMessageReceivedListener?.invoke(sessionId, result.msgId, decrypted)
                    }
                } catch (e: Exception) {
                    onErrorListener?.invoke("Erro ao decifrar notificação de dados: ${e.message}")
                }
            }
        }
    }

    /**
     * Verifica e encerra a conexão física se estiver inativa há mais de 15 segundos.
     */
    fun checkInactivity(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (connectionState == ConnectionState.READY || connectionState == ConnectionState.CONNECTED) {
            if (nowMs - lastActivityMs > inactivityTimeoutMs) {
                disconnect()
                return true
            }
        }
        return false
    }

    fun getQueuedPacketCount(): Int = transmissionQueue.size

    /**
     * Interface de abstração do rádio Bluetooth GATT Client para viabilizar testes unitários e produção.
     */
    interface BleGattClientDriver {
        fun connect(deviceAddress: String, autoConnect: Boolean, callback: BleGattClientCallback): Boolean
        fun discoverServices(): Boolean
        fun requestMtu(mtu: Int): Boolean
        fun setCharacteristicNotification(charUuid: UUID, enabled: Boolean): Boolean
        fun writeDescriptor(descUuid: UUID, value: ByteArray): Boolean
        fun writeCharacteristic(charUuid: UUID, value: ByteArray, writeType: Int): Boolean
        fun disconnect()
        fun close()
    }

    /**
     * Interface de retorno dos eventos de hardware do cliente GATT.
     */
    interface BleGattClientCallback {
        fun onConnectionStateChange(status: Int, newState: Int)
        fun onServicesDiscovered(status: Int, serviceUuids: List<UUID>)
        fun onMtuChanged(mtu: Int, status: Int)
        fun onCharacteristicWrite(charUuid: UUID, status: Int)
        fun onCharacteristicChanged(charUuid: UUID, value: ByteArray)
        fun onDescriptorWrite(descUuid: UUID, status: Int) {}
    }

    /**
     * Driver concreto que utiliza a API nativa Android Bluetooth no runtime físico.
     */
    private class DefaultAndroidGattClientDriver(private val context: Context?) : BleGattClientDriver {
        private var bluetoothGatt: BluetoothGatt? = null
        private var rxChar: BluetoothGattCharacteristic? = null
        private var txChar: BluetoothGattCharacteristic? = null

        @SuppressLint("MissingPermission")
        override fun connect(deviceAddress: String, autoConnect: Boolean, callback: BleGattClientCallback): Boolean {
            if (context == null) return false
            val bm = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return false
            val device = bm.adapter?.getRemoteDevice(deviceAddress) ?: return false

            val nativeCallback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                    callback.onConnectionStateChange(status, newState)
                }

                override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                    val uuids = gatt?.services?.map { it.uuid } ?: emptyList()
                    rxChar = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_RX_UUID)
                    txChar = gatt?.getService(SERVICE_UUID)?.getCharacteristic(CHAR_TX_UUID)
                    callback.onServicesDiscovered(status, uuids)
                }

                override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                    callback.onMtuChanged(mtu, status)
                }

                override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                    characteristic?.uuid?.let { callback.onCharacteristicWrite(it, status) }
                }

                @Deprecated("Deprecated in Java")
                override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
                    if (characteristic != null) {
                        callback.onCharacteristicChanged(characteristic.uuid, characteristic.value ?: ByteArray(0))
                    }
                }

                override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                    callback.onCharacteristicChanged(characteristic.uuid, value)
                }

                override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                    descriptor?.uuid?.let { callback.onDescriptorWrite(it, status) }
                }
            }

            try {
                bluetoothGatt = device.connectGatt(context, autoConnect, nativeCallback, BluetoothDevice.TRANSPORT_LE)
                return bluetoothGatt != null
            } catch (_: Exception) {
                return false
            }
        }

        @SuppressLint("MissingPermission")
        override fun discoverServices(): Boolean = try {
            bluetoothGatt?.discoverServices() == true
        } catch (_: Exception) {
            false
        }

        @SuppressLint("MissingPermission")
        override fun requestMtu(mtu: Int): Boolean = try {
            bluetoothGatt?.requestMtu(mtu) == true
        } catch (_: Exception) {
            false
        }

        @SuppressLint("MissingPermission")
        override fun setCharacteristicNotification(charUuid: UUID, enabled: Boolean): Boolean = try {
            val ch = if (charUuid == CHAR_TX_UUID) txChar else null
            if (ch != null) {
                bluetoothGatt?.setCharacteristicNotification(ch, enabled) == true
            } else false
        } catch (_: Exception) {
            false
        }

        @SuppressLint("MissingPermission")
        override fun writeDescriptor(descUuid: UUID, value: ByteArray): Boolean = try {
            val desc = txChar?.getDescriptor(descUuid)
            if (desc != null) {
                desc.value = value
                bluetoothGatt?.writeDescriptor(desc) == true
            } else false
        } catch (_: Exception) {
            false
        }

        @SuppressLint("MissingPermission")
        override fun writeCharacteristic(charUuid: UUID, value: ByteArray, writeType: Int): Boolean = try {
            val ch = if (charUuid == CHAR_RX_UUID) rxChar else null
            if (ch != null) {
                ch.writeType = writeType
                ch.value = value
                bluetoothGatt?.writeCharacteristic(ch) == true
            } else false
        } catch (_: Exception) {
            false
        }

        @SuppressLint("MissingPermission")
        override fun disconnect() {
            try {
                bluetoothGatt?.disconnect()
            } catch (_: Exception) {}
        }

        @SuppressLint("MissingPermission")
        override fun close() {
            try {
                bluetoothGatt?.close()
            } catch (_: Exception) {}
            bluetoothGatt = null
        }
    }
}
