package br.com.pessoasaqui.core.proximity

import android.content.Context
import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Coordenador Central de Transporte Bluetooth BLE Direto (ETAPA 3D).
 *
 * Responsável por:
 * - Orquestrar o envio e recebimento de mensagens temporárias diretamente via rádio Bluetooth BLE GATT (Modo A).
 * - Realizar handshake criptográfico mútuo inicial com desconhecidos via ECDH NIST P-256 e assinaturas digitais.
 * - Gerenciar a retomada rápida de sessão lógica em memória RAM (SESSION_RESUME de 20 bytes).
 * - Garantir isolamento rigoroso entre participantes múltiplos e rejeitar pacotes sem autenticação.
 * - Manter estrita separação em relação ao Modo B (nunca rotear mensagens permanentes por BLE e nunca
 *   fazer fallback para a internet/Render quando o Bluetooth falhar).
 */
open class BleDirectTransportCoordinator(
    private val context: Context? = null,
    val cryptoIdentityManager: CryptoIdentityManager = CryptoIdentityManager(context),
    val framingEngine: BlePacketFramingEngine = BlePacketFramingEngine(),
    val sessionManager: BleSessionTokenManager = BleSessionTokenManager(),
    serverDriver: BleGattServerManager.BleGattServerDriver? = null,
    clientDriver: BleGattClientManager.BleGattClientDriver? = null
) {

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

    // Gerador de identificadores locais de mensagem BLE (16-bit)
    private val messageIdCounter = AtomicInteger(1)

    // Ouvintes de aplicação
    var onTemporaryMessageReceived: ((peerId: String, text: String, msgId: Int) -> Unit)? = null
    var onDeliveryConfirmed: ((peerId: String, msgId: Int) -> Unit)? = null
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
            val session = sessionManager.getSession(sessionId)
            val peerId = session?.peerIdentityHash ?: "UNKNOWN_PEER"
            val text = String(plaintext, StandardCharsets.UTF_8)
            onTemporaryMessageReceived?.invoke(peerId, text, msgId)
        }

        clientManager.onMessageReceivedListener = { sessionId, msgId, plaintext ->
            val session = sessionManager.getSession(sessionId)
            val peerId = session?.peerIdentityHash ?: "UNKNOWN_PEER"
            val text = String(plaintext, StandardCharsets.UTF_8)
            onTemporaryMessageReceived?.invoke(peerId, text, msgId)
        }

        clientManager.onDeliveryConfirmedListener = { msgId ->
            val activeSession = clientManager.activeSessionId?.let { sessionManager.getSession(it) }
            val peerId = activeSession?.peerIdentityHash ?: "UNKNOWN_PEER"
            onDeliveryConfirmed?.invoke(peerId, msgId)
        }

        serverManager.onErrorListener = { error -> onErrorListener?.invoke("GATT Server: $error") }
        clientManager.onErrorListener = { error -> onErrorListener?.invoke("GATT Client: $error") }
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
        clientManager.close()
        serverManager.stopServer()
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
     * Regras Fundamentais:
     * - Comunicação exclusivamente direta (offline), sem contato com a internet ou Render.
     * - Se a sessão lógica já existir em memória, utiliza retomada rápida ou canal aberto.
     * - Se for o primeiro contato, executa handshake mútuo antes do envio dos fragmentos.
     * - Falhas de entrega NÃO acionam fallback para a internet.
     */
    @Synchronized
    open fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String): Boolean {
        val cleanRecipient = recipientId.removePrefix("peer-").trim().uppercase()
        val now = System.currentTimeMillis()

        // 1. Verifica se já existe uma sessão lógica válida em memória RAM
        val existingSession = sessionManager.getSessionByPeer(cleanRecipient)
        val msgId = nextMessageId()
        val payload = text.toByteArray(StandardCharsets.UTF_8)

        if (existingSession != null && !existingSession.isExpired(now)) {
            // Sessão lógica existente em memória: se cliente estiver pronto, envia direto
            if (clientManager.connectionState == BleGattClientManager.ConnectionState.READY &&
                clientManager.activeSessionId == existingSession.sessionId
            ) {
                return clientManager.sendEncryptedMessage(existingSession.sessionId, payload, msgId)
            }

            // Se desconectado fisicamente, reconecta e autentica via SESSION_RESUME de 20 bytes
            val deviceAddress = peerDeviceAddresses[cleanRecipient]
            if (deviceAddress != null) {
                val connected = clientManager.connect(deviceAddress)
                if (connected) {
                    clientManager.sendSessionResume(existingSession.sessionId)
                    return clientManager.sendEncryptedMessage(existingSession.sessionId, payload, msgId)
                }
            } else {
                // Se conectado ao servidor local como cliente, o servidor notifica a mensagem
                val serverSession = serverManager.activeSessionId?.let { sessionManager.getSession(it) }
                if (serverSession?.peerIdentityHash == cleanRecipient) {
                    return serverManager.sendEncryptedNotification(serverSession.sessionId, payload, msgId)
                }
                return false
            }
        }

        // 2. Primeiro contato entre participantes: Handshake Criptográfico Inicial
        val deviceAddress = peerDeviceAddresses[cleanRecipient] ?: return false
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val myKp = cryptoIdentityManager.getActiveKeyPair() ?: BleCryptoHandshakeEngine.generateEphemeralKeyPair()

        // Inicia conexão física
        val connected = clientManager.connect(deviceAddress)
        if (!connected) return false

        // Inicia Handshake Inicial (FRAME_TYPE_HANDSHAKE_INIT = 0x03)
        val handshakeSent = clientManager.startHandshake(
            peerIdentityHash = cleanRecipient,
            localIdHash = myId,
            localKeyPair = myKp,
            onComplete = { success, sessionId ->
                if (success && sessionId != null) {
                    clientManager.sendEncryptedMessage(sessionId, payload, msgId)
                } else {
                    onErrorListener?.invoke("Falha no handshake inicial com $cleanRecipient")
                }
            }
        )

        return handshakeSent
    }

    /**
     * Limpa sessões expiradas (> 45s) com memory wiping seguro.
     */
    fun cleanupExpiredSessions(nowMs: Long = System.currentTimeMillis()): List<String> {
        return sessionManager.cleanupExpiredSessions(nowMs)
    }

    private fun nextMessageId(): Int {
        val next = messageIdCounter.incrementAndGet() and 0xFFFF
        if (next == 0) return 1
        return next
    }
}
