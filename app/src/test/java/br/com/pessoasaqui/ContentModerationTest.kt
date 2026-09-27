package br.com.pessoasaqui

import br.com.pessoasaqui.core.moderation.ContentModerationManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ContentModerationTest {

    private lateinit var moderation: ContentModerationManager

    @Before
    fun setUp() {
        moderation = ContentModerationManager()
    }

    @Test
    fun testValidContentAllowed() {
        val result = moderation.evaluateContent("user-1", "Olá! Tudo bem? Alguém por aqui?")
        assertTrue(result.isAllowed)
        assertNull(result.reason)
    }

    @Test
    fun testZeroToleranceTriggered() {
        val result = moderation.evaluateContent("user-malicioso", "Tentativa de CSAM proibido")
        assertFalse("Conteúdo de tolerância zero deve ser bloqueado imediatamente", result.isAllowed)
        assertTrue(result.isZeroTolerance)
        assertTrue(moderation.isUserBlocked("user-malicioso"))
    }

    @Test
    fun testProgressiveAbuseStrikes() {
        val userId = "spammer-1"

        // 1ª violação: bloqueia e avisa
        val res1 = moderation.evaluateContent(userId, "golpe pix aqui")
        assertFalse(res1.isAllowed)
        assertFalse(moderation.isUserBlocked(userId)) // Ainda não banido

        // 2ª violação:
        val res2 = moderation.evaluateContent(userId, "cartao clonado")
        assertFalse(res2.isAllowed)

        // 3ª violação: banimento
        val res3 = moderation.evaluateContent(userId, "outro golpe pix")
        assertFalse(res3.isAllowed)
        assertTrue("Após 3 avisos deve ser bloqueado permanentemente", moderation.isUserBlocked(userId))
    }

    @Test
    fun testReservedAliasesBlocked() {
        assertTrue(moderation.isReservedAlias("Suporte"))
        assertTrue(moderation.isReservedAlias("Suporte Oficial"))
        assertTrue(moderation.isReservedAlias("Admin_123"))
        assertTrue(moderation.isReservedAlias("pessoasaqui_ajuda"))
        assertTrue(moderation.isReservedAlias("Moderação"))
        
        // Nomes legítimos permitidos
        assertFalse(moderation.isReservedAlias("Maria Silva"))
        assertFalse(moderation.isReservedAlias("Carlos Tech"))
        assertFalse(moderation.isReservedAlias("João"))
    }

    @Test
    fun testPhishingKeywordsBlocked() {
        val res1 = moderation.evaluateContent("golpista", "Olá, envie suas 12 palavras para validação da conta")
        assertFalse(res1.isAllowed)
        assertTrue(res1.isPhishingAlert)
        assertTrue(res1.reason?.contains("phishing") == true)

        val res2 = moderation.evaluateContent("golpista", "Sou do suporte oficial, passe seu pin")
        assertFalse(res2.isAllowed)
        assertTrue(res2.isPhishingAlert)
    }
}
