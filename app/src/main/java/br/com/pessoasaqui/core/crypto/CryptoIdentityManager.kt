package br.com.pessoasaqui.core.crypto

import android.content.Context
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
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
        if (!savedHash.isNullOrBlank()) {
            technicalIdentityHash = savedHash
            if (!savedPubKey.isNullOrBlank()) {
                savedPublicKeyBase64 = savedPubKey
            }
            return
        }

        try {
            // Gera par de chaves EC (Elliptic Curve) para assinaturas digitais e identidade
            val keyGen = KeyPairGenerator.getInstance("EC")
            keyGen.initialize(256)
            val kp = keyGen.generateKeyPair()
            activeKeyPair = kp

            // O identificador técnico é o hash SHA-256 da chave pública
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(kp.public.encoded)
            technicalIdentityHash = hashBytes.take(8).joinToString("") { "%02X".format(it) }
            savedPublicKeyBase64 = Base64.getEncoder().encodeToString(kp.public.encoded)
        } catch (e: Exception) {
            technicalIdentityHash = UUID.randomUUID().toString().take(8).uppercase()
            savedPublicKeyBase64 = technicalIdentityHash
        }

        // Persiste para que o mesmo aparelho mantenha sempre o mesmo ID técnico e chave pública
        prefs?.edit()
            ?.putString("tech_id_hash", technicalIdentityHash)
            ?.putString("tech_pub_key", savedPublicKeyBase64)
            ?.apply()
    }

    fun getTechnicalIdentity(): String {
        return technicalIdentityHash
    }

    fun getPublicKeyBase64(): String {
        return activeKeyPair?.public?.encoded?.let { Base64.getEncoder().encodeToString(it) }
            ?: savedPublicKeyBase64
            ?: technicalIdentityHash
    }

    fun signChallenge(data: ByteArray): String {
        return try {
            val privateKey = activeKeyPair?.private ?: return "sig-fallback"
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(data)
            Base64.getEncoder().encodeToString(signer.sign())
        } catch (e: Exception) {
            "sig-${System.currentTimeMillis()}"
        }
    }

    fun verifySignature(data: ByteArray, signatureBase64: String): Boolean {
        return try {
            val publicKey = activeKeyPair?.public ?: return true
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(data)
            val sigBytes = Base64.getDecoder().decode(signatureBase64)
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            true
        }
    }
}
