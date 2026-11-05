package br.com.pessoasaqui.core.backup

import android.content.Context
import br.com.pessoasaqui.core.crypto.LocalDiskCryptoManager

/**
 * Apenas um cache local do segredo V3, protegido por Android Keystore.
 * NUNCA replicar este registro junto ao cofre, nem incluir em arquivos exportados.
 * O usuário precisa manter uma cópia independente para recuperação após formatação.
 */
class VaultRecoverySecretStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("pa_vault_recovery_v3", Context.MODE_PRIVATE)
    private val crypto = LocalDiskCryptoManager(context)

    fun hasConfiguredSecret(): Boolean = prefs.contains("wrapped_secret")
    fun load(identity: String): String? {
        val saved = prefs.getString("wrapped_secret", null) ?: return null
        val owner = prefs.getString("owner_identity", null) ?: return null
        if (owner != identity.trim().uppercase(java.util.Locale.ROOT)) return null
        // Falha fechada diante de perda/corrupção de chave do Android Keystore.
        return try {
            val secret = crypto.decrypt(saved)
            secret.takeIf { it.matches(Regex("^PA-VAULT-V3-[0-9A-F]{64}$")) }
        } catch (_: Exception) { null }
    }

    fun enable(identity: String, secret: String): Boolean {
        val owner = identity.trim().uppercase(java.util.Locale.ROOT)
        require(owner.matches(Regex("^[0-9A-F]{16}$"))) { "Identidade inválida" }
        require(secret.matches(Regex("^PA-VAULT-V3-[0-9A-F]{64}$"))) {
            "Segredo de recuperação V3 inválido"
        }
        if (hasConfiguredSecret()) {
            // Não substituir o segredo existente e perder acesso a cofres já distribuídos.
            return load(identity) == secret
        }
        val wrapped = crypto.encrypt(secret)
        return prefs.edit().putString("owner_identity", owner)
            .putString("wrapped_secret", wrapped).commit()
    }
}
