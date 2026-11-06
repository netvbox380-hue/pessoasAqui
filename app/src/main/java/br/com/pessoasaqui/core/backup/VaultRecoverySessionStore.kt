package br.com.pessoasaqui.core.backup

import android.content.Context
import br.com.pessoasaqui.core.crypto.LocalDiskCryptoManager
import org.json.JSONObject

/** Sessão efêmera de recuperação, cifrada no Keystore e limitada à janela do QR/LINK. */
class VaultRecoverySessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("pa_recovery_claim_v1", Context.MODE_PRIVATE)
    private val crypto = LocalDiskCryptoManager(context)

    data class Session(val recoveryCode: String, val originalDeviceId: String,
                       val targetIdentity: String, val expiresAtMs: Long)

    fun save(session: Session): Boolean {
        require(session.recoveryCode.matches(Regex("^PA-REC-[A-F0-9]{64}$")))
        require(session.originalDeviceId.matches(Regex("^[A-F0-9]{16}$")))
        require(session.targetIdentity.matches(Regex("^[A-F0-9]{16}$")))
        require(session.expiresAtMs > System.currentTimeMillis())
        val record=JSONObject()
            .put("code",session.recoveryCode)
            .put("device",session.originalDeviceId)
            .put("target",session.targetIdentity)
            .put("expires",session.expiresAtMs)
        return prefs.edit().putString("wrapped",crypto.encrypt(record.toString())).commit()
    }

    fun load(targetIdentity: String): Session? {
        val encrypted=prefs.getString("wrapped",null) ?: return null
        return try {
            val r=JSONObject(crypto.decrypt(encrypted))
            val session=Session(r.getString("code"),r.getString("device"),
                r.getString("target"),r.getLong("expires"))
            if(session.targetIdentity!=targetIdentity ||
                !session.recoveryCode.matches(Regex("^PA-REC-[A-F0-9]{64}$")) ||
                !session.originalDeviceId.matches(Regex("^[A-F0-9]{16}$")) ||
                session.expiresAtMs<=System.currentTimeMillis()) null else session
        } catch (_: Exception) { null }
    }

    fun clear() {
        prefs.edit().remove("wrapped").commit()
    }
}
