package br.com.pessoasaqui.core.qrcode

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Valida o endereço de um convite ANTES de enviá-lo ao servidor.
 * Um QR não é uma prova de confiança: nunca abrir URLs arbitrárias ou efetuar
 * conexões sem confirmação explícita da pessoa.
 */
object InviteLinkParser {
    data class Invite(
        val id: String,
        val token: String,
        val sender: String,
        val alias: String,
        val signature: String?,
        val expiresAt: Long?
    )

    private val safeIdentity = Regex("^[A-Za-z0-9_-]{8,128}$")
    private val safeInviteId = Regex("^inv_[A-Za-z0-9_-]{8,64}$")
    private val safeToken = Regex("^[A-Za-z0-9_-]{16,128}$")
    private val safeSignature = Regex("^[a-fA-F0-9]{64}$")
    private val allowedHosts = setOf("pessoasaqui3.onrender.com")

    fun parse(value: String): Invite? {
        if (value.isBlank() || value.length > 4096) return null
        val candidate = Regex("""(?:https://[^\s<>"']+|pessoasaqui://[^\s<>"']+)""")
            .find(value)?.value?.trimEnd('.', ',', ';', ')') ?: return null
        return try {
            val uri = URI(candidate)
            val scheme = uri.scheme?.lowercase(java.util.Locale.ROOT)
            val isHttps = scheme == "https" &&
                uri.host?.lowercase(java.util.Locale.ROOT) in allowedHosts &&
                (uri.port == -1 || uri.port == 443) &&
                uri.path == "/invite" && uri.rawUserInfo == null
            val isAppLink = scheme == "pessoasaqui" &&
                uri.host?.lowercase(java.util.Locale.ROOT) == "invite" &&
                uri.port == -1 && (uri.path.isNullOrBlank() || uri.path == "/")
            if (!isHttps && !isAppLink) return null
            if (!uri.rawFragment.isNullOrBlank()) return null
            val params = mutableMapOf<String, String>()
            uri.rawQuery?.split('&')?.forEach { pair ->
                val (rawKey, rawValue) = pair.split('=', limit = 2)
                    .let { it.getOrNull(0).orEmpty() to it.getOrNull(1).orEmpty() }
                val key = URLDecoder.decode(rawKey, StandardCharsets.UTF_8.name())
                val valueDecoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
                if (key in params) return null
                params[key] = valueDecoded
            }
            val id = params["id"] ?: params["inviteId"] ?: return null
            val token = params["token"] ?: return null
            val sender = params["sender"] ?: params["senderIdentity"] ?: return null
            if (!safeInviteId.matches(id) || !safeToken.matches(token) ||
                !safeIdentity.matches(sender)) return null
            val signature = (params["sig"] ?: params["signature"])?.takeIf { it.isNotBlank() }
            if (signature != null && !safeSignature.matches(signature)) return null
            val exp = (params["exp"] ?: params["expiresAt"])?.toLongOrNull()
            if ((params["exp"] != null || params["expiresAt"] != null) && exp == null) return null
            Invite(
                id = id, token = token, sender = sender,
                alias = (params["alias"] ?: params["senderAlias"] ?: "Contato")
                    .take(80).trim().ifEmpty { "Contato" },
                signature = signature,
                expiresAt = exp
            )
        } catch (_: Exception) { null }
    }
}
