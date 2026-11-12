package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.ConnectionRevisionPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionRevisionPolicyTest {
    @Test fun staleOrEqualWebsocketEventsCannotRewindDurableRevision() {
        assertFalse(ConnectionRevisionPolicy.isNewerEvent(0L, 8L))
        assertFalse(ConnectionRevisionPolicy.isNewerEvent(7L, 8L))
        assertFalse(ConnectionRevisionPolicy.isNewerEvent(8L, 8L))
        assertTrue(ConnectionRevisionPolicy.isNewerEvent(9L, 8L))
    }

    @Test fun snapshotMustMatchPairRevisionStateWithoutInventingMutualConsent() {
        val policy = ConnectionRevisionPolicy
        assertTrue(policy.stateMatchesSnapshot("AAAA", "BBBB", "NONE", false, false, false))
        assertTrue(policy.stateMatchesSnapshot("AAAA", "BBBB", "MARKED_LOW", true, false, false))
        assertTrue(policy.stateMatchesSnapshot("AAAA", "BBBB", "MARKED_HIGH", false, true, false))
        assertTrue(policy.stateMatchesSnapshot("AAAA", "BBBB", "MUTUAL", true, true, true))
        assertTrue(policy.stateMatchesSnapshot("BBBB", "AAAA", "MARKED_LOW", false, true, false))
        assertTrue(policy.stateMatchesSnapshot("BBBB", "AAAA", "MARKED_HIGH", true, false, false))
        assertFalse(policy.stateMatchesSnapshot("AAAA", "BBBB", "MUTUAL", true, false, true))
        assertFalse(policy.stateMatchesSnapshot("AAAA", "BBBB", "NONE", true, false, false))
        assertFalse(policy.stateMatchesSnapshot("AAAA", "BBBB", "UNTRUSTED", false, false, false))
    }

    @Test fun oldOrMissingRestSnapshotCannotUndoVersionedWebsocketEvent() {
        assertTrue(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, 7L))
        assertTrue(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, null))
        assertFalse(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, 8L))
        assertFalse(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, 9L))
        assertFalse(ConnectionRevisionPolicy.preserveLocalSnapshotState(0L, null))
    }
}
