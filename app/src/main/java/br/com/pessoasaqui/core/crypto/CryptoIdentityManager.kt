package br.com.pessoasaqui.core.crypto

import android.content.Context
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Gerenciador da Identidade Técnica e Criptográfica do PessoasAqui.
 * Gera e mantém pares de chaves assimétricas seguras persistidas por aparelho,
 * sem exigir cadastro com e-mail ou telefone.
 */
class CryptoIdentityManager(
    private val context: Context? = null
) {
    private var activeKeyPair: KeyPair? = null
    private var technicalIdentityHash: String = ""
    private var savedPublicKeyBase64: String? = null

    init {
        initializeIdentity()
    }

    private fun initializeIdentity() {
        val prefs = context?.getSharedPreferences("pessoasaqui_crypto_prefs", Context.MODE_PRIVATE)
        val savedHash = prefs?.getString("tech_id_hash", null)
        val savedPubKey = prefs?.getString("tech_pub_key", null)
        val savedPrivEnc = prefs?.getString("tech_priv_key_enc", null)

        val localDiskCrypto = LocalDiskCryptoManager(context)

        // 1. Tenta restaurar par de chaves EC persistido criptograficamente
        if (!savedPrivEnc.isNullOrBlank() && !savedPubKey.isNullOrBlank()) {
            try {
                val decPrivB64 = localDiskCrypto.decrypt(savedPrivEnc)
                val privBytes = E2eeCryptoEngine.Base64Helper.decode(decPrivB64)
                val pubBytes = E2eeCryptoEngine.Base64Helper.decode(savedPubKey)

                val kf = KeyFactory.getInstance("EC")
                val privKey = kf.generatePrivate(PKCS8EncodedKeySpec(privBytes))
                val pubKey = kf.generatePublic(X509EncodedKeySpec(pubBytes))
                activeKeyPair = KeyPair(pubKey, privKey)
                savedPublicKeyBase64 = savedPubKey
                if (!savedHash.isNullOrBlank()) {
                    technicalIdentityHash = savedHash
                }
                return
            } catch (_: Exception) {}
        }

        // Chave cifrada perdida/Keystore inválido: não substituir silenciosamente
        // a chave privada mantendo o mesmo identificador público.
        if (!savedPrivEnc.isNullOrBlank() || !savedHash.isNullOrBlank()) {
            technicalIdentityHash = savedHash.orEmpty()
            savedPublicKeyBase64 = savedPubKey
            activeKeyPair = null
            return
        }

        // Primeiro uso sem identidade anterior: gerar chave e identidade novas.
        try {
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256)
            val kp = keyGen.generateKeyPair()
            activeKeyPair = kp

            val pubBytes = kp.public.encoded
            savedPublicKeyBase64 = E2eeCryptoEngine.Base64Helper.encode(pubBytes)

            // Se o usuário já tinha uma identidade pública (savedHash), preserva 100% para não quebrar contatos
            if (!savedHash.isNullOrBlank()) {
                technicalIdentityHash = savedHash
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(pubBytes)
                technicalIdentityHash = hashBytes.take(8).joinToString("") { "%02X".format(it) }
            }

            // Cifra e persiste a chave privada usando AES-256-GCM local
            val privB64 = E2eeCryptoEngine.Base64Helper.encode(kp.private.encoded)
            val encPrivKey = localDiskCrypto.encrypt(privB64)

            if (prefs != null && !prefs.edit()
                    .putString("tech_id_hash", technicalIdentityHash)
                    .putString("tech_pub_key", savedPublicKeyBase64)
                    .putString("tech_priv_key_enc", encPrivKey)
                    .commit()) {
                activeKeyPair = null
                throw IllegalStateException("Identidade criptográfica não foi persistida")
            }
        } catch (e: Exception) {
            // Sem chave persistente utilizável, a autenticação fica bloqueada.
            activeKeyPair = null
            technicalIdentityHash = savedHash.orEmpty()
            savedPublicKeyBase64 = savedPubKey
        }
    }

    fun getTechnicalIdentity(): String {
        return technicalIdentityHash
    }

    /**
     * Aplica e persiste a identidade recuperada via autorização familiar ("Entre na sua"),
     * transferindo a titularidade e o identificador criptográfico para este novo aparelho.
     */
    fun setRecoveredIdentity(newIdentityHash: String, newPublicKeyBase64: String? = null) {
        val clean = newIdentityHash.removePrefix("peer-").trim().uppercase()
        require(Regex("^[A-F0-9]{16}$").matches(clean)) { "Identidade recuperada inválida" }
        val active = activeKeyPair?.public?.encoded
            ?: throw SecurityException("Recuperação exige chave privada íntegra no novo aparelho")
        val activePublic = E2eeCryptoEngine.Base64Helper.encode(active)
        if (!newPublicKeyBase64.isNullOrBlank() && newPublicKeyBase64 != activePublic)
            throw SecurityException("A chave da recuperação não corresponde ao aparelho")
        val prefs = context?.getSharedPreferences("pessoasaqui_crypto_prefs", Context.MODE_PRIVATE)
        if (prefs != null && !prefs.edit()
                .putString("tech_id_hash", clean)
                .putString("tech_pub_key", activePublic)
                .commit()) throw IllegalStateException("Identidade recuperada não persistida")
        technicalIdentityHash = clean
        savedPublicKeyBase64 = activePublic
    }

    fun getPublicKeyBase64(): String {
        return activeKeyPair?.public?.encoded?.let { E2eeCryptoEngine.Base64Helper.encode(it) }
            ?: savedPublicKeyBase64
            ?: ""
    }

    fun getActiveKeyPair(): KeyPair? = activeKeyPair
    fun getActivePrivateKey(): PrivateKey? = activeKeyPair?.private
    fun getActivePublicKey(): PublicKey? = activeKeyPair?.public

    fun signChallenge(data: ByteArray): String {
        return try {
            val privateKey = activeKeyPair?.private ?: throw SecurityException("Chave privada indisponível")
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(data)
            E2eeCryptoEngine.Base64Helper.encode(signer.sign())
        } catch (e: Exception) {
            throw SecurityException("Não foi possível assinar desafio criptográfico", e)
        }
    }

    /** Segurança: uma assinatura inválida, ausente ou simulada NUNCA autentica uma identidade. */
    fun verifySignature(data: ByteArray, signatureBase64: String, peerPublicKeyBase64: String? = null): Boolean {
        if (signatureBase64.isBlank() || signatureBase64.startsWith("sig-")) return false
        return try {
            val pubKey: PublicKey = if (!peerPublicKeyBase64.isNullOrBlank()) {
                val pubBytes = E2eeCryptoEngine.Base64Helper.decode(peerPublicKeyBase64)
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
            } else {
                activeKeyPair?.public ?: return false
            }
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pubKey)
            verifier.update(data)
            val sigBytes = E2eeCryptoEngine.Base64Helper.decode(signatureBase64)
            verifier.verify(sigBytes)
        } catch (_: Exception) {
            false
        }
    }
}
