package br.com.pessoasaqui.core.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.domain.model.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pacote Criptografado do Cofre Distribuído (Zero-Knowledge).
 *
 * Contém o histórico de mensagens compactado em GZIP e cifrado com AES-256-GCM.
 * A chave de descriptografia só pode ser derivada a partir do PIN de 6 dígitos do usuário.
 */
@Serializable
data class EncryptedVaultPackage(
    val ownerIdentityHash: String,
    val ownerAlias: String,
    val version: Long,
    val timestampEpochMs: Long,
    val saltHex: String,
    val ivHex: String,
    val encryptedPayloadBase64: String,
    val payloadSha256: String,
    val totalMessagesCount: Int,
    // V1/V2: históricos legados protegidos somente por PIN; V3: PIN + segredo 256 bits.
    val formatVersion: Int = 1
)

/**
 * Contato salvo para inclusão no Cofre Criptografado.
 */
@Serializable
data class VaultContact(
    val id: String,
    val alias: String,
    val isMutual: Boolean = true,
    val isFamily: Boolean = false,
    val familyRole: String? = null
)

/**
 * Estrutura serializável para empacotamento completo de conversas e contatos.
 */
@Serializable
data class VaultContentData(
    val privateChats: Map<String, List<ChatMessage>>,
    val localMural: List<ChatMessage>,
    val contacts: List<VaultContact> = emptyList(),
    val exportTimestampEpochMs: Long = System.currentTimeMillis()
)

/**
 * Gerenciador de Cofre P2P Distribuído entre Aparelhos Familiares.
 *
 * Implementa:
 * - Criptografia militar ponta a ponta Zero-Knowledge (AES-256-GCM + PBKDF2WithHmacSHA256).
 * - Compactação GZIP para payloads minúsculos (~150KB - 300KB).
 * - Versionamento com eleição automática do backup mais recente.
 * - Replicação silenciosa redundante em até 3 dispositivos familiares.
 * - Armazenamento seguro de cofres de terceiros em diretório isolado.
 * - Dupla camada de contingência (exportação/importação de arquivo .backup pessoal).
 */
class P2PVaultManager(
    private val context: Context? = null,
    injectedPrefs: SharedPreferences? = null,
    injectedVaultDir: File? = null
) {

    companion object {
        private const val TAG = "P2PVaultManager"
        private const val PREFS_KEY = "pessoasaqui_backup_prefs"
        private const val VAULTS_DIR_NAME = "pessoasaqui_vaults"

        private const val KEY_DISTRIBUTED_BACKUP_ENABLED = "distributed_backup_enabled"
        private const val KEY_FREQUENCY_HOURS = "frequency_hours"
        private const val KEY_LAST_BACKUP_EPOCH = "last_backup_epoch"
        private const val KEY_LAST_BACKUP_VERSION = "last_backup_version"
        private const val KEY_LAST_REPLICATED_COUNT = "last_replicated_count"
        private const val KEY_LAST_MESSAGES_COUNT = "last_messages_count"

        private const val PBKDF2_ITERATIONS_V1 = 10_000
        private const val PBKDF2_ITERATIONS_V2 = 310_000
        private const val KEY_LENGTH_BITS = 256
        private const val GCM_IV_LENGTH_BYTES = 12
        private const val GCM_TAG_LENGTH_BITS = 128
        private const val MAX_ALLOWED_VAULT_BYTES = 10 * 1024 * 1024 // 10MB máximo por cofre

        private const val RECOVERY_SECRET_PREFIX = "PA-VAULT-V3-"
        private val RECOVERY_SECRET_PATTERN = Regex("^PA-VAULT-V3-[0-9A-F]{64}$")

        /** Compartilhar o segredo APENAS por canal autenticado separado do backup. */
        fun generateRecoverySecret(): String {
            val secret = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return RECOVERY_SECRET_PREFIX + secret.joinToString("") { "%02X".format(it.toInt() and 0xff) }
        }

        val json = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

        object SafeLog {
            fun i(tag: String, msg: String) {
                try {
                    android.util.Log.i(tag, msg)
                } catch (_: Throwable) {
                    println("[$tag] INFO: $msg")
                }
            }
            fun w(tag: String, msg: String) {
                try {
                    android.util.Log.w(tag, msg)
                } catch (_: Throwable) {
                    println("[$tag] WARN: $msg")
                }
            }
            fun e(tag: String, msg: String, tr: Throwable? = null) {
                try {
                    android.util.Log.e(tag, msg, tr)
                } catch (_: Throwable) {
                    println("[$tag] ERROR: $msg ${tr?.message ?: ""}")
                }
            }
        }
    }

    private val prefs: SharedPreferences? = injectedPrefs ?: context?.getSharedPreferences(PREFS_KEY, Context.MODE_PRIVATE)

    private val vaultsDir: File? = when {
        injectedVaultDir != null -> injectedVaultDir
        context != null -> File(context.filesDir, VAULTS_DIR_NAME)
        else -> null
    }

    init {
        vaultsDir?.let {
            if (!it.exists()) it.mkdirs()
        }
    }

    // Configurações de Frequência e Estado
    fun isDistributedBackupEnabled(): Boolean {
        return prefs?.getBoolean(KEY_DISTRIBUTED_BACKUP_ENABLED, true) ?: true
    }

    fun setDistributedBackupEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_DISTRIBUTED_BACKUP_ENABLED, enabled)?.apply()
    }

    fun getBackupFrequencyHours(): Int {
        return prefs?.getInt(KEY_FREQUENCY_HOURS, 24) ?: 24
    }

    fun setBackupFrequencyHours(hours: Int) {
        prefs?.edit()?.putInt(KEY_FREQUENCY_HOURS, hours)?.apply()
    }

    fun getLastBackupEpochMs(): Long {
        return prefs?.getLong(KEY_LAST_BACKUP_EPOCH, 0L) ?: 0L
    }

    fun getLastBackupVersion(): Long {
        return prefs?.getLong(KEY_LAST_BACKUP_VERSION, 0L) ?: 0L
    }

    fun getLastReplicatedCount(): Int {
        return prefs?.getInt(KEY_LAST_REPLICATED_COUNT, 0) ?: 0
    }

    fun getLastMessagesCount(): Int {
        return prefs?.getInt(KEY_LAST_MESSAGES_COUNT, 0) ?: 0
    }

    fun recordBackupSuccess(version: Long, replicatedCount: Int, messagesCount: Int) {
        prefs?.edit()
            ?.putLong(KEY_LAST_BACKUP_EPOCH, System.currentTimeMillis())
            ?.putLong(KEY_LAST_BACKUP_VERSION, version)
            ?.putInt(KEY_LAST_REPLICATED_COUNT, replicatedCount)
            ?.putInt(KEY_LAST_MESSAGES_COUNT, messagesCount)
            ?.apply()
    }

    /**
     * Verifica se o intervalo configurado já transcorreu desde o último backup.
     */
    fun isBackupDue(): Boolean {
        if (!isDistributedBackupEnabled()) return false
        val last = getLastBackupEpochMs()
        if (last <= 0L) return true
        val intervalMs = getBackupFrequencyHours().toLong() * 60 * 60 * 1000L
        return (System.currentTimeMillis() - last) >= intervalMs
    }

    // ==========================================
    // CRIPTOGRAFIA ZERO-KNOWLEDGE E GZIP
    // ==========================================

    /**
     * Deriva uma chave AES-256 a partir do PIN de 6 dígitos usando PBKDF2WithHmacSHA256.
     */
    private fun deriveKey(pin: String, salt: ByteArray, formatVersion: Int): SecretKeySpec {
        require(formatVersion in 1..3) { "Formato de cofre desconhecido" }
        require(salt.size == 16) { "Sal do cofre inválido" }
        require(pin.isNotBlank()) { "PIN do cofre vazio" }
        val rounds = if (formatVersion == 1) PBKDF2_ITERATIONS_V1 else PBKDF2_ITERATIONS_V2
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(pin.trim().toCharArray(), salt, rounds, KEY_LENGTH_BITS)
        return try {
            SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    /**
     * V3: derive a PIN-stretched key, then combine it with an independent
     * 256-bit recovery secret. The secret is never serialized in the vault.
     * The PIN alone must not be sufficient to decrypt an exported copy.
     */
    private fun vaultKey(pin: String, salt: ByteArray, format: Int, recoverySecret: String?): SecretKeySpec {
        val base = deriveKey(pin, salt, format)
        if (format != 3) return base
        val candidate = recoverySecret ?: throw SecurityException("Segredo de recuperação V3 obrigatório")
        require(RECOVERY_SECRET_PATTERN.matches(candidate)) { "Segredo de recuperação inválido" }
        val bytes = ByteArray(32) { index ->
            candidate.substring(RECOVERY_SECRET_PREFIX.length + index * 2,
                RECOVERY_SECRET_PREFIX.length + index * 2 + 2).toInt(16).toByte()
        }
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(bytes, "HmacSHA256"))
        bytes.fill(0)
        val derived = base.encoded
        return try {
            val domain = "PESSOASAQUI-VAULT-V3-KEY".toByteArray(Charsets.US_ASCII)
            SecretKeySpec(mac.doFinal(domain + salt + derived), "AES")
        } finally {
            derived.fill(0)
        }
    }

    /** Autenticação GCM de metadados que antes podiam ser alterados externamente. */
    private fun associatedData(vault: EncryptedVaultPackage): ByteArray {
        val parts = listOf(
            if (vault.formatVersion == 3) "PESSOASAQUI-VAULT-V3" else "PESSOASAQUI-VAULT-V2",
            vault.ownerIdentityHash,
            vault.ownerAlias,
            vault.version.toString(),
            vault.timestampEpochMs.toString(),
            vault.totalMessagesCount.toString()
        )
        return parts.joinToString("|") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8)
    }

    /**
     * Compacta os bytes usando GZIP de alta taxa.
     */
    fun compressGzip(data: ByteArray): ByteArray {
        val byteStream = ByteArrayOutputStream()
        GZIPOutputStream(byteStream).use { it.write(data) }
        return byteStream.toByteArray()
    }

    /**
     * Descompacta os bytes GZIP.
     */
    fun decompressGzip(compressed: ByteArray): ByteArray {
        val byteStream = ByteArrayInputStream(compressed)
        GZIPInputStream(byteStream).use { stream ->
            val output = ByteArrayOutputStream()
            val chunk = ByteArray(8_192)
            var total = 0
            while (true) {
                val size = stream.read(chunk)
                if (size < 0) break
                total += size
                if (total > MAX_ALLOWED_VAULT_BYTES) throw SecurityException("Cofre descompactado excede limite")
                output.write(chunk, 0, size)
            }
            return output.toByteArray()
        }
    }

    fun calculateSha256(data: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(data)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            val index = i * 2
            result[i] = hex.substring(index, index + 2).toInt(16).toByte()
        }
        return result
    }

    /**
     * Cria o pacote completo do Cofre Criptografado a partir de conversas e contatos locais.
     */
    fun createVaultPackage(
        pin: String,
        ownerIdentityHash: String,
        ownerAlias: String,
        privateChats: Map<String, List<ChatMessage>>,
        localMural: List<ChatMessage>,
        contacts: List<VaultContact> = emptyList(),
        recoverySecret: String? = null
    ): EncryptedVaultPackage {
        val totalMsgs = privateChats.values.sumOf { it.size } + localMural.size
        val content = VaultContentData(
            privateChats = privateChats,
            localMural = localMural,
            contacts = contacts,
            exportTimestampEpochMs = System.currentTimeMillis()
        )

        // 1. Serializa para JSON e compacta em GZIP
        val rawJson = json.encodeToString(VaultContentData.serializer(), content)
        val compressedBytes = compressGzip(rawJson.toByteArray(Charsets.UTF_8))

        // 2. Gera Salt e IV aleatórios
        val random = SecureRandom()
        val salt = ByteArray(16)
        random.nextBytes(salt)

        val iv = ByteArray(GCM_IV_LENGTH_BYTES)
        random.nextBytes(iv)

        // V3 é criado com segredo independente. Sem segredo explícito, conservar V2
        // para não tornar irrecuperáveis os backups dos clientes existentes.
        val meta = EncryptedVaultPackage(
            ownerIdentityHash = ownerIdentityHash.removePrefix("peer-").trim().lowercase(),
            ownerAlias = ownerAlias,
            version = getLastBackupVersion() + 1,
            timestampEpochMs = System.currentTimeMillis(),
            saltHex = bytesToHex(salt),
            ivHex = bytesToHex(iv),
            encryptedPayloadBase64 = "",
            payloadSha256 = "",
            totalMessagesCount = totalMsgs,
            formatVersion = if (recoverySecret != null) 3 else 2
        )
        val key = vaultKey(pin, salt, meta.formatVersion, recoverySecret)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
        cipher.init(Cipher.ENCRYPT_MODE, key, gcmSpec)
        cipher.updateAAD(associatedData(meta))
        val encryptedBytes = cipher.doFinal(compressedBytes)
        require(encryptedBytes.size <= MAX_ALLOWED_VAULT_BYTES) { "Cofre excede limite seguro" }
        return meta.copy(
            encryptedPayloadBase64 = E2eeCryptoEngine.Base64Helper.encode(encryptedBytes),
            payloadSha256 = calculateSha256(encryptedBytes)
        )
    }

    /**
     * Descriptografa e descompacta o pacote do Cofre com validação de PIN e integridade.
     */
    fun restoreFromVaultPackage(
        vault: EncryptedVaultPackage,
        pin: String,
        recoverySecret: String? = null
    ): Result<VaultContentData> {
        return try {
            require(vault.formatVersion in 1..3) { "Versão do cofre não suportada" }
            require(vault.encryptedPayloadBase64.length <= MAX_ALLOWED_VAULT_BYTES * 2) {
                "Envelope de backup excessivo"
            }
            val encryptedBytes = E2eeCryptoEngine.Base64Helper.decode(vault.encryptedPayloadBase64)
            require(encryptedBytes.size in 16..MAX_ALLOWED_VAULT_BYTES) { "Cofre inválido" }

            // Valida integridade do payload
            val checkSha = calculateSha256(encryptedBytes)
            if (checkSha != vault.payloadSha256) {
                return Result.failure(IllegalStateException("Arquivo de cofre corrompido: hash SHA-256 não confere."))
            }

            val salt = hexToBytes(vault.saltHex)
            val iv = hexToBytes(vault.ivHex)
            require(salt.size == 16 && iv.size == GCM_IV_LENGTH_BYTES) {
                "Sal ou IV do cofre inválido"
            }
            val key = vaultKey(pin, salt, vault.formatVersion, recoverySecret)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, gcmSpec)
            if (vault.formatVersion >= 2) cipher.updateAAD(associatedData(vault))

            val decryptedCompressedBytes = cipher.doFinal(encryptedBytes)
            val decompressedBytes = decompressGzip(decryptedCompressedBytes)
            val rawJson = String(decompressedBytes, Charsets.UTF_8)

            val content = json.decodeFromString(VaultContentData.serializer(), rawJson)
            Result.success(content)
        } catch (e: javax.crypto.AEADBadTagException) {
            Result.failure(IllegalArgumentException("PIN incorreto! Não foi possível decifrar o cofre."))
        } catch (e: Exception) {
            Result.failure(Exception("Falha ao restaurar cofre: ${e.message}"))
        }
    }

    // ==========================================
    // ARMAZENAMENTO DE COFRES DE TERCEIROS (P2P)
    // ==========================================

    private fun cleanKey(hash: String): String {
        return hash.removePrefix("peer-").trim().lowercase().replace(Regex("[^a-z0-9_-]"), "_")
    }

    private fun getVaultFile(cleanOwnerHash: String): File? {
        val dir = vaultsDir ?: return null
        return File(dir, "vault_$cleanOwnerHash.json")
    }

    /**
     * Salva o cofre recebido silenciosamente de um familiar.
     * Só substitui se a versão for estritamente mais recente (proteção contra rollback).
     */
    fun storePeerVault(vault: EncryptedVaultPackage): Boolean {
        val clean = cleanKey(vault.ownerIdentityHash)
        if (clean.isBlank() || vault.formatVersion !in 1..3 ||
            vault.encryptedPayloadBase64.length > MAX_ALLOWED_VAULT_BYTES * 2 ||
            vault.version < 1) return false
        val file = getVaultFile(clean) ?: return false

        try {
            if (file.exists()) {
                val existingJson = file.readText(Charsets.UTF_8)
                val existingVault = json.decodeFromString(EncryptedVaultPackage.serializer(), existingJson)
                // Nunca substituir um cofre V3 por formato fraco baseado apenas no PIN,
                // mesmo se o atacante anunciar número de versão artificialmente maior.
                if (existingVault.formatVersion == 3 && vault.formatVersion < 3) {
                    SafeLog.w(TAG, "Tentativa de downgrade de cofre V3 bloqueada")
                    return false
                }
                if (vault.version <= existingVault.version) {
                    SafeLog.i(TAG, "Cofre recebido ignorado: versão ${vault.version} é mais antiga que ${existingVault.version}")
                    return false
                }
            }

            val serialized = json.encodeToString(EncryptedVaultPackage.serializer(), vault)
            if (serialized.toByteArray().size > MAX_ALLOWED_VAULT_BYTES) {
                SafeLog.w(TAG, "Cofre excede limite máximo de tamanho permitido.")
                return false
            }

            val tempFile = File(file.parentFile, "${file.name}.tmp")
            tempFile.writeText(serialized, Charsets.UTF_8)
            // Nunca apagar o backup original antes de confirmar a substituição.
            Files.move(tempFile.toPath(), file.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            SafeLog.i(TAG, "Cofre silencioso do contato ${vault.ownerAlias} (${vault.ownerIdentityHash}) salvo com sucesso (v${vault.version}).")
            return true
        } catch (e: Exception) {
            SafeLog.e(TAG, "Erro ao armazenar cofre do peer $clean", e)
            return false
        }
    }

    /**
     * Recupera o cofre armazenado de um usuário específico para envio em resposta a restauração.
     */
    fun getStoredPeerVault(ownerIdentityHash: String): EncryptedVaultPackage? {
        val clean = cleanKey(ownerIdentityHash)
        val file = getVaultFile(clean) ?: return null
        if (!file.exists()) return null

        return try {
            val content = file.readText(Charsets.UTF_8)
            json.decodeFromString(EncryptedVaultPackage.serializer(), content)
        } catch (e: Exception) {
            SafeLog.e(TAG, "Erro ao ler cofre armazenado para $clean", e)
            null
        }
    }

    /**
     * Exporta o cofre em formato texto JSON seguro para arquivo local (.backup).
     */
    fun serializeVaultToJson(vault: EncryptedVaultPackage): String {
        return json.encodeToString(EncryptedVaultPackage.serializer(), vault)
    }

    /**
     * Importa um cofre a partir de um JSON ou arquivo (.backup).
     */
    fun deserializeVaultFromJson(jsonString: String): EncryptedVaultPackage? {
        return try {
            json.decodeFromString(EncryptedVaultPackage.serializer(), jsonString.trim())
        } catch (e: Exception) {
            SafeLog.e(TAG, "Falha ao desserializar arquivo de cofre", e)
            null
        }
    }
}
