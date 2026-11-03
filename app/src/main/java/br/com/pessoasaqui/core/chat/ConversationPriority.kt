package br.com.pessoasaqui.core.chat

import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson

/**
 * Política determinística e local da aba Conversas.
 *
 * Mensagens marcadas são exibidas primeiro; em seguida conversas não lidas
 * e depois conversas lidas, ordenadas por atividade dentro de cada grupo.
 * Nenhum dado é enviado à nuvem; a política não altera a ordem física do
 * histórico, nem promove sessões BLE temporárias para vínculo permanente.
 */
object ConversationPriority {
    data class Highlight(val contact: NearbyPerson, val message: ChatMessage)
    data class Conversation(
        val contact: NearbyPerson,
        val messages: List<ChatMessage>,
        val importantCount: Int,
        val unreadCount: Int,
        val lastActivityMs: Long
    )

    data class Snapshot(
        val highlights: List<Highlight>,
        val orderedConversations: List<Conversation>
    )

    fun canonicalPeerId(raw: String): String =
        raw.trim().removePrefix("peer-").uppercase(java.util.Locale.ROOT)

    fun build(
        contacts: List<NearbyPerson>,
        privateChats: Map<String, List<ChatMessage>>
    ): Snapshot {
        // Históricos podem ter chaves duplicadas: peer-id, id e hash.
        // Preferir a lista mais completa sem criar lembretes duplicados.
        val chatsByPeer = mutableMapOf<String, List<ChatMessage>>()
        privateChats.forEach { (key, messages) ->
            val id = canonicalPeerId(key)
            if (messages.size > (chatsByPeer[id]?.size ?: -1)) chatsByPeer[id] = messages
        }

        val uniqueContacts = contacts
            .filter { !it.id.startsWith("sim-", ignoreCase = true) &&
                (it.isMutualConnection || it.isFamily) }
            .distinctBy { canonicalPeerId(it.technicalIdentityHash.ifBlank { it.id }) }

        val conversations = uniqueContacts.map { contact ->
            val peerId = canonicalPeerId(contact.technicalIdentityHash.ifBlank { contact.id })
            val messages = chatsByPeer[peerId].orEmpty()
            val valid = messages.filter { !it.isDeleted && !it.text.startsWith("ENC_ECDH:") &&
                !it.text.startsWith("ENC:") }
            Conversation(
                contact = contact,
                messages = messages,
                importantCount = valid.count { it.isImportant },
                unreadCount = valid.count { !it.isFromMe && !it.isRead },
                lastActivityMs = messages.maxOfOrNull { it.timestampMs } ?: 0L
            )
        }

        val highlights = conversations.flatMap { conversation ->
            conversation.messages.asSequence()
                .filter { it.isImportant && !it.isDeleted &&
                    !it.text.startsWith("ENC_ECDH:") && !it.text.startsWith("ENC:") }
                .distinctBy { it.id }
                .map { Highlight(conversation.contact, it) }
                .toList()
        }.sortedWith(compareByDescending<Highlight> { it.message.timestampMs }
            .thenBy { it.contact.alias }
            .thenBy { it.message.id })

        val ordered = conversations.sortedWith(
            compareByDescending<Conversation> { it.importantCount > 0 }
                .thenByDescending { it.unreadCount > 0 }
                .thenByDescending { it.lastActivityMs }
                .thenBy { it.contact.alias }
        )
        return Snapshot(highlights, ordered)
    }
}
