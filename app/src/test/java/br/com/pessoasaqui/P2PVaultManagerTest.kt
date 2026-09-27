package br.com.pessoasaqui

import br.com.pessoasaqui.core.backup.P2PVaultManager
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.MessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class P2PVaultManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var vaultDir: File
    private lateinit var vaultManager: P2PVaultManager

    @Before
    fun setup() {
        vaultDir = tempFolder.newFolder("test_vaults")
        vaultManager = P2PVaultManager(injectedVaultDir = vaultDir)
    }

    @Test
    fun testGzipCompressionAndDecompression() {
        val originalText = "Mensagem de teste para o cofre distribuído do PessoasAqui. Repetindo: 1234567890.".repeat(20)
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val compressed = vaultManager.compressGzip(originalBytes)
        assertTrue("Comprimido deve ser menor que o original", compressed.size < originalBytes.size)

        val decompressed = vaultManager.decompressGzip(compressed)
        val decompressedText = String(decompressed, Charsets.UTF_8)
        assertEquals("Texto descompactado deve ser idêntico ao original", originalText, decompressedText)
    }

    @Test
    fun testVaultCreationAndRestorationWithCorrectPin() {
        val myPin = "987654"
        val myHash = "user-alice-pubkey-123"
        val myAlias = "Alice"

        val chatWithBob = listOf(
            ChatMessage(senderId = "me", senderAlias = "Alice", text = "Olá Bob!", isFromMe = true),
            ChatMessage(senderId = "bob", senderAlias = "Bob", text = "Oi Alice, tudo bem?", isFromMe = false)
        )
        val chatWithMaria = listOf(
            ChatMessage(senderId = "me", senderAlias = "Alice", text = "Oi Maria, tudo certo?", isFromMe = true)
        )
        val privateChats = mapOf("bob" to chatWithBob, "maria" to chatWithMaria)

        val mural = listOf(
            ChatMessage(senderId = "someone", senderAlias = "Carlos", text = "Alguém por perto?", isLocalOnly = true)
        )

        // 1. Cria o cofre criptografado
        val vault = vaultManager.createVaultPackage(
            pin = myPin,
            ownerIdentityHash = myHash,
            ownerAlias = myAlias,
            privateChats = privateChats,
            localMural = mural
        )

        assertNotNull("Cofre não pode ser nulo", vault)
        assertEquals("user-alice-pubkey-123", vault.ownerIdentityHash)
        assertEquals("Alice", vault.ownerAlias)
        assertEquals(4, vault.totalMessagesCount) // 2 do Bob + 1 da Maria + 1 do Mural = 4
        assertTrue("Payload deve estar preenchido", vault.encryptedPayloadBase64.isNotBlank())
        assertTrue("SHA256 deve estar calculado", vault.payloadSha256.isNotBlank())

        // 2. Restaura usando o PIN correto
        val restoreResult = vaultManager.restoreFromVaultPackage(vault, myPin)
        assertTrue("Restauração deve ter sucesso", restoreResult.isSuccess)

        val content = restoreResult.getOrThrow()
        assertEquals(2, content.privateChats.size)
        assertEquals(2, content.privateChats["bob"]?.size)
        assertEquals("Olá Bob!", content.privateChats["bob"]?.first()?.text)
        assertEquals(1, content.localMural.size)
        assertEquals("Alguém por perto?", content.localMural.first().text)
    }

    @Test
    fun testVaultRestorationFailsWithIncorrectPin() {
        val myPin = "123456"
        val wrongPin = "000000"

        val privateChats = mapOf(
            "amigo" to listOf(ChatMessage(senderId = "me", senderAlias = "Eu", text = "Segredo!", isFromMe = true))
        )

        val vault = vaultManager.createVaultPackage(
            pin = myPin,
            ownerIdentityHash = "my_hash",
            ownerAlias = "Eu",
            privateChats = privateChats,
            localMural = emptyList()
        )

        val restoreResult = vaultManager.restoreFromVaultPackage(vault, wrongPin)
        assertTrue("Restauração deve falhar com PIN errado", restoreResult.isFailure)
    }

    @Test
    fun testPeerVaultStorageAndAntiRollback() {
        val vaultV1 = vaultManager.createVaultPackage(
            pin = "1234",
            ownerIdentityHash = "peer_joao",
            ownerAlias = "João",
            privateChats = emptyMap(),
            localMural = emptyList()
        ).copy(version = 1)

        val storedV1 = vaultManager.storePeerVault(vaultV1)
        assertTrue("Deve armazenar v1", storedV1)

        val retrievedV1 = vaultManager.getStoredPeerVault("peer_joao")
        assertNotNull(retrievedV1)
        assertEquals(1L, retrievedV1?.version)

        // Tenta armazenar versão mais antiga (rollback) -> deve rejeitar
        val vaultV0 = vaultV1.copy(version = 0)
        val storedV0 = vaultManager.storePeerVault(vaultV0)
        assertFalse("Não deve aceitar versão mais antiga", storedV0)

        // Armazena versão mais nova -> deve aceitar e sobrescrever
        val vaultV2 = vaultV1.copy(version = 2)
        val storedV2 = vaultManager.storePeerVault(vaultV2)
        assertTrue("Deve aceitar versão mais nova", storedV2)

        val retrievedV2 = vaultManager.getStoredPeerVault("peer_joao")
        assertEquals(2L, retrievedV2?.version)
    }

    @Test
    fun testTamperDetectionWithSha256Mismatch() {
        val vault = vaultManager.createVaultPackage(
            pin = "5555",
            ownerIdentityHash = "peer_maria",
            ownerAlias = "Maria",
            privateChats = emptyMap(),
            localMural = emptyList()
        )

        // Adultera o SHA256
        val tamperedVault = vault.copy(payloadSha256 = "adulterado_00000000000000000000000000000000")
        val result = vaultManager.restoreFromVaultPackage(tamperedVault, "5555")
        assertTrue("Restauração deve falhar quando SHA256 foi adulterado", result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("corrompido") == true)
    }
}
