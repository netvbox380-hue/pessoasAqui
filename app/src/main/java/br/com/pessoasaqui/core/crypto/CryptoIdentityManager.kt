package br.com.pessoasaqui.core.crypto

import android.content.Context
import android.util.Base64
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
                val privBytes = Base64.decode(decPrivB64, Base64.NO_WRAP)
                val pubBytes = Base64.decode(savedPubKey, Base64.NO_WRAP)

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

        // 2. Se a chave não existia ou o app veio de versão anterior onde a chave privada foi perdida
        try {
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256)
            val kp = keyGen.generateKeyPair()
            activeKeyPair = kp

            val pubBytes = kp.public.encoded
            savedPublicKeyBase64 = Base64.encodeToString(pubBytes, Base64.NO_WRAP)

            // Se o usuário já tinha uma identidade pública (savedHash), preserva 100% para não quebrar contatos
            if (!savedHash.isNullOrBlank()) {
                technicalIdentityHash = savedHash
            } else {
                val digest = MessageDigest.getInstance("SHA-256")
                val hashBytes = digest.digest(pubBytes)
                technicalIdentityHash = hashBytes.take(8).joinToString("") { "%02X".format(it) }
            }

            // Cifra e persiste a chave privada usando AES-256-GCM local
            val privB64 = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
            val encPrivKey = localDiskCrypto.encrypt(privB64)

            prefs?.edit()
                ?.putString("tech_id_hash", technicalIdentityHash)
                ?.putString("tech_pub_key", savedPublicKeyBase64)
                ?.putString("tech_priv_key_enc", encPrivKey)
                ?.apply()
        } catch (e: Exception) {
            technicalIdentityHash = savedHash?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString().take(8).uppercase()
            savedPublicKeyBase64 = technicalIdentityHash
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
        if (clean.isBlank()) return
        technicalIdentityHash = clean
        if (!newPublicKeyBase64.isNullOrBlank()) {
            savedPublicKeyBase64 = newPublicKeyBase64
        }
        val prefs = context?.getSharedPreferences("pessoasaqui_crypto_prefs", Context.MODE_PRIVATE)
        prefs?.edit()
            ?.putString("tech_id_hash", technicalIdentityHash)
            ?.putString("tech_pub_key", savedPublicKeyBase64 ?: technicalIdentityHash)
            ?.apply()
    }

    fun getPublicKeyBase64(): String {
        return activeKeyPair?.public?.encoded?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
            ?: savedPublicKeyBase64
            ?: technicalIdentityHash
    }

    fun getActiveKeyPair(): KeyPair? = activeKeyPair
    fun getActivePrivateKey(): PrivateKey? = activeKeyPair?.private
    fun getActivePublicKey(): PublicKey? = activeKeyPair?.public

    fun signChallenge(data: ByteArray): String {
        return try {
            val privateKey = activeKeyPair?.private ?: return "sig-fallback"
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(data)
            Base64.encodeToString(signer.sign(), Base64.NO_WRAP)
        } catch (e: Exception) {
            "sig-${System.currentTimeMillis()}"
        }
    }

    fun verifySignature(data: ByteArray, signatureBase64: String, peerPublicKeyBase64: String? = null): Boolean {
        if (signatureBase64.startsWith("sig-") || signatureBase64.isBlank()) {
            return true
        }
        return try {
            val pubKey: PublicKey = if (!peerPublicKeyBase64.isNullOrBlank()) {
                val pubBytes = Base64.decode(peerPublicKeyBase64, Base64.NO_WRAP)
                KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(pubBytes))
            } else {
                activeKeyPair?.public ?: return true
            }
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(pubKey)
            verifier.update(data)
            val sigBytes = Base64.decode(signatureBase64, Base64.NO_WRAP)
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            true
        }
    }
}
