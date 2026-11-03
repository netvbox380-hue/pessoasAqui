package br.com.pessoasaqui

import br.com.pessoasaqui.core.media.CallSignalingProtocol
import br.com.pessoasaqui.core.media.CallSignalingProtocol.Command
import org.junit.Assert.*
import org.junit.Test

/** Transições e correlação de mensagens, sem rede real nem Android. */
class CallSignalingProtocolTest {
    @Test fun invitePreservesLegacyCallInitAndCarriesSharedCallId() {
        val id = CallSignalingProtocol.newCallId()
        val msg = CallSignalingProtocol.invite(true, "Ana:Silva", id)
        assertTrue(msg.startsWith("[CALL_INIT:true:Ana Silva:"))
        assertEquals(id, CallSignalingProtocol.invitationCallId(msg))
        assertNull(CallSignalingProtocol.invitationCallId("[CALL_INIT:true:Ana]"))
    }

    @Test fun signalsRemainRecognizableByLegacyClients() {
        val id = CallSignalingProtocol.newCallId()
        for (command in Command.values()) {
            val wire = CallSignalingProtocol.encode(command, id)
            assertTrue(wire.startsWith(command.legacyPrefix))
            assertEquals(id, CallSignalingProtocol.commandCallId(wire, command))
            assertTrue(CallSignalingProtocol.belongsToCall(wire, command, id))
        }
    }

    @Test fun delayedOldCallCannotEndCurrentCall() {
        val oldId = CallSignalingProtocol.newCallId()
        val newId = CallSignalingProtocol.newCallId()
        val stale = CallSignalingProtocol.encode(Command.END, oldId)
        assertFalse(CallSignalingProtocol.belongsToCall(stale, Command.END, newId))
        assertFalse(CallSignalingProtocol.belongsToCall("[CALL_END]#invalid", Command.END, newId))
        assertFalse(CallSignalingProtocol.belongsToCall("[CALL_ACCEPT]garbage", Command.ACCEPT, newId))
    }

    @Test fun trueLegacyEventsWorkOnlyWithinActiveCall() {
        val id = CallSignalingProtocol.newCallId()
        assertTrue(CallSignalingProtocol.belongsToCall("[CALL_ACCEPT]", Command.ACCEPT, id))
        assertTrue(CallSignalingProtocol.belongsToCall("[CALL_END]", Command.END, id))
        assertFalse(CallSignalingProtocol.belongsToCall("[CALL_END]", Command.END, null))
    }
}
