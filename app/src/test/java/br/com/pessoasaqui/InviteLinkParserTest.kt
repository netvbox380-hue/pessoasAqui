package br.com.pessoasaqui

import br.com.pessoasaqui.core.qrcode.InviteLinkParser
import org.junit.Assert.*
import org.junit.Test
import java.net.URLEncoder

class InviteLinkParserTest {
    private val id = "inv_0123456789abcdef01234567"
    private val token = "a".repeat(64)
    private val sender = "A1B2C3D4E5F60708"
    private val url = "https://pessoasaqui3.onrender.com/invite?id=$id&token=$token&sender=$sender&alias=Maria&exp=1794096000000"

    @Test fun validRemoteLinkAndNearbyQrHaveSamePayload() {
        val remote = InviteLinkParser.parse(url)
        val qr = InviteLinkParser.parse(url)
        assertNotNull(remote)
        assertEquals(remote, qr)
        assertEquals("Maria", remote!!.alias)
        assertEquals(id, remote.id)
    }

    @Test fun oldInvitationIdsAndOldTokenLengthsRemainValid() {
        val old = "https://pessoasaqui3.onrender.com/invite?id=inv_0123456789ab&token=${"f".repeat(32)}&sender=$sender"
        assertEquals("inv_0123456789ab", InviteLinkParser.parse(old)?.id)
    }

    @Test fun rejectsPhishingHttpUrlsAndUnexpectedPaths() {
        val bad = listOf(
            url.replace("https://", "http://"),
            url.replace("pessoasaqui3.onrender.com", "evil.example"),
            url.replace("/invite?", "/admin?"),
            url.replace("https://pessoasaqui3.onrender.com", "https://pessoasaqui3.onrender.com.evil.example"),
            url.replace("https://", "https://user:password@"),
            url + "#javascript:alert(1)",
            "https://evil.example/invite?id=$id&token=$token&sender=$sender"
        )
        for (candidate in bad) {
            assertNull("Deve rejeitar origem inválida: $candidate", InviteLinkParser.parse(candidate))
        }
    }

    @Test fun supportsDeepLinkAndEncodesAliasWithoutTrustingIt() {
        val alias = URLEncoder.encode("João da Silva", "UTF-8")
        val deep = "pessoasaqui://invite?id=$id&token=$token&sender=$sender&alias=$alias"
        assertEquals("João da Silva", InviteLinkParser.parse(deep)?.alias)
    }

    @Test fun rejectsMissingTokenMalformedSignatureAndDuplicateParameters() {
        assertNull(InviteLinkParser.parse(url.replace("&token=$token", "")))
        assertNull(InviteLinkParser.parse(url + "&sig=not-valid"))
        assertNull(InviteLinkParser.parse(url + "&token=$token"))
        assertNull(InviteLinkParser.parse(url + "&exp=1794096000000"))
        assertNull(InviteLinkParser.parse("a".repeat(5000)))
    }

    @Test fun acceptsPastedMessageWithOfficialInviteUrl() {
        val pasted = "Olá, segue convite pessoal:\n$url\n"
        assertEquals(id, InviteLinkParser.parse(pasted)?.id)
    }
}
