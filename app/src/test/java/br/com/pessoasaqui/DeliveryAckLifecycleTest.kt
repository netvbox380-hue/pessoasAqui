package br.com.pessoasaqui

import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.MessageDeliveryStatus
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Validação rigorosa do ciclo de vida dos status de entrega:
 * PENDING -> SENT -> DELIVERED -> READ / FAILED
 */
class DeliveryAckLifecycleTest {

    private val peerId = "A1B2C3D4E5F60708"

    @Test
    fun testModoBInitialMessageStatusIsSent() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)

        val result = repo.sendPrivateMessage(peerId, "Olá em Modo B!")
        assertTrue("Mensagem deve ser aceita para envio", result.isAllowed)

        val messages = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val sentMsg = messages.lastOrNull { it.isFromMe }
        assertNotNull("Deve existir uma mensagem enviada por mim", sentMsg)

        // Status inicial de envio NUNCA pode ser DELIVERED prematuro
        assertEquals(
            "Status inicial deve ser SENT até confirmação criptográfica (ACK)",
            MessageDeliveryStatus.SENT,
            sentMsg!!.deliveryStatus
        )
    }

    @Test
    fun testDeliveryAckTransitionsStatusToDelivered() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)

        val msgId = UUID.randomUUID().toString()
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = "Mensagem com ID rastreável",
            myAlias = "Eu",
            messageId = msgId,
            deliveryStatus = MessageDeliveryStatus.SENT
        )

        var currentMsg = repo.proximitySimulator.privateChats.value[peerId].orEmpty().first { it.id == msgId }
        assertEquals(MessageDeliveryStatus.SENT, currentMsg.deliveryStatus)

        // Simula recebimento de [DELIVERY_ACK:<msgId>] pelo protocolo
        repo.processVerifiedNetworkPayload(peerId, "Contato", "[DELIVERY_ACK:$msgId]", "ack-$msgId")

        currentMsg = repo.proximitySimulator.privateChats.value[peerId].orEmpty().first { it.id == msgId }
        assertEquals(
            "Status da mensagem deve transicionar para DELIVERED após o recebimento do ACK",
            MessageDeliveryStatus.DELIVERED,
            currentMsg.deliveryStatus
        )
    }

    @Test
    fun testReadAckTransitionsStatusToRead() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)

        val msgId = UUID.randomUUID().toString()
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = "Mensagem que será lida",
            myAlias = "Eu",
            messageId = msgId,
            deliveryStatus = MessageDeliveryStatus.DELIVERED
        )

        // Simula confirmação de leitura [READ_ACK]
        repo.processVerifiedNetworkPayload(peerId, "Contato", "[READ_ACK]", "read-ack-01")

        val currentMsg = repo.proximitySimulator.privateChats.value[peerId].orEmpty().first { it.id == msgId }
        assertTrue("Mensagem deve estar marcada como lida (isRead = true)", currentMsg.isRead)
        assertEquals(
            "Status da mensagem deve ser READ após [READ_ACK]",
            MessageDeliveryStatus.READ,
            currentMsg.deliveryStatus
        )
    }

    @Test
    fun testRevokedPeerCannotTriggerIncomingMessage() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)

        // Revoga vínculo mútuo
        repo.revokeMutualLocally(peerId, removeMyMark = true, queueRemoteUnmark = true)

        val countBefore = repo.proximitySimulator.privateChats.value[peerId].orEmpty().size

        // Mensagem de contato revogado deve ser descartada
        repo.processIncomingPayload(peerId, "Contato", "ENC_ECDH:teste", "iv", "revoked-msg")
        repo.processVerifiedNetworkPayload(peerId, "Contato", "Tentativa de envio", "revoked-msg-2")

        val countAfter = repo.proximitySimulator.privateChats.value[peerId].orEmpty().size
        assertEquals("Nenhuma mensagem de contato revogado deve ser aceita", countBefore, countAfter)
    }
}
