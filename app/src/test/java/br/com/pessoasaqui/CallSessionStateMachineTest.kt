package br.com.pessoasaqui

import br.com.pessoasaqui.core.media.CallSessionStateMachine
import br.com.pessoasaqui.core.media.CallSessionStateMachine.FinishReason
import br.com.pessoasaqui.core.media.CallSessionStateMachine.Phase
import org.junit.Assert.*
import org.junit.Test

class CallSessionStateMachineTest {
    private val peer = "A1B2C3D4"

    @Test fun incomingCannotBeAcceptedByRemotePeerOrMedia() {
        val fsm = CallSessionStateMachine()
        assertTrue(fsm.begin("c-1", peer, video = false, incoming = true))
        assertEquals(Phase.INCOMING, fsm.current()?.phase)
        assertFalse(fsm.accept("c-1", peer, byLocalUser = false))
        assertFalse(fsm.accept("c-1", "OTHER", byLocalUser = true))
        assertEquals(Phase.INCOMING, fsm.current()?.phase)
        assertEquals(FinishReason.MISSED, fsm.finish("c-1", peer, byTimeout = true))
    }

    @Test fun outgoingOnlyRemoteAcceptanceEstablishesCall() {
        val fsm = CallSessionStateMachine()
        assertTrue(fsm.begin("c-2", peer, video = true, incoming = false))
        assertFalse(fsm.accept("c-2", peer, byLocalUser = true))
        assertTrue(fsm.accept("c-2", peer, byLocalUser = false, nowMs = 1000L))
        assertEquals(Phase.ACTIVE, fsm.current()?.phase)
        assertFalse("Duplicated ACCEPT is inert", fsm.accept("c-2", peer, byLocalUser = false))
        assertEquals(FinishReason.COMPLETED, fsm.finish("c-2", peer))
    }

    @Test fun delayedOrForeignEndNeverClosesNewerCall() {
        val fsm = CallSessionStateMachine()
        assertTrue(fsm.begin("old", peer, false, false))
        assertEquals(FinishReason.CANCELLED, fsm.finish("old", peer))
        assertFalse("Old invite cannot replay", fsm.begin("old", peer, false, true))
        assertTrue(fsm.begin("new", peer, false, false))
        assertNull(fsm.finish("old", peer, byRemotePeer = true))
        assertNull(fsm.finish("new", "WRONG", byRemotePeer = true))
        assertEquals(Phase.OUTGOING, fsm.current()?.phase)
        assertEquals(FinishReason.NO_ANSWER, fsm.finish("new", peer, byTimeout = true))
        assertNull("Repeated END must not produce a second history record", fsm.finish("new", peer))
    }

    @Test fun busyAndDeclinedHaveDifferentTerminalOutcomes() {
        val declined = CallSessionStateMachine()
        assertTrue(declined.begin("reject-1", peer, false, false))
        assertEquals(FinishReason.DECLINED,
            declined.finish("reject-1", peer, byRemotePeer = true, remoteDeclined = true))
        assertEquals(Phase.IDLE, declined.phase())

        val busy = CallSessionStateMachine()
        assertTrue(busy.begin("busy-1", peer, true, false))
        assertEquals(FinishReason.BUSY,
            busy.finish("busy-1", peer, byRemotePeer = true, remoteBusy = true))
        assertEquals(Phase.IDLE, busy.phase())
    }

    @Test fun busyDeviceRejectsSecondCallWithoutOverwritingFirst() {
        val fsm = CallSessionStateMachine()
        assertTrue(fsm.begin("one", peer, false, true))
        assertFalse(fsm.begin("two", "DIFFERENT", true, false))
        assertEquals("one", fsm.current()?.callId)
        assertEquals(FinishReason.DECLINED, fsm.finish("one", peer))
    }
}
