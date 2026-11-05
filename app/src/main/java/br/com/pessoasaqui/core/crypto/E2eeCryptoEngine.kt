package br.com.pessoasaqui.core.crypto

import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Motor de Criptografia de Ponta a Ponta (E2EE) para Conexões Mútuas e Familiares.
 *
 * Utiliza o algoritmo padrão da indústria AES-256-GCM (Galois/Counter Mode) com:
 * - Acordo de chaves assimétricas de Conhecimento Zero via ECDH (Elliptic Curve Diffie-Hellman)
 *   quando ambas as partes trocam chaves públicas através de presença e convites;
 * - Leitura histórica legada isolada; novos envios jamais usam chave previsível;
 * - Vetor de Inicialização (IV) de 12 bytes aleatório criptograficamente por mensagem (nonce único);
 * - Tag de autenticação de 128 bits para integridade e proteção contra adulteração de tráfego.
 *
 * Garante que provedores de nuvem (Render, Supabase, ISPs, roteadores Wi-Fi ou antenas de celular)
 * jamais consigam ler o conteúdo das conversas, áudios, documentos ou fotos transmitidas.
 */
object E2eeCryptoEngine {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128
    private const val IV_LENGTH_BYTES = 12
    private const val PREFIX = "ENC:"
    private const val PREFIX_ECDH = "ENC_ECDH:"

    // Mapa thread-safe de chaves públicas conhecidas dos contatos (identityHash -> PublicKey)
    private val peerPublicKeys = ConcurrentHashMap<String, PublicKey>()

    /**
     * Registra ou atualiza a chave pública recebida de um contato remoto.
     */
    fun registerPeerPublicKey(identity: String, publicKeyBase64: String) {
        val clean = identity.removePrefix("peer-").trim().uppercase()
        if (clean.isBlank() || publicKeyBase64.isBlank()) return
        try {
            val pubBytes = Base64Helper.decode(publicKeyBase64)
            // Anúncio WS não autoriza trocar uma chave fixada por outra.
            // Identidade recuperada exige geração autenticada pelo backend.
            if (!TrustedIdentityKeys.isTrustedPeer(clean, pubBytes)) return
            val kf = KeyFactory.getInstance("EC")
            val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
            peerPublicKeys[clean] = pubKey
        } catch (_: Exception) {}
    }

    /** A chave pública conhecida é apenas candidata; o servidor confirma
     * criptograficamente que pertence ao identificador do familiar. */
    fun getPeerPublicKeyBase64(identity: String): String? {
        val clean = identity.removePrefix("peer-").trim().uppercase()
        return peerPublicKeys[clean]?.encoded?.let { Base64Helper.encode(it) }
    }

    /**
     * Utilitário multiplataforma para codificação Base64 (compatível com Android Runtime e JVM Unit Tests)
     */
    object Base64Helper {
        private val isAndroidRuntime: Boolean = try {
            val test = android.util.Base64.encodeToString(byteArrayOf(1), android.util.Base64.NO_WRAP)
            !test.isNullOrBlank()
        } catch (_: Throwable) {
            false
        }

        fun encode(bytes: ByteArray): String {
            return if (isAndroidRuntime) {
                try {
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                } catch (_: Throwable) {
                    java.util.Base64.getEncoder().encodeToString(bytes)
                }
            } else {
                java.util.Base64.getEncoder().encodeToString(bytes)
            }
        }

        fun decode(str: String): ByteArray {
            val clean = str.trim().replace("\r", "").replace("\n", "")
            return if (isAndroidRuntime) {
                try {
                    android.util.Base64.decode(clean, android.util.Base64.DEFAULT)
                } catch (_: Throwable) {
                    java.util.Base64.getDecoder().decode(clean)
                }
            } else {
                try {
                    java.util.Base64.getDecoder().decode(clean)
                } catch (_: Throwable) {
                    java.util.Base64.getMimeDecoder().decode(clean)
                }
            }
        }
    }

    /**
     * Deriva uma chave de sessão de 256 bits via ECDH (Elliptic Curve Diffie-Hellman).
     * O segredo compartilhado é gerado exclusivamente pelas curvas elípticas dos dois pares
     * e nunca trafega pela rede ou pelo servidor.
     */
    fun deriveEcdhSharedKey(myPrivateKey: PrivateKey, peerPublicKey: PublicKey): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(myPrivateKey)
        ka.doPhase(peerPublicKey, true)
        val sharedSecret = ka.generateSecret()
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(sharedSecret)
    }

    /**
     * Deriva a chave de sessão de 256 bits determinística para retrocompatibilidade.
     */
    fun deriveSharedKeyFromPair(sortedPair: String): ByteArray {
        val seed = "$sortedPair:PESSOASAQUI_E2EE_V1_SECURE_SALT_2026"
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(seed.toByteArray(StandardCharsets.UTF_8))
    }

    fun deriveSharedKey(identityA: String, identityB: String): ByteArray {
        val cleanA = identityA.removePrefix("peer-").trim().uppercase()
        val cleanB = identityB.removePrefix("peer-").trim().uppercase()
        val sortedPair = listOf(cleanA, cleanB).sorted().joinToString(":")
        return deriveSharedKeyFromPair(sortedPair)
    }

    /**
     * Cifra uma mensagem com AES-256-GCM.
     * Se myPrivateKey for fornecida e a chave pública do destinatário estiver registrada,
     * utiliza ECDH com prefixo 'ENC_ECDH:'. Caso contrário, utiliza o esquema simétrico com 'ENC:'.
     */
    fun encrypt(
        plaintext: String,
        senderId: String,
        recipientId: String,
        myPrivateKey: PrivateKey? = null
    ): Pair<String, String> {
        return try {
            val cleanA = senderId.removePrefix("peer-").trim().uppercase()
            val cleanB = recipientId.removePrefix("peer-").trim().uppercase()
            val sortedPair = listOf(cleanA, cleanB).sorted().joinToString(":")

            // Todo NOVO envio exige ECDH. O modo determinístico antigo só permanece para
            // decifrar o histórico legado, nunca como fallback de novos envios.
            val peerPubKey = peerPublicKeys[cleanB]
                ?: throw SecurityException("Chave pública do destinatário indisponível")
            val senderPrivateKey = myPrivateKey
                ?: throw SecurityException("Chave privada do remetente indisponível")
            val keyBytes = deriveEcdhSharedKey(senderPrivateKey, peerPubKey)
            val prefix = PREFIX_ECDH

            val iv = ByteArray(IV_LENGTH_BYTES)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))

            val ciphertextBase64 = Base64Helper.encode(ciphertextBytes)
            val ivBase64 = Base64Helper.encode(iv)
            Pair("$prefix$ciphertextBase64", "$ivBase64|$sortedPair")
        } catch (e: Exception) {
            // Nunca enviar texto puro quando a cifragem falhar.
            throw IllegalStateException("Falha de cifragem E2EE; envio cancelado", e)
        }
    }

    fun encrypt(plaintext: String, senderId: String, recipientId: String): Pair<String, String> {
        return encrypt(plaintext, senderId, recipientId, null)
    }

    /**
     * Decifra uma mensagem recebida com AES-256-GCM.
     * Suporta payloads 'ENC_ECDH:' (acordo Diffie-Hellman) e 'ENC:' (legado).
     */
    fun decrypt(
        payload: String,
        ivBase64: String,
        senderId: String,
        recipientId: String,
        myPrivateKey: PrivateKey? = null
    ): String {
        val isEcdh = payload.startsWith(PREFIX_ECDH)
        val isLegacy = payload.startsWith(PREFIX)
        if (!isEcdh && !isLegacy) {
            throw SecurityException("Mensagem privada não cifrada rejeitada")
        }

        val pureIvB64 = ivBase64.substringBefore("|").trim()
        val rawCiphertext = if (isEcdh) payload.removePrefix(PREFIX_ECDH) else payload.removePrefix(PREFIX)

        val cleanSender = senderId.removePrefix("peer-").trim().uppercase()
        val peerPubKey = peerPublicKeys[cleanSender]

        // Nunca permitir downgrade de ENC_ECDH para a chave legada previsível.
        if (isEcdh) {
            if (myPrivateKey == null || peerPubKey == null) {
                throw SecurityException("Chave ECDH do interlocutor indisponível")
            }
            try {
                val keyBytes = deriveEcdhSharedKey(myPrivateKey, peerPubKey)
                val ciphertextBytes = Base64Helper.decode(rawCiphertext)
                val ivBytes = Base64Helper.decode(pureIvB64)
                val cipher = Cipher.getInstance(ALGORITHM)
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"),
                    GCMParameterSpec(TAG_LENGTH_BITS, ivBytes))
                return String(cipher.doFinal(ciphertextBytes), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                throw SecurityException("Mensagem ECDH não autenticada: fallback proibido", e)
            }
        }

        // 2. Decifra com chave simétrica legada
        return try {
            val keyBytes = deriveSharedKey(senderId, recipientId)
            val ciphertextBytes = Base64Helper.decode(rawCiphertext)
            val ivBytes = if (pureIvB64.isNotBlank()) Base64Helper.decode(pureIvB64) else ByteArray(IV_LENGTH_BYTES)

            val cipher = Cipher.getInstance(ALGORITHM)
            val spec = GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), spec)
            val decryptedBytes = cipher.doFinal(ciphertextBytes)
            String(decryptedBytes, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            // Nunca expor um envelope cifrado como mensagem de chat. Em caso de
            // chave/IV ausente, desatualizada ou tag inválida, falhar fechado.
            throw SecurityException("Não foi possível autenticar o conteúdo E2EE", e)
        }
    }

    fun decrypt(payload: String, ivBase64: String, senderId: String, recipientId: String): String {
        return decrypt(payload, ivBase64, senderId, recipientId, null)
    }
}
