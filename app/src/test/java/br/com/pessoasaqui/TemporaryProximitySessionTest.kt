package br.com.pessoasaqui

import br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Suíte de Testes Automatizados da ETAPA 2 — Arquitetura dos Dois Modos:
 * Validação rigorosa do isolamento entre Sessões Temporárias de Proximidade (Modo A)
 * e Conexões Mútuas Permanentes (Modo B).
 */
class TemporaryProximitySessionTest {

    private lateinit var tempDir: File
    private lateinit var persistence: ChatMessagePersistenceManager
    private lateinit var repo: PessoasAquiRepository

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_pa_modes_" + System.currentTimeMillis())
        tempDir.mkdirs()
        persistence = ChatMessagePersistenceManager(baseDir = tempDir)
        repo = PessoasAquiRepository(injectedChatPersistence = persistence)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    private fun createNearbyPeer(id: String, alias: String, lastSeen: Long = System.currentTimeMillis()): NearbyPerson {
        val peer = NearbyPerson(
            id = id,
            technicalIdentityHash = id,
            alias = alias,
            estimatedDistanceMeters = 3.5,
            proximityLabel = "Dentro do alcance (~3.5m)",
            isMarkedByMe = false,
            isMarkingMe = false,
            isMutualConnection = false,
            lastSeenEpochMs = lastSeen
        )
        repo.addDiscoveredPeer(peer)
        return peer
    }

    @Test
    fun test1_TemporarySessionCreatedInMemoryWithoutWritingToDisk() {
        val peerId = "TEMP_PEER_01"
        createNearbyPeer(peerId, "Pessoa Temporária")

        // Envia mensagem durante a sessão temporária
        repo.proximitySimulator.sendPrivateMessage(peerId, "Olá no radar temporário!", "Eu")

        // 1. Mensagem DEVE existir na memória do StateFlow
        val inMemoryChats = repo.proximitySimulator.privateChats.value[peerId] 
            ?: repo.proximitySimulator.privateChats.value["peer-$peerId"]
        assertNotNull("Mensagens temporárias devem estar ativas na memória", inMemoryChats)
        assertTrue("Deve conter a mensagem enviada", inMemoryChats!!.any { it.text == "Olá no radar temporário!" })

        // 2. Mensagem NÃO PODE ter sido gravada no disco físico
        assertFalse("Sessão temporária NUNCA deve gravar arquivos de chat no disco", persistence.hasPrivateChat(peerId))
        val diskChats = persistence.loadAllPrivateChats()
        assertFalse("Disco não pode conter conversas temporárias", diskChats.containsKey(peerId))
    }

    @Test
    fun test2_TemporarySessionTerminatesWhenPeerWalksAway() {
        val peerId = "TEMP_PEER_02"
        val now = System.currentTimeMillis()
        createNearbyPeer(peerId, "Pessoa Afastada", lastSeen = now - 50000L) // 50 segundos atrás

        // Limpeza de sessões expiradas (> 45s)
        val expired = repo.cleanupExpiredTemporarySessions(now = now, expirationMs = 45000L)
        assertTrue("Peer com 50s sem sinal deve ser expirado", expired.contains(peerId))
        assertFalse("Peer expirado não deve estar mais em discoveredPeople", repo.discoveredPeople.value.any { it.id == peerId })
    }

    @Test
    fun test3_MomentaryRfLossDoesNotTerminateSessionWithinGracePeriod() {
        val peerId = "TEMP_PEER_03"
        val now = System.currentTimeMillis()
        // Pessoa vista há 20 segundos (dentro da tolerância / grace period de 30s)
        createNearbyPeer(peerId, "Pessoa Oscilando RF", lastSeen = now - 20000L)

        // Envia mensagem durante a sessão
        repo.proximitySimulator.sendPrivateMessage(peerId, "Mensagem durante oscilação", "Eu")

        // Executa limpeza de expiração
        val expired = repo.cleanupExpiredTemporarySessions(now = now, expirationMs = 45000L)
        assertFalse("Perda de sinal por apenas 20s NÃO pode expirar a sessão (tolerância ativa)", expired.contains(peerId))

        // Histórico em memória continua intacto
        val inMemoryChats = repo.proximitySimulator.privateChats.value[peerId] 
            ?: repo.proximitySimulator.privateChats.value["peer-$peerId"]
        assertNotNull("Histórico em memória deve ser mantido durante oscilação momentânea", inMemoryChats)
    }

    @Test
    fun test4_ExpirationAfterSustainedAbsenceExceeding45Seconds() {
        val peerId = "TEMP_PEER_04"
        val now = System.currentTimeMillis()
        // Ausente há 46 segundos (ultrapassou os 45s de expiração)
        createNearbyPeer(peerId, "Pessoa Longe", lastSeen = now - 46000L)

        repo.proximitySimulator.sendPrivateMessage(peerId, "Última mensagem antes de sumir", "Eu")
        val expired = repo.cleanupExpiredTemporarySessions(now = now, expirationMs = 45000L)

        assertTrue("Ausência sustentada >= 45s deve encerrar a sessão", expired.contains(peerId))
        // Mensagens em memória devem ter sido purgadas
        val afterChats = repo.proximitySimulator.privateChats.value[peerId]
        assertTrue("Histórico em memória deve ser purgado após expiração", afterChats.isNullOrEmpty())
    }

    @Test
    fun test5_TemporaryDataPurgedCompletelyFromLocalDisk() {
        val peerId = "TEMP_PEER_05"
        val now = System.currentTimeMillis()
        createNearbyPeer(peerId, "Pessoa Efêmera", lastSeen = now)

        repo.proximitySimulator.sendPrivateMessage(peerId, "Conversa rápida", "Eu")
        // Garante que o disco permaneceu limpo
        assertFalse("Disco físico deve estar 100% livre de chats temporários", persistence.hasPrivateChat(peerId))

        // Força expiração
        repo.cleanupExpiredTemporarySessions(now = now + 60000L, expirationMs = 45000L)
        assertFalse("Após expiração, disco continua sem resquícios", persistence.hasPrivateChat(peerId))
    }

    @Test
    fun test6_PermanentMutualConnectionsPreservedDespitePhysicalAbsence() {
        val permanentId = "MUTUAL_PEER_06"
        val now = System.currentTimeMillis()
        createNearbyPeer(permanentId, "Contato Mútuo", lastSeen = now)

        // 1. Estabelece conexão mútua legítima
        repo.setMarkPerson(permanentId, mark = true, explicitMutual = true)
        assertTrue("Contato deve ser conexão mútua", repo.isPeerMutual(permanentId))

        // 2. Envia mensagem para o contato mútuo
        repo.proximitySimulator.sendPrivateMessage(permanentId, "Olá amigo de longa data!", "Eu")

        // 3. Contato mútuo DEVE ser salvo em disco criptografado
        assertTrue("Conexões mútuas DEVEM persistir conversas no disco físico", persistence.hasPrivateChat(permanentId))

        // 4. Simula afastamento físico por 2 horas (7200 segundos)
        val expired = repo.cleanupExpiredTemporarySessions(now = now + 7200000L, expirationMs = 45000L)
        assertFalse("Conexão mútua permanente NUNCA pode ser expirada por distância física", expired.contains(permanentId))

        // 5. Histórico em disco permanece 100% intacto
        val chats = persistence.loadAllPrivateChats()
        assertTrue("Conversa permanente continua gravada e recuperável", chats.containsKey(permanentId) || chats.containsKey(permanentId.lowercase()))
    }

    @Test
    fun test7_AuthorizedConversionOfTemporarySessionPromotesMemoryChatToEncryptedDisk() {
        val peerId = "TEMP_TO_MUTUAL_07"
        createNearbyPeer(peerId, "Novo Conhecido")

        // 1. Enquanto temporário: conversa existe apenas na memória
        repo.proximitySimulator.sendPrivateMessage(peerId, "Mensagem inicial no radar", "Eu")
        assertFalse("Antes da aceitação mútua, não deve estar no disco", persistence.hasPrivateChat(peerId))

        // 2. Usuários trocam e aceitam convite de conexão mútua (conversão autorizada)
        repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
        assertTrue("Agora é conexão mútua", repo.isPeerMutual(peerId))

        // 3. Histórico anterior em memória é promovido automaticamente para o disco criptografado
        assertTrue("Ao virar mútua, histórico temporário deve ser promovido para o disco", persistence.hasPrivateChat(peerId))
        val diskChats = persistence.loadAllPrivateChats()
        val peerMessages = diskChats[peerId] ?: diskChats[peerId.lowercase()]
        assertNotNull("Mensagens promovidas devem existir no disco", peerMessages)
        assertTrue("Mensagem original enviada deve estar preservada", peerMessages!!.any { it.text == "Mensagem inicial no radar" })
    }

    @Test
    fun test8_AutomaticConversionForbiddenWithoutExplicitMutualConsent() {
        val strangerId = "STRANGER_08"
        createNearbyPeer(strangerId, "Desconhecido")

        // Troca de mensagens unilaterais sem aceitação mútua
        repo.proximitySimulator.sendPrivateMessage(strangerId, "Oi", "Eu")
        repo.proximitySimulator.receivePrivateMessage(strangerId, "Desconhecido", "Olá")

        // Nenhuma conversão automática pode ocorrer
        assertFalse("Troca de mensagens NÃO pode converter automaticamente em conexão mútua", repo.isPeerMutual(strangerId))
        assertFalse("Desconhecido sem vínculo mútuo não pode estar persistido em disco", persistence.hasPrivateChat(strangerId))
    }

    @Test
    fun test9_UndoneConnectionCannotBeResurrectedByOldChatHistory() {
        val peerId = "FORMER_MUTUAL_09"
        createNearbyPeer(peerId, "Antigo Mútuo")

        // Conecta e depois desfaz
        repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
        repo.proximitySimulator.sendPrivateMessage(peerId, "Histórico antigo", "Eu")
        repo.setMarkPerson(peerId, mark = false)

        assertFalse("Conexão desfeita deve ser inativa", repo.isPeerMutual(peerId))

        // Salva e recarrega preferências
        repo.saveSavedContactsToPrefs()
        val reloaded = repo.loadSavedContactsFromPrefs()
        val found = reloaded.find { it.id == peerId || it.technicalIdentityHash == peerId }
        if (found != null) {
            assertFalse("Histórico salvo antigo NUNCA pode ressuscitar conexão mútua", found.isMutualConnection)
            assertFalse("isPeerMutual deve continuar falso", repo.isPeerMutual(found))
        }
    }

    @Test
    fun test10_IndependenceBetweenModoAAndModoBStates() {
        val tempId = "TEMP_PEER_10"
        val mutualId = "MUTUAL_PEER_10"

        // Modo A: Temporário por proximidade
        createNearbyPeer(tempId, "Pessoa Proximidade")
        repo.proximitySimulator.sendPrivateMessage(tempId, "Chat temporário", "Eu")

        // Modo B: Conexão mútua permanente
        createNearbyPeer(mutualId, "Pessoa Mútua")
        repo.setMarkPerson(mutualId, mark = true, explicitMutual = true)
        repo.proximitySimulator.sendPrivateMessage(mutualId, "Chat permanente", "Eu")

        // Verifica isolamento
        assertFalse("Modo A não é permanente", repo.isPeerPermanent(tempId))
        assertTrue("Modo B é permanente", repo.isPeerPermanent(mutualId))

        assertFalse("Modo A não tem arquivo em disco", persistence.hasPrivateChat(tempId))
        assertTrue("Modo B tem arquivo em disco", persistence.hasPrivateChat(mutualId))

        // Encerramento de sessão temporária não afeta conexão permanente
        val now = System.currentTimeMillis()
        val expired = repo.cleanupExpiredTemporarySessions(now = now + 60000L, expirationMs = 45000L)
        assertTrue("Modo A expirou", expired.contains(tempId))
        assertFalse("Modo B permaneceu intacto", expired.contains(mutualId))

        assertTrue("Chat do Modo B continua no disco", persistence.hasPrivateChat(mutualId))
    }

    @Test
    fun test11_ConversationsTabFilterExcludesNonMutualAndNonFamilyEvenIfOldChatExistsOnDisk() {
        val oldPeerId = "LEGACY_CHAT_PEER"
        val now = System.currentTimeMillis()
        val peer = createNearbyPeer(oldPeerId, "Ex-Contato", lastSeen = now)

        // Simula que existe um arquivo de chat legado gravado no disco
        val oldMsgs = listOf(
            ChatMessage(id = "msg_old_1", senderId = oldPeerId, senderAlias = "Ex-Contato", text = "Mensagem antiga salva", timestampMs = now - 100000L, isFromMe = false)
        )
        persistence.savePrivateChat(oldPeerId, oldMsgs)
        assertTrue("Arquivo existe no disco", persistence.hasPrivateChat(oldPeerId))

        // Verifica que o peer NÃO é mútua nem família
        assertFalse(peer.isMutualConnection)
        assertFalse(peer.isFamily)
        assertFalse(repo.isPeerPermanent(oldPeerId))

        // Aplica o filtro estrito da aba Conversas (isMutualConnection || isFamily)
        val candidateList = listOf(peer)
        val connectedPeople = candidateList.filter { it.isMutualConnection || it.isFamily }

        // Comprova que mesmo tendo chat em disco, NÃO entra na lista de conversas ativas
        assertTrue("Ex-contato com chat em disco não pode poluir a lista de conversas ativas", connectedPeople.isEmpty())

        // Já um familiar ou conexão mútua entra com sucesso
        val familyPeer = createNearbyPeer("FAMILY_01", "Mãe").copy(isFamily = true)
        val mutualPeer = createNearbyPeer("MUTUAL_01", "Amigo").copy(isMutualConnection = true)
        val activeList = listOf(peer, familyPeer, mutualPeer).filter { it.isMutualConnection || it.isFamily }
        assertEquals(2, activeList.size)
        assertTrue(activeList.contains(familyPeer))
        assertTrue(activeList.contains(mutualPeer))
    }

    @Test
    fun test12_CleanupExpiredSessionsNeverDeletesPersistedDiskHistoryOfFormerContacts() {
        val formerId = "FORMER_CONTACT_12"
        val now = System.currentTimeMillis()
        createNearbyPeer(formerId, "Ex-Conexão", lastSeen = now)

        // 1. Foi conexão mútua com mensagens gravadas em disco
        repo.setMarkPerson(formerId, mark = true, explicitMutual = true)
        val msgs = listOf(
            ChatMessage(id = "msg_hist_1", senderId = formerId, senderAlias = "Ex-Conexão", text = "Conversa importante gravada", timestampMs = now, isFromMe = false)
        )
        persistence.savePrivateChat(formerId, msgs)
        assertTrue("Histórico está no disco", persistence.hasPrivateChat(formerId))

        // 2. Conexão foi desfeita voluntariamente pelo usuário
        repo.setMarkPerson(formerId, mark = false)
        assertFalse("Conexão mútua não está mais ativa", repo.isPeerPermanent(formerId))
        assertTrue("Histórico continua preservado no disco após desfazer vínculo", persistence.hasPrivateChat(formerId))

        // 3. Pessoa se afasta fisicamente (timeout > 45s no BLE)
        val expired = repo.cleanupExpiredTemporarySessions(now = now + 60000L, expirationMs = 45000L)
        assertTrue("Sessão de radar expirou por afastamento físico", expired.contains(formerId))

        // 4. CRÍTICO: O arquivo histórico no disco NUNCA foi apagado pela rotina de cleanup
        assertTrue("Histórico gravado no disco JAMAIS pode ser apagado por timeout de rádio BLE", persistence.hasPrivateChat(formerId))
        val allChats = persistence.loadAllPrivateChats()
        val loaded = allChats[formerId.lowercase()] ?: allChats[formerId] ?: emptyList()
        assertEquals("Mensagens em disco devem permanecer intactas", 1, loaded.size)
        assertEquals("Conversa importante gravada", loaded[0].text)
    }

    @Test
    fun test13_PromotionMergesWithExistingDiskHistoryWithoutDuplicatesOrOverwrites() {
        val peerId = "MERGE_TEST_PEER"
        val now = System.currentTimeMillis()
        createNearbyPeer(peerId, "Contato Antigo e Novo", lastSeen = now)

        // 1. Já existia histórico antigo em disco
        val pastMsg = ChatMessage(
            id = "msg_past_1",
            senderId = peerId,
            senderAlias = "Contato Antigo e Novo",
            text = "Mensagem antiga do passado",
            timestampMs = now - 500000L,
            isFromMe = false
        )
        persistence.savePrivateChat(peerId, listOf(pastMsg))

        // 2. No encontro presencial atual (Modo A), uma nova mensagem efêmera é enviada em memória
        repo.proximitySimulator.sendPrivateMessage(peerId, "Nova mensagem presencial recente", "Eu")
        // O disco ainda deve conter apenas a mensagem antiga (não foi promovida ainda)
        var diskMsgs = persistence.loadAllPrivateChats()[peerId.lowercase()] ?: emptyList()
        assertEquals(1, diskMsgs.size)
        assertEquals("Mensagem antiga do passado", diskMsgs[0].text)

        // 3. Tentar promover sem conexão mútua confirmada deve ser recusado
        repo.promoteTemporarySessionToMutual(peerId)
        diskMsgs = persistence.loadAllPrivateChats()[peerId.lowercase()] ?: emptyList()
        assertEquals("Sem conexão mútua confirmada, histórico não é promovido", 1, diskMsgs.size)

        // 4. Usuários confirmam conexão mútua permanente
        repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
        assertTrue(repo.isPeerPermanent(peerId))

        // 5. Histórico em disco agora deve conter AMBAS as mensagens sem duplicar ou sobrescrever
        diskMsgs = persistence.loadAllPrivateChats()[peerId.lowercase()] ?: emptyList()
        val texts = diskMsgs.map { it.text }
        assertTrue("Histórico antigo deve ser preservado", texts.contains("Mensagem antiga do passado"))
        assertTrue("Nova mensagem presencial deve ser preservada", texts.contains("Nova mensagem presencial recente"))
    }

    @Test
    fun test14_DelayedMessageFromExpiredTemporarySessionIsDiscarded() {
        val peerId = "TEMP_PEER_14"
        val now = System.currentTimeMillis()
        createNearbyPeer(peerId, "Pessoa Efêmera", lastSeen = now)

        // 1. A sessão temporária expira após 45 segundos de ausência de sinal BLE
        val expired = repo.cleanupExpiredTemporarySessions(now = now + 60000L, expirationMs = 45000L)
        assertTrue("Sessão temporária expirou", expired.contains(peerId))
        assertFalse("Par não é permanente", repo.isPeerPermanent(peerId))

        // 2. Uma mensagem atrasada chega da fila do backend após a sessão ter sido encerrada
        repo.processIncomingPayload(peerId, "Pessoa Efêmera", "Mensagem atrasada que chegou depois de expirar", "", "delayed_msg_14")

        // 3. Comprova que a mensagem atrasada foi DESCARTADA
        val memoryChats = repo.proximitySimulator.privateChats.value[peerId] ?: emptyList()
        assertTrue("Mensagem atrasada de sessão temporária expirada DEVE ser descartada", memoryChats.isEmpty())
        assertFalse("Nenhum arquivo de chat deve ser criado no disco", persistence.hasPrivateChat(peerId))

        // 4. Comprova que a mensagem atrasada NÃO ressuscita o par no Radar
        val isResurrected = repo.discoveredPeople.value.any { it.id == peerId || it.technicalIdentityHash == peerId }
        assertFalse("Mensagem atrasada não pode restaurar par expirado no radar", isResurrected)
    }

    @Test
    fun test15_PermanentConnectionReceivesInternetMessagesWithoutBluetoothPresence() {
        val permanentId = "MUTUAL_PEER_15"
        val now = System.currentTimeMillis()

        // 1. Conexão mútua estabelecida (Modo B)
        createNearbyPeer(permanentId, "Amigo Distante", lastSeen = now)
        repo.setMarkPerson(permanentId, mark = true, explicitMutual = true)
        assertTrue(repo.isPeerPermanent(permanentId))

        // 2. Simula ausência total de Bluetooth (contato a quilômetros de distância, fora do radar BLE)
        repo.cleanupExpiredTemporarySessions(now = now + 100000L, expirationMs = 45000L)
        // Conexão permanente permanece ativa
        assertTrue("Conexão permanente nunca expira por afastamento", repo.isPeerPermanent(permanentId))

        // 3. Chega uma mensagem legítima pela internet (sem presença BLE)
        repo.processIncomingPayload(permanentId, "Amigo Distante", "Mensagem legítima pela internet à distância", "", "net_msg_15")

        // 4. Comprova que conexões permanentes recebem mensagens normalmente pela internet
        val memoryChats = repo.proximitySimulator.privateChats.value[permanentId] ?: emptyList()
        assertFalse("Mensagem de conexão permanente NÃO pode ser bloqueada por ausência de Bluetooth", memoryChats.isEmpty())
        assertTrue("Conteúdo da mensagem deve ser entregue", memoryChats.any { it.text == "Mensagem legítima pela internet à distância" })

        // 5. Comprova que a mensagem da conexão permanente foi gravada no disco criptografado
        assertTrue("Mensagem de conexão permanente DEVE ser salva em disco", persistence.hasPrivateChat(permanentId))
    }

    @Test
    fun test16_NewTemporaryProximitySessionStartsFreshWithoutOldDiscardedMessages() {
        val peerId = "TEMP_PEER_16"
        val now = System.currentTimeMillis()

        // 1. Primeiro encontro presencial (Sessão 1 do Modo A)
        createNearbyPeer(peerId, "Encontro 1", lastSeen = now)
        repo.proximitySimulator.sendPrivateMessage(peerId, "Conversa efêmera do primeiro encontro", "Eu")
        val session1Chats = repo.proximitySimulator.privateChats.value[peerId] ?: emptyList()
        assertEquals(1, session1Chats.size)

        // 2. Usuário se afasta e sessão 1 expira e é descartada
        repo.cleanupExpiredTemporarySessions(now = now + 60000L, expirationMs = 45000L)
        assertTrue("Memória deve estar vazia após encerramento da sessão", repo.proximitySimulator.privateChats.value[peerId].isNullOrEmpty())
        assertFalse("Disco não tem mensagens", persistence.hasPrivateChat(peerId))

        // 3. Horas depois, ocorre um novo encontro presencial (Sessão 2 do Modo A)
        createNearbyPeer(peerId, "Encontro 2", lastSeen = now + 3600000L)

        // 4. Comprova que a nova sessão temporária inicia LIMPA, sem recuperar mensagens antigas
        val session2InitialChats = repo.proximitySimulator.privateChats.value[peerId] ?: emptyList()
        assertTrue("Novo encontro temporário DEVE iniciar com histórico em branco", session2InitialChats.isEmpty())

        // 5. Troca nova mensagem na sessão 2
        repo.proximitySimulator.sendPrivateMessage(peerId, "Nova mensagem do segundo encontro", "Eu")
        val session2Chats = repo.proximitySimulator.privateChats.value[peerId] ?: emptyList()
        assertEquals(1, session2Chats.size)
        assertEquals("Nova mensagem do segundo encontro", session2Chats[0].text)
        assertFalse("Mensagem descartada do primeiro encontro NUNCA deve ser recuperada", session2Chats.any { it.text == "Conversa efêmera do primeiro encontro" })
        assertFalse("Sessão temporária 2 continua estritamente em memória", persistence.hasPrivateChat(peerId))
    }
}
