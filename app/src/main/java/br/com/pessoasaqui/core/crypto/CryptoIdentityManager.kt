package br.com.pessoasaqui.core.crypto

import android.content.Context
import android.os.SystemClock
import android.util.Log
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
    @Volatile private var activeKeyPair: KeyPair? = null
    private var technicalIdentityHash: String = ""
    private var savedPublicKeyBase64: String? = null
    private val identityLock = Any()
    private var lastRestoreAttemptElapsedMs = 0L
    private var hasRetriedRestore = false

    companion object {
        private const val RETRY_RESTORE_INTERVAL_MS = 15_000L
        private const val TAG = "CryptoIdentityManager"
    }

    init {
        initializeIdentity()
    }

    private fun initializeIdentity() {
        val prefs = context?.getSharedPreferences("pessoasaqui_crypto_prefs", Context.MODE_PRIVATE)
        val savedHash = prefs?.getString("tech_id_hash", null)
        val savedPubKey = prefs?.getString("tech_pub_key", null)
        val savedPrivEnc = prefs?.getString("tech_priv_key_enc", null)

        val localDiskCrypto = LocalDiskCryptoManager(context)

        // A chave existente NUNCA é substituída quando sua restauração falha.
        if (!savedPrivEnc.isNullOrBlank() && !savedPubKey.isNullOrBlank()) {
            technicalIdentityHash = savedHash.orEmpty()
            savedPublicKeyBase64 = savedPubKey
            if (restoreSavedKeyPair(savedPrivEnc, savedPubKey, localDiskCrypto)) return
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

    /**
     * Recupera exclusivamente a chave já persistida. Falha de Keystore não
     * autoriza gerar outra identidade nem trocar os dados cifrados.
     * A prova de assinatura impede aceitar par público/privado divergente.
     */
    private fun restoreSavedKeyPair(
        encryptedPrivateKey: String,
        publicKeyBase64: String,
        crypto: LocalDiskCryptoManager
    ): Boolean {
        return try {
            val privateBytes = E2eeCryptoEngine.Base64Helper.decode(crypto.decrypt(encryptedPrivateKey))
            val publicBytes = E2eeCryptoEngine.Base64Helper.decode(publicKeyBase64)
            val factory = KeyFactory.getInstance("EC")
            val privateKey = factory.generatePrivate(PKCS8EncodedKeySpec(privateBytes))
            val publicKey = factory.generatePublic(X509EncodedKeySpec(publicBytes))
            val challenge = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
            val signer = Signature.getInstance("SHA256withECDSA")
            signer.initSign(privateKey)
            signer.update(challenge)
            val signature = signer.sign()
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(challenge)
            if (!verifier.verify(signature)) throw SecurityException("Par criptográfico inconsistente")
            activeKeyPair = KeyPair(publicKey, privateKey)
            savedPublicKeyBase64 = publicKeyBase64
            true
        } catch (e: Exception) {
            // Nunca emitir chaves, envelope cifrado ou material da identidade no log.
            Log.w(TAG, "Restauração da chave existente indisponível (${e.javaClass.simpleName}); identidade preservada")
            false
        }
    }

    /**
     * O Keystore pode falhar durante a inicialização do processo e voltar a
     * funcionar depois. Tentativas sob demanda têm limite e não fazem writes.
     */
    private fun recoverExistingIdentityIfDue(): KeyPair? = synchronized(identityLock) {
        activeKeyPair?.let { return@synchronized it }
        val ctx = context ?: return@synchronized null
        val now = SystemClock.elapsedRealtime()
        if (hasRetriedRestore && now - lastRestoreAttemptElapsedMs < RETRY_RESTORE_INTERVAL_MS) {
            return@synchronized null
        }
        hasRetriedRestore = true
        lastRestoreAttemptElapsedMs = now
        val prefs = ctx.getSharedPreferences("pessoasaqui_crypto_prefs", Context.MODE_PRIVATE)
        val savedId = prefs.getString("tech_id_hash", null)
        val savedPub = prefs.getString("tech_pub_key", null)
        val savedEncryptedPrivate = prefs.getString("tech_priv_key_enc", null)
        // O material persistido precisa corresponder à identidade pública em memória.
        if (savedId.isNullOrBlank() || savedId != technicalIdentityHash ||
            savedPub.isNullOrBlank() || savedPub != savedPublicKeyBase64 ||
            savedEncryptedPrivate.isNullOrBlank()) return@synchronized null
        restoreSavedKeyPair(savedEncryptedPrivate, savedPub, LocalDiskCryptoManager(ctx))
        activeKeyPair
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

    fun getActiveKeyPair(): KeyPair? = activeKeyPair ?: recoverExistingIdentityIfDue()
    fun getActivePrivateKey(): PrivateKey? = getActiveKeyPair()?.private
    fun getActivePublicKey(): PublicKey? = getActiveKeyPair()?.public

    fun signChallenge(data: ByteArray): String {
        return try {
            val privateKey = getActivePrivateKey() ?: throw SecurityException("Chave privada indisponível")
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
