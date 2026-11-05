package br.com.pessoasaqui.core.crypto

import org.json.JSONObject
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Prova de posse da chave EC do aparelho para criação/aceitação de convites.
 * Sem assinatura válida, NUNCA enviar requisição de vínculo ao servidor.
 */
data class InviteIdentityProof(
    val v: Int = 1,
    val publicKey: String,
    val timestamp: Long,
    val nonce: String,
    val signature: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("v", v)
        put("publicKey", publicKey)
        put("timestamp", timestamp)
        put("nonce", nonce)
        put("signature", signature)
    }

    companion object {
        fun tokenDigest(token: String): String =
            MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        /** SHA-256 de campos com limites explícitos para evitar substituições. */
        fun digestParts(vararg parts: String): String = tokenDigest(
            parts.joinToString("|") { "${it.length}:${it}" }
        )

        fun canonical(
            operation: String, identity: String, alias: String, inviteId: String,
            tokenHash: String, timestamp: Long, nonce: String
        ): String {
            val parts = listOf(
                "PA-INVITE-AUTH-V1", operation, identity.uppercase(Locale.ROOT),
                alias, inviteId, tokenHash, timestamp.toString(), nonce
            )
            return parts.joinToString("|") { "${it.length}:${it}" }
        }

        fun create(
            operation: String, identity: String, alias: String,
            publicKeyBase64: String, signChallenge: (ByteArray) -> String,
            inviteId: String = "", token: String = "", signedHash: String? = null
        ): InviteIdentityProof {
            require(operation in setOf("CREATE", "REDEEM",
                "RECOVERY_DELEGATE", "RECOVERY_ISSUE", "RECOVERY_CLAIM", "RECOVERY_REVOKE",
                "OFFER_PUBLISH", "OFFER_DELETE", "CONNECTION_MARK", "CONNECTION_UNMARK", "IDENTITY_REGISTER", "MESSAGE_SEND", "MESSAGE_PENDING", "PRESENCE_HEARTBEAT"))
            val timestamp = System.currentTimeMillis()
            val nonce = UUID.randomUUID().toString()
            val hash = signedHash ?: if (operation in setOf("REDEEM",
                    "RECOVERY_DELEGATE", "RECOVERY_CLAIM",
                    "OFFER_PUBLISH", "OFFER_DELETE")) tokenDigest(token) else ""
            val payload = canonical(
                operation, identity.removePrefix("peer-").trim(), alias,
                inviteId, hash, timestamp, nonce
            )
            val signature = signChallenge(payload.toByteArray(Charsets.UTF_8))
            if (signature.isBlank() || signature.startsWith("sig-")) {
                throw IllegalStateException("Chave privada indisponível. Convite não autorizado.")
            }
            return InviteIdentityProof(
                publicKey = publicKeyBase64,
                timestamp = timestamp,
                nonce = nonce,
                signature = signature
            )
        }
    }
}
