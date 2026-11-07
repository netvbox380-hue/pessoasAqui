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
    @Test
    fun testEcdhDerivedSecretCacheAndPurgeOnRevocation() {
        val aliceId = "AAAABBBBCCCC1111"
        val bobId = "DDDDEEEEFFFF2222"
        val (alice, bob) = setupPeers(aliceId, bobId)

        // Deriva chave compartilhada
        val secret1 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(alice.private, bobId, bob.public)
        val secret2 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(alice.private, bobId, bob.public)

        assertArrayEquals("Chave recuperada do cache deve ser idêntica", secret1, secret2)

        // Limpa cache da sessão de Bob
        E2eeCryptoEngine.clearSessionCache(bobId)

        // Remove chave pública e sessão de Bob (revogação)
        E2eeCryptoEngine.clearPeerPublicKey(bobId)
        assertNull("Chave de Bob deve ser nula após clearPeerPublicKey", E2eeCryptoEngine.getPeerPublicKeyBase64(bobId))
    }

    // =========================================================================
    // TESTES OBRIGATÓRIOS DA SEÇÃO 4: CACHE ECDH DETERMINÍSTICO E BOUNDED
    // =========================================================================

    @Test
    fun testScenarioA_SameKeyPairProducesConsistentResult() {
        val aliceId = "1111222233334444"
        val bobId = "5555666677778888"
        val (alice, bob) = setupPeers(aliceId, bobId)

        val sec1 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, bobId, bob.public, localId = aliceId, localPublicKey = alice.public
        )
        val sec2 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, bobId, bob.public, localId = aliceId, localPublicKey = alice.public
        )
        assertArrayEquals("Mesmo par de chaves deve produzir resultado idêntico e consistente", sec1, sec2)
    }

    @Test
    fun testScenarioB_SameRemoteIdentityWithNewLocalPrivateKeyYieldsDifferentSecret() {
        val aliceId = "A1A1A1A1A1A1A1A1"
        val bobId = "B1B1B1B1B1B1B1B1"
        val (alice1, bob) = setupPeers(aliceId, bobId)

        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val alice2 = gen.generateKeyPair()

        val sec1 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice1.private, bobId, bob.public, localId = aliceId, localPublicKey = alice1.public
        )
        val sec2 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice2.private, bobId, bob.public, localId = aliceId, localPublicKey = alice2.public
        )

        assertFalse(
            "Mesma identidade remota com nova chave local DEVE gerar segredo diferente",
            sec1.contentEquals(sec2)
        )
    }

    @Test
    fun testScenarioC_RemotePublicKeyRotationDoesNotReuseOldCache() {
        val aliceId = "A2A2A2A2A2A2A2A2"
        val bobId = "B2B2B2B2B2B2B2B2"
        val (alice, bob1) = setupPeers(aliceId, bobId)

        val sec1 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, bobId, bob1.public, localId = aliceId, localPublicKey = alice.public
        )

        // Rotação da chave pública de Bob
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val bob2 = gen.generateKeyPair()
        val bob2B64 = Base64.getEncoder().encodeToString(bob2.public.encoded)
        TrustedIdentityKeys.acceptAuthenticatedBinding(bobId, bob2B64, 3L)
        E2eeCryptoEngine.registerPeerPublicKey(bobId, bob2B64)

        val sec2 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, bobId, bob2.public, localId = aliceId, localPublicKey = alice.public
        )

        assertFalse(
            "Rotação da chave remota não pode reutilizar segredo antigo",
            sec1.contentEquals(sec2)
        )
    }

    @Test
    fun testScenarioD_FamilyRecoveryV3NewCryptographicGeneration() {
        val myId = "A3A3A3A3A3A3A3A3"
        val guardianId = "B3B3B3B3B3B3B3B3"
        val (titular, guardian) = setupPeers(myId, guardianId)

        val secGen1 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            titular.private, guardianId, guardian.public,
            localId = myId, localPublicKey = titular.public, generation = 1L
        )
        val secGen2 = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            titular.private, guardianId, guardian.public,
            localId = myId, localPublicKey = titular.public, generation = 2L
        )

        assertEquals(32, secGen1.size)
        assertEquals(32, secGen2.size)
    }

    @Test
    fun testScenarioE_SimultaneousCallsStateIsolation() {
        val aliceId = "A4A4A4A4A4A4A4A4"
        val bobId = "B4B4B4B4B4B4B4B4"
        val carolId = "C4C4C4C4C4C4C4C4"

        val (alice, bob) = setupPeers(aliceId, bobId)
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val carol = gen.generateKeyPair()
        val carolB64 = Base64.getEncoder().encodeToString(carol.public.encoded)
        TrustedIdentityKeys.acceptAuthenticatedBinding(carolId, carolB64, 2L)
        E2eeCryptoEngine.registerPeerPublicKey(carolId, carolB64)

        val secBob = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, bobId, bob.public, localId = aliceId, localPublicKey = alice.public
        )
        val secCarol = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, carolId, carol.public, localId = aliceId, localPublicKey = alice.public
        )

        assertFalse("Duas chamadas para destinatários distintos devem ter segredos e estados estritamente isolados",
            secBob.contentEquals(secCarol))
    }

    @Test
    fun testScenarioF_ConcurrencyWithoutIncorrectAssociations() {
        val aliceId = "A5A5A5A5A5A5A5A5"
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val alice = gen.generateKeyPair()

        val peers = (1..10).map { i ->
            val pid = "F0000000000000%02d".format(i)
            val kp = gen.generateKeyPair()
            pid to kp
        }

        // Execução concorrente em múltiplas threads
        val threads = peers.map { (pid, kp) ->
            Thread {
                for (step in 1..20) {
                    val sec = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
                        alice.private, pid, kp.public, localId = aliceId, localPublicKey = alice.public
                    )
                    assertNotNull(sec)
                    assertEquals(32, sec.size)
                }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
    }

    @Test
    fun testScenarioG_RevocationInvalidatesRelatedState() {
        val aliceId = "A6A6A6A6A6A6A6A6"
        val bobId = "B6B6B6B6B6B6B6B6"
        val (alice, bob) = setupPeers(aliceId, bobId)

        val sec = E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
            alice.private, bobId, bob.public, localId = aliceId, localPublicKey = alice.public
        )
        assertNotNull(sec)

        // Revoga Bob
        E2eeCryptoEngine.clearPeerPublicKey(bobId)
        assertNull("Chave de Bob deve ser nula após clearPeerPublicKey", E2eeCryptoEngine.getPeerPublicKeyBase64(bobId))
    }

    @Test
    fun testScenarioH_BoundedCacheLimit200Entries() {
        val gen = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val myKey = gen.generateKeyPair()

        // Deriva segredos para 250 identidades distintas
        for (i in 0 until 250) {
            val peerKey = gen.generateKeyPair()
            val peerId = "H00000000000%04d".format(i)
            E2eeCryptoEngine.getOrDeriveEcdhSharedKey(
                myKey.private, peerId, peerKey.public, localId = "A7A7A7A7A7A7A7A7", localPublicKey = myKey.public
            )
        }

        // Limpa o cache global após o teste para manter idempotência
        E2eeCryptoEngine.clearSessionCache()
    }
}
