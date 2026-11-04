package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine.ReassemblyResult
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine.RejectionReason
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.Random

/**
 * Suíte de Testes Obrigatórios da FASE 3A:
 * Motor de Fragmentação e Reconstrução de Mensagens Bluetooth (BlePacketFramingEngine).
 *
 * Valida isoladamente todos os 16 requisitos da especificação formal:
 * 1. Fragmentação com MTU 23.
 * 2. Fragmentação com MTU 185.
 * 3. Fragmentação com MTU 247.
 * 4. Fragmentação com MTU 517.
 * 5. Reconstrução de mensagens pequenas.
 * 6. Reconstrução de mensagens de 4.096 bytes.
 * 7. Fragmento final de tamanho reduzido.
 * 8. Fragmentos duplicados (idênticos vs divergentes).
 * 9. Fragmentos ausentes (não conclusão prematura).
 * 10. Fragmentos fora de ordem.
 * 11. Fragmentos com tamanho inválido.
 * 12. Quantidade excessiva de fragmentos e estouro de memória.
 * 13. Expiração e limpeza de buffers.
 * 14. Separação entre mensagens simultâneas (mesmo msgId em sessões distintas e múltiplos msgIds).
 * 15. Rejeição de dados malformados.
 * 16. Preservação exata dos bytes originais após reconstrução.
 */
class BlePacketFramingEngineTest {

    private lateinit var engine: BlePacketFramingEngine

    @Before
    fun setUp() {
        engine = BlePacketFramingEngine(bufferTimeoutMs = 5000L, maxActiveBuffers = 32)
    }

    // -------------------------------------------------------------------------
    // 1. Fragmentação com MTU 23
    // -------------------------------------------------------------------------
    @Test
    fun test01_FragmentationWithMtu23() {
        val payload = ByteArray(4096) { (it % 256).toByte() }
        val fragments = engine.fragment(payload, mtu = 23, msgId = 1)

        // Com MTU 23: Overhead ATT (3B) + Header (10B) = 13B. Carga útil máxima = 10B.
        // ceil(4096 / 10) = 410 fragmentos
        assertEquals(410, fragments.size)

        // 409 fragmentos cheios de 10B (pacote com 20B)
        for (i in 0 until 409) {
            val packet = fragments[i]
            assertEquals(20, packet.size)
            assertEquals(BlePacketFramingEngine.MAGIC_BYTE, packet[0])
            assertEquals(BlePacketFramingEngine.FRAME_TYPE_DATA, packet[1])
            assertEquals(1, BlePacketFramingEngine.readUInt16BE(packet, 2)) // msgId
            assertEquals(i, BlePacketFramingEngine.readUInt16BE(packet, 4)) // fragIndex
            assertEquals(410, BlePacketFramingEngine.readUInt16BE(packet, 6)) // totalFrags
            assertEquals(10, BlePacketFramingEngine.readUInt16BE(packet, 8)) // chunkLen
        }

        // Último fragmento: 4096 - (409 * 10) = 6 bytes de carga (pacote com 16B)
        val lastPacket = fragments[409]
        assertEquals(16, lastPacket.size)
        assertEquals(409, BlePacketFramingEngine.readUInt16BE(lastPacket, 4))
        assertEquals(410, BlePacketFramingEngine.readUInt16BE(lastPacket, 6))
        assertEquals(6, BlePacketFramingEngine.readUInt16BE(lastPacket, 8))
    }

    // -------------------------------------------------------------------------
    // 2. Fragmentação com MTU 185
    // -------------------------------------------------------------------------
    @Test
    fun test02_FragmentationWithMtu185() {
        val payload = ByteArray(4096) { 0x42 }
        val fragments = engine.fragment(payload, mtu = 185, msgId = 100)

        // Com MTU 185: Carga útil máxima = 185 - 13 = 172B.
        // ceil(4096 / 172) = 24 fragmentos (23 de 172B + 1 de 140B)
        assertEquals(24, fragments.size)

        for (i in 0 until 23) {
            assertEquals(182, fragments[i].size) // 10B header + 172B carga
            assertEquals(172, BlePacketFramingEngine.readUInt16BE(fragments[i], 8))
        }

        val lastPacket = fragments[23]
        assertEquals(150, lastPacket.size) // 10B header + 140B carga
        assertEquals(140, BlePacketFramingEngine.readUInt16BE(lastPacket, 8))
    }

    // -------------------------------------------------------------------------
    // 3. Fragmentação com MTU 247
    // -------------------------------------------------------------------------
    @Test
    fun test03_FragmentationWithMtu247() {
        val payload = ByteArray(4096) { 0x55 }
        val fragments = engine.fragment(payload, mtu = 247, msgId = 200)

        // Com MTU 247: Carga útil máxima = 247 - 13 = 234B.
        // ceil(4096 / 234) = 18 fragmentos (17 de 234B + 1 de 118B)
        assertEquals(18, fragments.size)

        for (i in 0 until 17) {
            assertEquals(244, fragments[i].size)
            assertEquals(234, BlePacketFramingEngine.readUInt16BE(fragments[i], 8))
        }

        val lastPacket = fragments[17]
        assertEquals(128, lastPacket.size) // 10B header + 118B carga
        assertEquals(118, BlePacketFramingEngine.readUInt16BE(lastPacket, 8))
    }

    // -------------------------------------------------------------------------
    // 4. Fragmentação com MTU 517
    // -------------------------------------------------------------------------
    @Test
    fun test04_FragmentationWithMtu517() {
        val payload = ByteArray(4096) { 0x77 }
        val fragments = engine.fragment(payload, mtu = 517, msgId = 300)

        // Com MTU 517: Carga útil máxima = 517 - 13 = 504B.
        // ceil(4096 / 504) = 9 fragmentos (8 de 504B + 1 de 64B)
        assertEquals(9, fragments.size)

        for (i in 0 until 8) {
            assertEquals(514, fragments[i].size)
            assertEquals(504, BlePacketFramingEngine.readUInt16BE(fragments[i], 8))
        }

        val lastPacket = fragments[8]
        assertEquals(74, lastPacket.size) // 10B header + 64B carga
        assertEquals(64, BlePacketFramingEngine.readUInt16BE(lastPacket, 8))

        // Mensagem típica pequena (250B) resulta em fragmento único
        val smallFragments = engine.fragment(ByteArray(250), mtu = 517, msgId = 301)
        assertEquals(1, smallFragments.size)
        assertEquals(260, smallFragments[0].size)
    }

    // -------------------------------------------------------------------------
    // 5. Reconstrução de Mensagens Pequenas
    // -------------------------------------------------------------------------
    @Test
    fun test05_ReassemblySmallMessage() {
        val originalText = "Olá, PessoasAqui!"
        val originalBytes = originalText.toByteArray(StandardCharsets.UTF_8)
        val fragments = engine.fragment(originalBytes, mtu = 23, msgId = 42)

        val sessionId = "session-test-01"
        var finalResult: ReassemblyResult? = null

        for (pkt in fragments) {
            finalResult = engine.receivePacket(pkt, sessionId)
        }

        assertNotNull(finalResult)
        assertTrue(finalResult is ReassemblyResult.Completed)
        val completed = finalResult as ReassemblyResult.Completed
        assertEquals(42, completed.msgId)
        assertEquals(BlePacketFramingEngine.FRAME_TYPE_DATA, completed.frameType)
        assertArrayEquals(originalBytes, completed.payload)
        assertEquals(originalText, String(completed.payload, StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // 6. Reconstrução de Mensagens de 4.096 Bytes
    // -------------------------------------------------------------------------
    @Test
    fun test06_ReassemblyMaxMessage4096Bytes() {
        val random = Random(12345)
        val originalBytes = ByteArray(4096)
        random.nextBytes(originalBytes)

        // Fragmenta em MTU mínimo 23 (410 pacotes)
        val fragments = engine.fragment(originalBytes, mtu = 23, msgId = 999)
        assertEquals(410, fragments.size)

        val sessionId = "session-max-4096"
        var lastResult: ReassemblyResult? = null

        for (i in 0 until 409) {
            val res = engine.receivePacket(fragments[i], sessionId)
            assertTrue("Fragmento $i deveria estar InProgress", res is ReassemblyResult.InProgress)
        }

        // O 410º fragmento completa a mensagem
        lastResult = engine.receivePacket(fragments[409], sessionId)
        assertTrue("Deveria completar no último fragmento", lastResult is ReassemblyResult.Completed)
        val completed = lastResult as ReassemblyResult.Completed
        assertEquals(999, completed.msgId)
        assertEquals(4096, completed.payload.size)
        assertArrayEquals(originalBytes, completed.payload)
    }

    // -------------------------------------------------------------------------
    // 7. Fragmento Final de Tamanho Reduzido
    // -------------------------------------------------------------------------
    @Test
    fun test07_FinalFragmentReducedSize() {
        // 35 bytes em MTU 23: 10 + 10 + 10 + 5 (4 fragmentos)
        val originalBytes = "Mensagem com 35 caracteres exatos..".toByteArray(StandardCharsets.UTF_8)
        assertEquals(35, originalBytes.size)

        val fragments = engine.fragment(originalBytes, mtu = 23, msgId = 55)
        assertEquals(4, fragments.size)
        assertEquals(15, fragments[3].size) // 10B header + 5B carga

        val sessionId = "session-reduced-final"
        var finalResult: ReassemblyResult? = null
        for (pkt in fragments) {
            finalResult = engine.receivePacket(pkt, sessionId)
        }

        assertTrue(finalResult is ReassemblyResult.Completed)
        assertArrayEquals(originalBytes, (finalResult as ReassemblyResult.Completed).payload)
    }

    // -------------------------------------------------------------------------
    // 8. Fragmentos Duplicados (Idênticos vs Divergentes)
    // -------------------------------------------------------------------------
    @Test
    fun test08_DuplicateFragmentsHandling() {
        val payload = "Teste de fragmentos duplicados".toByteArray(StandardCharsets.UTF_8)
        val fragments = engine.fragment(payload, mtu = 23, msgId = 77)

        val sessionId = "session-duplicates"

        // 1. Envia fragmento 0 pela primeira vez
        val res1 = engine.receivePacket(fragments[0], sessionId)
        assertTrue(res1 is ReassemblyResult.InProgress)
        assertEquals(1, (res1 as ReassemblyResult.InProgress).receivedFrags)

        // 2. Reenvia exatamente o mesmo fragmento 0 (retransmissão idempotente)
        val resDuplicateSame = engine.receivePacket(fragments[0], sessionId)
        assertTrue(resDuplicateSame is ReassemblyResult.InProgress)
        // Não incrementa a contagem de recebidos
        assertEquals(1, (resDuplicateSame as ReassemblyResult.InProgress).receivedFrags)

        // 3. Envia um fragmento 0 adulterado (conteúdo divergente para o mesmo índice)
        val tamperedFragment0 = fragments[0].copyOf()
        tamperedFragment0[BlePacketFramingEngine.HEADER_SIZE] = (tamperedFragment0[BlePacketFramingEngine.HEADER_SIZE] + 1).toByte()

        val resTampered = engine.receivePacket(tamperedFragment0, sessionId)
        assertTrue(resTampered is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.INCONSISTENT_DUPLICATE_PAYLOAD, (resTampered as ReassemblyResult.Rejected).reason)

        // Confirma que o buffer foi purgado por segurança
        assertFalse(engine.hasActiveBuffer(sessionId, 77))
    }

    // -------------------------------------------------------------------------
    // 9. Fragmentos Ausentes
    // -------------------------------------------------------------------------
    @Test
    fun test09_MissingFragmentsDoesNotComplete() {
        val payload = "Mensagem que perderá um fragmento intermediário".toByteArray(StandardCharsets.UTF_8)
        val fragments = engine.fragment(payload, mtu = 23, msgId = 88)
        assertTrue("Deve possuir múltiplos fragmentos", fragments.size > 2)

        val sessionId = "session-missing-frag"

        // Envia todos exceto o fragmento de índice 1
        for (i in fragments.indices) {
            if (i == 1) continue
            val res = engine.receivePacket(fragments[i], sessionId)
            assertTrue(res is ReassemblyResult.InProgress)
        }

        // O buffer deve permanecer incompleto na memória
        assertTrue(engine.hasActiveBuffer(sessionId, 88))
    }

    // -------------------------------------------------------------------------
    // 10. Fragmentos Fora de Ordem
    // -------------------------------------------------------------------------
    @Test
    fun test10_OutOfOrderFragmentsReassemblesCorrectly() {
        val originalText = "Fragmentos chegando completamente fora de ordem pela rede BLE"
        val originalBytes = originalText.toByteArray(StandardCharsets.UTF_8)
        val fragments = engine.fragment(originalBytes, mtu = 23, msgId = 123)

        val sessionId = "session-out-of-order"

        // Ordem reversa
        val reversedFragments = fragments.reversed()
        var finalResult: ReassemblyResult? = null

        for (pkt in reversedFragments) {
            finalResult = engine.receivePacket(pkt, sessionId)
        }

        assertTrue(finalResult is ReassemblyResult.Completed)
        assertArrayEquals(originalBytes, (finalResult as ReassemblyResult.Completed).payload)
        assertEquals(originalText, String((finalResult).payload, StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // 11. Fragmentos com Tamanho Inválido
    // -------------------------------------------------------------------------
    @Test
    fun test11_InvalidPacketSizeRejected() {
        val payload = "Teste de tamanho inválido".toByteArray(StandardCharsets.UTF_8)
        val fragments = engine.fragment(payload, mtu = 23, msgId = 321)
        val validPacket = fragments[0]

        val sessionId = "session-invalid-size"

        // 1. Pacote truncado fisicamente (menor que HEADER_SIZE + CHUNK_LEN)
        val truncatedPacket = validPacket.copyOfRange(0, validPacket.size - 2)
        val resTruncated = engine.receivePacket(truncatedPacket, sessionId)
        assertTrue(resTruncated is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.CHUNK_LENGTH_MISMATCH, (resTruncated as ReassemblyResult.Rejected).reason)

        // 2. Pacote com bytes extras além de CHUNK_LEN anunciado
        val extraBytesPacket = validPacket + byteArrayOf(0x00, 0x01)
        val resExtra = engine.receivePacket(extraBytesPacket, sessionId)
        assertTrue(resExtra is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.CHUNK_LENGTH_MISMATCH, (resExtra as ReassemblyResult.Rejected).reason)
    }

    // -------------------------------------------------------------------------
    // 12. Quantidade Excessiva de Fragmentos e Limites de Memória
    // -------------------------------------------------------------------------
    @Test
    fun test12_ExcessiveFragmentsRejected() {
        val sessionId = "session-excessive"

        // Cria pacote forjado com totalFrags = 600 (excede MAX_ALLOWED_FRAGMENTS = 512)
        val forgedPacket = ByteArray(BlePacketFramingEngine.HEADER_SIZE + 5)
        forgedPacket[0] = BlePacketFramingEngine.MAGIC_BYTE
        forgedPacket[1] = BlePacketFramingEngine.FRAME_TYPE_DATA
        BlePacketFramingEngine.writeUInt16BE(forgedPacket, 2, 700) // msgId
        BlePacketFramingEngine.writeUInt16BE(forgedPacket, 4, 0)   // fragIndex
        BlePacketFramingEngine.writeUInt16BE(forgedPacket, 6, 600) // totalFrags inválido
        BlePacketFramingEngine.writeUInt16BE(forgedPacket, 8, 5)   // chunkLen

        val result = engine.receivePacket(forgedPacket, sessionId)
        assertTrue(result is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.INVALID_TOTAL_FRAGS, (result as ReassemblyResult.Rejected).reason)
    }

    // -------------------------------------------------------------------------
    // 13. Expiração de Buffers
    // -------------------------------------------------------------------------
    @Test
    fun test13_BufferExpirationAndPruning() {
        val payload = "Buffer que deve expirar após inatividade".toByteArray(StandardCharsets.UTF_8)
        val fragments = engine.fragment(payload, mtu = 23, msgId = 505)

        val sessionId = "session-expiry"
        val t0 = 1000L

        // Ingesta fragmento 0 no instante t0
        val res = engine.receivePacket(fragments[0], sessionId, nowMs = t0)
        assertTrue(res is ReassemblyResult.InProgress)
        assertTrue(engine.hasActiveBuffer(sessionId, 505))

        // Em t0 + 3000ms (dentro do timeout de 5000ms), limpeza não remove nada
        val removedWithin = engine.cleanupExpiredBuffers(nowMs = t0 + 3000L, timeoutMs = 5000L)
        assertEquals(0, removedWithin)
        assertTrue(engine.hasActiveBuffer(sessionId, 505))

        // Em t0 + 6000ms (ultrapassou 5000ms), limpeza deve remover e liberar a memória
        val removedAfter = engine.cleanupExpiredBuffers(nowMs = t0 + 6000L, timeoutMs = 5000L)
        assertEquals(1, removedAfter)
        assertFalse(engine.hasActiveBuffer(sessionId, 505))
        assertEquals(0, engine.getActiveBufferCount())
    }

    // -------------------------------------------------------------------------
    // 14. Separação entre Mensagens Simultâneas
    // -------------------------------------------------------------------------
    @Test
    fun test14_IsolationBetweenSimultaneousMessages() {
        val payloadA = "Mensagem para Alice com dados AAAAA".toByteArray(StandardCharsets.UTF_8)
        val payloadB = "Mensagem para Bob com dados BBBBBBB".toByteArray(StandardCharsets.UTF_8)
        val payloadC = "Segunda mensagem para Alice com CCCCC".toByteArray(StandardCharsets.UTF_8)

        // Mensagens A e B usam o MESMO msgId (10), mas sessões diferentes ("session-alice" vs "session-bob")
        val fragsA = engine.fragment(payloadA, mtu = 23, msgId = 10)
        val fragsB = engine.fragment(payloadB, mtu = 23, msgId = 10)
        // Mensagem C usa msgId diferente (20) na mesma sessão "session-alice"
        val fragsC = engine.fragment(payloadC, mtu = 23, msgId = 20)

        // Intercala o envio dos fragmentos
        val maxLen = maxOf(fragsA.size, fragsB.size, fragsC.size)
        var resultA: ReassemblyResult? = null
        var resultB: ReassemblyResult? = null
        var resultC: ReassemblyResult? = null

        for (i in 0 until maxLen) {
            if (i < fragsA.size) resultA = engine.receivePacket(fragsA[i], "session-alice")
            if (i < fragsB.size) resultB = engine.receivePacket(fragsB[i], "session-bob")
            if (i < fragsC.size) resultC = engine.receivePacket(fragsC[i], "session-alice")
        }

        assertTrue(resultA is ReassemblyResult.Completed)
        assertTrue(resultB is ReassemblyResult.Completed)
        assertTrue(resultC is ReassemblyResult.Completed)

        assertArrayEquals(payloadA, (resultA as ReassemblyResult.Completed).payload)
        assertArrayEquals(payloadB, (resultB as ReassemblyResult.Completed).payload)
        assertArrayEquals(payloadC, (resultC as ReassemblyResult.Completed).payload)

        // Limpeza de sessão específica
        val sessionCleanCount = engine.clearSession("session-alice")
        assertEquals(0, sessionCleanCount) // Já completaram e foram removidos
    }

    // -------------------------------------------------------------------------
    // 15. Rejeição de Dados Malformados
    // -------------------------------------------------------------------------
    @Test
    fun test15_MalformedDataRejection() {
        val sessionId = "session-malformed"

        // 1. Pacote com tamanho inferior ao cabeçalho (menos de 10 bytes)
        val tooShort = ByteArray(8) { 0x01 }
        val resShort = engine.receivePacket(tooShort, sessionId)
        assertTrue(resShort is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.PACKET_TOO_SHORT, (resShort as ReassemblyResult.Rejected).reason)

        // 2. Pacote com Magic Byte inválido (diferente de 0x7A)
        val wrongMagic = ByteArray(15)
        wrongMagic[0] = 0x12 // Errado
        val resMagic = engine.receivePacket(wrongMagic, sessionId)
        assertTrue(resMagic is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.INVALID_MAGIC, (resMagic as ReassemblyResult.Rejected).reason)

        // 3. Pacote com fragIndex >= totalFrags
        val invalidIndex = ByteArray(12)
        invalidIndex[0] = BlePacketFramingEngine.MAGIC_BYTE
        invalidIndex[1] = BlePacketFramingEngine.FRAME_TYPE_DATA
        BlePacketFramingEngine.writeUInt16BE(invalidIndex, 2, 1) // msgId
        BlePacketFramingEngine.writeUInt16BE(invalidIndex, 4, 5) // fragIndex = 5
        BlePacketFramingEngine.writeUInt16BE(invalidIndex, 6, 5) // totalFrags = 5 (inválido: 5 >= 5)
        BlePacketFramingEngine.writeUInt16BE(invalidIndex, 8, 2) // chunkLen = 2
        val resIndex = engine.receivePacket(invalidIndex, sessionId)
        assertTrue(resIndex is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.INVALID_FRAG_INDEX, (resIndex as ReassemblyResult.Rejected).reason)

        // 4. Inconsistência de metadados: pacote com mesmo msgId mas totalFrags divergente
        val validFrags = engine.fragment(ByteArray(50), mtu = 23, msgId = 888)
        engine.receivePacket(validFrags[0], sessionId)

        val conflictingPkt = validFrags[1].copyOf()
        BlePacketFramingEngine.writeUInt16BE(conflictingPkt, 6, 99) // totalFrags divergente do buffer
        val resConflict = engine.receivePacket(conflictingPkt, sessionId)
        assertTrue(resConflict is ReassemblyResult.Rejected)
        assertEquals(RejectionReason.INCONSISTENT_METADATA, (resConflict as ReassemblyResult.Rejected).reason)
    }

    // -------------------------------------------------------------------------
    // 16. Preservação Exata dos Bytes Originais após Reconstrução
    // -------------------------------------------------------------------------
    @Test
    fun test16_ExactBytePreservationAfterReconstruction() {
        val mtusToTest = listOf(23, 185, 247, 517)
        val random = Random(98765)

        for (mtu in mtusToTest) {
            // Testa diferentes padrões de bytes:
            // a) Zeros absolutos
            val zeros = ByteArray(500)
            verifyPreservation(zeros, mtu, 1001)

            // b) 0xFF absoluto
            val ones = ByteArray(750) { 0xFF.toByte() }
            verifyPreservation(ones, mtu, 1002)

            // c) Dados binários totalmente aleatórios
            val randomData = ByteArray(2048)
            random.nextBytes(randomData)
            verifyPreservation(randomData, mtu, 1003)

            // d) Payload no teto máximo de 4096 bytes
            val fullPayload = ByteArray(4096)
            random.nextBytes(fullPayload)
            verifyPreservation(fullPayload, mtu, 1004)
        }
    }

    private fun verifyPreservation(original: ByteArray, mtu: Int, msgId: Int) {
        val fragments = engine.fragment(original, mtu, msgId)
        val sessionId = "session-preservation-mtu-$mtu"
        var result: ReassemblyResult? = null

        for (pkt in fragments) {
            result = engine.receivePacket(pkt, sessionId)
        }

        assertNotNull("Resultado não deve ser nulo para MTU $mtu", result)
        assertTrue("Deve ser Completed para MTU $mtu", result is ReassemblyResult.Completed)
        val completed = result as ReassemblyResult.Completed
        assertEquals(msgId, completed.msgId)
        assertArrayEquals("Bytes não preservados perfeitamente para MTU $mtu", original, completed.payload)
    }
}
