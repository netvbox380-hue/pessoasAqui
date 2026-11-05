package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.core.crypto.TrustedIdentityKeys
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.util.Base64

class E2eeCryptoEngineTest {
    private fun setupPeers(aliceId: String, bobId: String): Pair<java.security.KeyPair,java.security.KeyPair> {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val alice = gen.generateKeyPair()
        val bob = gen.generateKeyPair()
        // Modelo de identidades pós-recuperação, previamente certificadas pelo backend.
        // O pinning não aceita IDs arbitrários, nem troca silenciosa de geração.
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(
            aliceId, Base64.getEncoder().encodeToString(alice.public.encoded), 2L))
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(
            bobId, Base64.getEncoder().encodeToString(bob.public.encoded), 2L))
        E2eeCryptoEngine.registerPeerPublicKey(aliceId, Base64.getEncoder().encodeToString(alice.public.encoded))
        E2eeCryptoEngine.registerPeerPublicKey(bobId, Base64.getEncoder().encodeToString(bob.public.encoded))
        return alice to bob
    }

    @Test
    fun testKeyDerivationSymmetry() {
        val aliceId = "A1B2C3D4E5"
        val bobId = "F6G7H8I9J0"

        val keyAliceBob = E2eeCryptoEngine.deriveSharedKey(aliceId, bobId)
        val keyBobAlice = E2eeCryptoEngine.deriveSharedKey(bobId, aliceId)

        assertArrayEquals("Chaves compartilhadas derivadas devem ser estritamente idênticas independente da ordem", keyAliceBob, keyBobAlice)
        assertEquals("Chave derivada deve possuir 256 bits (32 bytes)", 32, keyAliceBob.size)
    }

    @Test
    fun testKeyDerivationWithPrefixAndCaseInsensitive() {
        val alice = "peer-a1b2c3d4e5"
        val bob = "F6G7H8I9J0"

        val key1 = E2eeCryptoEngine.deriveSharedKey(alice, bob)
        val key2 = E2eeCryptoEngine.deriveSharedKey("A1B2C3D4E5", "peer-f6g7h8i9j0")

        assertArrayEquals("Chaves devem ser imunes a prefixo 'peer-' e caixa alta/baixa", key1, key2)
    }

    @Test
    fun testEncryptAndDecryptMessage() {
        val aliceId = "1111111111111111"
        val bobId = "2222222222222222"
        val message = "Olá Bob! Esta mensagem é estritamente confidencial e protegida por AES-256-GCM."

        // Alice criptografa para Bob
        val (alice,bob) = setupPeers(aliceId,bobId)
        val (ciphertext, iv) = E2eeCryptoEngine.encrypt(message, aliceId, bobId, alice.private)

        assertTrue("Novos envios exigem ECDH", ciphertext.startsWith("ENC_ECDH:"))
        assertNotEquals("Texto plano não pode trafegar aberto", message, ciphertext)
        assertTrue("IV não pode ser vazio", iv.isNotBlank())

        // Bob decifra a mensagem recebida de Alice
        val decrypted = E2eeCryptoEngine.decrypt(ciphertext, iv, aliceId, bobId, bob.private)
        assertEquals("Mensagem decifrada deve coincidir perfeitamente com o texto original", message, decrypted)
    }

    @Test
    fun testSignalingPayloadEncryptionAndDecryption() {
        val sender = "3333333333333333"
        val recipient = "4444444444444444"
        val callSignal = "[CALL_INIT:true:Alice]"

        val (alice,bob) = setupPeers(sender, recipient)
        val (ciphertext, iv) = E2eeCryptoEngine.encrypt(callSignal, sender, recipient, alice.private)
        val decrypted = E2eeCryptoEngine.decrypt(ciphertext, iv, sender, recipient, bob.private)

        assertEquals("Sinalização de chamada de voz/vídeo deve ser decifrada intacta", callSignal, decrypted)
    }

    @Test
    fun testThirdPartyCannotDecryptWithWrongIdentity() {
        val aliceId = "5555555555555555"
        val bobId = "6666666666666666"
        val eveId = "7777777777777777"
        val secretMessage = "Dados ultrassecretos de localização e fotos privadas."

        val (alice,bob) = setupPeers(aliceId, bobId)
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val eve = gen.generateKeyPair()
        val (ciphertext, iv) = E2eeCryptoEngine.encrypt(secretMessage, aliceId, bobId, alice.private)

        // O envelope cifrado nunca é devolvido ao chamador após falhar a autenticação.
        try {
            E2eeCryptoEngine.decrypt(ciphertext, iv, aliceId, eveId, eve.private)
            fail("Terceiro não autorizado deve receber falha de autenticação")
        } catch (_: SecurityException) {
            // Falha fechada: sem exposição de ENC/ENC_ECDH no histórico.
        }
    }

    @Test
    fun testDamagedCiphertextCannotBecomeChatText() {
        val sender = "8888888888888888"
        val recipient = "9999999999999999"
        val (alice,bob) = setupPeers(sender, recipient)
        val (cipher, iv) = E2eeCryptoEngine.encrypt("[CALL_INIT:false:Alice]", sender, recipient, alice.private)
        try {
            E2eeCryptoEngine.decrypt(cipher, "invalid-IV", sender, recipient, bob.private)
            fail("Uma falha de IV não pode devolver ENC como texto")
        } catch (_: SecurityException) {
            // O aplicativo deve ignorar o comando antes de persistir uma bolha.
        }
    }

    @Test
    fun testNewMessageCannotFallBackToPredictableKey() {
        try {
            E2eeCryptoEngine.encrypt("Segredo", "UNKNOWN_SENDER_NEW", "UNKNOWN_RECIPIENT_NEW")
            fail("Envio E2EE sem chave compartilhada deve ser bloqueado")
        } catch (_: IllegalStateException) {}
    }

    @Test
    fun testPlaintextFallbackWhenNotEncrypted() {
        val rawMessage = "Mensagem legado em texto claro"
        try {
            E2eeCryptoEngine.decrypt(rawMessage, "", "A", "B")
            fail("Mensagem de chat sem criptografia não deve ser aceita")
        } catch (_: SecurityException) {
            // Entrada não cifrada deve ser rejeitada, nunca exibida como mensagem privada.
        }
    }
}
