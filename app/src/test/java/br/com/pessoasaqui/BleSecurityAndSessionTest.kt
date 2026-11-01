package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.core.proximity.BleCryptoHandshakeEngine
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine
import br.com.pessoasaqui.core.proximity.BlePacketFramingEngine.ReassemblyResult
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager
import br.com.pessoasaqui.core.proximity.BleSessionTokenManager.BleProximitySession
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.security.KeyPairGenerator
import java.util.Arrays
import javax.crypto.AEADBadTagException

/**
 * Suíte de Testes Obrigatórios da FASE 3B:
 * Segurança, Autenticação e Gerenciamento de Sessões BLE.
 *
 * Valida exaustivamente os 18 requisitos de segurança:
 * 1. Geração de sessões criptográficas distintas.
 * 2. Autenticação mútua válida.
 * 3. Rejeição de assinatura inválida.
 * 4. Rejeição de chave pública incorreta.
 * 5. Detecção de alteração do handshake (Transcript Binding).
 * 6. Negociação ECDH entre dois participantes simulados.
 * 7. Derivação HKDF-SHA256 (separação de chaves).
 * 8. Cifragem e decifragem AES-256-GCM.
 * 9. Rejeição de mensagem adulterada.
 * 10. Proteção contra repetição de mensagens.
 * 11. Unicidade dos IVs.
 * 12. Reconexão lógica sem reutilização insegura de contadores.
 * 13. Expiração de sessão temporária (30s carência / 45s expiração).
 * 14. Rejeição de sessões antigas/expiradas.
 * 15. Proteção contra reprodução de pacotes de retomada (SESSION_RESUME).
 * 16. Independência entre sessões simultâneas.
 * 17. Compatibilidade lógica com o motor de fragmentação da FASE 3A.
 * 18. Preservação da comunicação permanente existente.
 */
class BleSecurityAndSessionTest {

    private lateinit var sessionManager: BleSessionTokenManager

    @Before
    fun setUp() {
        sessionManager = BleSessionTokenManager(
            gracePeriodMs = 30000L,
            expirationTimeoutMs = 45000L
        )
    }

    // -------------------------------------------------------------------------
    // 1. Geração de Sessões Criptográficas Distintas
    // -------------------------------------------------------------------------
    @Test
    fun test01_GenerationOfDistinctCryptographicSessions() {
        val kpA1 = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val kpB1 = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val nonceA1 = BleCryptoHandshakeEngine.generateNonce()
        val nonceB1 = BleCryptoHandshakeEngine.generateNonce()
        val secret1 = BleCryptoHandshakeEngine.computeEcdhSharedSecret(kpA1.private, kpB1.public)
        val keys1 = BleCryptoHandshakeEngine.deriveSessionKeys(secret1, nonceA1, nonceB1, kpA1.public.encoded, kpB1.public.encoded)

        val kpA2 = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val kpB2 = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val nonceA2 = BleCryptoHandshakeEngine.generateNonce()
        val nonceB2 = BleCryptoHandshakeEngine.generateNonce()
        val secret2 = BleCryptoHandshakeEngine.computeEcdhSharedSecret(kpA2.private, kpB2.public)
        val keys2 = BleCryptoHandshakeEngine.deriveSessionKeys(secret2, nonceA2, nonceB2, kpA2.public.encoded, kpB2.public.encoded)

        assertNotEquals("SessionIds devem ser distintos", keys1.sessionId, keys2.sessionId)
        assertNotEquals("SessionTokens devem ser distintos", keys1.sessionToken, keys2.sessionToken)
        assertFalse("Chaves de criptografia devem ser distintas", keys1.encKey.contentEquals(keys2.encKey))
        assertFalse("Chaves de autenticação devem ser distintas", keys1.macKey.contentEquals(keys2.macKey))
    }

    // -------------------------------------------------------------------------
    // 2. Autenticação Mútua Válida
    // -------------------------------------------------------------------------
    @Test
    fun test02_MutualAuthenticationValid() {
        val aliceIdentity = BleCryptoHandshakeEngine.generateEphemeralKeyPair() // Representa identidade persistente de Alice
        val bobIdentity = BleCryptoHandshakeEngine.generateEphemeralKeyPair()   // Representa identidade persistente de Bob

        val aliceEphemeral = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val bobEphemeral = BleCryptoHandshakeEngine.generateEphemeralKeyPair()

        val aliceNonce = BleCryptoHandshakeEngine.generateNonce()
        val bobNonce = BleCryptoHandshakeEngine.generateNonce()
        val timestamp = System.currentTimeMillis()

        // 1. Alice cria transcript e assina com sua chave de identidade
        val aliceTranscript = BleCryptoHandshakeEngine.buildTranscriptHash(
            version = BleCryptoHandshakeEngine.PROTOCOL_VERSION,
            role = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            initiatorIdHash = "ALICE01",
            responderIdHash = "BOB0002",
            initiatorEphemeralPubKey = aliceEphemeral.public.encoded,
            responderEphemeralPubKey = bobEphemeral.public.encoded,
            initiatorNonce = aliceNonce,
            responderNonce = bobNonce,
            timestampMs = timestamp
        )
        val aliceSignature = BleCryptoHandshakeEngine.signTranscript(aliceIdentity.private, aliceTranscript)

        // 2. Bob verifica a assinatura de Alice
        val aliceSigValid = BleCryptoHandshakeEngine.verifyTranscriptSignature(aliceIdentity.public, aliceTranscript, aliceSignature)
        assertTrue("Assinatura de Alice deve ser válida para Bob", aliceSigValid)

        // 3. Bob calcula segredo compartilhado e SessionConfirmMac
        val bobSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(bobEphemeral.private, aliceEphemeral.public)
        val bobKeys = BleCryptoHandshakeEngine.deriveSessionKeys(
            bobSecret, aliceNonce, bobNonce, aliceEphemeral.public.encoded, bobEphemeral.public.encoded
        )
        val confirmMac = BleCryptoHandshakeEngine.computeSessionConfirmMac(bobKeys.macKey, bobKeys.sessionId)

        // 4. Alice calcula segredo compartilhado e valida SessionConfirmMac
        val aliceSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(aliceEphemeral.private, bobEphemeral.public)
        val aliceKeys = BleCryptoHandshakeEngine.deriveSessionKeys(
            aliceSecret, aliceNonce, bobNonce, aliceEphemeral.public.encoded, bobEphemeral.public.encoded
        )
        val macValid = BleCryptoHandshakeEngine.verifySessionConfirmMac(aliceKeys.macKey, aliceKeys.sessionId, confirmMac)

        assertTrue("SessionConfirmMac deve ser autenticado com sucesso por Alice", macValid)
        assertEquals("Ambos devem concordar no mesmo SessionId", aliceKeys.sessionId, bobKeys.sessionId)
        assertEquals("Ambos devem concordar no mesmo SessionToken", aliceKeys.sessionToken, bobKeys.sessionToken)
        assertArrayEquals("Chaves de criptografia devem ser idênticas", aliceKeys.encKey, bobKeys.encKey)
        assertArrayEquals("Chaves MAC devem ser idênticas", aliceKeys.macKey, bobKeys.macKey)
    }

    // -------------------------------------------------------------------------
    // 3. Rejeição de Assinatura Inválida
    // -------------------------------------------------------------------------
    @Test
    fun test03_RejectionOfInvalidSignature() {
        val aliceIdentity = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val transcript = ByteArray(32) { 0x11 }
        val validSig = BleCryptoHandshakeEngine.signTranscript(aliceIdentity.private, transcript)

        // Adultera 1 byte da assinatura
        val corruptedSig = validSig.copyOf()
        corruptedSig[corruptedSig.size - 1] = (corruptedSig[corruptedSig.size - 1] + 1).toByte()

        val verified = BleCryptoHandshakeEngine.verifyTranscriptSignature(aliceIdentity.public, transcript, corruptedSig)
        assertFalse("Assinatura adulterada deve ser rejeitada", verified)
    }

    // -------------------------------------------------------------------------
    // 4. Rejeição de Chave Pública Incorreta
    // -------------------------------------------------------------------------
    @Test
    fun test04_RejectionOfIncorrectPublicKey() {
        val aliceIdentity = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val eveIdentity = BleCryptoHandshakeEngine.generateEphemeralKeyPair() // Chave de terceiro
        val transcript = ByteArray(32) { 0x22 }
        val aliceSig = BleCryptoHandshakeEngine.signTranscript(aliceIdentity.private, transcript)

        // Tenta verificar a assinatura de Alice contra a chave de Eve
        val verified = BleCryptoHandshakeEngine.verifyTranscriptSignature(eveIdentity.public, transcript, aliceSig)
        assertFalse("Assinatura verificada com chave pública errada deve falhar", verified)
    }

    // -------------------------------------------------------------------------
    // 5. Detecção de Alteração do Handshake (Transcript Binding)
    // -------------------------------------------------------------------------
    @Test
    fun test05_DetectionOfHandshakeTranscriptAlteration() {
        val aliceIdentity = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val epkA = ByteArray(65) { 0x04 }
        val epkB = ByteArray(65) { 0x04 }
        val nonceA = ByteArray(16) { 0x01 }
        val nonceB = ByteArray(16) { 0x02 }
        val timestamp = 1700000000000L

        val originalTranscript = BleCryptoHandshakeEngine.buildTranscriptHash(
            1, 1, "ALICE", "BOB", epkA, epkB, nonceA, nonceB, timestamp
        )
        val signature = BleCryptoHandshakeEngine.signTranscript(aliceIdentity.private, originalTranscript)

        // Simula interceptador alterando o timestamp por 1 segundo
        val alteredTranscript = BleCryptoHandshakeEngine.buildTranscriptHash(
            1, 1, "ALICE", "BOB", epkA, epkB, nonceA, nonceB, timestamp + 1000L
        )

        val verified = BleCryptoHandshakeEngine.verifyTranscriptSignature(aliceIdentity.public, alteredTranscript, signature)
        assertFalse("Transcript alterado não pode ser validado pela assinatura original", verified)
    }

    // -------------------------------------------------------------------------
    // 6. Negociação ECDH entre Dois Participantes Simulados
    // -------------------------------------------------------------------------
    @Test
    fun test06_EcdhNegotiationBetweenTwoSimulatedPeers() {
        val aliceEphem = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        val bobEphem = BleCryptoHandshakeEngine.generateEphemeralKeyPair()

        val aliceSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(aliceEphem.private, bobEphem.public)
        val bobSecret = BleCryptoHandshakeEngine.computeEcdhSharedSecret(bobEphem.private, aliceEphem.public)

        assertNotNull(aliceSecret)
        assertTrue(aliceSecret.isNotEmpty())
        assertArrayEquals("Segredo derivado via ECDH em ambos os lados deve ser idêntico", aliceSecret, bobSecret)
    }

    // -------------------------------------------------------------------------
    // 7. Derivação HKDF-SHA256
    // -------------------------------------------------------------------------
    @Test
    fun test07_HkdfSha256Derivation() {
        val ikm = ByteArray(32) { (it * 3).toByte() }
        val salt = ByteArray(32) { (it * 7).toByte() }

        val prk = BleCryptoHandshakeEngine.hkdfExtract(salt, ikm)
        assertEquals(32, prk.size)

        val okm1 = BleCryptoHandshakeEngine.hkdfExpand(prk, "Info-A".toByteArray(StandardCharsets.UTF_8), 32)
        val okm2 = BleCryptoHandshakeEngine.hkdfExpand(prk, "Info-B".toByteArray(StandardCharsets.UTF_8), 32)

        assertEquals(32, okm1.size)
        assertEquals(32, okm2.size)
        assertFalse("Derivações com rótulos (info) distintos devem gerar chaves independentes", okm1.contentEquals(okm2))
    }

    // -------------------------------------------------------------------------
    // 8. Cifragem e Decifragem AES-256-GCM
    // -------------------------------------------------------------------------
    @Test
    fun test08_Aes256GcmEncryptionAndDecryption() {
        val encKey = ByteArray(32) { 0x42 }
        val sessionSalt = ByteArray(3) { 0x07 }
        val sessionToken = 0x12345678
        val msgId = 42
        val direction: Byte = BleCryptoHandshakeEngine.ROLE_INITIATOR
        val sequence = 1L

        val plaintext = "Mensagem Confidencial PessoasAqui BLE".toByteArray(StandardCharsets.UTF_8)

        val encryptedEnvelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext, encKey, sessionSalt, sessionToken, msgId, direction, sequence
        )

        // Envelope deve conter IV (12B) + Ciphertext + Tag (16B)
        assertEquals(12 + plaintext.size + 16, encryptedEnvelope.size)

        val decrypted = BleCryptoHandshakeEngine.decryptMessage(
            encryptedEnvelope, encKey, sessionToken, msgId, direction, sequence
        )

        assertArrayEquals(plaintext, decrypted)
        assertEquals("Mensagem Confidencial PessoasAqui BLE", String(decrypted, StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // 9. Rejeição de Mensagem Adulterada
    // -------------------------------------------------------------------------
    @Test
    fun test09_RejectionOfTamperedCiphertext() {
        val encKey = ByteArray(32) { 0x55 }
        val sessionSalt = ByteArray(3) { 0x09 }
        val sessionToken = 0x55555555
        val msgId = 10
        val direction: Byte = 0x01
        val sequence = 1L

        val plaintext = "Texto que sofrerá alteração".toByteArray(StandardCharsets.UTF_8)
        val envelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext, encKey, sessionSalt, sessionToken, msgId, direction, sequence
        )

        // Adultera 1 byte na porção de dados cifrados
        val corruptedEnvelope = envelope.copyOf()
        corruptedEnvelope[15] = (corruptedEnvelope[15] + 1).toByte()

        try {
            BleCryptoHandshakeEngine.decryptMessage(corruptedEnvelope, encKey, sessionToken, msgId, direction, sequence)
            fail("Deveria lançar exceção de integridade AEAD")
        } catch (_: Exception) {
            // Sucesso na detecção de adulteração
        }

        // Tenta decifrar com número de sequência alterado nos dados associados (AAD)
        try {
            BleCryptoHandshakeEngine.decryptMessage(envelope, encKey, sessionToken, msgId, direction, sequence = 2L)
            fail("Deveria falhar com AAD inconsistente")
        } catch (_: Exception) {
            // Sucesso na detecção
        }
    }

    // -------------------------------------------------------------------------
    // 10. Proteção contra Repetição de Mensagens
    // -------------------------------------------------------------------------
    @Test
    fun test10_ProtectionAgainstMessageReplay() {
        val session = createTestSession("session-replay", "ALICE", "BOB")

        // 1. Mensagem 1 deve ser aceita
        assertTrue("Sequência 1 deve ser aceita", session.validateAndRecordRxSequence(1L))

        // 2. Repetição da mensagem 1 deve ser rejeitada
        assertFalse("Sequência 1 repetida deve ser rejeitada", session.validateAndRecordRxSequence(1L))

        // 3. Mensagem 2 aceita
        assertTrue("Sequência 2 deve ser aceita", session.validateAndRecordRxSequence(2L))

        // 4. Mensagem antiga com sequência 1 rejeitada
        assertFalse("Sequência antiga deve ser rejeitada", session.validateAndRecordRxSequence(1L))
    }

    // -------------------------------------------------------------------------
    // 11. Unicidade dos IVs
    // -------------------------------------------------------------------------
    @Test
    fun test11_IvUniquenessGuarantee() {
        val sessionSalt = byteArrayOf(0x01, 0x02, 0x03)
        val generatedIvs = HashSet<String>()

        // Simula 50 mensagens do Initiator (0x01) e 50 do Responder (0x02)
        for (seq in 1L..50L) {
            val ivInit = BleCryptoHandshakeEngine.buildDeterministicIv(0x01, sessionSalt, seq)
            val ivResp = BleCryptoHandshakeEngine.buildDeterministicIv(0x02, sessionSalt, seq)

            val hexInit = ivInit.joinToString("") { "%02X".format(it) }
            val hexResp = ivResp.joinToString("") { "%02X".format(it) }

            assertTrue("IV Initiator não pode repetir", generatedIvs.add(hexInit))
            assertTrue("IV Responder não pode repetir", generatedIvs.add(hexResp))
        }

        assertEquals(100, generatedIvs.size)
    }

    // -------------------------------------------------------------------------
    // 12. Reconexão Lógica sem Reutilização Insegura de Contadores
    // -------------------------------------------------------------------------
    @Test
    fun test12_LogicalReconnectionWithoutSequenceReset() {
        val session = createTestSession("session-reconnect", "ALICE", "BOB")
        sessionManager.registerSession(session)

        // Envia mensagens 1 e 2
        assertEquals(1L, session.nextTxSequence())
        assertEquals(2L, session.nextTxSequence())

        // Simula desconexão física GATT de 15 segundos e envio de SESSION_RESUME
        val resumeNonce = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.RESUME_NONCE_SIZE_BYTES)
        val resumeMac = BleCryptoHandshakeEngine.computeResumeMac(
            session.macKey, session.sessionToken, resumeNonce, BleCryptoHandshakeEngine.ROLE_INITIATOR
        )
        val resumePacket = BleCryptoHandshakeEngine.buildResumePacket(session.sessionToken, resumeNonce, resumeMac)

        val resumedSession = sessionManager.processResumePacket(resumePacket, BleCryptoHandshakeEngine.ROLE_INITIATOR)
        assertNotNull("Retomada deve ser validada", resumedSession)

        // Ao continuar o envio no canal restabelecido, o contador NÃO reinicia
        val nextSeq = resumedSession!!.nextTxSequence()
        assertEquals("Contador deve continuar estritamente monotônico (3)", 3L, nextSeq)
    }

    // -------------------------------------------------------------------------
    // 13. Expiração de Sessão Temporária
    // -------------------------------------------------------------------------
    @Test
    fun test13_TemporarySessionExpiration() {
        val session = createTestSession("session-expiry", "ALICE", "BOB")
        sessionManager.registerSession(session)

        val t0 = 100000L
        session.lastActiveMs = t0

        // Em t0 + 20s: Ativa normalmente
        assertFalse(session.isGracePeriod(t0 + 20000L))
        assertFalse(session.isExpired(t0 + 20000L))

        // Em t0 + 35s: Em período de carência (tolerância de 30s)
        assertTrue(session.isGracePeriod(t0 + 35000L))
        assertFalse(session.isExpired(t0 + 35000L))

        // Em t0 + 46s: Expirada definitivamente (45s)
        assertTrue(session.isExpired(t0 + 46000L))

        // Limpeza remove a sessão expirada
        val expired = sessionManager.cleanupExpiredSessions(t0 + 46000L)
        assertEquals(listOf("session-expiry"), expired)
        assertNull(sessionManager.getSession("session-expiry"))
    }

    // -------------------------------------------------------------------------
    // 14. Rejeição de Sessões Antigas/Expiradas
    // -------------------------------------------------------------------------
    @Test
    fun test14_RejectionOfOldExpiredSessions() {
        val session = createTestSession("session-old", "ALICE", "BOB")
        sessionManager.registerSession(session)

        val t0 = 10000L
        session.lastActiveMs = t0

        // Atualização de atividade após 46s rejeitada
        val updated = sessionManager.updateActivity("session-old", nowMs = t0 + 46000L)
        assertFalse("Não deve permitir atividade em sessão expirada", updated)
        assertNull(sessionManager.getSession("session-old"))
    }

    // -------------------------------------------------------------------------
    // 15. Proteção contra Reprodução de Pacotes de Retomada (SESSION_RESUME)
    // -------------------------------------------------------------------------
    @Test
    fun test15_ProtectionAgainstResumePacketReplay() {
        val session = createTestSession("session-resume-test", "ALICE", "BOB")
        sessionManager.registerSession(session)

        val nonce = BleCryptoHandshakeEngine.generateNonce(BleCryptoHandshakeEngine.RESUME_NONCE_SIZE_BYTES)
        val mac = BleCryptoHandshakeEngine.computeResumeMac(session.macKey, session.sessionToken, nonce, 0x01)
        val packet = BleCryptoHandshakeEngine.buildResumePacket(session.sessionToken, nonce, mac)

        // 1. Primeira apresentação do pacote aceita
        val s1 = sessionManager.processResumePacket(packet, direction = 0x01)
        assertNotNull("Primeira retomada deve ser bem-sucedida", s1)

        // 2. Reenvio do mesmo pacote (Replay Attack) deve ser rejeitado
        val s2 = sessionManager.processResumePacket(packet, direction = 0x01)
        assertNull("Pacote de retomada retransmitido deve ser bloqueado", s2)
    }

    // -------------------------------------------------------------------------
    // 16. Independência entre Sessões Simultâneas
    // -------------------------------------------------------------------------
    @Test
    fun test16_IndependenceBetweenSimultaneousSessions() {
        val sessionBob = createTestSession("session-bob", "ALICE", "BOB")
        val sessionCarol = createTestSession("session-carol", "ALICE", "CAROL")

        sessionManager.registerSession(sessionBob)
        sessionManager.registerSession(sessionCarol)

        assertEquals(2, sessionManager.getActiveSessionCount())

        // Mensagens em uma sessão não afetam a outra
        sessionBob.nextTxSequence()
        sessionBob.nextTxSequence()

        assertEquals(2L, sessionBob.txSequenceCounter.get())
        assertEquals(0L, sessionCarol.txSequenceCounter.get())

        sessionManager.terminateSession("session-bob")
        assertNull(sessionManager.getSession("session-bob"))
        assertNotNull(sessionManager.getSession("session-carol"))
    }

    // -------------------------------------------------------------------------
    // 17. Compatibilidade Lógica com o Motor de Fragmentação da FASE 3A
    // -------------------------------------------------------------------------
    @Test
    fun test17_CompatibilityWithBlePacketFramingEngine() {
        val framingEngine = BlePacketFramingEngine()
        val encKey = ByteArray(32) { 0x33 }
        val sessionSalt = ByteArray(3) { 0x01 }
        val sessionToken = 0x12345678
        val msgId = 101
        val direction: Byte = BleCryptoHandshakeEngine.ROLE_INITIATOR
        val sequence = 1L

        val originalText = "Mensagem cifrada e fragmentada a MTU 23 entre dois dispositivos BLE"
        val plaintext = originalText.toByteArray(StandardCharsets.UTF_8)

        // 1. Cifra com AES-256-GCM
        val encryptedEnvelope = BleCryptoHandshakeEngine.encryptMessage(
            plaintext, encKey, sessionSalt, sessionToken, msgId, direction, sequence
        )

        // 2. Fragmenta em MTU mínimo de 23 bytes
        val fragments = framingEngine.fragment(encryptedEnvelope, mtu = 23, msgId = msgId)
        assertTrue("Deve gerar múltiplos fragmentos no MTU 23", fragments.size > 5)

        // 3. Reconstrói os fragmentos
        var reassembledEnvelope: ByteArray? = null
        for (pkt in fragments) {
            val res = framingEngine.receivePacket(pkt, sessionId = "session-integ")
            if (res is ReassemblyResult.Completed) {
                reassembledEnvelope = res.payload
            }
        }

        assertNotNull("Envelope completo deve ser montado", reassembledEnvelope)
        assertArrayEquals(encryptedEnvelope, reassembledEnvelope)

        // 4. Decifra e valida o texto original
        val decrypted = BleCryptoHandshakeEngine.decryptMessage(
            reassembledEnvelope!!, encKey, sessionToken, msgId, direction, sequence
        )

        assertEquals(originalText, String(decrypted, StandardCharsets.UTF_8))
    }

    // -------------------------------------------------------------------------
    // 18. Preservação da Comunicação Permanente Existente
    // -------------------------------------------------------------------------
    @Test
    fun test18_PreservationOfPermanentMutualConnections() {
        // Valida que E2eeCryptoEngine existente continua operando com 100% de estabilidade
        val idManagerAlice = CryptoIdentityManager()
        val idManagerBob = CryptoIdentityManager()

        val text = "Mensagem do Modo Permanente via Internet"
        val (encrypted, ivInfo) = E2eeCryptoEngine.encrypt(
            text, idManagerAlice.getTechnicalIdentity(), idManagerBob.getTechnicalIdentity()
        )

        val decrypted = E2eeCryptoEngine.decrypt(
            encrypted, ivInfo, idManagerAlice.getTechnicalIdentity(), idManagerBob.getTechnicalIdentity()
        )

        assertEquals("Modo Permanente B deve permanecer intacto e funcional", text, decrypted)
    }

    private fun createTestSession(
        sessionId: String,
        localId: String,
        peerId: String,
        nowMs: Long = System.currentTimeMillis()
    ): BleProximitySession {
        return BleProximitySession(
            sessionId = sessionId,
            sessionToken = sessionId.hashCode(),
            localIdentityHash = localId,
            peerIdentityHash = peerId,
            localRole = BleCryptoHandshakeEngine.ROLE_INITIATOR,
            encKey = ByteArray(32) { 0x01 },
            macKey = ByteArray(32) { 0x02 },
            sessionSalt = byteArrayOf(0x01, 0x02, 0x03),
            createdAtMs = nowMs,
            lastActiveMs = nowMs
        )
    }
}
