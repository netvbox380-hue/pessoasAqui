package br.com.pessoasaqui

import br.com.pessoasaqui.core.chat.ConversationPriority
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.*
import org.junit.Test

class ConversationPriorityTest {
    private fun person(id: String) = NearbyPerson(
        id = id, technicalIdentityHash = id, alias = id,
        estimatedDistanceMeters = 0.0, proximityLabel = "Permanente",
        isMutualConnection = true
    )
    private fun message(id: String, timestamp: Long, star: Boolean = false,
                        read: Boolean = true, deleted: Boolean = false,
                        fromMe: Boolean = false, text: String = "mensagem") = ChatMessage(
        id = id, senderId = "user", senderAlias = "Pessoa", text = text,
        timestampMs = timestamp, isImportant = star, isRead = read,
        isDeleted = deleted, isFromMe = fromMe
    )

    @Test fun starredMessagesComeFirstEvenWhenConversationIsOlder() {
        val starred = person("A")
        val unread = person("B")
        val read = person("C")
        val snapshot = ConversationPriority.build(listOf(read, unread, starred), mapOf(
            "peer-A" to listOf(message("a-1", 10, star = true)),
            "B" to listOf(message("b-1", 90, read = false)),
            "C" to listOf(message("c-1", 100))
        ))
        assertEquals(listOf("A", "B", "C"), snapshot.orderedConversations.map { it.contact.id })
        assertEquals(listOf("a-1"), snapshot.highlights.map { it.message.id })
        assertEquals(1, snapshot.orderedConversations[1].unreadCount)
    }

    @Test fun excludesDeletedAndEncryptedMessagesAndTemporaryPeers() {
        val permanent = person("PERM")
        val temporary = permanent.copy(id = "TEMP", technicalIdentityHash = "TEMP",
            isMutualConnection = false)
        val snapshot = ConversationPriority.build(listOf(permanent, temporary), mapOf(
            "PERM" to listOf(message("good", 1, star = true),
                message("deleted", 2, star = true, deleted = true),
                message("cipher", 3, star = true, text = "ENC_ECDH:abc")),
            "TEMP" to listOf(message("ephemeral", 100, star = true))
        ))
        assertEquals(listOf("good"), snapshot.highlights.map { it.message.id })
        assertEquals(1, snapshot.orderedConversations.size)
    }

    @Test fun duplicatePeerKeysDoNotDuplicateBookmarks() {
        val p = person("A1")
        val one = message("m", 100, star = true)
        val snapshot = ConversationPriority.build(listOf(p, p), mapOf(
            "A1" to listOf(one),
            "peer-A1" to listOf(one)
        ))
        assertEquals(1, snapshot.highlights.size)
        assertEquals(1, snapshot.orderedConversations.size)
    }

    @Test fun systemCallEventsDoNotRaiseUnreadCounter() {
        val p = person("C1")
        val system = message("call", 90, read = false)
            .copy(messageType = br.com.pessoasaqui.domain.model.MessageType.SYSTEM,
                  text = "📞 Chamada de voz perdida")
        val result = ConversationPriority.build(listOf(p), mapOf("C1" to listOf(system)))
        assertEquals(0, result.orderedConversations.single().unreadCount)
    }

    @Test fun anUnstarredMessageIsImmediatelyRemovedFromHighlights() {
        val p = person("A1")
        val original = message("m", 100, star = true)
        assertEquals(1, ConversationPriority.build(listOf(p), mapOf("A1" to listOf(original))).highlights.size)
        assertTrue(ConversationPriority.build(listOf(p),
            mapOf("A1" to listOf(original.copy(isImportant = false)))).highlights.isEmpty())
    }
}
