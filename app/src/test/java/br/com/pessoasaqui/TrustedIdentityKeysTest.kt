package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.TrustedIdentityKeys
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64

class TrustedIdentityKeysTest {
    private fun ec() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun selfCertifiedFirstContactRequiresMatchingP256Key() {
        val pair = ec()
        val pub = pair.public.encoded
        val identity = MessageDigest.getInstance("SHA-256").digest(pub)
            .take(8).joinToString("") { "%02X".format(it.toInt() and 255) }
        assertTrue(TrustedIdentityKeys.isSelfCertified(identity, pub))
        assertTrue(TrustedIdentityKeys.isTrustedPeer(identity, pub))
        assertFalse(TrustedIdentityKeys.isTrustedPeer("FEEDFEEDFEEDFEED", pub))
    }

    @Test
    fun recoveredIdentityRequiresAuthenticatedMonotonicBinding() {
        val id = "ABCDEFABCDEF1234"
        val original = ec().public.encoded
        val recovered = ec().public.encoded
        // A posse de outra chave não permite assinar a identidade original por conta própria.
        assertFalse(TrustedIdentityKeys.isTrustedPeer(id, recovered))
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(id, b64(original), 2L))
        assertTrue(TrustedIdentityKeys.isTrustedPeer(id, original))
        assertFalse(TrustedIdentityKeys.isTrustedPeer(id, recovered))
        assertFalse(TrustedIdentityKeys.acceptAuthenticatedBinding(id, b64(recovered), 2L))
        assertFalse(TrustedIdentityKeys.acceptAuthenticatedBinding(id, b64(recovered), 1L))
        // A geração seguinte somente é entregue após prova da rotação no backend.
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(id, b64(recovered), 3L))
        assertFalse(TrustedIdentityKeys.isTrustedPeer(id, original))
        assertTrue(TrustedIdentityKeys.isTrustedPeer(id, recovered))
    }

    @Test
    fun rejectsInvalidOrForgedBinding() {
        val pair = ec()
        assertFalse(TrustedIdentityKeys.acceptAuthenticatedBinding("NOT-A-HEX-ID", b64(pair.public.encoded), 2))
        assertFalse(TrustedIdentityKeys.acceptAuthenticatedBinding("1122334455667788", "not base64", 2))
        assertFalse(TrustedIdentityKeys.acceptAuthenticatedBinding("1122334455667788", b64(pair.public.encoded), 0))
        assertFalse(TrustedIdentityKeys.acceptAuthenticatedBinding("1122334455667788", b64(pair.public.encoded), 1))
    }
}
