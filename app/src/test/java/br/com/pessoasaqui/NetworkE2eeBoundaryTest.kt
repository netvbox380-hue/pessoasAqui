package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.core.crypto.TrustedIdentityKeys
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
    @Test fun authenticatedP256EcdhControlMessageStillWorks() {
        // Valida o caminho real de ciphertext, não apenas o dispatcher interno.
        val sender=CryptoIdentityManager()
        val receiver=CryptoIdentityManager()
        val senderId=sender.getTechnicalIdentity()
        val receiverId=receiver.getTechnicalIdentity()
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(
            senderId,sender.getPublicKeyBase64(),1L
        ))
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(
            receiverId,receiver.getPublicKeyBase64(),1L
        ))
        E2eeCryptoEngine.registerPeerPublicKey(senderId,sender.getPublicKeyBase64())
        E2eeCryptoEngine.registerPeerPublicKey(receiverId,receiver.getPublicKeyBase64())
        val repo=PessoasAquiRepository(cryptoIdentityManager=receiver)
        repo.setMarkPerson(senderId,mark=true)
        repo.confirmMutualConnectionFromServer(senderId)
        val (payload,iv)=E2eeCryptoEngine.encrypt(
            "[CALL_INIT:false:Contato]",senderId,receiverId,
            sender.getActivePrivateKey()
        )
        assertTrue(payload.startsWith("ENC_ECDH:"))
        repo.processIncomingPayload(senderId,"Contato",payload,iv,"authenticated-call")
        assertNotNull("Chamada autenticada não pode ser bloqueada",repo.activeCallState.value)
    }

    @Test fun tamperedEcdhCallMustNotRing() {
        val sender=CryptoIdentityManager()
        val receiver=CryptoIdentityManager()
        val senderId=sender.getTechnicalIdentity()
        val receiverId=receiver.getTechnicalIdentity()
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(
            senderId,sender.getPublicKeyBase64(),1L
        ))
        assertTrue(TrustedIdentityKeys.acceptAuthenticatedBinding(
            receiverId,receiver.getPublicKeyBase64(),1L
        ))
        E2eeCryptoEngine.registerPeerPublicKey(senderId,sender.getPublicKeyBase64())
        E2eeCryptoEngine.registerPeerPublicKey(receiverId,receiver.getPublicKeyBase64())
        val repo=PessoasAquiRepository(cryptoIdentityManager=receiver)
        repo.setMarkPerson(senderId,mark=true)
        repo.confirmMutualConnectionFromServer(senderId)
        val (cipher,iv)=E2eeCryptoEngine.encrypt(
            "[CALL_INIT:true:Contato]",senderId,receiverId,
            sender.getActivePrivateKey()
        )
        val badIv=iv.replaceRange(0,1,if(iv[0]=='A') "B" else "A")
        repo.processIncomingPayload(senderId,"Contato",cipher,badIv,"tampered-call")
        assertNull("Tag AES-GCM inválida jamais inicia chamada",repo.activeCallState.value)
    }

}
