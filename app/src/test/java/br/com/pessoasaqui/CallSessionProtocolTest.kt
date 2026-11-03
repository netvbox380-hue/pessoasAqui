package br.com.pessoasaqui

import br.com.pessoasaqui.core.media.CallEndReason
import br.com.pessoasaqui.core.media.CallSessionMachine
import br.com.pessoasaqui.core.media.CallSessionProtocol
import br.com.pessoasaqui.core.media.CallSignalType
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class CallSessionProtocolTest {
    private val alice = "A1B2C3D4"
    private val bob = "BB22CC33"
    private val id = UUID.randomUUID().toString()

    @Test fun v2RoundTripIncludesUniqueIdentifier() {
        val init = CallSessionProtocol.parse(CallSessionProtocol.invite(true, "Fábio", id))!!
        assertEquals(CallSignalType.INIT, init.type)
        assertEquals(id, init.callId)
        assertEquals("Fábio", init.alias)
        assertTrue(init.video)
        assertEquals(CallSignalType.RINGING, CallSessionProtocol.parse(CallSessionProtocol.ringing(id))!!.type)
        assertEquals(CallSignalType.ACCEPT, CallSessionProtocol.parse(CallSessionProtocol.accept(id))!!.type)
        val end = CallSessionProtocol.parse(CallSessionProtocol.end(id, CallEndReason.CANCELLED))!!
        assertEquals(id, end.callId)
        assertEquals(CallEndReason.CANCELLED, end.endReason)
    }

    @Test fun legacyInviteAndAcceptAreStillRecognized() {
        val init = CallSessionProtocol.parse("[CALL_INIT:false:Alice]")!!
        assertNull(init.callId)
        assertFalse(init.video)
        assertEquals("Alice", init.alias)
        assertNull(CallSessionProtocol.parse("[CALL_ACCEPT]")!!.callId)
        assertNull(CallSessionProtocol.parse("[CALL_END]")!!.callId)
    }

    @Test fun unexpectedAndSpoofedCallIdentifiersAreRejected() {
        assertNull(CallSessionProtocol.parse("[CALL_ACCEPT:INVALID]"))
        assertNull(CallSessionProtocol.parse("[CALL_RINGING:INVALID]"))
        assertNull(CallSessionProtocol.parse("[CALL_END:$id:UNRECOGNIZED]"))
        assertNull(CallSessionProtocol.parse("[CALL_INIT:maybe:Alice:$id]"))
        assertNull(CallSessionProtocol.parse("[CALL_AUDIO_FRAME:abc]"))
    }

    @Test fun onlyMatchingSenderAndIdCanAcceptOrEnd() {
        val fsm = CallSessionMachine()
        val session = fsm.outgoing(bob, video = true)!!
        assertFalse(fsm.remoteAccept(alice, session.id))
        assertFalse(fsm.remoteAccept(bob, UUID.randomUUID().toString()))
        assertFalse(fsm.validRemoteEnd(alice, session.id))
        assertFalse(fsm.validRemoteEnd(bob, UUID.randomUUID().toString()))
        assertTrue(fsm.ringing(bob, session.id))
        assertFalse("V2 não aceita CALL_ACCEPT legado sem ID após negociação", fsm.remoteAccept(bob, null))
        assertTrue(fsm.remoteAccept(bob, session.id))
        assertFalse("Duplicata de ACCEPT não muda ACTIVE", fsm.remoteAccept(bob, session.id))
        assertTrue(fsm.validRemoteEnd(bob, session.id))
        assertNotNull(fsm.finish())
        assertNull(fsm.active)
        assertFalse(fsm.validRemoteEnd(bob, session.id))
    }

    @Test fun replayedInviteAndSimultaneousCallsDoNotOverrideActiveSession() {
        val fsm = CallSessionMachine()
        val event = CallSessionProtocol.parse(CallSessionProtocol.invite(false, "Alice", id, issuedAtMs = 1000L))!!
        assertEquals(CallSessionMachine.OfferResult.NEW, fsm.incoming(alice, event, 1000L))
        assertEquals(CallSessionMachine.OfferResult.DUPLICATE, fsm.incoming(alice, event, 1001L))
        assertEquals(CallSessionMachine.OfferResult.BUSY, fsm.incoming(bob, event, 1002L))
        assertNotNull(fsm.answer())
        assertNull("Apenas quem recebe pode atender", fsm.answer())
        assertNotNull(fsm.finish(2000L))
        assertEquals(CallSessionMachine.OfferResult.STALE, fsm.incoming(alice, event, 2001L))
        assertEquals(CallSessionMachine.OfferResult.STALE, fsm.incoming(alice, event, 599_999L))
        assertEquals("Convites expirados não podem reabrir chamadas após reiniciar o app",
            CallSessionMachine.OfferResult.STALE, fsm.incoming(alice, event, 602_001L))
    }

    @Test fun expiredInviteAndFutureDatedInviteDoNotRing() {
        val fsm = CallSessionMachine()
        val old = CallSessionProtocol.parse(CallSessionProtocol.invite(false, "Alice", id, 1000L))!!
        assertEquals(CallSessionMachine.OfferResult.STALE, fsm.incoming(alice, old, 400_000L))
        val future = CallSessionProtocol.parse(CallSessionProtocol.invite(false, "Alice", id, 900_000L))!!
        assertEquals(CallSessionMachine.OfferResult.STALE, fsm.incoming(alice, future, 1000L))
        assertNull(fsm.active)
    }

    @Test fun incomingLegacyCallDoesNotRequireMutualProtocolV2() {
        val fsm = CallSessionMachine()
        val legacy = CallSessionProtocol.parse("[CALL_INIT:false:Old Phone]")!!
        assertEquals(CallSessionMachine.OfferResult.NEW, fsm.incoming(alice, legacy))
        assertEquals(CallSessionMachine.OfferResult.DUPLICATE, fsm.incoming(alice, legacy))
        assertNotNull(fsm.answer())
        assertTrue(fsm.remoteAck(alice, null))
        assertTrue(fsm.validRemoteEnd(alice, null))
        assertNotNull(fsm.finish())
    }
}
