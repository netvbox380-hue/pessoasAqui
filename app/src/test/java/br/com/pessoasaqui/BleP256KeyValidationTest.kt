package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.BleCryptoHandshakeEngine
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.fail
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

class BleP256KeyValidationTest {
    @Test
    fun acceptsValidP256HandshakeKey() {
        val pair = BleCryptoHandshakeEngine.generateEphemeralKeyPair()
        assertArrayEquals(pair.public.encoded,
            BleCryptoHandshakeEngine.decodePublicKey(pair.public.encoded).encoded)
    }

    @Test
    fun rejectsOtherEcCurveForHandshake() {
        val generator = KeyPairGenerator.getInstance("EC")
        generator.initialize(ECGenParameterSpec("secp384r1"))
        val badKey = generator.generateKeyPair().public.encoded
        try {
            BleCryptoHandshakeEngine.decodePublicKey(badKey)
            fail("ECDH handshake não deve aceitar curva diferente de P-256")
        } catch (_: IllegalArgumentException) {
            // Rejeição esperada.
        } catch (_: SecurityException) {
            // Rejeição esperada.
        }
    }
}
