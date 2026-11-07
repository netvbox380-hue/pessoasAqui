package br.com.pessoasaqui.core.crypto

import android.content.Context
import android.content.SharedPreferences
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap

/**
 * Pinning durável das chaves P-256 verificadas pela API autenticada.
 *
 * Sem conexão com a API, identidades autocertificadas continuam válidas no
 * Modo A. Uma identidade recuperada (ID antigo, chave nova) só é reconhecida
 * offline se a geração ativa foi previamente confirmada e persistida por HTTPS.
 * Falha de armazenamento impede a aceitação de uma nova rotação.
 */
object TrustedIdentityKeys {
    private data class Pin(val generation: Long, val spki: String)
    private val pins = ConcurrentHashMap<String, Pin>()
    @Volatile private var prefs: SharedPreferences? = null

    fun initialize(context: Context?) {
        if (context == null) return
        val storage = context.getSharedPreferences("pa_identity_key_pins_v1", Context.MODE_PRIVATE)
        val loaded = mutableMapOf<String, Pin>()
        storage.all.forEach { (key, value) ->
            if (!key.startsWith("pin_") || value !is String) return@forEach
            val id = key.removePrefix("pin_")
            val separator = value.indexOf('|')
            if (!Regex("^[A-F0-9]{16}$").matches(id) || separator <= 0) return@forEach
            val generation = value.substring(0, separator).toLongOrNull() ?: return@forEach
            if (generation > 0) loaded[id] = Pin(generation, value.substring(separator + 1))
        }
        // Não manter pins de outro perfil/contexto ao reinicializar o cache.
        pins.clear()
        pins.putAll(loaded)
        prefs = storage
        // Hidratação durável do E2eeCryptoEngine a partir dos pins autenticados e persistidos
        loaded.forEach { (id, pin) ->
            E2eeCryptoEngine.registerPeerPublicKey(id, pin.spki)
        }
    }

    fun getPinSpki(identity: String): String? = pins[normalized(identity)]?.spki

    private fun normalized(identity: String): String =
        identity.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT)

    private fun validEcKey(keyBytes: ByteArray): Boolean = try {
        val key = KeyFactory.getInstance("EC")
            .generatePublic(X509EncodedKeySpec(keyBytes)) as ECPublicKey
        // P-256 é a única curva suportada no protocolo de identidade.
        val params = AlgorithmParameters.getInstance("EC")
        params.init(ECGenParameterSpec("secp256r1"))
        val p256 = params.getParameterSpec(ECParameterSpec::class.java)
        key.params.curve == p256.curve && key.params.generator == p256.generator &&
            key.params.order == p256.order && key.params.cofactor == p256.cofactor
    } catch (_: Exception) { false }

    fun isSelfCertified(identity: String, keyBytes: ByteArray): Boolean {
        val id = normalized(identity)
        if (!Regex("^[A-F0-9]{16}$").matches(id) || !validEcKey(keyBytes)) return false
        val computed = MessageDigest.getInstance("SHA-256").digest(keyBytes)
            .take(8).joinToString("") { "%02X".format(it.toInt() and 0xff) }
        return computed == id
    }

    fun isTrustedPeer(identity: String, keyBytes: ByteArray): Boolean {
        val id = normalized(identity)
        val pin = pins[id]
        if (pin == null) return isSelfCertified(id, keyBytes)
        return try {
            MessageDigest.isEqual(
                E2eeCryptoEngine.Base64Helper.decode(pin.spki), keyBytes
            ) && validEcKey(keyBytes)
        } catch (_: Exception) { false }
    }

    /**
     * Somente chamar após sucesso de /api/identities/key em HTTPS, assinado
     * com a chave do solicitante; a geração é controlada pelo PostgreSQL.
     */
    @Synchronized
    fun acceptAuthenticatedBinding(identity: String, base64Spki: String, generation: Long): Boolean {
        val id = normalized(identity)
        if (!Regex("^[A-F0-9]{16}$").matches(id) || generation < 1 || generation > 1_000_000)
            return false
        val bytes = try { E2eeCryptoEngine.Base64Helper.decode(base64Spki) }
            catch (_: Exception) { return false }
        if (!validEcKey(bytes) || (generation == 1L && !isSelfCertified(id, bytes))) return false
        val canonical = E2eeCryptoEngine.Base64Helper.encode(bytes)
        val previous = pins[id]
        if (previous != null &&
            (generation < previous.generation ||
                (generation == previous.generation && previous.spki != canonical))) return false
        if (previous?.generation == generation && previous.spki == canonical) {
            E2eeCryptoEngine.registerPeerPublicKey(id, canonical)
            return true
        }
        val storage = prefs
        if (storage != null && !storage.edit()
                .putString("pin_$id", "$generation|$canonical").commit()) return false
        pins[id] = Pin(generation, canonical)
        E2eeCryptoEngine.registerPeerPublicKey(id, canonical)
        return true
    }
}
