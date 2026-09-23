package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.PinSecurityManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class PinSecurityManagerTest {

    private lateinit var pinManager: PinSecurityManager

    @Before
    fun setUp() {
        pinManager = PinSecurityManager()
        pinManager.registerUserPin("2B5C") // PIN cadastrado pelo usuário
    }

    @Test
    fun testCorrectPinSucceeds() {
        val result = pinManager.verifyPinForRecovery("session-1", "2B5C")
        assertTrue(result.isSuccess)
    }

    @Test
    fun testProgressiveLockoutLadder() {
        val session = "session-test-ladder"

        // 1ª tentativa errada: sem bloqueio
        val res1 = pinManager.verifyPinForRecovery(session, "WRONG1")
        assertTrue(res1.isFailure)
        var state = pinManager.getLockoutState(session)
        assertFalse("1ª tentativa não deve bloquear", state.isLocked)
        assertEquals(1, state.attemptCount)

        // 2ª tentativa errada: sem bloqueio
        val res2 = pinManager.verifyPinForRecovery(session, "WRONG2")
        assertTrue(res2.isFailure)
        state = pinManager.getLockoutState(session)
        assertFalse("2ª tentativa não deve bloquear", state.isLocked)
        assertEquals(2, state.attemptCount)

        // 3ª tentativa errada: bloqueio de 5 horas
        val res3 = pinManager.verifyPinForRecovery(session, "WRONG3")
        assertTrue(res3.isFailure)
        state = pinManager.getLockoutState(session)
        assertTrue("3ª tentativa deve bloquear", state.isLocked)
        assertEquals(3, state.attemptCount)
        assertEquals(5L * 3600L, pinManager.calculateLockoutDurationSeconds(3))

        // Validação da escada completa
        assertEquals(10L * 3600L, pinManager.calculateLockoutDurationSeconds(4)) // 4ª: 10h
        assertEquals(24L * 3600L, pinManager.calculateLockoutDurationSeconds(5)) // 5ª: 24h
        assertEquals(48L * 3600L, pinManager.calculateLockoutDurationSeconds(6)) // 6ª: 48h
        assertEquals(7L * 24L * 3600L, pinManager.calculateLockoutDurationSeconds(7)) // 7ª: 7 dias
        assertEquals(15L * 24L * 3600L, pinManager.calculateLockoutDurationSeconds(8)) // 8ª: 15 dias
        assertEquals(30L * 24L * 3600L, pinManager.calculateLockoutDurationSeconds(9)) // 9ª: 30 dias
    }

    @Test
    fun testSessionIsolation_DeviceIsolation() {
        // Regra 24: Uma tentativa com erro na sessão A não afeta a sessão B
        val sessionA = "novo-celular-invasor"
        val sessionB = "aparelho-legitimo"

        // Erra na sessão A 3 vezes até bloquear
        pinManager.verifyPinForRecovery(sessionA, "ERR1")
        pinManager.verifyPinForRecovery(sessionA, "ERR2")
        pinManager.verifyPinForRecovery(sessionA, "ERR3")

        val stateA = pinManager.getLockoutState(sessionA)
        assertTrue(stateA.isLocked)

        // A sessão B deve estar 100% limpa e desbloqueada
        val stateB = pinManager.getLockoutState(sessionB)
        assertFalse("Aparelho legítimo não deve ser bloqueado por erros em outro aparelho", stateB.isLocked)
        assertEquals(0, stateB.attemptCount)
    }
}
