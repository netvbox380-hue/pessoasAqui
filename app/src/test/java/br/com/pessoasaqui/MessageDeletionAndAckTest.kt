package br.com.pessoasaqui

import br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager
import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.domain.model.ChatMessage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class MessageDeletionAndAckTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var persistenceManager: ChatMessagePersistenceManager
    private lateinit var proximitySimulator: ProximitySimulator

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("test_deletion_files")
        persistenceManager = ChatMessagePersistenceManager(baseDir = baseDir)
        proximitySimulator = ProximitySimulator(persistenceManager = persistenceManager)
    }

    @Test
    fun testDeleteMessageForMeRemovesMessageLocally() {
        val peerId = "contato-123"
        val msgId = "msg-to-delete"
        proximitySimulator.sendPrivateMessage(peerId, "Mensagem privada teste", "Eu", msgId)

        val chatBefore = proximitySimulator.privateChats.value[peerId]
        assertNotNull("Chat deve existir", chatBefore)
        assertTrue("Mensagem deve estar na lista", chatBefore!!.any { it.id == msgId })

        // Apagar para mim
        val deleted = proximitySimulator.deleteMessageForMe(peerId, msgId)
        assertTrue("Exclusão local deve retornar true", deleted)

        val chatAfter = proximitySimulator.privateChats.value[peerId]
        assertFalse("Mensagem deve ter sido removida da lista local", chatAfter!!.any { it.id == msgId })
    }

    @Test
    fun testDeleteMessageForEveryoneWithin30Minutes() {
        val peerId = "contato-123"
        val msgId = "msg-recent"
        proximitySimulator.sendPrivateMessage(peerId, "Esta mensagem será apagada para todos", "Eu", msgId)

        val deleted = proximitySimulator.deleteMessageForEveryone(peerId, msgId)
        assertTrue("Apagar para todos dentro de 30 min deve retornar true", deleted)

        val chat = proximitySimulator.privateChats.value[peerId]
        val target = chat!!.first { it.id == msgId }
        assertTrue("Flag isDeleted deve ser true", target.isDeleted)
        assertEquals("Texto deve ser o aviso padrão", "🚫 Esta mensagem foi apagada", target.text)
    }

    @Test
    fun testDeleteMessageForEveryoneFailsIfOlderThan30Minutes() {
        val peerId = "contato-123"
        val msgId = "msg-old"
        val fortyMinutesAgo = System.currentTimeMillis() - 40 * 60 * 1000L

        // Injeta mensagem antiga
        val oldMsg = ChatMessage(
            id = msgId,
            senderId = "me",
            senderAlias = "Eu",
            text = "Mensagem antiga",
            timestampMs = fortyMinutesAgo,
            isFromMe = true
        )
        persistenceManager.savePrivateChat(peerId, listOf(oldMsg))
        proximitySimulator.loadPersistedMessages()

        val deleted = proximitySimulator.deleteMessageForEveryone(peerId, msgId)
        assertFalse("Apagar para todos após 30 min NÃO deve ser permitido", deleted)
    }

    @Test
    fun testReceiveMessageDeleteFromPeerUpdatesMessageLocally() {
        val peerId = "contato-remoto"
        val msgId = "msg-remota"

        // Contato enviou uma mensagem
        proximitySimulator.receivePrivateMessage(peerId, "Contato", "Mensagem original", msgId)
        val chatBefore = proximitySimulator.privateChats.value[peerId]
        assertEquals("Mensagem original", chatBefore!!.first().text)
        assertFalse(chatBefore.first().isDeleted)

        // Contato enviou o sinal de exclusão remota
        proximitySimulator.receivePrivateMessage(peerId, "Contato", "[MSG_DELETE:$msgId]", "signal-id")
        val chatAfter = proximitySimulator.privateChats.value[peerId]
        val target = chatAfter!!.first { it.id == msgId }
        assertTrue("isDeleted deve ser true após receber comando remoto", target.isDeleted)
        assertEquals("🚫 Esta mensagem foi apagada", target.text)
    }

    @Test
    fun testMarkMessagesAsReadReturnsFalseWhenNoUnread() {
        val peerId = "contato-teste"
        // Sem mensagens não lidas
        val hadUnread = proximitySimulator.markMessagesAsRead(peerId)
        assertFalse("Não deve marcar nada quando não houver mensagens não lidas", hadUnread)
    }
}
