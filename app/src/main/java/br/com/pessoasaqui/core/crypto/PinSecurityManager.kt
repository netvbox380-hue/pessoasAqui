package br.com.pessoasaqui.core.crypto

import br.com.pessoasaqui.domain.model.LockoutState
import java.security.MessageDigest

/**
 * Gerencia a segurança do PIN do usuário e a proteção estrita contra força bruta
 * durante o processo de recuperação ("🔐 Entre na sua").
 *
 * Em conformidade com a Seção 23 e 24 do Prompt-Mestre:
 * - Escada progressiva de bloqueio: 1-2 livre, 3ª=5h, 4ª=10h, 5ª=24h, 6ª=48h, 7ª=7d, 8ª=15d, 9ª=30d, 10ª=cancelada.
 * - Regra Crítica: Falhas no novo aparelho NÃO afetam nem desconectam a sessão ativa no aparelho antigo!
 */
class PinSecurityManager {

    private val recoveryAttempts = mutableMapOf<String, RecoveryAttemptTracker>()
    
    // Hash do PIN configurado pelo usuário para sua identidade (salgado e com SHA-256)
    private var registeredPinHash: String = hashPin("2B5C") // Exemplo padrão do prompt

    data class RecoveryAttemptTracker(
        var failedAttempts: Int = 0,
        var lockedUntilEpochMs: Long = 0L,
        var isPermanentlyCancelled: Boolean = false
    )

    fun registerUserPin(pin: String) {
        registeredPinHash = hashPin(pin)
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

        val candidateHash = hashPin(candidatePin)
        if (candidateHash == registeredPinHash) {
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
     * Duração do bloqueio em segundos conforme Seção 23 do Prompt:
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
