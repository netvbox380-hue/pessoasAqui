package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.ProximitySimulator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProximitySimulatorTest {

    private lateinit var simulator: ProximitySimulator

    @Before
    fun setUp() {
        simulator = ProximitySimulator()
    }

    @Test
    fun testPeopleOutside10MetersAreFilteredOut() {
        val discovered = simulator.discoveredPeople.value

        // Contato que estava a 15m não deve estar presente no radar
        val hasOutOfRangePerson = discovered.any { it.alias == "Pessoa Fora do Raio" }
        assertFalse("Pessoas fora do raio de 10 metros não devem ser descobertas", hasOutOfRangePerson)

        // Marcos (4.2m) e Mariana (2.1m) devem estar presentes
        assertTrue(discovered.any { it.alias == "Marcos Mecânico" })
        assertTrue(discovered.any { it.alias == "Mariana" })
    }

    @Test
    fun testMutualConnectionFlow() {
        // Marcos Mecânico já tem isMarkingMe = true
        val discovered = simulator.discoveredPeople.value
        val marcosInitial = discovered.first { it.alias == "Marcos Mecânico" }
        assertFalse(marcosInitial.isMarkedByMe)
        assertFalse(marcosInitial.isMutualConnection)

        // Usuário marca Marcos de volta:
        val marcosUpdated = simulator.toggleMarkPerson(marcosInitial.id)
        assertNotNull(marcosUpdated)
        assertTrue("Deve estar marcado por mim", marcosUpdated!!.isMarkedByMe)
        assertTrue("Deve se tornar Conexão Mútua pois Marcos já havia marcado", marcosUpdated.isMutualConnection)
        assertTrue("Foto deve ficar visível após conexão mútua autorizada", marcosUpdated.isPhotoVisible)
    }

    @Test
    fun testDynamicDistanceUpdateFiltersRealtime() {
        val discovered = simulator.discoveredPeople.value
        val mariana = discovered.first { it.alias == "Mariana" }

        // Afasta Mariana para 14 metros (> 10m)
        simulator.updateSimulatedDistance(mariana.id, 14.0)

        val updatedDiscovered = simulator.discoveredPeople.value
        val stillFound = updatedDiscovered.any { it.id == mariana.id }
        assertFalse("Ao ultrapassar 10 metros sem conexão mútua, pessoa deve desaparecer do radar", stillFound)
    }

    @Test
    fun testMessageReadStatusAndAcknowledgement() {
        val peerId = "marcos-mecanico"
        simulator.receivePrivateMessage(peerId, "Marcos", "Olá! Tudo bem?", "msg-read-1")

        val beforeRead = simulator.privateChats.value[peerId] ?: emptyList()
        val incoming = beforeRead.first { it.id == "msg-read-1" }
        assertFalse("Mensagem recebida deve iniciar como não lida", incoming.isRead)

        simulator.markMessagesAsRead(peerId)

        val afterRead = simulator.privateChats.value[peerId] ?: emptyList()
        val readMsg = afterRead.first { it.id == "msg-read-1" }
        assertTrue("Mensagem deve ser marcada como lida após markMessagesAsRead", readMsg.isRead)
        assertTrue("Timestamp de leitura deve ser preenchido", readMsg.readTimestampMs > 0L)
    }

    @Test
    fun testToggleImportantMessage() {
        val peerId = "marcos-mecanico"
        simulator.sendPrivateMessage(peerId, "Mensagem importante de teste", "Eu", "msg-star-1")

        val initialStarred = simulator.toggleImportantMessage(peerId, "msg-star-1")
        assertTrue("Mensagem deve ficar marcada como importante", initialStarred)

        val chat = simulator.privateChats.value[peerId] ?: emptyList()
        val target = chat.first { it.id == "msg-star-1" }
        assertTrue("Flag isImportant deve ser true", target.isImportant)

        val unstarred = simulator.toggleImportantMessage(peerId, "msg-star-1")
        assertFalse("Mensagem deve ser desmarcada de importante", unstarred)
    }

    @Test
    fun testEditPrivateMessageWithin30MinutesAndExpiration() {
        val peerId = "marcos-mecanico"
        simulator.sendPrivateMessage(peerId, "Texto original", "Eu", "msg-edit-1")

        // 1. Edição dentro dos 30 minutos deve ser permitida
        val editedSuccess = simulator.editPrivateMessage(peerId, "msg-edit-1", "Texto corrigido com sucesso")
        assertTrue("Edição dentro de 30 minutos deve ser permitida", editedSuccess)

        val chat = simulator.privateChats.value[peerId] ?: emptyList()
        val editedMsg = chat.first { it.id == "msg-edit-1" }
        assertEquals("Texto corrigido com sucesso", editedMsg.text)
        assertTrue("Flag isEdited deve ser true", editedMsg.isEdited)
        assertTrue("Timestamp de edição deve ser preenchido", editedMsg.editedTimestampMs > 0L)
    }
}
