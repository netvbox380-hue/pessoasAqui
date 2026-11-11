package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import org.junit.Assert.*
import org.junit.Test

/**
 * Pure JVM safety checks. Transient Android Keystore recovery is exercised
 * separately on an instrumented device with an existing encrypted key.
 */
class CryptoIdentityRecoverySafetyTest {
    @Test
    fun identitySignsAndVerifiesWithItsOriginalKey() {
        val identity = CryptoIdentityManager()
        val originalId = identity.getTechnicalIdentity()
        val originalPublicKey = identity.getPublicKeyBase64()
        val challenge = "identity-proof".toByteArray(Charsets.UTF_8)

        val signature = identity.signChallenge(challenge)

        assertTrue(identity.verifySignature(challenge, signature, originalPublicKey))
        assertEquals(originalId, identity.getTechnicalIdentity())
        assertEquals(originalPublicKey, identity.getPublicKeyBase64())
    }

    @Test
    fun unavailableKeyNeverGeneratesReplacementOrSigns() {
        val identity = CryptoIdentityManager()
        val originalId = identity.getTechnicalIdentity()
        val originalPublicKey = identity.getPublicKeyBase64()
        val field = CryptoIdentityManager::class.java.getDeclaredField("activeKeyPair")
        field.isAccessible = true
        field.set(identity, null) // Simulates absent in-memory private material.

        assertNull(identity.getActivePrivateKey())
        try {
            identity.signChallenge("test".toByteArray(Charsets.UTF_8))
            fail("Missing private key must not produce an identity proof")
        } catch (expected: SecurityException) {
            assertTrue(expected.message.orEmpty().contains("Não foi possível assinar"))
        }
        assertEquals(originalId, identity.getTechnicalIdentity())
        assertEquals(originalPublicKey, identity.getPublicKeyBase64())
        assertNull(identity.getActiveKeyPair())
    }
}
