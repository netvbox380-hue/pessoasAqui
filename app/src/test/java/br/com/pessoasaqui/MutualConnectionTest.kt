package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class MutualConnectionTest {

    private lateinit var simulator: ProximitySimulator

    @Before
    fun setUp() {
        simulator = ProximitySimulator()
    }

    @Test
    fun testPeerAlreadyMarkingMeBecomesMutualWhenMarked() {
        val people = simulator.discoveredPeople.value
        val marcos = people.first { it.alias == "Marcos Mecânico" }
        assertTrue("Marcos já marcou o usuário", marcos.isMarkingMe)
        assertFalse("Usuário ainda não marcou Marcos", marcos.isMarkedByMe)
        assertFalse("Não é conexão mútua ainda", marcos.isMutualConnection)

        // Usuário aceita / marca de volta
        val updated = simulator.toggleMarkPerson(marcos.id)
        assertNotNull(updated)
        assertTrue("Usuário marcou Marcos", updated!!.isMarkedByMe)
        assertTrue("Conexão agora é mútua", updated.isMutualConnection)
        assertTrue("Foto fica visível após conexão mútua", updated.isPhotoVisible)
    }

    @Test
    fun testPeerNotMarkingMeAwaitsReciprocity() {
        val people = simulator.discoveredPeople.value
        val mariana = people.first { it.alias == "Mariana" }
        assertFalse("Mariana não marcou o usuário inicialmente", mariana.isMarkingMe)
        assertFalse("Usuário não marcou Mariana", mariana.isMarkedByMe)

        // Usuário marca Mariana unilateralmente
        val updated = simulator.toggleMarkPerson(mariana.id)
        assertNotNull(updated)
        assertTrue("Usuário marcou Mariana", updated!!.isMarkedByMe)
        assertFalse("Não é conexão mútua ainda pois Mariana não marcou", updated.isMutualConnection)

        // Desmarca
        val unmark = simulator.toggleMarkPerson(mariana.id)
        assertNotNull(unmark)
        assertFalse("Marcação removida", unmark!!.isMarkedByMe)
        assertFalse("Não é mútua", unmark.isMutualConnection)
    }

    @Test
    fun testNearbyPersonModelStateConsistency() {
        val person = NearbyPerson(
            id = "3F8E10BC",
            technicalIdentityHash = "3F8E10BC",
            alias = "rex",
            estimatedDistanceMeters = 3.0,
            proximityLabel = "Dentro do alcance (~3m)",
            isMarkedByMe = true,
            isMarkingMe = true,
            isMutualConnection = true
        )
        assertTrue(person.isMutualConnection)
        assertTrue(person.isMarkedByMe)
        assertTrue(person.isMarkingMe)
    }

    @Test
    fun testHysteresisSortingSuppressesMariaAndRexJitter() {
        val repo = br.com.pessoasaqui.data.repository.PessoasAquiRepository()
        val maria1 = NearbyPerson(id = "maria", technicalIdentityHash = "maria", alias = "Maria", estimatedDistanceMeters = 3.0, proximityLabel = "3m")
        val rex1 = NearbyPerson(id = "rex", technicalIdentityHash = "rex", alias = "Rex", estimatedDistanceMeters = 3.2, proximityLabel = "3m")

        // Rodada 1: Maria está mais próxima que Rex (3.0m vs 3.2m)
        val sorted1 = repo.sortWithHysteresis(listOf(rex1, maria1))
        assertEquals("maria", sorted1[0].id)
        assertEquals("rex", sorted1[1].id)

        // Rodada 2: Ruído de RF faz Rex parecer estar a 3.1m e Maria a 3.3m
        // Variação é de apenas 0.2m (< limiar de 1.5m de histerese)
        val maria2 = NearbyPerson(id = "maria", technicalIdentityHash = "maria", alias = "Maria", estimatedDistanceMeters = 3.3, proximityLabel = "3m")
        val rex2 = NearbyPerson(id = "rex", technicalIdentityHash = "rex", alias = "Rex", estimatedDistanceMeters = 3.1, proximityLabel = "3m")
        val sorted2 = repo.sortWithHysteresis(listOf(rex2, maria2))

        // Graças à histerese de posição, Maria permanece estável no topo, sem oscilar / inverter posição!
        assertEquals("Maria deve manter sua posição estável no topo", "maria", sorted2[0].id)
        assertEquals("Rex deve manter sua posição estável abaixo de Maria", "rex", sorted2[1].id)

        // Rodada 3: Rex se move fisicamente para perto (< 2.5m, Zona 0) enquanto Maria fica a 3.3m (Zona 1)
        val rex3 = NearbyPerson(id = "rex", technicalIdentityHash = "rex", alias = "Rex", estimatedDistanceMeters = 1.8, proximityLabel = "2m")
        val sorted3 = repo.sortWithHysteresis(listOf(rex3, maria2))
        assertEquals("Rex agora está em faixa de proximidade imediata (< 2.5m) e deve subir", "rex", sorted3[0].id)
        assertEquals("maria", sorted3[1].id)
    }

    @Test
    fun testAliasMatchingDoesNotGrantMutualConnection() {
        val repo = br.com.pessoasaqui.data.repository.PessoasAquiRepository()
        // Pessoa com mesmo apelido 'Eu' mas ID técnico diferente NÃO deve ser mútua por engano
        val stranger = NearbyPerson(
            id = "STRANGER_HASH_123",
            technicalIdentityHash = "STRANGER_HASH_123",
            alias = "Eu",
            estimatedDistanceMeters = 3.0,
            proximityLabel = "3m"
        )
        assertFalse("Desconhecido com mesmo apelido NÃO é conexão mútua", repo.isPeerMutual(stranger))
    }

    @Test
    fun testRadarFiltersOutDistantContacts() {
        // Contatos a 500m (conexões à distância) devem ser excluídos do radar de 10 metros
        val distantMutual = NearbyPerson(
            id = "REMOTE_MUTUAL_1",
            technicalIdentityHash = "REMOTE_MUTUAL_1",
            alias = "natv de sua casa",
            estimatedDistanceMeters = 500.0,
            proximityLabel = "Conexão Mútua • Ilimitada",
            isMutualConnection = true
        )
        val nearbyPeer = NearbyPerson(
            id = "LOCAL_PEER_1",
            technicalIdentityHash = "LOCAL_PEER_1",
            alias = "Pessoa Próxima",
            estimatedDistanceMeters = 4.0,
            proximityLabel = "4m",
            isMutualConnection = false
        )
        val allPeople = listOf(distantMutual, nearbyPeer)
        val radarPeople = allPeople.filter { it.estimatedDistanceMeters <= br.com.pessoasaqui.core.proximity.BleConstants.MAX_DISCOVERY_DISTANCE_METERS }

        assertEquals(1, radarPeople.size)
        assertEquals("LOCAL_PEER_1", radarPeople[0].id)
        assertFalse("Contato de 500m não pode estar no radar de 10m", radarPeople.any { it.estimatedDistanceMeters > 10.0 })
    }

    @Test
    fun testProductionModeStartsCompletelyIsolated() {
        val repo = br.com.pessoasaqui.data.repository.PessoasAquiRepository()
        // Modo produção desativado por padrão: novo usuário não pode ter mensagens ou contatos fantasmas
        assertFalse(repo.isDemoMode.value)
        assertTrue("Mural local nasce 100% vazio em produção", repo.localChatMessages.value.isEmpty())
        assertTrue("Conversas privadas nascem 100% vazias em produção", repo.privateChats.value.isEmpty())
        assertTrue("Radar nasce 100% vazio em produção para novo usuário", repo.discoveredPeople.value.isEmpty())
    }

    @Test
    fun testUndoneConnectionPreservesChatHistoryWithoutResurrectingMutualConnection() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "test_pessoasaqui_chat_" + System.currentTimeMillis())
        tempDir.mkdirs()
        try {
            val persistence = br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager(baseDir = tempDir)
            val peerId = "093F4EC86E8AA845"
            val peerAlias = "Teste S24"

            // 1. Simula mensagem existente no disco para o contato
            val msg = br.com.pessoasaqui.domain.model.ChatMessage(
                id = "msg-123",
                senderId = peerId,
                senderAlias = peerAlias,
                text = "Olá, tudo bem?",
                timestampMs = System.currentTimeMillis(),
                isFromMe = false
            )
            persistence.savePrivateChat(peerId, listOf(msg))

            // 2. Instancia o repositório com o gerenciador de persistência injetado
            val repo = br.com.pessoasaqui.data.repository.PessoasAquiRepository(injectedChatPersistence = persistence)

            // Estabelece conexão mútua legítima
            val connected = repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
            assertNotNull(connected)
            assertTrue("Conexão deve ser mútua após aceitação", repo.isPeerMutual(connected!!))

            // 3. Usuário desfaz a conexão mútua
            val disconnected = repo.setMarkPerson(peerId, mark = false)
            assertNotNull(disconnected)
            assertFalse("Marcação própria deve ser removida", disconnected!!.isMarkedByMe)
            assertFalse("Conexão mútua não deve estar mais ativa", repo.isPeerMutual(disconnected))

            // 4. Salva contatos e carrega contatos salvos
            repo.saveSavedContactsToPrefs()
            val contacts = repo.loadSavedContactsFromPrefs()
            val peerInContacts = contacts.find { it.id == peerId || it.technicalIdentityHash == peerId }
            assertNotNull("Contato ainda deve existir na lista recuperada devido ao histórico", peerInContacts)
            assertFalse("Contato com histórico salvo NÃO pode ressuscitar como conexão mútua", peerInContacts!!.isMutualConnection)
            assertFalse("isPeerMutual deve continuar falso após salvar e recarregar", repo.isPeerMutual(peerInContacts))

            // 5. Histórico em disco permanece 100% preservado
            val chatsOnDisk = persistence.loadAllPrivateChats()
            val messages = chatsOnDisk[peerId] ?: chatsOnDisk[peerId.lowercase()]
            assertNotNull("Histórico de mensagens deve estar preservado no disco", messages)
            assertTrue("Deve haver mensagens preservadas no histórico", messages!!.isNotEmpty())
            assertTrue("Mensagem original enviada/recebida deve permanecer preservada", messages.any { it.text == "Olá, tudo bem?" })

            // 6. Simula fechar e reabrir o app (novo repositório com o mesmo armazenamento em disco)
            val reloadedRepo = br.com.pessoasaqui.data.repository.PessoasAquiRepository(injectedChatPersistence = persistence)
            val reloadedContacts = reloadedRepo.loadSavedContactsFromPrefs()
            val reloadedPeer = reloadedContacts.find { it.id == peerId || it.technicalIdentityHash == peerId }
            assertNotNull(reloadedPeer)
            assertFalse("Ao reabrir o app, a conexão deve permanecer desfeita", reloadedPeer!!.isMutualConnection)
            assertFalse("isPeerMutual no repositório recarregado deve ser falso", reloadedRepo.isPeerMutual(reloadedPeer))

            // 7. Reconexão legítima: quando o usuário reconecta explicitamente, deve virar mútua
            val reconnected = reloadedRepo.setMarkPerson(peerId, mark = true, explicitMutual = true)
            assertNotNull(reconnected)
            assertTrue("Reconexão legítima deve ser aceita com sucesso", reloadedRepo.isPeerMutual(reconnected!!))
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
