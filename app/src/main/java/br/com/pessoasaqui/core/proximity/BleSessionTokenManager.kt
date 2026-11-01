package br.com.pessoasaqui.core.proximity

import java.util.Arrays
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Gerenciador de Sessões Criptográficas Temporárias BLE (FASE 3B).
 *
 * Responsável por:
 * - Manter as sessões ativas na memória RAM com isolamento estrito por identificador e peer.
 * - Controlar os prazos de carência (30 segundos) e expiração definitiva (45 segundos).
 * - Executar a destruição segura das chaves criptográficas (memory wiping) ao encerrar sessões.
 * - Gerenciar contadores monotônicos de 64 bits para unicidade estrita de IVs AES-GCM.
 * - Garantir que desconexões ou retomadas de canal GATT não reiniciem contadores de sequência.
 * - Proteger contra reprodução de mensagens e de pacotes de retomada (SESSION_RESUME).
 * - Deduplicar mensagens processadas recentemente.
 */
class BleSessionTokenManager(
    private val gracePeriodMs: Long = DEFAULT_GRACE_PERIOD_MS,
    private val expirationTimeoutMs: Long = DEFAULT_EXPIRATION_TIMEOUT_MS
) {

    companion object {
        const val DEFAULT_GRACE_PERIOD_MS = 30000L
        const val DEFAULT_EXPIRATION_TIMEOUT_MS = 45000L
        const val DEDUPLICATION_WINDOW_MS = 60000L
        const val MAX_RESUME_NONCES_TRACKED = 64
    }

    /**
     * Representação completa da sessão lógica de proximidade em memória.
     */
    data class BleProximitySession(
        val sessionId: String,
        val sessionToken: Int,
        val localIdentityHash: String,
        val peerIdentityHash: String,
        val localRole: Byte,
        val encKey: ByteArray,
        val macKey: ByteArray,
        val sessionSalt: ByteArray,
        val createdAtMs: Long,
        var lastActiveMs: Long
    ) {
        val txSequenceCounter = AtomicLong(0L)
        val highestRxSequenceCounter = AtomicLong(0L)

        // Nonces de retomada já utilizados para impedir repetição de SESSION_RESUME
        private val usedResumeNonces = ConcurrentHashMap.newKeySet<String>()

        // Mensagens processadas recentemente para deduplicação (msgId -> timestampMs)
        private val processedMessages = ConcurrentHashMap<Int, Long>()

        fun recordResumeNonce(nonce: ByteArray): Boolean {
            val key = nonce.joinToString("") { "%02X".format(it) }
            if (usedResumeNonces.contains(key)) {
                return false
            }
            if (usedResumeNonces.size >= MAX_RESUME_NONCES_TRACKED) {
                usedResumeNonces.clear()
            }
            return usedResumeNonces.add(key)
        }

        fun isMessageDuplicate(msgId: Int, nowMs: Long): Boolean {
            // Limpa mensagens com mais de 60 segundos
            processedMessages.entries.removeIf { nowMs - it.value > DEDUPLICATION_WINDOW_MS }
            return processedMessages.containsKey(msgId)
        }

        fun markMessageProcessed(msgId: Int, nowMs: Long) {
            processedMessages[msgId] = nowMs
        }

        fun nextTxSequence(): Long {
            return txSequenceCounter.incrementAndGet()
        }

        fun validateAndRecordRxSequence(sequence: Long): Boolean {
            while (true) {
                val current = highestRxSequenceCounter.get()
                if (sequence <= current) {
                    return false // Mensagem repetida ou fora de ordem
                }
                if (highestRxSequenceCounter.compareAndSet(current, sequence)) {
                    return true
                }
            }
        }

        fun isGracePeriod(
            nowMs: Long,
            graceMs: Long = DEFAULT_GRACE_PERIOD_MS,
            timeoutMs: Long = DEFAULT_EXPIRATION_TIMEOUT_MS
        ): Boolean {
            val elapsed = nowMs - lastActiveMs
            return elapsed in (graceMs + 1)..timeoutMs
        }

        fun isExpired(
            nowMs: Long,
            timeoutMs: Long = DEFAULT_EXPIRATION_TIMEOUT_MS
        ): Boolean {
            return (nowMs - lastActiveMs) > timeoutMs
        }

        /**
         * Sobrescreve as chaves de sessão com zeros antes de liberar da memória.
         */
        fun wipe() {
            Arrays.fill(encKey, 0.toByte())
            Arrays.fill(macKey, 0.toByte())
            Arrays.fill(sessionSalt, 0.toByte())
            usedResumeNonces.clear()
            processedMessages.clear()
        }
    }

    private val sessionsById = ConcurrentHashMap<String, BleProximitySession>()
    private val sessionsByToken = ConcurrentHashMap<Int, BleProximitySession>()
    private val sessionsByPeer = ConcurrentHashMap<String, BleProximitySession>()

    /**
     * Registra uma nova sessão criptográfica acordada entre os dois aparelhos.
     */
    @Synchronized
    fun registerSession(session: BleProximitySession) {
        // Encerra sessão anterior com o mesmo interlocutor se houver
        val oldPeerSession = sessionsByPeer[session.peerIdentityHash]
        if (oldPeerSession != null && oldPeerSession.sessionId != session.sessionId) {
            terminateSession(oldPeerSession.sessionId)
        }

        sessionsById[session.sessionId] = session
        sessionsByToken[session.sessionToken] = session
        sessionsByPeer[session.peerIdentityHash] = session
    }

    fun getSession(sessionId: String): BleProximitySession? = sessionsById[sessionId]

    fun getSessionByToken(sessionToken: Int): BleProximitySession? = sessionsByToken[sessionToken]

    fun getSessionByPeer(peerIdentityHash: String): BleProximitySession? = sessionsByPeer[peerIdentityHash]

    fun updateActivity(sessionId: String, nowMs: Long = System.currentTimeMillis()): Boolean {
        val session = sessionsById[sessionId] ?: return false
        if (session.isExpired(nowMs, expirationTimeoutMs)) {
            terminateSession(sessionId)
            return false
        }
        session.lastActiveMs = nowMs
        return true
    }

    /**
     * Valida um pacote de retomada de sessão (SESSION_RESUME de 20 bytes).
     * Retorna a sessão ativa se a autenticação for bem-sucedida, ou null se rejeitada.
     */
    @Synchronized
    fun processResumePacket(
        packet: ByteArray,
        direction: Byte,
        nowMs: Long = System.currentTimeMillis()
    ): BleProximitySession? {
        if (packet.size != 20) return null
        if (packet[0] != BlePacketFramingEngine.MAGIC_BYTE) return null
        if (packet[1] != BlePacketFramingEngine.FRAME_TYPE_SESSION_RESUME) return null

        val sessionToken = ((packet[2].toInt() and 0xFF) shl 24) or
                ((packet[3].toInt() and 0xFF) shl 16) or
                ((packet[4].toInt() and 0xFF) shl 8) or
                (packet[5].toInt() and 0xFF)

        val session = sessionsByToken[sessionToken] ?: return null

        if (session.isExpired(nowMs, expirationTimeoutMs)) {
            terminateSession(session.sessionId)
            return null
        }

        val resumeNonce = packet.copyOfRange(6, 12)
        val resumeMac = packet.copyOfRange(12, 20)

        // 1. Verifica se o nonce de retomada é novo (proteção contra replay)
        if (!session.recordResumeNonce(resumeNonce)) {
            return null
        }

        // 2. Valida o MAC criptográfico com a chave K_mac da sessão
        val validMac = BleCryptoHandshakeEngine.verifyResumeMac(
            macKey = session.macKey,
            sessionToken = sessionToken,
            resumeNonce = resumeNonce,
            direction = direction,
            receivedMac = resumeMac
        )

        return if (validMac) {
            session.lastActiveMs = nowMs
            session
        } else {
            null
        }
    }

    /**
     * Remove sessões que ultrapassaram os 45 segundos sem presença ou atividade válida.
     * As chaves criptográficas são sobregravadas com zeros para segurança em memória.
     */
    @Synchronized
    fun cleanupExpiredSessions(nowMs: Long = System.currentTimeMillis()): List<String> {
        val expiredIds = ArrayList<String>()
        val iterator = sessionsById.entries.iterator()

        while (iterator.hasNext()) {
            val entry = iterator.next()
            val session = entry.value
            if (session.isExpired(nowMs, expirationTimeoutMs)) {
                iterator.remove()
                sessionsByToken.remove(session.sessionToken)
                sessionsByPeer.remove(session.peerIdentityHash)
                session.wipe()
                expiredIds.add(session.sessionId)
            }
        }

        return expiredIds
    }

    /**
     * Encerra compulsoriamente uma sessão e destrói suas chaves da memória.
     */
    @Synchronized
    fun terminateSession(sessionId: String): Boolean {
        val session = sessionsById.remove(sessionId) ?: return false
        sessionsByToken.remove(session.sessionToken)
        sessionsByPeer.remove(session.peerIdentityHash)
        session.wipe()
        return true
    }

    fun getActiveSessionCount(): Int = sessionsById.size

    @Synchronized
    fun clearAll() {
        for (session in sessionsById.values) {
            session.wipe()
        }
        sessionsById.clear()
        sessionsByToken.clear()
        sessionsByPeer.clear()
    }
}
