package br.com.pessoasaqui

import br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.MessageType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ChatMessagePersistenceTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var persistenceManager: ChatMessagePersistenceManager

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("test_app_files")
        persistenceManager = ChatMessagePersistenceManager(baseDir = baseDir)
    }

    @Test
    fun testSerializationAndDeserializationIntegrity() {
        val original = ChatMessage(
            id = "msg-12345",
            senderId = "peer-mariana",
            senderAlias = "Mariana",
            text = "Relatório de projeto em PDF",
            timestampMs = 1727376000000L,
            isLocalOnly = false,
            isEncrypted = true,
            isFromMe = false,
            messageType = MessageType.DOCUMENT,
            audioDurationSeconds = 0,
            mediaBase64 = "JVBERi0xLjQKJ...",
            fileName = "relatorio_projeto.pdf",
            fileSizeBytes = 2048576L
        )

        val json = ChatMessagePersistenceManager.serializeMessage(original)
        val deserialized = ChatMessagePersistenceManager.deserializeMessage(json)

        assertEquals("ID deve coincidir", original.id, deserialized.id)
        assertEquals("senderId deve coincidir", original.senderId, deserialized.senderId)
        assertEquals("senderAlias deve coincidir", original.senderAlias, deserialized.senderAlias)
        assertEquals("text deve coincidir", original.text, deserialized.text)
        assertEquals("timestampMs deve coincidir", original.timestampMs, deserialized.timestampMs)
        assertEquals("isLocalOnly deve coincidir", original.isLocalOnly, deserialized.isLocalOnly)
        assertEquals("isEncrypted deve coincidir", original.isEncrypted, deserialized.isEncrypted)
        assertEquals("isFromMe deve coincidir", original.isFromMe, deserialized.isFromMe)
        assertEquals("messageType deve coincidir", MessageType.DOCUMENT, deserialized.messageType)
        assertEquals("mediaBase64 deve coincidir", original.mediaBase64, deserialized.mediaBase64)
        assertEquals("fileName deve coincidir", original.fileName, deserialized.fileName)
        assertEquals("fileSizeBytes deve coincidir", original.fileSizeBytes, deserialized.fileSizeBytes)
    }

    @Test
    fun testSaveAndLoadPrivateChat() {
        val peerId = "marcos-mecanico"
        val messages = listOf(
            ChatMessage(
                id = "m1",
                senderId = "me",
                senderAlias = "Eu",
                text = "Olá Marcos!",
                isFromMe = true
            ),
            ChatMessage(
                id = "m2",
                senderId = peerId,
                senderAlias = "Marcos",
                text = "Olá! Como vai?",
                isFromMe = false
            ),
            ChatMessage(
                id = "m3",
                senderId = peerId,
                senderAlias = "Marcos",
                text = "Segue o orçamento em XML",
                messageType = MessageType.DOCUMENT,
                fileName = "orcamento.xml",
                mediaBase64 = "PD94bWwgdmVyc2lvbj0iMS4wIj8+",
                fileSizeBytes = 4096L,
                isFromMe = false
            )
        )

        persistenceManager.savePrivateChat(peerId, messages)

        // Carrega do disco como se o app tivesse sido reaberto
        val loaded = persistenceManager.loadAllPrivateChats()
        assertTrue("Deve conter o chat de Marcos", loaded.containsKey(peerId))
        val loadedList = loaded[peerId] ?: emptyList()
        assertEquals("Deve conter 3 mensagens", 3, loadedList.size)
        assertEquals("Mensagem 1 texto", "Olá Marcos!", loadedList[0].text)
        assertEquals("Mensagem 2 texto", "Olá! Como vai?", loadedList[1].text)
        assertEquals("Mensagem 3 tipo DOCUMENT", MessageType.DOCUMENT, loadedList[2].messageType)
        assertEquals("Mensagem 3 nome do arquivo", "orcamento.xml", loadedList[2].fileName)
    }

    @Test
    fun testSaveAndLoadLocalMural() {
        val muralMessages = listOf(
            ChatMessage(id = "l1", senderId = "carlos", senderAlias = "Carlos", text = "Bom dia a todos no raio de 10m!", isLocalOnly = true),
            ChatMessage(id = "l2", senderId = "me", senderAlias = "Eu", text = "Bom dia Carlos!", isLocalOnly = true, isFromMe = true)
        )

        persistenceManager.saveLocalMural(muralMessages)

        val loaded = persistenceManager.loadLocalMural()
        assertEquals("Deve carregar 2 mensagens do mural", 2, loaded.size)
        assertEquals("Texto 1", "Bom dia a todos no raio de 10m!", loaded[0].text)
        assertEquals("Texto 2", "Bom dia Carlos!", loaded[1].text)

        // Limpa mural
        persistenceManager.clearLocalMural()
        val emptyLoaded = persistenceManager.loadLocalMural()
        assertTrue("Mural limpo deve retornar vazio", emptyLoaded.isEmpty())
    }
}
