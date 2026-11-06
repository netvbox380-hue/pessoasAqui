package br.com.pessoasaqui.core.backup

import android.content.Context
import br.com.pessoasaqui.core.crypto.LocalDiskCryptoManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine

/** Registro local de parte Shamir no aparelho autorizado; nunca usar storage em claro. */
class FamilyRecoveryShareStore(context: Context) {
    private val prefs = context.getSharedPreferences("pa_family_shares_v1", Context.MODE_PRIVATE)
    private val crypto = LocalDiskCryptoManager(context)

    @Synchronized
    fun store(owner: String, version: Long, payload: String): Boolean {
        require(owner.matches(Regex("^[A-F0-9]{16}$")))
        require(version > 0 && payload.length <= 160)
        val part = E2eeCryptoEngine.Base64Helper.decode(payload)
        require(part.size == RecoveryThresholdCodec.SHARE_SIZE && (part[0].toInt() and 255) > 0)
        val old = prefs.getString(owner, null)
        if (old != null) {
            if (!old.startsWith("ENC_V2:")) return false
            val previous = try { crypto.decrypt(old) } catch (_: Exception) { return false }
            val oldVersion = previous.substringBefore(':').toLongOrNull() ?: return false
            if (oldVersion > version) return false
            if (oldVersion == version) return previous == "$version:$payload"
        }
        val wrapped = crypto.encrypt("$version:$payload")
        return prefs.edit().putString(owner, wrapped).commit()
    }

    @Synchronized
    fun load(owner: String): Pair<Long, String>? {
        if (!owner.matches(Regex("^[A-F0-9]{16}$"))) return null
        val encrypted = prefs.getString(owner, null) ?: return null
        if (!encrypted.startsWith("ENC_V2:")) return null
        val plain = try { crypto.decrypt(encrypted) } catch (_: Exception) { return null }
        val version = plain.substringBefore(':').toLongOrNull() ?: return null
        val payload = plain.substringAfter(':', "")
        if (version <= 0 || payload.length > 160 || payload.isBlank()) return null
        return version to payload
    }
}
