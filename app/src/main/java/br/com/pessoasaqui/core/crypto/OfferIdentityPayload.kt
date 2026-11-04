package br.com.pessoasaqui.core.crypto

import br.com.pessoasaqui.domain.model.OfferItem

/**
 * Mesmo esquema de assinaturas ECDSA P-256/SHA256 dos convites.
 * Domínio separado para impedir que a assinatura de um convite seja
 * reutilizada para publicar ou apagar uma oferta.
 */
object OfferIdentityPayload {
    fun canonical(offer: OfferItem): String = canonicalFields(
        offer.id, offer.authorId, offer.authorAlias, offer.profession,
        offer.description, offer.imageBase64 ?: "", offer.externalLink ?: ""
    )

    fun canonicalFields(
        id: String, authorId: String, authorAlias: String, profession: String,
        description: String, imageBase64: String, externalLink: String
    ): String {
        val parts = listOf("PA-OFFER-PUBLISH-V1", id, authorId.uppercase(),
            authorAlias, profession, description, imageBase64, externalLink)
        return parts.joinToString("|") { "${it.length}:${it}" }
    }

    fun canonicalDelete(offerId: String) =
        listOf("PA-OFFER-DELETE-V1", offerId)
            .joinToString("|") { "${it.length}:${it}" }
}
