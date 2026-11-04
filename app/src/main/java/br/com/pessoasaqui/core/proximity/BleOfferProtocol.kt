package br.com.pessoasaqui.core.proximity

import android.util.Base64
import br.com.pessoasaqui.domain.model.OfferItem
import org.json.JSONArray
import org.json.JSONObject

/**
 * Catálogo efêmero de proximidade transmitido no canal BLE GATT criptografado.
 * O payload NÃO contém foto real do perfil, arquivos ou dados GPS.
 * A identidade do autor é o peer autenticado da sessão BLE, nunca um ID do JSON.
 */
object BleOfferProtocol {
    const val PREFIX = "[PA_OFFERS_V1:"
    private const val MAX_BYTES = 3500
    private const val MAX_ITEMS = 3
    private val offerIdPattern = Regex("^offer-[0-9a-fA-F-]{36}$")

    fun isOfferPayload(message: String): Boolean = message.startsWith(PREFIX)

    fun encode(ownerId: String, offers: List<OfferItem>): String? {
        val owner = ownerId.removePrefix("peer-").uppercase()
        if (!Regex("[A-F0-9]{16}").matches(owner)) return null
        val arr = JSONArray()
        offers.filter { it.authorId.removePrefix("peer-").equals(owner, ignoreCase = true) }
            .take(MAX_ITEMS).forEach { offer ->
                if (offer.id.matches(offerIdPattern)) {
                    arr.put(JSONObject().apply {
                        put("id", offer.id)
                        put("alias", offer.authorAlias.take(48))
                        put("profession", offer.profession.take(64))
                        put("description", offer.description.take(280))
                        put("link", (offer.externalLink ?: "").take(180))
                        put("createdAt", offer.createdAtMs)
                        // Foto grande em BLE é insegura para o limite físico de 4096 bytes:
                        // somente os metadados/links são transmitidos.
                    })
                }
            }
        val json = JSONObject().apply {
            put("v", 1)
            put("offers", arr)
        }.toString()
        val payload = PREFIX + Base64.encodeToString(
            json.toByteArray(Charsets.UTF_8),
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ) + "]"
        return payload.takeIf { it.toByteArray(Charsets.UTF_8).size <= MAX_BYTES }
    }

    fun decode(senderIdentity: String, payload: String): List<OfferItem>? {
        if (!isOfferPayload(payload) || !payload.endsWith("]") ||
            payload.toByteArray(Charsets.UTF_8).size > MAX_BYTES) return null
        val sender = senderIdentity.removePrefix("peer-").uppercase()
        if (!Regex("[A-F0-9]{16}").matches(sender)) return null
        return try {
            val decoded = String(Base64.decode(payload.removePrefix(PREFIX).dropLast(1),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING), Charsets.UTF_8)
            val obj = JSONObject(decoded)
            if (obj.optInt("v") != 1) return null
            val arr = obj.optJSONArray("offers") ?: return null
            if (arr.length() > MAX_ITEMS) return null
            (0 until arr.length()).map { i ->
                val entry = arr.getJSONObject(i)
                val id = entry.optString("id")
                val profession = entry.optString("profession")
                val description = entry.optString("description")
                if (!id.matches(offerIdPattern) || profession.isBlank() ||
                    profession.length > 64 || description.isBlank() || description.length > 280)
                    return null
                OfferItem(
                    id = id,
                    authorId = sender,
                    authorAlias = entry.optString("alias").take(48),
                    profession = profession,
                    description = description,
                    distanceMeters = 10.0,
                    proximityLabel = "Descoberto via Bluetooth",
                    createdAtMs = entry.optLong("createdAt", System.currentTimeMillis()),
                    imageBase64 = null,
                    externalLink = entry.optString("link").takeIf { it.isNotBlank() }
                )
            }
        } catch (_: Exception) { null }
    }
}
