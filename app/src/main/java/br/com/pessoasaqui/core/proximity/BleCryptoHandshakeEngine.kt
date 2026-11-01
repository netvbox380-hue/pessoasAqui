package br.com.pessoasaqui.core.proximity

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Arrays
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Motor de Criptografia, Autenticação de Handshake e Derivação de Chaves BLE (FASE 3B).
 *
 * Responsável por:
 * - Geração de pares efêmeros NIST P-256 (secp256r1) com SecureRandom.
 * - Assinatura digital ECDSA (SHA256withECDSA) com vinculação de contexto completa (Transcript Binding).
 * - Acordo Diffie-Hellman (ECDH) gerando segredo compartilhado sem transmissão de chaves privadas.
 * - KDF padrão HKDF-SHA256 (RFC 5869) para separação estrita de chaves (K_enc, K_mac, SessionSalt, SessionToken).
 * - Cifragem e decifragem autenticada AES-256-GCM com IVs determinísticos e AAD.
 * - Cálculo e validação de ACKs autenticados e pacotes de retomada rápida (SESSION_RESUME).
 *
 * 100% compatível com Android API 24+ sem dependência de recursos exclusivos da API 31.
 */
object BleCryptoHandshakeEngine {

    const val PROTOCOL_VERSION: Byte = 0x01
    const val ROLE_INITIATOR: Byte = 0x01 // Central / GATT Client
    const val ROLE_RESPONDER: Byte = 0x02 // Peripheral / GATT Server

    const val NONCE_SIZE_BYTES = 16
    const val RESUME_NONCE_SIZE_BYTES = 6
    const val MAC_KEY_SIZE_BYTES = 32
    const val ENC_KEY_SIZE_BYTES = 32
    const val GCM_IV_SIZE_BYTES = 12
    const val GCM_TAG_SIZE_BITS = 128
    const val ACK_HMAC_SIZE_BYTES = 8
    const val RESUME_MAC_SIZE_BYTES = 8
    const val SESSION_TOKEN_SIZE_BYTES = 4
    const val MAX_HANDSHAKE_DRIFT_MS = 30000L

    private val secureRandom = SecureRandom()

    /**
     * Gera um novo par de chaves efêmeras NIST P-256 com fonte segura de entropia.
     */
    fun generateEphemeralKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        try {
            kpg.initialize(ECGenParameterSpec("secp256r1"), secureRandom)
        } catch (_: Exception) {
            kpg.initialize(256, secureRandom)
        }
        return kpg.generateKeyPair()
    }

    /**
     * Gera um nonce criptográfico aleatório com o tamanho especificado.
     */
    fun generateNonce(size: Int = NONCE_SIZE_BYTES): ByteArray {
        val nonce = ByteArray(size)
        secureRandom.nextBytes(nonce)
        return nonce
    }

    /**
     * Constrói o hash do transcript do handshake que vincula todo o contexto da negociação.
     * Impede ataques de substituição de chaves (KCI/UKS), personificação e reflexão de papéis.
     */
    fun buildTranscriptHash(
        version: Byte,
        role: Byte,
        initiatorIdHash: String,
        responderIdHash: String,
        initiatorEphemeralPubKey: ByteArray,
        responderEphemeralPubKey: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        timestampMs: Long
    ): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(version)
        md.update(role)
        md.update(initiatorIdHash.trim().uppercase().toByteArray(StandardCharsets.UTF_8))
        md.update(responderIdHash.trim().uppercase().toByteArray(StandardCharsets.UTF_8))
        md.update(initiatorEphemeralPubKey)
        md.update(responderEphemeralPubKey)
        md.update(initiatorNonce)
        md.update(responderNonce)

        val bb = ByteBuffer.allocate(8)
        bb.putLong(timestampMs)
        md.update(bb.array())

        return md.digest()
    }

    /**
     * Assina digitalmente o transcript do handshake utilizando a chave privada de identidade persistente.
     */
    fun signTranscript(identityPrivateKey: PrivateKey, transcriptHash: ByteArray): ByteArray {
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(identityPrivateKey, secureRandom)
        signer.update(transcriptHash)
        return signer.sign()
    }

    /**
     * Valida a assinatura digital do transcript contra a chave pública de identidade do interlocutor.
     */
    fun verifyTranscriptSignature(
        identityPublicKey: PublicKey,
        transcriptHash: ByteArray,
        signature: ByteArray
    ): Boolean {
        return try {
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(identityPublicKey)
            verifier.update(transcriptHash)
            verifier.verify(signature)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executa o acordo Diffie-Hellman em curva elíptica (ECDH) gerando o segredo compartilhado de 32 bytes.
     */
    fun computeEcdhSharedSecret(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivateKey)
        ka.doPhase(peerPublicKey, true)
        return ka.generateSecret()
    }

    // =========================================================================
    // HKDF-SHA256 (RFC 5869)
    // =========================================================================

    fun hkdfExtract(salt: ByteArray?, ikm: ByteArray): ByteArray {
        val actualSalt = if (salt == null || salt.isEmpty()) ByteArray(32) else salt
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        return mac.doFinal(ikm)
    }

    fun hkdfExpand(prk: ByteArray, info: ByteArray, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(prk, "HmacSHA256"))
        val okm = ByteArray(length)
        var t = ByteArray(0)
        var offset = 0
        var counter: Byte = 1

        while (offset < length) {
            mac.reset()
            mac.update(t)
            mac.update(info)
            mac.update(counter)
            t = mac.doFinal()
            val toCopy = Math.min(t.size, length - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    /**
     * Conjunto de chaves de sessão derivadas de forma criptograficamente independente.
     */
    data class DerivedSessionKeys(
        val sessionId: String,
        val sessionToken: Int,
        val encKey: ByteArray,
        val macKey: ByteArray,
        val sessionSalt: ByteArray
    ) {
        fun wipe() {
            Arrays.fill(encKey, 0.toByte())
            Arrays.fill(macKey, 0.toByte())
            Arrays.fill(sessionSalt, 0.toByte())
        }
    }

    /**
     * Deriva todas as chaves da sessão utilizando HKDF-SHA256 a partir do segredo ECDH e nonces.
     */
    fun deriveSessionKeys(
        ecdhSecret: ByteArray,
        initiatorNonce: ByteArray,
        responderNonce: ByteArray,
        initiatorPubKey: ByteArray,
        responderPubKey: ByteArray
    ): DerivedSessionKeys {
        // Salt = initiatorNonce || responderNonce (32 bytes)
        val salt = ByteArray(initiatorNonce.size + responderNonce.size)
        System.arraycopy(initiatorNonce, 0, salt, 0, initiatorNonce.size)
        System.arraycopy(responderNonce, 0, salt, initiatorNonce.size, responderNonce.size)

        val prk = hkdfExtract(salt, ecdhSecret)

        val encKey = hkdfExpand(prk, "PessoasAqui-BLE-v1-Encryption".toByteArray(StandardCharsets.UTF_8), ENC_KEY_SIZE_BYTES)
        val macKey = hkdfExpand(prk, "PessoasAqui-BLE-v1-Authentication".toByteArray(StandardCharsets.UTF_8), MAC_KEY_SIZE_BYTES)
        val sessionSalt = hkdfExpand(prk, "PessoasAqui-BLE-v1-SessionSalt".toByteArray(StandardCharsets.UTF_8), 3)
        val tokenBytes = hkdfExpand(prk, "PessoasAqui-BLE-v1-SessionToken".toByteArray(StandardCharsets.UTF_8), SESSION_TOKEN_SIZE_BYTES)

        val sessionToken = ((tokenBytes[0].toInt() and 0xFF) shl 24) or
                ((tokenBytes[1].toInt() and 0xFF) shl 16) or
                ((tokenBytes[2].toInt() and 0xFF) shl 8) or
                (tokenBytes[3].toInt() and 0xFF)

        // SessionId = SHA256(initiatorNonce || responderNonce || initiatorPubKey || responderPubKey)
        val md = MessageDigest.getInstance("SHA-256")
        md.update(initiatorNonce)
        md.update(responderNonce)
        md.update(initiatorPubKey)
        md.update(responderPubKey)
        val sessionHash = md.digest()
        val sessionId = sessionHash.take(16).joinToString("") { "%02X".format(it) }

        return DerivedSessionKeys(
            sessionId = sessionId,
            sessionToken = sessionToken,
            encKey = encKey,
            macKey = macKey,
            sessionSalt = sessionSalt
        )
    }

    /**
     * Calcula o código de autenticação da confirmação do handshake (SessionConfirmMac).
     */
    fun computeSessionConfirmMac(macKey: ByteArray, sessionId: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update("PessoasAqui-BLE-Session-Confirm".toByteArray(StandardCharsets.UTF_8))
        mac.update(sessionId.toByteArray(StandardCharsets.UTF_8))
        return mac.doFinal()
    }

    /**
     * Valida o SessionConfirmMac de forma em tempo constante (proteção contra timing attacks).
     */
    fun verifySessionConfirmMac(macKey: ByteArray, sessionId: String, receivedMac: ByteArray): Boolean {
        val expected = computeSessionConfirmMac(macKey, sessionId)
        return MessageDigest.isEqual(expected, receivedMac)
    }

    // =========================================================================
    // Cifragem e Decifragem AES-256-GCM com IVs Determinísticos
    // =========================================================================

    /**
     * Constrói o IV determinístico de 12 bytes (96 bits) garantindo unicidade absoluta.
     * - Byte 0: Direção (0x01 Iniciador -> Respondente, 0x02 Respondente -> Iniciador)
     * - Bytes 1..3: SessionSalt (3 bytes derivados por HKDF)
     * - Bytes 4..11: Contador monotônico de 64 bits (Big-Endian UInt64)
     */
    fun buildDeterministicIv(direction: Byte, sessionSalt: ByteArray, sequence: Long): ByteArray {
        require(sessionSalt.size >= 3) { "SessionSalt deve ter ao menos 3 bytes" }
        val iv = ByteArray(GCM_IV_SIZE_BYTES)
        iv[0] = direction
        iv[1] = sessionSalt[0]
        iv[2] = sessionSalt[1]
        iv[3] = sessionSalt[2]
        val bb = ByteBuffer.allocate(8)
        bb.putLong(sequence)
        System.arraycopy(bb.array(), 0, iv, 4, 8)
        return iv
    }

    /**
     * Constrói os dados associados autenticados (AAD) para o AES-GCM.
     */
    fun buildAad(sessionToken: Int, msgId: Int, direction: Byte, sequence: Long): ByteArray {
        val bb = ByteBuffer.allocate(4 + 2 + 1 + 8)
        bb.putInt(sessionToken)
        bb.putShort(msgId.toShort())
        bb.put(direction)
        bb.putLong(sequence)
        return bb.array()
    }

    /**
     * Cifra uma mensagem com AES-256-GCM com autenticação de integridade de 128 bits.
     * Retorna envelope cifrado: [IV: 12B][Ciphertext + Tag: N + 16B]
     */
    fun encryptMessage(
        plaintext: ByteArray,
        encKey: ByteArray,
        sessionSalt: ByteArray,
        sessionToken: Int,
        msgId: Int,
        direction: Byte,
        sequence: Long
    ): ByteArray {
        val iv = buildDeterministicIv(direction, sessionSalt, sequence)
        val aad = buildAad(sessionToken, msgId, direction, sequence)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(encKey, "AES"), spec)
        cipher.updateAAD(aad)

        val ciphertext = cipher.doFinal(plaintext)
        val envelope = ByteArray(iv.size + ciphertext.size)
        System.arraycopy(iv, 0, envelope, 0, iv.size)
        System.arraycopy(ciphertext, 0, envelope, iv.size, ciphertext.size)
        return envelope
    }

    /**
     * Decifra uma mensagem AES-256-GCM, validando a integridade da tag e dos dados adicionais (AAD).
     */
    fun decryptMessage(
        envelope: ByteArray,
        encKey: ByteArray,
        sessionToken: Int,
        msgId: Int,
        direction: Byte,
        sequence: Long
    ): ByteArray {
        require(envelope.size >= GCM_IV_SIZE_BYTES + 16) { "Envelope criptográfico truncado" }
        val iv = ByteArray(GCM_IV_SIZE_BYTES)
        System.arraycopy(envelope, 0, iv, 0, GCM_IV_SIZE_BYTES)
        val ciphertextLen = envelope.size - GCM_IV_SIZE_BYTES
        val ciphertext = ByteArray(ciphertextLen)
        System.arraycopy(envelope, GCM_IV_SIZE_BYTES, ciphertext, 0, ciphertextLen)

        val aad = buildAad(sessionToken, msgId, direction, sequence)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(GCM_TAG_SIZE_BITS, iv)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(encKey, "AES"), spec)
        cipher.updateAAD(aad)

        return cipher.doFinal(ciphertext)
    }

    // =========================================================================
    // ACKs e Retomada de Sessão Autenticados
    // =========================================================================

    /**
     * Gera o código HMAC de 8 bytes para autenticar a entrega de uma mensagem (MSG_ACK).
     */
    fun computeAckHmac(
        macKey: ByteArray,
        sessionId: String,
        direction: Byte,
        msgId: Int,
        statusCode: Byte
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update("ACK".toByteArray(StandardCharsets.UTF_8))
        mac.update(sessionId.toByteArray(StandardCharsets.UTF_8))
        mac.update(direction)
        val bb = ByteBuffer.allocate(2)
        bb.putShort(msgId.toShort())
        mac.update(bb.array())
        mac.update(statusCode)
        val full = mac.doFinal()
        return full.copyOfRange(0, ACK_HMAC_SIZE_BYTES)
    }

    fun verifyAckHmac(
        macKey: ByteArray,
        sessionId: String,
        direction: Byte,
        msgId: Int,
        statusCode: Byte,
        receivedHmac: ByteArray
    ): Boolean {
        val expected = computeAckHmac(macKey, sessionId, direction, msgId, statusCode)
        return MessageDigest.isEqual(expected, receivedHmac)
    }

    /**
     * Gera o código HMAC de 8 bytes para autenticar o pacote SESSION_RESUME de 20 bytes.
     */
    fun computeResumeMac(
        macKey: ByteArray,
        sessionToken: Int,
        resumeNonce: ByteArray,
        direction: Byte
    ): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(macKey, "HmacSHA256"))
        mac.update("RESUME".toByteArray(StandardCharsets.UTF_8))
        val bb = ByteBuffer.allocate(4)
        bb.putInt(sessionToken)
        mac.update(bb.array())
        mac.update(resumeNonce)
        mac.update(direction)
        val full = mac.doFinal()
        return full.copyOfRange(0, RESUME_MAC_SIZE_BYTES)
    }

    fun verifyResumeMac(
        macKey: ByteArray,
        sessionToken: Int,
        resumeNonce: ByteArray,
        direction: Byte,
        receivedMac: ByteArray
    ): Boolean {
        val expected = computeResumeMac(macKey, sessionToken, resumeNonce, direction)
        return MessageDigest.isEqual(expected, receivedMac)
    }

    /**
     * Constrói o pacote SESSION_RESUME binário calibrado exatamente em 20 bytes (MTU 23 compatível).
     */
    fun buildResumePacket(
        sessionToken: Int,
        resumeNonce: ByteArray,
        resumeMac: ByteArray
    ): ByteArray {
        require(resumeNonce.size == RESUME_NONCE_SIZE_BYTES) { "ResumeNonce deve ter exatamente $RESUME_NONCE_SIZE_BYTES bytes" }
        require(resumeMac.size == RESUME_MAC_SIZE_BYTES) { "ResumeMac deve ter exatamente $RESUME_MAC_SIZE_BYTES bytes" }

        val packet = ByteArray(20)
        packet[0] = BlePacketFramingEngine.MAGIC_BYTE
        packet[1] = BlePacketFramingEngine.FRAME_TYPE_SESSION_RESUME

        packet[2] = ((sessionToken ushr 24) and 0xFF).toByte()
        packet[3] = ((sessionToken ushr 16) and 0xFF).toByte()
        packet[4] = ((sessionToken ushr 8) and 0xFF).toByte()
        packet[5] = (sessionToken and 0xFF).toByte()

        System.arraycopy(resumeNonce, 0, packet, 6, RESUME_NONCE_SIZE_BYTES)
        System.arraycopy(resumeMac, 0, packet, 12, RESUME_MAC_SIZE_BYTES)
        return packet
    }

    /**
     * Decodifica a chave pública EC codificada em formato X.509 padrão.
     */
    fun decodePublicKey(encodedBytes: ByteArray): PublicKey {
        val kf = KeyFactory.getInstance("EC")
        return kf.generatePublic(X509EncodedKeySpec(encodedBytes))
    }

    // =========================================================================
    // Serialização e Desserialização de Handshake Inicial (FRAME_TYPE 0x03 e 0x04)
    // =========================================================================

    data class HandshakeInitData(
        val version: Byte,
        val initiatorIdHash: String,
        val responderIdHash: String,
        val timestampMs: Long,
        val initiatorNonce: ByteArray,
        val initiatorIdentityPubKeyBytes: ByteArray,
        val initiatorEphemeralPubKeyBytes: ByteArray,
        val signature: ByteArray
    )

    data class HandshakeRespData(
        val version: Byte,
        val responderNonce: ByteArray,
        val responderIdentityPubKeyBytes: ByteArray,
        val responderEphemeralPubKeyBytes: ByteArray,
        val signature: ByteArray,
        val sessionConfirmMac: ByteArray
    )

    fun encodeHandshakeInit(data: HandshakeInitData): ByteArray {
        val initIdBytes = data.initiatorIdHash.toByteArray(StandardCharsets.UTF_8)
        val respIdBytes = data.responderIdHash.toByteArray(StandardCharsets.UTF_8)
        val totalSize = 1 +
                1 + initIdBytes.size +
                1 + respIdBytes.size +
                8 +
                data.initiatorNonce.size +
                2 + data.initiatorIdentityPubKeyBytes.size +
                2 + data.initiatorEphemeralPubKeyBytes.size +
                2 + data.signature.size

        val bb = ByteBuffer.allocate(totalSize)
        bb.put(data.version)
        bb.put(initIdBytes.size.toByte())
        bb.put(initIdBytes)
        bb.put(respIdBytes.size.toByte())
        bb.put(respIdBytes)
        bb.putLong(data.timestampMs)
        bb.put(data.initiatorNonce)
        bb.putShort(data.initiatorIdentityPubKeyBytes.size.toShort())
        bb.put(data.initiatorIdentityPubKeyBytes)
        bb.putShort(data.initiatorEphemeralPubKeyBytes.size.toShort())
        bb.put(data.initiatorEphemeralPubKeyBytes)
        bb.putShort(data.signature.size.toShort())
        bb.put(data.signature)
        return bb.array()
    }

    fun decodeHandshakeInit(bytes: ByteArray): HandshakeInitData? {
        return try {
            val bb = ByteBuffer.wrap(bytes)
            val version = bb.get()
            if (version != PROTOCOL_VERSION) return null
            val initIdLen = bb.get().toInt() and 0xFF
            val initIdBytes = ByteArray(initIdLen).also { bb.get(it) }
            val initIdHash = String(initIdBytes, StandardCharsets.UTF_8)
            val respIdLen = bb.get().toInt() and 0xFF
            val respIdBytes = ByteArray(respIdLen).also { bb.get(it) }
            val respIdHash = String(respIdBytes, StandardCharsets.UTF_8)
            val timestampMs = bb.long
            val initiatorNonce = ByteArray(NONCE_SIZE_BYTES).also { bb.get(it) }
            val idPubLen = bb.short.toInt() and 0xFFFF
            val idPubBytes = ByteArray(idPubLen).also { bb.get(it) }
            val ephPubLen = bb.short.toInt() and 0xFFFF
            val ephPubBytes = ByteArray(ephPubLen).also { bb.get(it) }
            val sigLen = bb.short.toInt() and 0xFFFF
            val sigBytes = ByteArray(sigLen).also { bb.get(it) }

            HandshakeInitData(
                version = version,
                initiatorIdHash = initIdHash,
                responderIdHash = respIdHash,
                timestampMs = timestampMs,
                initiatorNonce = initiatorNonce,
                initiatorIdentityPubKeyBytes = idPubBytes,
                initiatorEphemeralPubKeyBytes = ephPubBytes,
                signature = sigBytes
            )
        } catch (_: Exception) {
            null
        }
    }

    fun encodeHandshakeResp(data: HandshakeRespData): ByteArray {
        val totalSize = 1 +
                data.responderNonce.size +
                2 + data.responderIdentityPubKeyBytes.size +
                2 + data.responderEphemeralPubKeyBytes.size +
                2 + data.signature.size +
                data.sessionConfirmMac.size

        val bb = ByteBuffer.allocate(totalSize)
        bb.put(data.version)
        bb.put(data.responderNonce)
        bb.putShort(data.responderIdentityPubKeyBytes.size.toShort())
        bb.put(data.responderIdentityPubKeyBytes)
        bb.putShort(data.responderEphemeralPubKeyBytes.size.toShort())
        bb.put(data.responderEphemeralPubKeyBytes)
        bb.putShort(data.signature.size.toShort())
        bb.put(data.signature)
        bb.put(data.sessionConfirmMac)
        return bb.array()
    }

    fun decodeHandshakeResp(bytes: ByteArray): HandshakeRespData? {
        return try {
            val bb = ByteBuffer.wrap(bytes)
            val version = bb.get()
            if (version != PROTOCOL_VERSION) return null
            val responderNonce = ByteArray(NONCE_SIZE_BYTES).also { bb.get(it) }
            val idPubLen = bb.short.toInt() and 0xFFFF
            val idPubBytes = ByteArray(idPubLen).also { bb.get(it) }
            val ephPubLen = bb.short.toInt() and 0xFFFF
            val ephPubBytes = ByteArray(ephPubLen).also { bb.get(it) }
            val sigLen = bb.short.toInt() and 0xFFFF
            val sigBytes = ByteArray(sigLen).also { bb.get(it) }
            val confirmMac = ByteArray(32).also { bb.get(it) }

            HandshakeRespData(
                version = version,
                responderNonce = responderNonce,
                responderIdentityPubKeyBytes = idPubBytes,
                responderEphemeralPubKeyBytes = ephPubBytes,
                signature = sigBytes,
                sessionConfirmMac = confirmMac
            )
        } catch (_: Exception) {
            null
        }
    }
}
