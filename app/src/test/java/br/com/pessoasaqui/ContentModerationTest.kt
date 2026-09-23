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
}
