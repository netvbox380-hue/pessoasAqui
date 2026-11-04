package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.OfferIdentityPayload
import br.com.pessoasaqui.core.crypto.InviteIdentityProof
import org.junit.Assert.*
import org.junit.Test

class OfferIdentityPayloadTest {
    @Test fun signedOfferChangesWithDescriptionAndAuthor() {
        val a=OfferIdentityPayload.canonicalFields(
            "offer-12345678-1234-1234-1234-123456789abc","ABCDEF0123456789",
            "Maria","Padeiro","Pães caseiros","","https://example.com"
        )
        val b=OfferIdentityPayload.canonicalFields(
            "offer-12345678-1234-1234-1234-123456789abc","ABCDEF0123456789",
            "Maria","Padeiro","Descrição falsificada","","https://example.com"
        )
        assertNotEquals(a,b)
        assertNotEquals(InviteIdentityProof.tokenDigest(a),InviteIdentityProof.tokenDigest(b))
    }

    @Test fun deleteSignatureIsSeparatedFromPublish() {
        val id="offer-12345678-1234-1234-1234-123456789abc"
        val del=OfferIdentityPayload.canonicalDelete(id)
        val pub=OfferIdentityPayload.canonicalFields(
            id,"ABCDEF0123456789","Maria","Serviço","Descrição","",""
        )
        assertNotEquals(del,pub)
        assertTrue(del.startsWith("18:PA-OFFER-DELETE-V1"))
    }

    @Test fun canonicalUtf16CodeUnitsMatchNode() {
        val s=OfferIdentityPayload.canonicalFields(
            "offer-12345678-1234-1234-1234-123456789abc",
            "ABCDEF0123456789","Maria","Serviço","🐈","",""
        )
        assertTrue(s.contains("2:🐈"))
    }
}
