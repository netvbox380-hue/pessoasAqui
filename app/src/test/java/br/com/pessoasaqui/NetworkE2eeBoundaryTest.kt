package br.com.pessoasaqui

import br.com.pessoasaqui.data.repository.PessoasAquiRepository
import org.junit.Assert.*
import org.junit.Test

/**
 * Endpoint de recepção WebSocket/HTTP: nenhuma sequência de bytes sem ECDH
 * pode alcançar o dispatcher de chamadas, vínculos ou recuperação V3.
 */
class NetworkE2eeBoundaryTest {
    private val peer = "A1B2C3D4E5F60708"

    @Test fun unsignedCallInviteCannotStartRinging() {
        val repo = PessoasAquiRepository()
        repo.setMarkPerson(peer, mark = true)
        repo.confirmMutualConnectionFromServer(peer)
        val before=repo.proximitySimulator.privateChats.value[peer].orEmpty().toList()
        repo.processIncomingPayload(peer, "Contato", "[CALL_INIT:false:Contato]",
            "", "unencrypted-call")
        assertNull(repo.activeCallState.value)
        assertEquals(before,repo.proximitySimulator.privateChats.value[peer].orEmpty())
    }

    @Test fun plaintextNetworkCommandsCannotAlterMutualState() {
        val repo=PessoasAquiRepository()
        repo.processIncomingPayload(peer,"Contato","[MARK_ON:Contato]","","plain-mark")
        repo.processIncomingPayload(peer,"Contato","[MARK_MUTUAL:Contato]","","plain-mutual")
        assertFalse(repo.isPeerPermanent(peer))
        assertFalse(repo.isPeerMutual(peer))
    }

    @Test fun legacyPredictableCiphertextIsNotAcceptedOnNetwork() {
        val repo=PessoasAquiRepository()
        repo.setMarkPerson(peer,mark=true)
        repo.confirmMutualConnectionFromServer(peer)
        val original=repo.proximitySimulator.privateChats.value[peer].orEmpty().toList()
        repo.processIncomingPayload(peer,"Contato","ENC:ABCD","AAAA","legacy")
        assertEquals(original,repo.proximitySimulator.privateChats.value[peer].orEmpty())
        assertNull(repo.activeCallState.value)
    }

    @Test fun malformedEcdhEnvelopeCannotTriggerControlOperation() {
        val repo=PessoasAquiRepository()
        repo.setMarkPerson(peer,mark=true)
        repo.confirmMutualConnectionFromServer(peer)
        val original=repo.proximitySimulator.privateChats.value[peer].orEmpty().toList()
        repo.processIncomingPayload(peer,"Contato","ENC_ECDH:BAD","BAD","malformed")
        assertEquals(original,repo.proximitySimulator.privateChats.value[peer].orEmpty())
        assertNull(repo.activeCallState.value)
    }

    @Test fun forgedFamilyRecoveryCommandIsNeverPresentedAsUserMessage() {
        val repo=PessoasAquiRepository()
        repo.setMarkPerson(peer,mark=true)
        repo.confirmMutualConnectionFromServer(peer)
        val original=repo.proximitySimulator.privateChats.value[peer].orEmpty().toList()
        repo.processIncomingPayload(peer,"Contato",
            "[VAULT_SHARE_REQUEST_V1:PA-REC-${"A".repeat(64)}:$peer]",
            "", "forged-guardian")
        assertEquals(original,repo.proximitySimulator.privateChats.value[peer].orEmpty())
    }
}
