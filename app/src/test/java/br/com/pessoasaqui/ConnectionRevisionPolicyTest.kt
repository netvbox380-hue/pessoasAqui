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

    @Test fun oldOrMissingRestSnapshotCannotUndoVersionedWebsocketEvent() {
        assertTrue(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, 7L))
        assertTrue(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, null))
        assertFalse(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, 8L))
        assertFalse(ConnectionRevisionPolicy.preserveLocalSnapshotState(8L, 9L))
        assertFalse(ConnectionRevisionPolicy.preserveLocalSnapshotState(0L, null))
    }
}
