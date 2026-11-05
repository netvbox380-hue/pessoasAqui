package br.com.pessoasaqui.core.proximity

import android.content.Context
import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordenador Central de Transporte Bluetooth BLE Direto (ETAPA 3D & 3E - Correção).
 *
 * Responsável por:
 * - Orquestrar o envio e recebimento de mensagens temporárias diretamente via rádio Bluetooth BLE GATT (Modo A).
 * - Gerenciar a conexão GATT de forma estritamente assíncrona, aguardando o estado READY antes de emitir pacotes.
 * - Fila segura de operações pendentes associadas ao destinatário correto, com timeout e controle de estado.
 * - Realizar handshake criptográfico mútuo inicial via ECDH NIST P-256 e assinaturas digitais.
 * - Transmissão segura do nome completo do participante dentro do canal criptografado via AES-256-GCM.
 * - Notificar a confirmação física real de entrega (MSG_ACK) e falhas reais sem falsos positivos.
 * - Isolamento absoluto em relação ao Modo B (nunca rotear mensagens permanentes por BLE e nunca fazer fallback para o Render).
 */
open class BleDirectTransportCoordinator(
    private val context: Context? = null,
    val cryptoIdentityManager: CryptoIdentityManager = CryptoIdentityManager(context),
    val framingEngine: BlePacketFramingEngine = BlePacketFramingEngine(),
    val sessionManager: BleSessionTokenManager = BleSessionTokenManager(),
    serverDriver: BleGattServerManager.BleGattServerDriver? = null,
    clientDriver: BleGattClientManager.BleGattClientDriver? = null
) {

    companion object {
        const val META_ALIAS_PREFIX = "[META_ALIAS:"
        const val META_ALIAS_SUFFIX = "]"
        const val MAX_ALIAS_LENGTH = 64
        const val GATT_CONNECT_TIMEOUT_SECONDS = 12L
    }

    /**
     * Registro de solicitação de envio pendente de confirmação física de conexão.
     */
    data class PendingGattRequest(
        val recipientId: String,
        val deviceAddress: String,
        val payload: ByteArray,
        val msgId: Int,
        val messageUuid: String,
        val myAlias: String,
        val isResume: Boolean,
        val sessionId: String?,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    val serverManager = BleGattServerManager(
        context = context,
        framingEngine = framingEngine,
        sessionManager = sessionManager,
        customDriver = serverDriver
    )

    val clientManager = BleGattClientManager(
        context = context,
        framingEngine = framingEngine,
        sessionManager = sessionManager,
        customDriver = clientDriver
    )

    // Mapeamento de hash de identidade técnica para endereço de rádio BLE (MAC)
    private val peerDeviceAddresses = ConcurrentHashMap<String, String>()

    // Fila segura de operações pendentes aguardando conexão física e estado READY
    private val pendingRequests = ConcurrentLinkedQueue<PendingGattRequest>()

    // Mapeamento de msgId (16-bit) para identificador da mensagem na UI (UUID) e destinatário
    private val msgIdToUuidMap = ConcurrentHashMap<Int, Pair<String, String>>()

    // Registro de apelidos já transmitidos por sessão para evitar spam redundante
    private val aliasesTransmittedPerSession = ConcurrentHashMap.newKeySet<String>()

    // Temporizadores de watchdog de conexão física
    private val activeWatchdogs = ConcurrentHashMap<String, ScheduledFuture<*>>()
    private val scheduler = Executors.newSingleThreadScheduledExecutor()

    // Gerador de identificadores locais de mensagem BLE (16-bit)
    private val messageIdCounter = AtomicInteger(1)

    // Ouvintes de aplicação
    var onTemporaryMessageReceived: ((peerId: String, text: String, msgId: Int) -> Unit)? = null
    var onDeliveryConfirmed: ((peerId: String, msgId: Int) -> Unit)? = null
    var onDeliveryConfirmedWithUuid: ((peerId: String, msgId: Int, messageUuid: String) -> Unit)? = null
    var onDeliveryFailed: ((peerId: String, msgId: Int, messageUuid: String, reason: String) -> Unit)? = null
    var onPeerAliasReceived: ((peerId: String, fullAlias: String) -> Unit)? = null
    var onErrorListener: ((errorMessage: String) -> Unit)? = null

    init {
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val myKp = cryptoIdentityManager.getActiveKeyPair()
        serverManager.localIdentityHash = myId
        serverManager.localIdentityKeyPair = myKp
        clientManager.localIdentityHash = myId
        clientManager.localIdentityKeyPair = myKp

        // Registra receptores de mensagens tanto no Server quanto no Client
        serverManager.onMessageReceivedListener = { sessionId, msgId, plaintext ->
            handleIncomingPlaintext(sessionId, msgId, plaintext)
        }

        clientManager.onMessageReceivedListener = { sessionId, msgId, plaintext ->
            handleIncomingPlaintext(sessionId, msgId, plaintext)
        }

        // Confirmação autenticada de entrega (ACK verificado via HMAC)
        clientManager.onDeliveryConfirmedListener = { msgId ->
            val mapping = msgIdToUuidMap.remove(msgId)
            val peerId = mapping?.first ?: (clientManager.activeSessionId?.let { sessionManager.getSession(it) }?.peerIdentityHash ?: "UNKNOWN_PEER")
            val messageUuid = mapping?.second ?: ""
            onDeliveryConfirmed?.invoke(peerId, msgId)
            onDeliveryConfirmedWithUuid?.invoke(peerId, msgId, messageUuid)
        }

        // Ouvinte de transição de estado da máquina GATT Client
        clientManager.onStateChangedListener = { newState ->
            handleClientStateChanged(newState)
        }

        serverManager.onErrorListener = { error -> onErrorListener?.invoke("GATT Server: $error") }
        clientManager.onErrorListener = { error -> onErrorListener?.invoke("GATT Client: $error") }
    }

    /**
     * Processa a mudança de estado da conexão física do cliente GATT.
     */
    @Synchronized
    private fun handleClientStateChanged(newState: BleGattClientManager.ConnectionState) {
        val address = clientManager.connectedDeviceAddress ?: return

        when (newState) {
            BleGattClientManager.ConnectionState.READY -> {
                // Cancela watchdog de timeout pois a conexão atingiu prontidão completa
                cancelWatchdog(address)

                // Processa todas as requisições pendentes para este dispositivo
                val matching = pendingRequests.filter { it.deviceAddress == address }
                pendingRequests.removeAll(matching)

                for (req in matching) {
                    processPendingRequest(req)
                }
            }
            BleGattClientManager.ConnectionState.DISCONNECTED -> {
                cancelWatchdog(address)

                // Notifica falha para requisições pendentes deste endereço
                val matching = pendingRequests.filter { it.deviceAddress == address }
                pendingRequests.removeAll(matching)

                for (req in matching) {
                    msgIdToUuidMap.remove(req.msgId)
                    onDeliveryFailed?.invoke(req.recipientId, req.msgId, req.messageUuid, "Desconectado antes de concluir o envio")
                }
            }
            else -> {
                // Estados intermediários (CONNECTING, CONNECTED, SERVICES_DISCOVERED): aguarda READY
            }
        }
    }

    /**
     * Executa o processamento de uma requisição pendente quando o canal GATT está READY.
     */
    private fun processPendingRequest(req: PendingGattRequest) {
        val now = System.currentTimeMillis()

        if (req.isResume && req.sessionId != null) {
            val session = sessionManager.getSession(req.sessionId)
            if (session != null && !session.isExpired(now)) {
                clientManager.sendSessionResume(req.sessionId)
                sendMetadataAliasIfPending(req.sessionId, req.myAlias)
                val sent = clientManager.sendEncryptedMessage(req.sessionId, req.payload, req.msgId)
                if (!sent) {
                    msgIdToUuidMap.remove(req.msgId)
                    onDeliveryFailed?.invoke(req.recipientId, req.msgId, req.messageUuid, "Falha ao enfileirar mensagem de retomada")
                }
                return
            }
        }

        // Se for primeiro contato ou a sessão tiver expirado, executa Handshake Inicial
        executeHandshakeAndSend(req)
    }

    /**
     * Inicia o Handshake criptográfico e transmite a mensagem e metadados ao autenticar.
     */
    private fun executeHandshakeAndSend(req: PendingGattRequest): Boolean {
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val myKp = cryptoIdentityManager.getActiveKeyPair() ?: BleCryptoHandshakeEngine.generateEphemeralKeyPair()

        return clientManager.startHandshake(
            peerIdentityHash = req.recipientId,
            localIdHash = myId,
            localKeyPair = myKp,
            onComplete = { success, sessionId ->
                if (success && sessionId != null) {
                    // 1. Transmite o nome completo autenticado em envelope criptografado AES-256-GCM
                    sendMetadataAliasIfPending(sessionId, req.myAlias)

                    // 2. Transmite a mensagem do usuário
                    val sent = clientManager.sendEncryptedMessage(sessionId, req.payload, req.msgId)
                    if (!sent) {
                        msgIdToUuidMap.remove(req.msgId)
                        onDeliveryFailed?.invoke(req.recipientId, req.msgId, req.messageUuid, "Falha ao transmitir fragmentos criptografados")
                    }
                } else {
                    msgIdToUuidMap.remove(req.msgId)
                    onErrorListener?.invoke("Falha no handshake inicial com ${req.recipientId}")
                    onDeliveryFailed?.invoke(req.recipientId, req.msgId, req.messageUuid, "Falha no handshake inicial")
                }
            }
        )
    }

    /**
     * Transmite o nome completo do participante de forma criptografada (AES-256-GCM)
     * caso ainda não tenha sido transmitido na sessão ativa.
     */
    private fun sendMetadataAliasIfPending(sessionId: String, myAlias: String, isServer: Boolean = false) {
        if (myAlias.isBlank()) return
        val sessionKey = "$sessionId:$myAlias"
        if (!aliasesTransmittedPerSession.add(sessionKey)) {
            return // Já transmitido nesta sessão
        }

        val safeAlias = myAlias.take(MAX_ALIAS_LENGTH).trim()
        val metaText = "$META_ALIAS_PREFIX$safeAlias$META_ALIAS_SUFFIX"
        val payload = metaText.toByteArray(StandardCharsets.UTF_8)
        val aliasMsgId = nextMessageId()

        if (isServer) {
            serverManager.sendEncryptedNotification(sessionId, payload, aliasMsgId)
        } else {
            clientManager.sendEncryptedMessage(sessionId, payload, aliasMsgId)
        }
    }

    /**
     * Trata dados decifrados recebidos, separando metadados de mensagens de chat.
     */
    private fun handleIncomingPlaintext(sessionId: String, msgId: Int, plaintext: ByteArray) {
        val session = sessionManager.getSession(sessionId)
        val peerId = session?.peerIdentityHash ?: "UNKNOWN_PEER"
        val text = String(plaintext, StandardCharsets.UTF_8)

        // Envelope criptografado de metadados: atualização de nome completo do participante
        if (text.startsWith(META_ALIAS_PREFIX) && text.endsWith(META_ALIAS_SUFFIX)) {
            val rawAlias = text.removePrefix(META_ALIAS_PREFIX).removeSuffix(META_ALIAS_SUFFIX).trim()
            if (rawAlias.isNotBlank() && rawAlias.length <= MAX_ALIAS_LENGTH) {
                onPeerAliasReceived?.invoke(peerId, rawAlias)
            }
            return
        }

        // Mensagem normal do chat
        onTemporaryMessageReceived?.invoke(peerId, text, msgId)
    }

    /**
     * Inicializa a infraestrutura de rádio GATT Server local.
     */
    fun start(): Boolean {
        return serverManager.startServer()
    }

    /**
     * Encerra a infraestrutura de rádio e desconecta clientes ativos.
     */
    fun stop() {
        cancelAllWatchdogs()
        clientManager.close()
        serverManager.stopServer()
        pendingRequests.clear()
        msgIdToUuidMap.clear()
    }

    /**
     * Recria estado de identidade GATT após CLAIM familiar confirmado.
     * Nunca reutilizar tokens/sessões efêmeras associados ao aparelho antigo.
     */
    @Synchronized
    fun onLocalIdentityRecovered() {
        stop()
        sessionManager.clearAll()
        peerDeviceAddresses.clear()
        aliasesTransmittedPerSession.clear()
        val identity = cryptoIdentityManager.getTechnicalIdentity()
        val pair = cryptoIdentityManager.getActiveKeyPair()
            ?: throw SecurityException("Chave de identidade recuperada indisponível")
        serverManager.localIdentityHash = identity
        serverManager.localIdentityKeyPair = pair
        clientManager.localIdentityHash = identity
        clientManager.localIdentityKeyPair = pair
        start()
    }

    /**
     * Registra o endereço de hardware Bluetooth associado a um hash de identidade técnica.
     */
    fun recordPeerDeviceAddress(identityHash: String, deviceAddress: String) {
        val clean = identityHash.removePrefix("peer-").trim().uppercase()
        peerDeviceAddresses[clean] = deviceAddress
    }

    fun getPeerDeviceAddress(identityHash: String): String? {
        val clean = identityHash.removePrefix("peer-").trim().uppercase()
        return peerDeviceAddresses[clean]
    }

    /**
     * Envia uma mensagem temporária direta via Bluetooth BLE GATT (Modo A).
     *
     * Orquestração:
     * - Se a conexão já estiver READY para este participante, despacha diretamente.
     * - Se estiver desconectado ou conectando, enfileira de forma segura e dispara/aguarda a conexão.
     * - Timeout automático de 12 segundos caso o hardware remoto não responda.
     * - Nunca aciona fallback para o Render ou a Internet.
     */
    @Synchronized
    open fun sendTemporaryMessage(
        recipientId: String,
        text: String,
        myAlias: String,
        messageUuid: String = ""
    ): Boolean {
        val cleanRecipient = recipientId.removePrefix("peer-").trim().uppercase()
        val now = System.currentTimeMillis()

        val existingSession = sessionManager.getSessionByPeer(cleanRecipient)
        val msgId = nextMessageId()
        val payload = text.toByteArray(StandardCharsets.UTF_8)

        if (messageUuid.isNotBlank()) {
            msgIdToUuidMap[msgId] = Pair(cleanRecipient, messageUuid)
        }

        // 1. Cenário de canal aberto como cliente e já em estado READY
        if (existingSession != null && !existingSession.isExpired(now)) {
            if (clientManager.connectionState == BleGattClientManager.ConnectionState.READY &&
                clientManager.activeSessionId == existingSession.sessionId
            ) {
                sendMetadataAliasIfPending(existingSession.sessionId, myAlias)
                return clientManager.sendEncryptedMessage(existingSession.sessionId, payload, msgId)
            }

            // Cenário em que este aparelho é o GATT Server e o destinatário é o cliente conectado
            val serverSession = serverManager.activeSessionId?.let { sessionManager.getSession(it) }
            if (serverSession?.peerIdentityHash == cleanRecipient) {
                sendMetadataAliasIfPending(serverSession.sessionId, myAlias, isServer = true)
                return serverManager.sendEncryptedNotification(serverSession.sessionId, payload, msgId)
            }

            // Sessão lógica existe em RAM, mas canal físico precisa ser reconectado
            val deviceAddress = peerDeviceAddresses[cleanRecipient]
            if (deviceAddress != null) {
                val req = PendingGattRequest(
                    recipientId = cleanRecipient,
                    deviceAddress = deviceAddress,
                    payload = payload,
                    msgId = msgId,
                    messageUuid = messageUuid,
                    myAlias = myAlias,
                    isResume = true,
                    sessionId = existingSession.sessionId
                )
                pendingRequests.add(req)

                if (clientManager.connectionState == BleGattClientManager.ConnectionState.READY &&
                    clientManager.connectedDeviceAddress == deviceAddress
                ) {
                    pendingRequests.remove(req)
                    processPendingRequest(req)
                    return true
                }

                startWatchdogTimer(deviceAddress)
                val connecting = clientManager.connect(deviceAddress)
                if (!connecting) {
                    cancelWatchdog(deviceAddress)
                    pendingRequests.remove(req)
                    msgIdToUuidMap.remove(msgId)
                    onDeliveryFailed?.invoke(cleanRecipient, msgId, messageUuid, "Conexão GATT não iniciada")
                }
                return connecting
            }
            return false
        }

        // 2. Primeiro contato ou sessão expirada: Handshake Criptográfico Inicial
        val deviceAddress = peerDeviceAddresses[cleanRecipient] ?: return false

        val req = PendingGattRequest(
            recipientId = cleanRecipient,
            deviceAddress = deviceAddress,
            payload = payload,
            msgId = msgId,
            messageUuid = messageUuid,
            myAlias = myAlias,
            isResume = false,
            sessionId = null
        )
        pendingRequests.add(req)

        if (clientManager.connectionState == BleGattClientManager.ConnectionState.READY &&
            clientManager.connectedDeviceAddress == deviceAddress
        ) {
            pendingRequests.remove(req)
            processPendingRequest(req)
            return true
        }

        startWatchdogTimer(deviceAddress)
        val connecting = clientManager.connect(deviceAddress)
        if (!connecting) {
            cancelWatchdog(deviceAddress)
            pendingRequests.remove(req)
            msgIdToUuidMap.remove(msgId)
            onDeliveryFailed?.invoke(cleanRecipient, msgId, messageUuid, "Conexão GATT não iniciada")
        }
        return connecting
    }

    private fun startWatchdogTimer(deviceAddress: String) {
        cancelWatchdog(deviceAddress)
        val future = scheduler.schedule({
            synchronized(this) {
                if (clientManager.connectedDeviceAddress == deviceAddress &&
                    clientManager.connectionState != BleGattClientManager.ConnectionState.READY
                ) {
                    onErrorListener?.invoke("Timeout aguardando prontidão GATT para $deviceAddress")
                    clientManager.disconnect()
                    val timedOut = pendingRequests.filter { it.deviceAddress == deviceAddress }
                    pendingRequests.removeAll(timedOut)
                    timedOut.forEach { req ->
                        msgIdToUuidMap.remove(req.msgId)
                        onDeliveryFailed?.invoke(req.recipientId, req.msgId, req.messageUuid, "Timeout de conexão")
                    }
                }
            }
        }, GATT_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        activeWatchdogs[deviceAddress] = future
    }

    private fun cancelWatchdog(deviceAddress: String) {
        activeWatchdogs.remove(deviceAddress)?.cancel(false)
    }

    private fun cancelAllWatchdogs() {
        activeWatchdogs.values.forEach { it.cancel(false) }
        activeWatchdogs.clear()
    }

    /**
     * Limpa sessões expiradas (> 45s) com memory wiping seguro e limpa requisições órfãs.
     */
    fun cleanupExpiredSessions(nowMs: Long = System.currentTimeMillis()): List<String> {
        val expired = sessionManager.cleanupExpiredSessions(nowMs)
        if (expired.isNotEmpty()) {
            pendingRequests.removeIf { req -> req.sessionId != null && expired.contains(req.sessionId) }
            aliasesTransmittedPerSession.removeIf { key -> expired.any { key.startsWith(it) } }
        }
        return expired
    }

    private fun nextMessageId(): Int {
        val next = messageIdCounter.incrementAndGet() and 0xFFFF
        if (next == 0) return 1
        return next
    }
}
