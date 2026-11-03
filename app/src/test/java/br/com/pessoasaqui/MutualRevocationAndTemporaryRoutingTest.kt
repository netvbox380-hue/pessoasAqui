package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.BleDirectTransportCoordinator
import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient
import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import br.com.pessoasaqui.domain.model.MessageDeliveryStatus
import br.com.pessoasaqui.data.remote.RemotePeer
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.*
import org.junit.Test

/** Regressões para separação Modo A/Modo B e revogação unilateral.
 *  Context nulo: nenhum teste deve abrir conexão externa com o Render.
 */
class MutualRevocationAndTemporaryRoutingTest {
    private val peerId = "A1B2C3D4E5F60708"

    @Test fun corruptEncryptedCallSignalMustNotAppearAsChatBubble() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.processIncomingPayload(peerId, "Contato", "ENC_ECDH:not-valid-base64", "invalid-IV", "bad-call")
        assertTrue("Nenhum conteúdo ENC_ECDH inválido pode aparecer no chat",
            repo.proximitySimulator.privateChats.value[peerId].orEmpty().isEmpty())
    }

    @Test fun unansweredIncomingCallBecomesOneMissedCallEntry() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.processIncomingPayload(peerId, "Contato", "[CALL_INIT:false:Contato]", "", "call-start-01")
        // O segundo comando CALL_INIT é retransmissão, não outra chamada.
        repo.processIncomingPayload(peerId, "Contato", "[CALL_INIT:false:Contato]", "", "call-start-02")
        assertNotNull(repo.activeCallState.value)
        repo.processIncomingPayload(peerId, "Contato", "[CALL_END]", "", "call-end-01")
        repo.processIncomingPayload(peerId, "Contato", "[CALL_END]", "", "call-end-02")
        val history = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Uma ligação deve gerar somente um registro", 1, history.size)
        assertTrue(history.single().text.contains("Chamada de voz perdida"))
        assertEquals(br.com.pessoasaqui.domain.model.MessageType.SYSTEM, history.single().messageType)
        assertNull(repo.activeCallState.value)
    }

    @Test fun incomingVideoCallDoesNotAcceptAckBeforeUserAnswers() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.processIncomingPayload(peerId, "Contato", "[CALL_INIT:true:Contato]", "", "video-call-start")
        repo.processIncomingPayload(peerId, "Contato", "[CALL_ACK]", "", "video-call-ack")
        assertFalse("O chamador não pode atender no lugar do destinatário",
            repo.activeCallState.value?.isConnected ?: true)
        repo.processIncomingPayload(peerId, "Contato", "[CALL_END]", "", "video-call-end")
        val history = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals(1, history.size)
        assertTrue(history.single().text.contains("Videochamada perdida"))
        assertNull(repo.activeCallState.value)
    }

    @Test fun callHistoryEntryIsDeduplicatedAndIsASystemRecord() {
        val sim = br.com.pessoasaqui.core.proximity.ProximitySimulator()
        sim.recordCallHistory(peerId, "📞 Chamada de voz não atendida", outgoing = true, myAlias = "Alice", entryId = "call-once")
        sim.recordCallHistory(peerId, "📞 Chamada de voz não atendida", outgoing = true, myAlias = "Alice", entryId = "call-once")
        val events = sim.privateChats.value[peerId].orEmpty()
        assertEquals(1, events.size)
        assertEquals(br.com.pessoasaqui.domain.model.MessageType.SYSTEM, events.single().messageType)
        assertEquals("📞 Chamada de voz não atendida", events.single().safeDisplayText)
    }

    @Test fun delayedOldCallEndCannotTerminateDifferentSession() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        val first = br.com.pessoasaqui.core.media.CallSignalingProtocol.newCallId()
        val second = br.com.pessoasaqui.core.media.CallSignalingProtocol.newCallId()

        repo.processIncomingPayload(peerId, "Contato",
            "[CALL_INIT:false:Contato:$first]", "", "invite-1")
        assertNotNull(repo.activeCallState.value)
        repo.processIncomingPayload(peerId, "Contato", "[CALL_END]#$first", "", "end-1")
        assertNull(repo.activeCallState.value)

        repo.processIncomingPayload(peerId, "Contato",
            "[CALL_INIT:false:Contato:$second]", "", "invite-2")
        repo.processIncomingPayload(peerId, "Contato", "[CALL_END]#$first", "", "stale-end")
        assertNotNull("A chamada antiga não pode encerrar uma ligação nova", repo.activeCallState.value)
        repo.processIncomingPayload(peerId, "Contato", "[CALL_END]#$second", "", "end-2")
        assertNull(repo.activeCallState.value)

        val entries = repo.proximitySimulator.privateChats.value[peerId].orEmpty()
        assertEquals("Um registro por callId", 2, entries.size)
        repo.processIncomingPayload(peerId, "Contato",
            "[CALL_INIT:false:Contato:$second]", "", "delayed-replay")
        assertNull("Convites encerrados não podem tocar novamente", repo.activeCallState.value)
    }

    @Test fun wifiDisconnectMustNotRemoveNativeBleRadarPeer() {
        val repo = PessoasAquiRepository()
        val seenAt = System.currentTimeMillis()
        val person = NearbyPerson(
            id = peerId,
            technicalIdentityHash = peerId,
            alias = "Pessoa BLE",
            estimatedDistanceMeters = 1.0,
            proximityLabel = "1 m",
            lastSeenEpochMs = seenAt
        )
        repo.addDiscoveredPeer(person)
        repo.recordNativeBlePresence(peerId, seenAt)

        repo.handlePeerOffline(peerId) // Internet caiu; BLE continua físico.
        assertTrue("PEER_OFFLINE do Render não remove contato BLE",
            repo.discoveredPeople.value.any { it.technicalIdentityHash == peerId })
    }

    @Test fun internetPresenceCannotRefreshPhysicalBleExpiry() {
        val repo = PessoasAquiRepository()
        val seenAt = System.currentTimeMillis()
        val person = NearbyPerson(
            id = peerId,
            technicalIdentityHash = peerId,
            alias = "Pessoa BLE",
            estimatedDistanceMeters = 1.0,
            proximityLabel = "1 m",
            lastSeenEpochMs = seenAt
        )
        repo.addDiscoveredPeer(person)
        repo.recordNativeBlePresence(peerId, seenAt)

        val remote = RemotePeer(identityHash = peerId, alias = "Pessoa BLE", intent = "QUERO_CONVERSAR")
        repo.handleRemotePresenceSync(listOf(remote))
        repo.handlePeerOnline(remote)

        // Somente anúncio BLE renova a validade física do Radar.
        val removed = repo.cleanupExpiredTemporarySessions(
            now = seenAt + PessoasAquiRepository.TEMPORARY_SESSION_EXPIRATION_MS + 1L
        )
        assertTrue("Backend não pode prolongar artificialmente os 10 metros", peerId in removed)
        assertFalse(repo.discoveredPeople.value.any { it.technicalIdentityHash == peerId })
    }

    @Test fun temporaryChatDoesNotRequireMutualInviteOrInternet() {
        var bleSends = 0
        var internetSends = 0
        val coordinator = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                bleSends++
                return true
            }
        }
        val network = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                internetSends++
                return true
            }
        }
        val repo = PessoasAquiRepository(injectedBleTransportCoordinator = coordinator, networkClient = network)
        assertFalse(repo.isPeerPermanent(peerId))
        repo.sendPrivateMessage(peerId, "Oi por Bluetooth, sem convite")
        assertEquals(1, bleSends)
        assertEquals(0, internetSends)
        assertFalse(repo.isPeerPermanent(peerId))
    }

    @Test fun clickingAcceptBeforeServerConfirmsCannotActivatePermanentMode() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true, explicitMutual = true)
        assertFalse("Aceitação local isolada não é confirmação mútua", repo.isPeerPermanent(peerId))
        assertFalse(repo.isPeerMutual(peerId))
        repo.confirmMutualConnectionFromServer(peerId)
        assertTrue("Somente a confirmação validada do servidor eleva a Modo B", repo.isPeerPermanent(peerId))
    }

    @Test fun revocationImmediatelyDowngradesToTemporaryAndKeepsHistoryPolicy() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        assertTrue(repo.isPeerPermanent(peerId))
        repo.setMarkPerson(peerId, mark = false)
        assertFalse(repo.isPeerPermanent(peerId))
        assertFalse(repo.isPeerMutual(peerId))
    }

    @Test fun serverRevocationInvalidatesRelationshipEvenIfMyMarkRemains() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.revokeMutualLocally(peerId) // WebSocket MUTUAL_CONNECTION_BROKEN
        assertFalse(repo.isPeerPermanent(peerId))
        assertFalse(repo.isPeerMutual(peerId))
    }

    @Test fun serverSyncRemovesMutualNotPresentInAuthoritativeSnapshot() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.reconcileConnectionStatus(listOf(peerId), emptyList(), emptyList())
        assertFalse("O cache local não pode repovoar conexão revogada", repo.isPeerPermanent(peerId))
    }

    @Test fun temporaryEmptyStatusSnapshotDoesNotPermanentlyBanValidMutualRelationship() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.reconcileConnectionStatus(emptyList(), emptyList(), emptyList())
        assertFalse(repo.isPeerPermanent(peerId))
        // A fonte da verdade volta a relatar vínculo bilateral já existente.
        repo.reconcileConnectionStatus(listOf(peerId), listOf(peerId), listOf(peerId))
        assertTrue("Ausência temporária não é revogação irreversível", repo.isPeerPermanent(peerId))
    }

    @Test fun oldConfirmationCannotResurrectRevokedRelationship() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.revokeMutualLocally(peerId)
        repo.confirmMutualConnectionFromServer(peerId) // evento atrasado
        repo.reconcileConnectionStatus(listOf(peerId), listOf(peerId), listOf(peerId)) // status antigo
        assertFalse(repo.isPeerPermanent(peerId))
        assertFalse(repo.isPeerMutual(peerId))
    }

    @Test fun newlyApprovedRelationshipMayBeConfirmedAgain() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peerId, mark = true)
        repo.confirmMutualConnectionFromServer(peerId)
        repo.revokeMutualLocally(peerId)
        assertFalse(repo.isPeerPermanent(peerId))
        repo.setMarkPerson(peerId, mark = true) // nova decisão explícita do usuário
        assertFalse(repo.isPeerPermanent(peerId))
        repo.confirmMutualConnectionFromServer(peerId)
        assertTrue(repo.isPeerPermanent(peerId))
    }

    @Test fun legacyMutualControlTextAloneNeverGrantsPermanentMode() {
        val repo = PessoasAquiRepository()
        repo.processIncomingPayload(peerId, "Contato", "[MARK_MUTUAL:Contato]", "", "legacy-mark-1")
        assertFalse(repo.isPeerPermanent(peerId))
    }

    @Test fun temporaryMediaAndCallsMustNeverBeSentOrStoredBeforeMutualApproval() {
        var bleCalls = 0
        val ble = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean {
                bleCalls++
                return true
            }
        }
        val repo = PessoasAquiRepository(injectedBleTransportCoordinator = ble)
        for (payload in listOf("[IMAGE:base64]", "[DOC:doc.pdf:3:abc]", "[AUDIO:3:abc]", "[CALL_INIT:true:Alice]")) {
            val result = repo.sendPrivateMessage(peerId, payload)
            assertFalse("Mídia/chamada não é permitida em conversas temporárias", result.isAllowed)
        }
        assertEquals("Mídia temporária não pode trafegar pelo BLE", 0, bleCalls)
        assertTrue("Mídia temporária não pode entrar no histórico RAM", repo.proximitySimulator.privateChats.value.isEmpty())
    }

    @Test fun rejectedGattSendMustSetFailedStatusInsteadOfPretendingDelivery() {
        var networkSends = 0
        val ble = object : BleDirectTransportCoordinator(context = null) {
            override fun sendTemporaryMessage(recipientId: String, text: String, myAlias: String, messageUuid: String): Boolean = false
        }
        val network = object : PessoasAquiNetworkClient() {
            override fun sendE2eeEnvelope(recipientHash: String, ciphertext: String, ivNonce: String, messageId: String): Boolean {
                networkSends++
                return true
            }
        }
        val repo = PessoasAquiRepository(injectedBleTransportCoordinator = ble, networkClient = network)
        val result = repo.sendPrivateMessage(peerId, "Mensagem sem rádio disponível")
        assertFalse(result.isAllowed)
        val msg = repo.proximitySimulator.privateChats.value[peerId]?.lastOrNull()
        assertNotNull(msg)
        assertEquals(MessageDeliveryStatus.FAILED, msg?.deliveryStatus)
        assertEquals("Sem fallback para internet", 0, networkSends)
    }

}
