package br.com.pessoasaqui.core.moderation

import java.security.MessageDigest

/**
 * Motor de Moderação de Conteúdo e Proteção da Comunidade:
 * "Liberdade para entrar. Consequências para abuso."
 *
 * Bloqueia conteúdos impróprios, assédio, spam, fraudes e possui tolerância zero
 * para conteúdo sexual envolvendo menores.
 */
class ContentModerationManager {

    private val blockedUsers = mutableSetOf<String>()
    private val strikeCounts = mutableMapOf<String, Int>()

    // Lista de termos e padrões para filtro léxico e heurístico inicial
    private val prohibitedKeywords = listOf(
        "pedofilia", "csam", "estupro", "arma ilegal", "venda de drogas",
        "golpe pix", "cartao clonado", "hackear", "invasao"
    )

    data class ModerationResult(
        val isAllowed: Boolean,
        val reason: String? = null,
        val isZeroTolerance: Boolean = false
    )

    /**
     * Avalia o texto publicado ou enviado.
     */
    fun evaluateContent(authorId: String, text: String): ModerationResult {
        if (blockedUsers.contains(authorId)) {
            return ModerationResult(
                isAllowed = false,
                reason = "Usuário bloqueado por violações reiteradas."
            )
        }

        val lower = text.lowercase()

        // Tolerância Zero (Seção 33)
        if (lower.contains("pedofilia") || lower.contains("csam") || lower.contains("menor sexual")) {
            banUser(authorId)
            return ModerationResult(
                isAllowed = false,
                reason = "Violação crítica de segurança: Conteúdo proibido por lei.",
                isZeroTolerance = true
            )
        }

        // Moderação de termos abusivos/spam
        for (keyword in prohibitedKeywords) {
            if (lower.contains(keyword)) {
                val strikes = strikeCounts.getOrDefault(authorId, 0) + 1
                strikeCounts[authorId] = strikes
                
                if (strikes >= 3) {
                    banUser(authorId)
                    return ModerationResult(
                        isAllowed = false,
                        reason = "Conteúdo impróprio recorrente. Conta suspensa."
                    )
                }

                return ModerationResult(
                    isAllowed = false,
                    reason = "Mensagem bloqueada por conter termos que violam as regras da comunidade (Aviso $strikes/3)."
                )
            }
        }

        return ModerationResult(isAllowed = true)
    }

    fun blockUser(userId: String) {
        blockedUsers.add(userId)
    }

    fun isUserBlocked(userId: String): Boolean {
        return blockedUsers.contains(userId)
    }

    private fun banUser(userId: String) {
        blockedUsers.add(userId)
    }

    /**
     * Gera identificador criptográfico do dispositivo para mitigar evasão de banimentos
     * via simples "desinstalar e reinstalar" (Seção 34).
     */
    fun computeDeviceBanFingerprint(deviceId: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val bytes = md.digest(("PA_BAN_PREV_" + deviceId).toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
