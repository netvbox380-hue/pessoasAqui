package br.com.pessoasaqui

import br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.MessageDeliveryStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

/**
 * Bateria de Testes Mandatórios (T01 a T10):
 * Confiabilidade de Entrega, Persistência Local Real, Idempotência de ACK e Isolamento Criptográfico.
 */
class MessagePersistenceAndAckOrderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var baseDir: File
    private lateinit var chatPersistence: ChatMessagePersistenceManager
    private val peerId = "A1B2C3D4E5F60708"
    private val ackedServerIds = mutableListOf<String>()

    private val testNetworkClient = object : PessoasAquiNetworkClient() {
        override fun sendServerMessageAck(messageId: String) {
            if (messageId.isNotBlank()) {
                ackedServerIds.add(messageId)
            }
        }
    }

    @Before
    fun setUp() {
        baseDir = tempFolder.newFolder("pessoasaqui_test_storage")
        chatPersistence = ChatMessagePersistenceManager(baseDir = baseDir)
        ackedServerIds.clear()
    }

    private fun createRepository(persistence: ChatMessagePersistenceManager = chatPersistence): PessoasAquiRepository {
        val repo = PessoasAquiRepository(
            injectedChatPersistence = persistence,
            networkClient = testNetworkClient
        )
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        return repo
    }

    @Test
    fun testT01_mensagemRecebidaEGravadaComSucesso() {
        val repo = createRepository()
        val msgId = "msg_t01_${UUID.randomUUID()}"
        val text = "Mensagem com persistência durável comprovada"

        val success = repo.processVerifiedNetworkPayload(peerId, "Contato", text, msgId)
        assertTrue("Processamento e persistência devem retornar sucesso", success)

        // 1. Comprova gravação no arquivo em disco
        assertTrue("Arquivo em disco deve conter a mensagem", chatPersistence.hasMessage(peerId, msgId))

        // 2. Comprova emissão do ACK ao servidor
        assertTrue("ACK de transporte deve ter sido emitido para o servidor", ackedServerIds.contains(msgId))

        // 3. Comprova que está na memória do chat
        val chat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val storedMsg = chat.find { it.id == msgId }
        assertNotNull("Mensagem deve constar na conversa ativa", storedMsg)
        assertEquals(text, storedMsg?.text)
    }

    @Test
    fun testT02_falhaDeGravacaoLocalImpedeAck() {
        // Cria persistência apontando para diretório inválido/somente-leitura para forçar falha de I/O
        val readOnlyFile = tempFolder.newFile("unwritable_as_dir")
        val failingPersistence = ChatMessagePersistenceManager(baseDir = readOnlyFile)

        val repo = createRepository(failingPersistence)
        val msgId = "msg_t02_fail_${UUID.randomUUID()}"

        val success = repo.processVerifiedNetworkPayload(peerId, "Contato", "Texto que não pode ser gravado", msgId)
        assertFalse("Falha de persistência física deve retornar false", success)

        // ACK NÃO PODE ser emitido quando a persistência falha
        assertFalse("ACK JAMAIS pode ser emitido prematuramente se a gravação falhou", ackedServerIds.contains(msgId))
    }

    @Test
    fun testT03_encerramentoDuranteProcessamentoPermiteRetry() {
        val repo = createRepository()
        val msgId = "msg_t03_crash_${UUID.randomUUID()}"

        // Simula falha inicial
        val invalidPersistence = ChatMessagePersistenceManager(baseDir = tempFolder.newFile("blocked_file"))
        repo.proximitySimulator.setPersistenceManager(invalidPersistence)

        val attempt1 = repo.processVerifiedNetworkPayload(peerId, "Contato", "Texto tentativa 1", msgId)
        assertFalse("Primeira tentativa deve falhar", attempt1)
        assertFalse("Nenhum ACK deve ser emitido na falha", ackedServerIds.contains(msgId))

        // Restaura persistência válida e reprocessa
        repo.proximitySimulator.setPersistenceManager(chatPersistence)
        val attempt2 = repo.processVerifiedNetworkPayload(peerId, "Contato", "Texto tentativa 1", msgId)
        assertTrue("Segunda tentativa deve ter sucesso após recuperação", attempt2)
        assertTrue("ACK deve ser emitido após persistência comprovada", ackedServerIds.contains(msgId))
    }

    @Test
    fun testT04_reinicializacaoAposGravacaoEAntesDoAckEmiteAckIdempotente() {
        val repo1 = createRepository()
        val msgId = "msg_t04_reboot_${UUID.randomUUID()}"
        val text = "Texto gravado antes da queda"

        // 1. Mensagem é recebida e gravada com sucesso no repo1
        val ok = repo1.processVerifiedNetworkPayload(peerId, "Contato", text, msgId)
        assertTrue(ok)
        assertTrue(chatPersistence.hasMessage(peerId, msgId))

        // Limpa lista de ACKs simulando queda da conexão antes do ACK chegar ao servidor
        ackedServerIds.clear()

        // 2. Simula reinicialização do aplicativo criando nova instância repo2 sobre o MESMO disco
        val repo2 = createRepository()
        assertTrue("Ao reiniciar, disco deve conter a mensagem", repo2.proximitySimulator.isMessagePersistedOrPresent(peerId, msgId))

        // 3. Servidor retransmite a mesma mensagem
        val retransmittedOk = repo2.processVerifiedNetworkPayload(peerId, "Contato", text, msgId)
        assertTrue("Retransmissão deve ser reconhecida com sucesso", retransmittedOk)

        // 4. Deve emitir novo ACK para desobstruir a fila do servidor
        assertTrue("Deve emitir ACK idempotente para o servidor", ackedServerIds.contains(msgId))
    }

    @Test
    fun testT05_mensagemDuplicadaAposReinicializacaoNaoCriaDuplicatas() {
        val repo1 = createRepository()
        val msgId = "msg_t05_dedup_${UUID.randomUUID()}"
        repo1.processVerifiedNetworkPayload(peerId, "Contato", "Conteúdo único", msgId)

        // Reinicializa
        val repo2 = createRepository()
        val countInitial = repo2.proximitySimulator.privateChats.value[peerId].orEmpty().count { it.id == msgId }
        assertEquals(1, countInitial)

        // Recebe retransmissões duplicadas
        repo2.processVerifiedNetworkPayload(peerId, "Contato", "Conteúdo único", msgId)
        repo2.processVerifiedNetworkPayload(peerId, "Contato", "Conteúdo único", msgId)

        val countFinal = repo2.proximitySimulator.privateChats.value[peerId].orEmpty().count { it.id == msgId }
        assertEquals("Mensagem não pode ser duplicada no histórico de chat", 1, countFinal)
    }

    @Test
    fun testT06_armazenamentoIndisponivelRejeitaMensagem() {
        val unwritableDir = tempFolder.newFile("unwritable_target")
        val brokenPersistence = ChatMessagePersistenceManager(baseDir = unwritableDir)
        val repo = createRepository(brokenPersistence)

        val msgId = "msg_t06_${UUID.randomUUID()}"
        val success = repo.processVerifiedNetworkPayload(peerId, "Contato", "Mensagem sem disco", msgId)

        assertFalse("Armazenamento indisponível deve retornar false", success)
        assertFalse("ACK não pode ser despachado", ackedServerIds.contains(msgId))
    }

    @Test
    fun testT07_conteudoInvalidoRejeitado() {
        val repo = createRepository()
        val msgId = "msg_t07_invalid"

        // Envelope quebrado sem formato ECDH
        val res1 = repo.processIncomingPayload(peerId, "Contato", "PLAINTEXT_CORRUPTO", "iv", msgId)
        assertFalse("Plaintext sem ECDH deve ser rejeitado", res1)

        // Envelope ECDH com IV vazio
        val res2 = repo.processIncomingPayload(peerId, "Contato", "ENC_ECDH:dados_corrompidos", "", msgId)
        assertFalse("Envelope sem IV deve ser rejeitado", res2)

        // Nenhum ACK emitido para payloads inválidos
        assertFalse("Payloads inválidos não geram ACK", ackedServerIds.contains(msgId))
    }

    @Test
    fun testT08_contatoRevogadoNaoGeraGravacaoNemAck() {
        val repo = createRepository()
        val msgId = "msg_t08_revoked_${UUID.randomUUID()}"

        // Revoga vínculo mútuo
        repo.revokeMutualLocally(peerId, removeMyMark = true, queueRemoteUnmark = true)

        val success = repo.processIncomingPayload(peerId, "Contato", "ENC_ECDH:payload_revogado", "iv_valido", msgId)
        assertFalse("Mensagem de contato revogado deve ser rejeitada", success)
        assertFalse("Nenhum ACK pode ser emitido para contato revogado", ackedServerIds.contains(msgId))
        assertFalse("Contato revogado não pode ter mensagem gravada em disco", chatPersistence.hasMessage(peerId, msgId))
    }

    @Test
    fun testT09_chavePublicaIndisponivelRetemSemAckPrematuro() {
        val repo = createRepository()
        val strangerWithoutKey = "9988776655443322"
        val msgId = "msg_t09_unknown_key_${UUID.randomUUID()}"

        // Remetente sem chave registrada
        val accepted = repo.processIncomingPayload(strangerWithoutKey, "Desconhecido", "ENC_ECDH:cifrado", "iv_abc", msgId)

        // Não pode dar sucesso imediato nem emitir ACK prematuro
        assertFalse("Sem chave pública o processamento não pode concluir imediatamente", accepted)
        assertFalse("ACK prematuro não pode ser despachado sem descriptografia real", ackedServerIds.contains(msgId))
    }

    @Test
    fun testT10_mensagemAntigaRetransmitidaTratadaComIdempotencia() {
        val repo = createRepository()
        val msgId = "msg_t10_old_${UUID.randomUUID()}"
        val text = "Mensagem antiga gravada há tempos"

        // Primeira entrega legítima
        repo.processVerifiedNetworkPayload(peerId, "Contato", text, msgId)
        assertTrue(chatPersistence.hasMessage(peerId, msgId))

        ackedServerIds.clear()

        // Retransmissão da mesma mensagem antiga
        val idempotenceSuccess = repo.processVerifiedNetworkPayload(peerId, "Contato", text, msgId)
        assertTrue("Idempotência deve aceitar e retornar sucesso", idempotenceSuccess)

        // Novo ACK emitido para o servidor
        assertTrue("Deve reenviar ACK para limpar fila remota", ackedServerIds.contains(msgId))

        // Total de mensagens no chat deve permanecer 1
        val count = repo.proximitySimulator.privateChats.value[peerId].orEmpty().count { it.id == msgId }
        assertEquals("Histórico não pode ser inflado", 1, count)
    }

    @Test
    fun testT11_modoBFailsWhenPersistenceManagerIsNull() {
        val repo = createRepository()
        // Define persistenceManager como nulo para simular falha de inicialização / indisponibilidade do storage
        repo.proximitySimulator.setPersistenceManager(null)

        val msgId = "msg_modob_null_pm_${UUID.randomUUID()}"
        val success = repo.processVerifiedNetworkPayload(peerId, "Contato", "Texto Modo B sem PM", msgId)

        // Modo B NUNCA deve retornar true se persistenceManager for nulo!
        assertFalse("Modo B deve falhar categoricamente quando persistenceManager for nulo", success)
        assertFalse("ACK JAMAIS deve ser enviado se a mensagem não foi gravada em disco", ackedServerIds.contains(msgId))
        assertFalse("Mensagem não pode ser marcada como persistida", repo.proximitySimulator.isMessagePersistedOrPresent(peerId, msgId))
    }

    @Test
    fun testT12_modoAPreservesRamPolicyWithoutDiskPersistence() {
        val repo = createRepository()
        val blePeerId = "BLE_PEER_MODO_A_10M"

        // Configura política de Modo A: efêmero em RAM, sem gravação em disco
        repo.proximitySimulator.shouldPersistPrivateChat = { id -> id != blePeerId }

        val msgId = "msg_modoa_ephem_${UUID.randomUUID()}"
        val text = "Mensagem efêmera Modo A Bluetooth 10m"

        // Para Modo A, recebe mensagem no chat privado
        val received = repo.proximitySimulator.receivePrivateMessage(
            senderId = blePeerId,
            senderAlias = "Vizinho BLE",
            text = text,
            messageId = msgId
        )

        assertTrue("Modo A deve ser aceito na memória RAM", received)
        // Comprova que NÃO gravou em disco (preservando privacidade do Modo A)
        assertFalse("Modo A não pode gravar histórico em disco", chatPersistence.hasMessage(blePeerId, msgId))
        // Comprova que está na RAM
        val inRam = repo.proximitySimulator.privateChats.value[blePeerId].orEmpty()
        assertTrue("Modo A deve estar presente na RAM para exibição na UI", inRam.any { it.id == msgId })
    }

    @Test
    fun testT13_falha503MantemMensagemParaRetentativaComMesmoId() {
        val repo = createRepository()
        val msgId = "msg_t13_503_${UUID.randomUUID()}"
        val text = "Mensagem com persistência durável diante de 503"

        // 1. Envio da mensagem com status inicial SENT/PENDING
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = text,
            myAlias = "Eu",
            messageId = msgId,
            deliveryStatus = MessageDeliveryStatus.SENT
        )

        val initialChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val initialMsg = initialChat.find { it.id == msgId }
        assertNotNull("Mensagem deve estar presente no chat", initialMsg)
        assertEquals(MessageDeliveryStatus.SENT, initialMsg?.deliveryStatus)

        // 2. Simula resposta de erro HTTP 503 do backend disparando onMessageError
        testNetworkClient.onMessageError?.invoke(msgId, "HTTP 503 Service Unavailable")

        // 3. Comprova que a mensagem NÃO foi descartada e seu status mudou para FAILED
        val failedChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val failedMsg = failedChat.find { it.id == msgId }
        assertNotNull("Mensagem não pode ser descartada após falha 503", failedMsg)
        assertEquals("Status deve ser FAILED para permitir retentativa durável", MessageDeliveryStatus.FAILED, failedMsg?.deliveryStatus)
        assertEquals(1, failedChat.count { it.id == msgId })

        // 4. Servidor se recupera e confirmação criptográfica de entrega (ACK) é recebida
        val ackProcessed = repo.processVerifiedNetworkPayload(peerId, "Contato", "[DELIVERY_ACK:$msgId]", "ack_t13_evt")
        assertTrue("Processamento de ACK de entrega deve ter sucesso", ackProcessed)

        // 5. Comprova que a mensagem com o MESMO msgId agora está marcada como DELIVERED
        val deliveredChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val deliveredMsg = deliveredChat.find { it.id == msgId }
        assertNotNull("Mensagem deve permanecer no histórico com o mesmo ID", deliveredMsg)
        assertEquals("Status deve ter transitado para DELIVERED", MessageDeliveryStatus.DELIVERED, deliveredMsg?.deliveryStatus)
        assertEquals("Não deve haver duplicações no histórico de chat", 1, deliveredChat.count { it.id == msgId })
    }

    @Test
    fun testT14_webSocketMessageErrorNaoDescartaMensagem() {
        val repo = createRepository()
        val msgId = "msg_t14_ws_err_${UUID.randomUUID()}"
        val text = "Mensagem que recebe MESSAGE_ERROR do WebSocket"

        // 1. Mensagem registrada no chat
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = text,
            myAlias = "Eu",
            messageId = msgId,
            deliveryStatus = MessageDeliveryStatus.SENT
        )

        // 2. Simula evento WebSocket "MESSAGE_ERROR" vindo do backend
        testNetworkClient.onMessageError?.invoke(msgId, "DATABASE_ERROR: PostgreSQL connection failed")

        // 3. Comprova que a mensagem é mantida no chat e seu conteúdo está íntegro
        val chat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Apenas 1 mensagem deve constar no histórico", 1, chat.count { it.id == msgId })

        val msg = chat.first { it.id == msgId }
        assertEquals("Texto deve permanecer idêntico e intacto para reenvio", text, msg.text)
        assertEquals("Status da mensagem deve ser FAILED", MessageDeliveryStatus.FAILED, msg.deliveryStatus)
    }

    @Test
    fun testT15_retryPrivateMessagePreservaMessageIdERecuperaEntrega() {
        val repo = createRepository()
        val msgId = "msg_t15_retry_${UUID.randomUUID()}"
        val text = "Texto para teste de retentativa durável com mesmo ID"

        // 1. Mensagem enviada inicialmente e marcada como FAILED após erro
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = text,
            myAlias = "Eu",
            messageId = msgId,
            deliveryStatus = MessageDeliveryStatus.FAILED
        )

        val initialChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val initialMsg = initialChat.find { it.id == msgId }
        assertNotNull("Mensagem deve constar no chat", initialMsg)
        assertEquals(MessageDeliveryStatus.FAILED, initialMsg?.deliveryStatus)

        // 2. Executa retentativa através de retryPrivateMessage
        val retryResult = repo.retryPrivateMessage(peerId, msgId)
        assertTrue("Retentativa deve ser aceita", retryResult.isAllowed)

        // 3. Comprova que não gerou mensagem duplicada no chat
        val retriedChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Não pode haver duplicação do messageId no chat após retry", 1, retriedChat.count { it.id == msgId })
        val retriedMsg = retriedChat.first { it.id == msgId }
        assertEquals("Conteúdo da mensagem deve permanecer intacto", text, retriedMsg.text)

        // 4. Recebe confirmação criptográfica de entrega (ACK)
        val ackOk = repo.processVerifiedNetworkPayload(peerId, "Contato", "[DELIVERY_ACK:$msgId]", "ack_t15")
        assertTrue(ackOk)

        // 5. Comprova transição para DELIVERED
        val deliveredChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        val deliveredMsg = deliveredChat.first { it.id == msgId }
        assertEquals(MessageDeliveryStatus.DELIVERED, deliveredMsg.deliveryStatus)

        // 6. Blindagem da máquina de estados: mensagem DELIVERED não regride para SENT
        repo.proximitySimulator.updateMessageDeliveryStatus(peerId, msgId, MessageDeliveryStatus.SENT)
        val afterAttemptChat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Mensagem confirmada como DELIVERED jamais regride para SENT",
            MessageDeliveryStatus.DELIVERED, afterAttemptChat.first { it.id == msgId }.deliveryStatus)
    }

    @Test
    fun testT16_lateTransportErrorNeverUndoesDeliveredOrReadStatus() {
        val repo = createRepository()
        val msgId1 = "msg_t16_del_${UUID.randomUUID()}"
        val msgId2 = "msg_t16_read_${UUID.randomUUID()}"

        // 1. Cria mensagem 1 e transita para DELIVERED
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = "Mensagem Entregue",
            myAlias = "Eu",
            messageId = msgId1,
            deliveryStatus = MessageDeliveryStatus.SENT
        )
        val ackOk = repo.processVerifiedNetworkPayload(peerId, "Contato", "[DELIVERY_ACK:$msgId1]", "ack_t16")
        assertTrue("ACK deve ser aceito", ackOk)

        var chat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals(MessageDeliveryStatus.DELIVERED, chat.first { it.id == msgId1 }.deliveryStatus)

        // 2. Erros tardios de transporte (networkClient.onMessageError ou bleTransportCoordinator.onDeliveryFailed)
        // Tentativa de forçar FAILED na mensagem DELIVERED
        repo.proximitySimulator.updateMessageDeliveryStatus(peerId, msgId1, MessageDeliveryStatus.FAILED)
        chat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Erro tardio de transporte JAMAIS deve desfazer confirmação DELIVERED",
            MessageDeliveryStatus.DELIVERED, chat.first { it.id == msgId1 }.deliveryStatus)

        // 3. Cria mensagem 2 e transita para READ
        repo.proximitySimulator.sendPrivateMessage(
            recipientId = peerId,
            text = "Mensagem Lida",
            myAlias = "Eu",
            messageId = msgId2,
            deliveryStatus = MessageDeliveryStatus.SENT
        )
        repo.processVerifiedNetworkPayload(peerId, "Contato", "[DELIVERY_ACK:$msgId2]", "ack_t16_2")
        repo.processVerifiedNetworkPayload(peerId, "Contato", "[READ_ACK]", "ack_t16_read")

        chat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals(MessageDeliveryStatus.READ, chat.first { it.id == msgId2 }.deliveryStatus)

        // 4. Erros tardios de transporte tentando forçar FAILED ou SENT na mensagem READ
        repo.proximitySimulator.updateMessageDeliveryStatus(peerId, msgId2, MessageDeliveryStatus.FAILED)
        repo.proximitySimulator.updateMessageDeliveryStatus(peerId, msgId2, MessageDeliveryStatus.SENT)
        chat = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Erro tardio de transporte JAMAIS deve desfazer confirmação READ",
            MessageDeliveryStatus.READ, chat.first { it.id == msgId2 }.deliveryStatus)
    }
}
