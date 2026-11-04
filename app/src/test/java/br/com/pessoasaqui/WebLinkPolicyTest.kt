package br.com.pessoasaqui

import br.com.pessoasaqui.core.link.WebLinkPolicy
import org.junit.Assert.*
import org.junit.Test

class WebLinkPolicyTest {
    @Test fun recognizesWebLinksFromMessageWithoutInternet() {
        val text = "Site https://example.com/p?q=1, loja.com.br e www.youtube.com/watch?v=abc"
        val links = WebLinkPolicy.findWebLinks(text)
        assertEquals(3, links.size)
        assertEquals("https://example.com/p?q=1", links[0].url)
        assertEquals("https://loja.com.br", links[1].url)
        assertEquals("https://www.youtube.com/watch?v=abc", links[2].url)
    }

    @Test fun preservesOriginalTextAndOffsets() {
        val text = "Veja (https://example.com/a_(b)). Depois."
        val links = WebLinkPolicy.findWebLinks(text)
        assertEquals(1, links.size)
        assertEquals("https://example.com/a_(b)", links[0].url)
        assertEquals(links[0].displayText, text.substring(links[0].start, links[0].endExclusive))
    }

    @Test fun doesNotTurnEmailIntoWebsite() {
        assertTrue(WebLinkPolicy.findWebLinks("contato@empresa.com.br").isEmpty())
    }

    @Test fun rejectsUnsafeSchemesAndCredentials() {
        assertNull(WebLinkPolicy.normalizeWebUrl("javascript://alert(1)"))
        assertNull(WebLinkPolicy.normalizeWebUrl("file:///storage/emulated/0/secret"))
        assertNull(WebLinkPolicy.normalizeWebUrl("intent://scan/#Intent"))
        assertNull(WebLinkPolicy.normalizeWebUrl("https://user:pass@example.com"))
    }

    @Test fun acceptsWhatsappAndInstagramWithoutScheme() {
        assertEquals("https://wa.me/5511999999999",
            WebLinkPolicy.normalizeWebUrl("wa.me/5511999999999"))
        assertEquals("https://instagram.com/exemplo",
            WebLinkPolicy.normalizeWebUrl("instagram.com/exemplo"))
    }

    @Test fun neverLinkifiesFragmentsOfUnsupportedSchemes() {
        assertTrue(WebLinkPolicy.findWebLinks("file://secret.com").isEmpty())
        assertTrue(WebLinkPolicy.findWebLinks("ftp://example.com").isEmpty())
    }

    @Test fun rejectsWhitespaceInExternalLink() {
        assertNull(WebLinkPolicy.normalizeWebUrl("https://example.com/falso caminho"))
    }
}
