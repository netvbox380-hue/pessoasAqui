package br.com.pessoasaqui.core.qrcode

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * Canonicaliza e valida convites recebidos por QR, texto, clipboard e deep link.
 * Nunca usar QR arbitrário como destino de WebView ou executar conteúdo
 * recebido. A validação criptográfica e uso único acontecem no servidor.
 */
object InviteLinkParser {
    data class Invite(
        val inviteId: String,
        val token: String,
        val senderIdentity: String,
        val senderAlias: String,
        val signature: String?,
        val expiresAt: Long?
    )

    private val allowedHosts = setOf(
        "pessoasaqui3.onrender.com", "pessoasaqui.onrender.com"
    )
    private val linkRegex = Regex("""(?:https://|pessoasaqui://)[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val idRegex = Regex("""inv_[A-Fa-f0-9]{12,48}""")
    private val tokenRegex = Regex("""[A-Fa-f0-9]{32,128}""")
    private val identityRegex = Regex("""[A-Za-z0-9_-]{4,128}""")
    private val signatureRegex = Regex("""[A-Fa-f0-9]{64}""")

    fun parse(input: String, nowMs: Long = System.currentTimeMillis()): Invite? {
        if (input.isBlank() || input.length > 8_192) return null
        val trimmed = input.trim()
        val extracted = linkRegex.find(trimmed)?.value ?: return null
        val rawUrl = extracted.trimEnd('.', ',', ';', '!', ')', ']', '}')
        if (rawUrl.length !in 40..4_096) return null
        return try {
            val uri = URI(rawUrl)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
            when (scheme) {
                "https" -> {
                    if (uri.host?.lowercase(Locale.ROOT) !in allowedHosts ||
                        uri.rawUserInfo != null ||
                        uri.port !in setOf(-1, 443) ||
                        uri.path != "/invite") return null
                }
                "pessoasaqui" -> {
                    if (uri.host != "invite" || uri.path.orEmpty() !in setOf("", "/") ||
                        uri.port != -1 || uri.rawUserInfo != null) return null
                }
                else -> return null
            }
            if (uri.fragment != null || uri.rawQuery.isNullOrBlank()) return null

            val params = mutableMapOf<String, String>()
            for (part in uri.rawQuery.split('&')) {
                if (part.isBlank()) continue
                val key = URLDecoder.decode(part.substringBefore('='), "UTF-8")
                val value = URLDecoder.decode(part.substringAfter('=', ""), "UTF-8")
                if (key in params) return null // Ambiguidade de parâmetros = rejeitar
                params[key] = value
            }
            val id = params["id"] ?: params["inviteId"] ?: return null
            val token = params["token"] ?: return null
            val sender = params["sender"] ?: params["senderIdentity"] ?: return null
            if (!idRegex.matches(id) || !tokenRegex.matches(token) ||
                !identityRegex.matches(sender)) return null
            val alias = (params["alias"] ?: params["senderAlias"] ?: "Pessoa Convidante")
                .trim().take(64)
                .filter { !Character.isISOControl(it) }
                .ifBlank { "Pessoa Convidante" }
            val signature = params["sig"] ?: params["signature"]
            if (signature != null && !signatureRegex.matches(signature)) return null
            val rawExpiry = params["exp"] ?: params["expiresAt"]
            val expiry = rawExpiry?.toLongOrNull()
            if (rawExpiry != null && (expiry == null || expiry <= nowMs ||
                    expiry > nowMs + 90L * 24 * 60 * 60 * 1000)) return null
            Invite(id, token, sender, alias, signature, expiry)
        } catch (_: Exception) {
            null
        }
    }
}
