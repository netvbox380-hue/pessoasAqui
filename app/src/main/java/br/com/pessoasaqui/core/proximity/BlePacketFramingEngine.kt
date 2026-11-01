package br.com.pessoasaqui.core.proximity

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.min

/**
 * Motor de Fragmentação e Reconstrução de Envelopes de Mensagens BLE (FASE 3A).
 *
 * Responsável exclusivamente por:
 * - Segmentar envelopes de dados (até 4.096 bytes) em fragmentos compatíveis com o MTU negociado (23 a 517 bytes).
 * - Adicionar cabeçalho estruturado de 10 bytes com campos Big-Endian UInt16.
 * - Reconstruir pacotes recebidos fora de ordem, identificando duplicatas e rejeitando inconsistências.
 * - Garantir isolamento estrito entre sessões e mensagens simultâneas.
 * - Proteger contra alocações excessivas de memória e eliminar buffers expirados.
 *
 * Componente modular desacoplado do rádio físico GATT, UI e criptografia.
 */
class BlePacketFramingEngine(
    private val bufferTimeoutMs: Long = DEFAULT_BUFFER_TIMEOUT_MS,
    private val maxActiveBuffers: Int = MAX_ACTIVE_BUFFERS
) {

    companion object {
        const val MAGIC_BYTE: Byte = 0x7A // 'z' - Identificador de protocolo PessoasAqui
        const val HEADER_SIZE: Int = 10
        const val ATT_OVERHEAD: Int = 3
        const val MIN_MTU: Int = 23
        const val MAX_MTU: Int = 517
        const val MAX_MESSAGE_BYTES: Int = 4096
        const val MAX_ALLOWED_FRAGMENTS: Int = 512
        const val DEFAULT_BUFFER_TIMEOUT_MS: Long = 5000L
        const val MAX_ACTIVE_BUFFERS: Int = 64

        // Tipos de Quadros Homologados
        const val FRAME_TYPE_DATA: Byte = 0x01
        const val FRAME_TYPE_ACK: Byte = 0x02
        const val FRAME_TYPE_HANDSHAKE_INIT: Byte = 0x03
        const val FRAME_TYPE_HANDSHAKE_RESP: Byte = 0x04
        const val FRAME_TYPE_SESSION_RESUME: Byte = 0x05
        const val FRAME_TYPE_SESSION_CLOSE: Byte = 0x06

        fun writeUInt16BE(buffer: ByteArray, offset: Int, value: Int) {
            require(offset + 2 <= buffer.size) { "Buffer insuficiente para escrever UInt16" }
            buffer[offset] = ((value ushr 8) and 0xFF).toByte()
            buffer[offset + 1] = (value and 0xFF).toByte()
        }

        fun readUInt16BE(buffer: ByteArray, offset: Int): Int {
            require(offset + 2 <= buffer.size) { "Buffer insuficiente para ler UInt16" }
            return ((buffer[offset].toInt() and 0xFF) shl 8) or (buffer[offset + 1].toInt() and 0xFF)
        }
    }

    /**
     * Chave composta para isolamento de montagem de mensagens por sessão e identificador.
     */
    data class MessageKey(
        val sessionId: String,
        val msgId: Int
    )

    /**
     * Razões de rejeição de pacotes para diagnóstico e auditoria.
     */
    enum class RejectionReason {
        INVALID_MAGIC,
        PACKET_TOO_SHORT,
        INVALID_TOTAL_FRAGS,
        INVALID_FRAG_INDEX,
        CHUNK_LENGTH_MISMATCH,
        INCONSISTENT_METADATA,
        INCONSISTENT_DUPLICATE_PAYLOAD,
        MESSAGE_TOO_LARGE,
        BUFFER_LIMIT_REACHED
    }

    /**
     * Resultado da tentativa de processamento de um pacote recebido.
     */
    sealed class ReassemblyResult {
        data class InProgress(
            val msgId: Int,
            val receivedFrags: Int,
            val totalFrags: Int
        ) : ReassemblyResult()

        data class Completed(
            val msgId: Int,
            val frameType: Byte,
            val payload: ByteArray
        ) : ReassemblyResult() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as Completed
                return msgId == other.msgId && frameType == other.frameType && payload.contentEquals(other.payload)
            }

            override fun hashCode(): Int {
                var result = msgId
                result = 31 * result + frameType
                result = 31 * result + payload.contentHashCode()
                return result
            }
        }

        data class Rejected(
            val reason: RejectionReason,
            val msgId: Int? = null
        ) : ReassemblyResult()
    }

    /**
     * Estrutura interna para rastreamento da montagem progressiva de uma mensagem.
     */
    private class MessageAssemblyBuffer(
        val totalFrags: Int,
        val frameType: Byte,
        var lastUpdatedMs: Long
    ) {
        val fragments: Array<ByteArray?> = arrayOfNulls(totalFrags)
        val receivedMask = BooleanArray(totalFrags)
        var receivedCount: Int = 0
        var accumulatedBytes: Int = 0
    }

    // Mapa thread-safe de buffers de montagem ativos
    private val activeBuffers = ConcurrentHashMap<MessageKey, MessageAssemblyBuffer>()

    /**
     * Fragmenta um envelope de dados em múltiplos pacotes estruturados.
     *
     * @param payload Bytes do envelope original (até 4.096 bytes).
     * @param mtu MTU efetivo do rádio BLE (entre 23 e 517).
     * @param msgId Identificador único da mensagem (0 a 65535).
     * @param frameType Tipo do quadro (padrão FRAME_TYPE_DATA).
     * @return Lista de pacotes prontos para transmissão pelo rádio.
     */
    fun fragment(
        payload: ByteArray,
        mtu: Int,
        msgId: Int,
        frameType: Byte = FRAME_TYPE_DATA
    ): List<ByteArray> {
        require(payload.size <= MAX_MESSAGE_BYTES) {
            "Tamanho do payload (${payload.size}B) excede o limite máximo permitido de $MAX_MESSAGE_BYTES bytes"
        }
        require(msgId in 0..0xFFFF) {
            "msgId ($msgId) fora da faixa válida de 16 bits (0 a 65535)"
        }

        val effectiveMtu = mtu.coerceIn(MIN_MTU, MAX_MTU)
        val maxChunkSize = effectiveMtu - ATT_OVERHEAD - HEADER_SIZE
        require(maxChunkSize > 0) {
            "Capacidade de dados por fragmento inválida ($maxChunkSize) para MTU $effectiveMtu"
        }

        val totalFrags = if (payload.isEmpty()) {
            1
        } else {
            ceil(payload.size.toDouble() / maxChunkSize).toInt()
        }

        require(totalFrags <= MAX_ALLOWED_FRAGMENTS) {
            "Número de fragmentos calculado ($totalFrags) excede o limite seguro de $MAX_ALLOWED_FRAGMENTS"
        }

        val fragmentsList = ArrayList<ByteArray>(totalFrags)

        for (fragIndex in 0 until totalFrags) {
            val offset = fragIndex * maxChunkSize
            val chunkLen = if (payload.isEmpty()) {
                0
            } else {
                min(maxChunkSize, payload.size - offset)
            }

            val packet = ByteArray(HEADER_SIZE + chunkLen)
            packet[0] = MAGIC_BYTE
            packet[1] = frameType
            writeUInt16BE(packet, 2, msgId)
            writeUInt16BE(packet, 4, fragIndex)
            writeUInt16BE(packet, 6, totalFrags)
            writeUInt16BE(packet, 8, chunkLen)

            if (chunkLen > 0) {
                System.arraycopy(payload, offset, packet, HEADER_SIZE, chunkLen)
            }

            fragmentsList.add(packet)
        }

        return fragmentsList
    }

    /**
     * Processa a recepção de um fragmento individual de pacote.
     *
     * @param packet Bytes brutos recebidos pelo canal de comunicação.
     * @param sessionId Identificador da sessão de proximidade (garante isolamento).
     * @param nowMs Timestamp atual para expiração e controle temporal.
     * @return [ReassemblyResult] indicando o estado atual da montagem.
     */
    @Synchronized
    fun receivePacket(
        packet: ByteArray,
        sessionId: String,
        nowMs: Long = System.currentTimeMillis()
    ): ReassemblyResult {
        if (packet.size < HEADER_SIZE) {
            return ReassemblyResult.Rejected(RejectionReason.PACKET_TOO_SHORT)
        }

        if (packet[0] != MAGIC_BYTE) {
            return ReassemblyResult.Rejected(RejectionReason.INVALID_MAGIC)
        }

        val frameType = packet[1]
        val msgId = readUInt16BE(packet, 2)
        val fragIndex = readUInt16BE(packet, 4)
        val totalFrags = readUInt16BE(packet, 6)
        val chunkLen = readUInt16BE(packet, 8)

        // Validação da integridade do comprimento anunciado contra o pacote físico
        if (packet.size != HEADER_SIZE + chunkLen) {
            return ReassemblyResult.Rejected(RejectionReason.CHUNK_LENGTH_MISMATCH, msgId)
        }

        // Validação dos limites de fragmentação
        if (totalFrags <= 0 || totalFrags > MAX_ALLOWED_FRAGMENTS) {
            return ReassemblyResult.Rejected(RejectionReason.INVALID_TOTAL_FRAGS, msgId)
        }

        if (fragIndex < 0 || fragIndex >= totalFrags) {
            return ReassemblyResult.Rejected(RejectionReason.INVALID_FRAG_INDEX, msgId)
        }

        val key = MessageKey(sessionId = sessionId, msgId = msgId)
        var buffer = activeBuffers[key]

        if (buffer == null) {
            // Limpa buffers expirados antes de alocar se a capacidade estiver próxima do teto
            if (activeBuffers.size >= maxActiveBuffers) {
                cleanupExpiredBuffers(nowMs)
                if (activeBuffers.size >= maxActiveBuffers) {
                    return ReassemblyResult.Rejected(RejectionReason.BUFFER_LIMIT_REACHED, msgId)
                }
            }

            buffer = MessageAssemblyBuffer(
                totalFrags = totalFrags,
                frameType = frameType,
                lastUpdatedMs = nowMs
            )
            activeBuffers[key] = buffer
        } else {
            // Valida consistência de metadados para o mesmo identificador
            if (buffer.totalFrags != totalFrags || buffer.frameType != frameType) {
                activeBuffers.remove(key)
                return ReassemblyResult.Rejected(RejectionReason.INCONSISTENT_METADATA, msgId)
            }
        }

        val chunkData = ByteArray(chunkLen)
        if (chunkLen > 0) {
            System.arraycopy(packet, HEADER_SIZE, chunkData, 0, chunkLen)
        }

        // Tratamento de fragmento duplicado
        if (buffer.receivedMask[fragIndex]) {
            val existingChunk = buffer.fragments[fragIndex]
            if (existingChunk != null && existingChunk.contentEquals(chunkData)) {
                // Fragmento idêntico já recebido (retransmissão idempotente)
                buffer.lastUpdatedMs = nowMs
                return ReassemblyResult.InProgress(msgId, buffer.receivedCount, totalFrags)
            } else {
                // Fragmento duplicado com conteúdo divergente: corrupção ou adulteração
                activeBuffers.remove(key)
                return ReassemblyResult.Rejected(RejectionReason.INCONSISTENT_DUPLICATE_PAYLOAD, msgId)
            }
        }

        // Verificação progressiva de memória cumulativa
        if (buffer.accumulatedBytes + chunkLen > MAX_MESSAGE_BYTES) {
            activeBuffers.remove(key)
            return ReassemblyResult.Rejected(RejectionReason.MESSAGE_TOO_LARGE, msgId)
        }

        // Armazenamento do fragmento
        buffer.fragments[fragIndex] = chunkData
        buffer.receivedMask[fragIndex] = true
        buffer.receivedCount++
        buffer.accumulatedBytes += chunkLen
        buffer.lastUpdatedMs = nowMs

        // Verificação de conclusão
        return if (buffer.receivedCount == totalFrags) {
            activeBuffers.remove(key)
            val completePayload = ByteArray(buffer.accumulatedBytes)
            var currentOffset = 0
            for (i in 0 until totalFrags) {
                val part = buffer.fragments[i] ?: ByteArray(0)
                System.arraycopy(part, 0, completePayload, currentOffset, part.size)
                currentOffset += part.size
            }
            ReassemblyResult.Completed(msgId, frameType, completePayload)
        } else {
            ReassemblyResult.InProgress(msgId, buffer.receivedCount, totalFrags)
        }
    }

    /**
     * Remove buffers de montagem incompletos que ultrapassaram o tempo limite de espera.
     *
     * @param nowMs Timestamp de referência.
     * @param timeoutMs Tempo máximo de inatividade tolerado para montagem.
     * @return Quantidade de buffers expirados e liberados da memória.
     */
    @Synchronized
    fun cleanupExpiredBuffers(
        nowMs: Long = System.currentTimeMillis(),
        timeoutMs: Long = bufferTimeoutMs
    ): Int {
        var removedCount = 0
        val iterator = activeBuffers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastUpdatedMs > timeoutMs) {
                iterator.remove()
                removedCount++
            }
        }
        return removedCount
    }

    /**
     * Remove todos os buffers vinculados a uma sessão encerrada.
     */
    @Synchronized
    fun clearSession(sessionId: String): Int {
        var removedCount = 0
        val iterator = activeBuffers.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.sessionId == sessionId) {
                iterator.remove()
                removedCount++
            }
        }
        return removedCount
    }

    /**
     * Reseta completamente os buffers em memória.
     */
    @Synchronized
    fun reset() {
        activeBuffers.clear()
    }

    /**
     * Retorna a quantidade de buffers atualmente aguardando fragmentos.
     */
    fun getActiveBufferCount(): Int = activeBuffers.size

    /**
     * Verifica se existe um buffer ativo para uma sessão e identificador de mensagem específicos.
     */
    fun hasActiveBuffer(sessionId: String, msgId: Int): Boolean {
        return activeBuffers.containsKey(MessageKey(sessionId, msgId))
    }
}
