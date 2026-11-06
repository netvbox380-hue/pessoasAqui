package br.com.pessoasaqui.core.crypto

import android.content.Context
import br.com.pessoasaqui.domain.model.LockoutState
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Gerencia a segurança do PIN do usuário e a proteção estrita contra força bruta
 * durante o processo de recuperação de identidade.
 *
 * Escada progressiva de bloqueio: 1-2 livre, 3ª=5h, 4ª=10h, 5ª=24h, 6ª=48h, 7ª=7d, 8ª=15d, 9ª=30d, 10ª=cancelada.
 * Regra Crítica: Falhas no novo aparelho NÃO afetam nem desconectam a sessão ativa no aparelho antigo.
 */
class PinSecurityManager(
    private val context: Context? = null
) {
    private val prefs = context?.getSharedPreferences("pessoasaqui_pin_prefs", Context.MODE_PRIVATE)
    private val recoveryAttempts = mutableMapOf<String, RecoveryAttemptTracker>()
    
    // Hash do PIN configurado pelo usuário para sua identidade (salgado e com SHA-256)
    private val diskCrypto = context?.let { LocalDiskCryptoManager(it) }
    private var registeredPinHash: String = prefs?.getString("pin_hash", null) ?: ""
    private var savedVaultPin: String? = null

    data class RecoveryAttemptTracker(
        var failedAttempts: Int = 0,
        var lockedUntilEpochMs: Long = 0L,
        var isPermanentlyCancelled: Boolean = false
    )

    /** V2: criptografa o PIN recuperável em repouso; nunca grava PIN em claro. */
    fun registerUserPin(pin: String) {
        val clean=pin.trim()
        require(clean.length>=4) { "PIN muito curto" }
        val encrypted=if(context!=null) diskCrypto!!.encrypt(clean) else null
        val hash=strongPinHash(clean)
        if(context!=null) {
            val written=prefs?.edit()?.putString("pin_hash",hash)
                ?.putString("vault_pin_encrypted",encrypted)?.commit() ?: false
            if(!written) throw IllegalStateException("Não foi possível persistir PIN cifrado")
        }
        registeredPinHash=hash
        savedVaultPin=clean
    }

    fun getUserPin(): String? {
        savedVaultPin?.let { return it }
        val stored=prefs?.getString("vault_pin_encrypted",null) ?: return null
        return try {
            val clear=if(stored.startsWith("ENC_V1:")||stored.startsWith("ENC_V2:")){
                diskCrypto?.decrypt(stored) ?: return null
            }else{
                val encrypted=diskCrypto?.encrypt(stored) ?: return null
                if(!prefs.edit().putString("vault_pin_encrypted",encrypted).commit())return null
                stored
            }
            savedVaultPin=clear
            clear
        }catch(_:Exception){null}
    }

    private fun strongPinHash(pin:String):String {
        val salt=ByteArray(16).also{SecureRandom().nextBytes(it)}
        val spec=PBEKeySpec(pin.toCharArray(),salt,150_000,256)
        val key=try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}
            finally{spec.clearPassword()}
        fun hex(bytes:ByteArray)=bytes.joinToString(""){"%02x".format(it.toInt() and 0xff)}
        return "pbkdf2v2$"+hex(salt)+"$"+hex(key)
    }

    private fun verifyStrongPin(pin:String,record:String):Boolean {
        val parts=record.split('$')
        if(parts.size!=3||parts[0]!="pbkdf2v2"||parts[1].length!=32||parts[2].length!=64)return false
        return try{
            val salt=parts[1].chunked(2).map{it.toInt(16).toByte()}.toByteArray()
            val expected=parts[2].chunked(2).map{it.toInt(16).toByte()}.toByteArray()
            val spec=PBEKeySpec(pin.toCharArray(),salt,150_000,256)
            val found=try{SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded}
                finally{spec.clearPassword()}
            MessageDigest.isEqual(expected,found)
        }catch(_:Exception){false}
    }

    /**
     * Verifica o PIN já cadastrado sem alterar o registro nem os contadores
     * de recuperação. Usado antes de exportar ou migrar backups existentes.
     * Um PIN diferente nunca deve sobrescrever silenciosamente o PIN antigo.
     */
    fun matchesConfiguredPin(candidate: String): Boolean {
        if (!hasCustomPin() || candidate.length < 4) return false
        return if (registeredPinHash.startsWith("pbkdf2v2$")) {
            verifyStrongPin(candidate, registeredPinHash)
        } else {
            registeredPinHash.isNotBlank() && MessageDigest.isEqual(
                hashPin(candidate).toByteArray(Charsets.UTF_8),
                registeredPinHash.toByteArray(Charsets.UTF_8)
            )
        }
    }

    fun hasCustomPin(): Boolean {
        return prefs?.contains("pin_hash") == true
    }

    /**
     * Calcula o hash do PIN com sal para jamais armazenar o valor em texto puro.
     */
    fun hashPin(pin: String): String {
        val salt = "PA_SALT_2026_"
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest((salt + pin.trim()).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Verifica o estado de bloqueio de uma sessão de recuperação específica.
     */
    fun getLockoutState(recoverySessionId: String): LockoutState {
        val tracker = recoveryAttempts.getOrPut(recoverySessionId) { RecoveryAttemptTracker() }
        val now = System.currentTimeMillis()

        if (tracker.isPermanentlyCancelled) {
            return LockoutState(
                isLocked = true,
                attemptCount = tracker.failedAttempts,
                remainingLockoutSeconds = Long.MAX_VALUE,
                lockoutMessage = "Tentativa de recuperação encerrada definitivamente após 10 erros."
            )
        }

        if (now < tracker.lockedUntilEpochMs) {
            val remainingSec = (tracker.lockedUntilEpochMs - now) / 1000
            return LockoutState(
                isLocked = true,
                attemptCount = tracker.failedAttempts,
                remainingLockoutSeconds = remainingSec,
                lockoutMessage = formatLockoutMessage(tracker.failedAttempts, remainingSec)
            )
        }

        return LockoutState(
            isLocked = false,
            attemptCount = tracker.failedAttempts,
            remainingLockoutSeconds = 0L,
            lockoutMessage = null
        )
    }

    /**
     * Valida a inserção do PIN para uma sessão de recuperação.
     * Retorna Result.success se correto, ou Result.failure com o tempo de bloqueio.
     */
    fun verifyPinForRecovery(
        recoverySessionId: String,
        candidatePin: String
    ): Result<Boolean> {
        val tracker = recoveryAttempts.getOrPut(recoverySessionId) { RecoveryAttemptTracker() }
        val now = System.currentTimeMillis()

        if (tracker.isPermanentlyCancelled) {
            return Result.failure(IllegalStateException("Esta tentativa de recuperação foi encerrada definitivamente."))
        }

        if (now < tracker.lockedUntilEpochMs) {
            val remainingSec = (tracker.lockedUntilEpochMs - now) / 1000
            return Result.failure(IllegalStateException("Aguarde o término do bloqueio de segurança: ${remainingSec}s restantes."))
        }

        val accepted=if(registeredPinHash.startsWith("pbkdf2v2$")){
            verifyStrongPin(candidatePin,registeredPinHash)
        }else registeredPinHash.isNotBlank() &&
            MessageDigest.isEqual(hashPin(candidatePin).toByteArray(),registeredPinHash.toByteArray())
        if(accepted) {
            if(!registeredPinHash.startsWith("pbkdf2v2$"))registerUserPin(candidatePin)
            // Sucesso! Limpa o rastreador dessa tentativa
            recoveryAttempts.remove(recoverySessionId)
            return Result.success(true)
        }

        // Falha no PIN: Incrementa contador de tentativas e aplica a escada da Seção 23
        tracker.failedAttempts += 1
        val lockoutSeconds = calculateLockoutDurationSeconds(tracker.failedAttempts)

        if (tracker.failedAttempts >= 10) {
            tracker.isPermanentlyCancelled = true
            return Result.failure(IllegalStateException("10ª tentativa incorreta: tentativa de recuperação encerrada definitivamente."))
        }

        if (lockoutSeconds > 0) {
            tracker.lockedUntilEpochMs = now + (lockoutSeconds * 1000L)
            val msg = formatLockoutMessage(tracker.failedAttempts, lockoutSeconds)
            return Result.failure(IllegalStateException(msg))
        }

        return Result.failure(IllegalStateException("PIN incorreto. Tentativa ${tracker.failedAttempts} de 10."))
    }

    /**
     * Duração do bloqueio progressivo em segundos:
     * 1ª e 2ª: 0s
     * 3ª: 5h (18.000s)
     * 4ª: 10h (36.000s)
     * 5ª: 24h (86.400s)
     * 6ª: 48h (172.800s)
     * 7ª: 7 dias (604.800s)
     * 8ª: 15 dias (1.296.000s)
     * 9ª: 30 dias (2.592.000s)
     * 10ª: Cancelamento permanente
     */
    fun calculateLockoutDurationSeconds(attempts: Int): Long {
        return when (attempts) {
            1, 2 -> 0L
            3 -> 5L * 3600L
            4 -> 10L * 3600L
            5 -> 24L * 3600L
            6 -> 48L * 3600L
            7 -> 7L * 24L * 3600L
            8 -> 15L * 24L * 3600L
            9 -> 30L * 24L * 3600L
            else -> Long.MAX_VALUE
        }
    }

    private fun formatLockoutMessage(attempts: Int, seconds: Long): String {
        val hours = seconds / 3600
        val days = hours / 24
        val durationStr = when {
            days > 0 -> "$days dia(s)"
            hours > 0 -> "$hours hora(s)"
            else -> "$seconds segundo(s)"
        }
        return "Tentativa $attempts inválida. Novo aparelho bloqueado por $durationStr para proteção contra invasão."
    }
}
