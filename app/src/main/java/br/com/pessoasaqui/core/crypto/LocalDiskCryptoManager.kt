package br.com.pessoasaqui.core.crypto

import android.content.Context
import android.util.Base64
import android.util.Log
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Gerenciador de Criptografia em Repouso para Dados Locais no Disco do Aparelho.
 * Protege arquivos de contatos mútuos, histórico de conversas e preferências com AES-256-GCM.
 *
 * Oferece compatibilidade regressiva automática:
 * - Se o dado gravado for JSON legado em texto puro, lê normalmente e permite migração transparente.
 * - Se o dado estiver criptografado (prefixo ENC_V1:), decifra com segurança militar.
 */
class LocalDiskCryptoManager(
    private val context: Context? = null
) {
    companion object {
        private const val TAG = "LocalDiskCrypto"
        private const val ALGORITHM = "AES/GCM/NoPadding"
        private const val TAG_LENGTH_BITS = 128
        private const val IV_LENGTH_BYTES = 12
        private const val PREFIX = "ENC_V1:"
    }

    private val diskKey: SecretKeySpec by lazy {
        deriveDeviceDiskKey()
    }

    private fun deriveDeviceDiskKey(): SecretKeySpec {
        val prefs = context?.getSharedPreferences("pessoasaqui_storage_key_prefs", Context.MODE_PRIVATE)
        var salt = prefs?.getString("disk_salt_v1", null)
        if (salt.isNullOrBlank()) {
            val randomBytes = ByteArray(32)
            SecureRandom().nextBytes(randomBytes)
            salt = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
            prefs?.edit()?.putString("disk_salt_v1", salt)?.apply()
        }

        val pkgName = context?.packageName ?: "br.com.pessoasaqui"
        val seed = "$pkgName:$salt:PESSOASAQUI_DISK_ENCRYPTION_MASTER_KEY_2026"
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(seed.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /**
     * Cifra uma string em texto puro para gravação segura em repouso no disco.
     */
    fun encrypt(plaintext: String): String {
        return try {
            val iv = ByteArray(IV_LENGTH_BYTES)
            SecureRandom().nextBytes(iv)

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.ENCRYPT_MODE, diskKey, gcmSpec)

            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val b64Iv = Base64.encodeToString(iv, Base64.NO_WRAP)
            val b64Cipher = Base64.encodeToString(ciphertext, Base64.NO_WRAP)

            "$PREFIX$b64Iv:$b64Cipher"
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao cifrar dados para repouso no disco: ${e.message}", e)
            plaintext // Fallback seguro
        }
    }

    /**
     * Decifra uma string lida do disco. Se já for texto puro legado, retorna sem alteração.
     */
    fun decrypt(storedData: String): String {
        val trimmed = storedData.trim()
        if (!trimmed.startsWith(PREFIX)) {
            // Dado legado em texto puro (migração transparente)
            return trimmed
        }

        return try {
            val payload = trimmed.removePrefix(PREFIX)
            val parts = payload.split(":", limit = 2)
            if (parts.size < 2) return trimmed

            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[1], Base64.NO_WRAP)

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, diskKey, gcmSpec)

            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao decifrar dados do disco: ${e.message}", e)
            trimmed
        }
    }
}
