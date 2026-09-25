package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import org.junit.Assert.*
import org.junit.Test

class E2eeCryptoEngineTest {

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
        val aliceId = "USER_ALICE_ID"
        val bobId = "USER_BOB_ID"
        val message = "Olá Bob! Esta mensagem é estritamente confidencial e protegida por AES-256-GCM."

        // Alice criptografa para Bob
        val (ciphertext, iv) = E2eeCryptoEngine.encrypt(message, aliceId, bobId)

        assertTrue("Envelope cifrado deve possuir prefixo ENC:", ciphertext.startsWith("ENC:"))
        assertNotEquals("Texto plano não pode trafegar aberto", message, ciphertext)
        assertTrue("IV não pode ser vazio", iv.isNotBlank())

        // Bob decifra a mensagem recebida de Alice
        val decrypted = E2eeCryptoEngine.decrypt(ciphertext, iv, aliceId, bobId)
        assertEquals("Mensagem decifrada deve coincidir perfeitamente com o texto original", message, decrypted)
    }

    @Test
    fun testSignalingPayloadEncryptionAndDecryption() {
        val sender = "7A8B9C0D"
        val recipient = "1E2F3A4B"
        val callSignal = "[CALL_INIT:true:Alice]"

        val (ciphertext, iv) = E2eeCryptoEngine.encrypt(callSignal, sender, recipient)
        val decrypted = E2eeCryptoEngine.decrypt(ciphertext, iv, sender, recipient)

        assertEquals("Sinalização de chamada de voz/vídeo deve ser decifrada intacta", callSignal, decrypted)
    }

    @Test
    fun testThirdPartyCannotDecryptWithWrongIdentity() {
        val aliceId = "ALICE_999"
        val bobId = "BOB_888"
        val eveId = "EVE_EVIL_777"
        val secretMessage = "Dados ultrassecretos de localização e fotos privadas."

        val (ciphertext, iv) = E2eeCryptoEngine.encrypt(secretMessage, aliceId, bobId)

        // Eve tenta decifrar se passando por Bob ou Alice
        val eveAttempt = E2eeCryptoEngine.decrypt(ciphertext, iv, aliceId, eveId)
        assertNotEquals("Terceiro não autorizado não pode decifrar a mensagem", secretMessage, eveAttempt)
    }

    @Test
    fun testPlaintextFallbackWhenNotEncrypted() {
        val rawMessage = "Mensagem legado em texto claro"
        val decrypted = E2eeCryptoEngine.decrypt(rawMessage, "", "A", "B")
        assertEquals("Mensagens sem prefixo ENC: devem ser mantidas intactas para retrocompatibilidade", rawMessage, decrypted)
    }
}
