package br.com.pessoasaqui

import br.com.pessoasaqui.core.qrcode.InviteLinkParser
import org.junit.Assert.*
import org.junit.Test

class InviteLinkParserTest {
    private val now = 1_800_000_000_000L
    private val token = "abcdef0123456789abcdef0123456789abcdef0123456789"
    private val signature = "a".repeat(64)
    private val url =
        "https://pessoasaqui3.onrender.com/invite?id=inv_1234567890abcdef&token=$token&sender=ABCDEF01&alias=Fernando&sig=$signature&exp=${now + 86_400_000}"

    @Test fun supportsOnePayloadForShareAndQrAndQuotedMessage() {
        val parsed = InviteLinkParser.parse(url, now)
        assertNotNull(parsed)
        assertEquals("Fernando", parsed!!.senderAlias)
        assertEquals("ABCDEF01", parsed.senderIdentity)
        assertEquals(token, parsed.token)
        assertEquals("inv_1234567890abcdef", parsed.inviteId)
        val fromMessage = InviteLinkParser.parse("Convite para você!\n$url\nToque para abrir.", now)
        assertEquals(parsed, fromMessage)
    }

    @Test fun supportsLegacyDeepLinkOnSameServerCredentials() {
        val legacy = url.replace("https://pessoasaqui3.onrender.com/invite", "pessoasaqui://invite")
        assertNotNull(InviteLinkParser.parse(legacy, now))
    }

    @Test fun rejectsUntrustedSitesHttpAndUserinfoTricks() {
        val evil = listOf(
            url.replace("pessoasaqui3.onrender.com", "example.org"),
            url.replace("https://", "http://"),
            url.replace("pessoasaqui3.onrender.com", "pessoasaqui3.onrender.com.evil.org"),
            url.replace("https://", "https://user@"),
            url.replace("/invite?", "/invite/other?")
        )
        evil.forEach { assertNull("URL não confiável: $it", InviteLinkParser.parse(it, now)) }
    }

    @Test fun rejectsExpiredModifiedAndAmbiguousLinks() {
        assertNull(InviteLinkParser.parse(url, now + 2 * 86_400_000))
        assertNull(InviteLinkParser.parse("$url&id=inv_aaaaaaaaaaaa", now))
        assertNull(InviteLinkParser.parse(url.replace(token, "123"), now))
        assertNull(InviteLinkParser.parse(url.replace("&sig=$signature", "&sig=NOTHEX"), now))
    }

    @Test fun missingEssentialCredentialsDoNotTriggerRedemption() {
        assertNull(InviteLinkParser.parse("https://pessoasaqui3.onrender.com/invite", now))
        assertNull(InviteLinkParser.parse(url.replace("&sender=ABCDEF01", ""), now))
        assertNull(InviteLinkParser.parse(url.replace("&token=$token", ""), now))
        assertNull(InviteLinkParser.parse("https://example.org/?id=inv_1234567890abcdef", now))
    }
}
