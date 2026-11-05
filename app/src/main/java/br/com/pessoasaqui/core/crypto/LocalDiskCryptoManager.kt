package br.com.pessoasaqui.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
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
        private const val PREFIX_V2 = "ENC_V2:"
        private const val KEYSTORE_ALIAS = "pessoasaqui_local_disk_aes_v2"
        private const val FALLBACK_SALT = "PESSOASAQUI_DISK_SALT_V1_STABLE_PEPPER_2026"
    }

    private val diskKey: SecretKeySpec by lazy {
        deriveDeviceDiskKey()
    }

    private fun deriveDeviceDiskKey(): SecretKeySpec {
        var salt: String? = null
        try {
            val prefs = context?.getSharedPreferences("pessoasaqui_storage_key_prefs", Context.MODE_PRIVATE)
            salt = prefs?.getString("disk_salt_v1", null)
            if (salt.isNullOrBlank()) {
                val randomBytes = ByteArray(32)
                SecureRandom().nextBytes(randomBytes)
                salt = Base64.encodeToString(randomBytes, Base64.NO_WRAP)
                prefs?.edit()?.putString("disk_salt_v1", salt)?.commit()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Aviso ao obter disk salt do SharedPreferences: ${e.message}")
        }

        if (salt.isNullOrBlank()) {
            salt = FALLBACK_SALT
        }

        val pkgName = context?.packageName ?: "br.com.pessoasaqui"
        val seed = "$pkgName:$salt:PESSOASAQUI_DISK_ENCRYPTION_MASTER_KEY_2026"
        val md = MessageDigest.getInstance("SHA-256")
        val keyBytes = md.digest(seed.toByteArray(StandardCharsets.UTF_8))
        return SecretKeySpec(keyBytes, "AES")
    }

    /** V2: chave AES não exportável. V1 permanece apenas para leitura histórica. */
    private fun keystoreKey(): SecretKey {
        check(context != null) { "Android Keystore exige Context" }
        val store = KeyStore.getInstance("AndroidKeyStore")
        store.load(null)
        val previous = store.getKey(KEYSTORE_ALIAS, null)
        if (previous is SecretKey) return previous
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val params = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(params)
        return generator.generateKey()
    }

    private fun base64Encode(bytes: ByteArray): String {
        return try {
            android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                ?.takeIf { it.isNotBlank() }
                ?: java.util.Base64.getEncoder().encodeToString(bytes)
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
    }

    private fun base64Decode(str: String): ByteArray {
        return try {
            android.util.Base64.decode(str, android.util.Base64.NO_WRAP)
                ?.takeIf { it.isNotEmpty() }
                ?: java.util.Base64.getDecoder().decode(str)
        } catch (_: Throwable) {
            java.util.Base64.getDecoder().decode(str)
        }
    }

    /**
     * Cifra uma string em texto puro para gravação segura em repouso no disco.
     */
    fun encrypt(plaintext: String): String {
        return try {
            val cipher = Cipher.getInstance(ALGORITHM)
            val iv: ByteArray
            if (context != null) {
                // Android Keystore gera o IV internamente; não fornecer IV fixo.
                cipher.init(Cipher.ENCRYPT_MODE, keystoreKey())
                iv = cipher.iv
            } else {
                // Caminho isolado para testes sem Context (sem acesso ao Keystore).
                iv = ByteArray(IV_LENGTH_BYTES).also { SecureRandom().nextBytes(it) }
                cipher.init(Cipher.ENCRYPT_MODE,diskKey,GCMParameterSpec(TAG_LENGTH_BITS,iv))
            }

            val ciphertext = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
            val b64Iv = base64Encode(iv)
            val b64Cipher = base64Encode(ciphertext)

            "${if (context != null) PREFIX_V2 else PREFIX}$b64Iv:$b64Cipher"
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao cifrar dados para repouso no disco: ${e.message}", e)
            throw IllegalStateException("Não foi possível criptografar dados para armazenamento", e)
        }
    }

    /**
     * Decifra uma string lida do disco. Se já for texto puro legado, retorna sem alteração.
     */
    fun decrypt(storedData: String): String {
        val trimmed = storedData.trim()
        val isV2 = trimmed.startsWith(PREFIX_V2)
        val isV1 = trimmed.startsWith(PREFIX)
        if (!isV1 && !isV2) {
            if (trimmed.startsWith("ENC_V")) throw SecurityException("Versão do envelope inválida")
            // Somente leitura de dados históricos sem envelope; gravações novas usam V2.
            return trimmed
        }

        return try {
            val payload = trimmed.removePrefix(if (isV2) PREFIX_V2 else PREFIX)
            val parts = payload.split(":", limit = 2)
            if (parts.size < 2) throw SecurityException("Envelope local cifrado inválido")

            val iv = base64Decode(parts[0])
            val ciphertext = base64Decode(parts[1])

            val cipher = Cipher.getInstance(ALGORITHM)
            val gcmSpec = GCMParameterSpec(TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, if (isV2) keystoreKey() else diskKey, gcmSpec)

            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao decifrar dados do disco: ${e.message}", e)
            throw SecurityException("Falha de integridade ao decifrar armazenamento local", e)
        }
    }
}
