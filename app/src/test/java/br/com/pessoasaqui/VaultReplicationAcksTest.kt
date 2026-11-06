package br.com.pessoasaqui

import br.com.pessoasaqui.core.backup.VaultReplicationAcks
import org.junit.Assert.*
import org.junit.Test

class VaultReplicationAcksTest {
    @Test fun onlyExpectedUniqueAcksCountForExactVersion() {
        val ack = VaultReplicationAcks()
        ack.begin(19, listOf("peer-AABBCCDD", "11223344", "peer-99887766"))
        assertFalse(ack.acknowledge(18, "AABBCCDD"))
        assertFalse(ack.acknowledge(19, "UNKNOWN"))
        assertTrue(ack.acknowledge(19, "AABBCCDD"))
        assertFalse(ack.acknowledge(19, "peer-aabbccdd"))
        assertTrue(ack.acknowledge(19, "11223344"))
        assertEquals(2, ack.confirmedCount(19))
        assertEquals(2, ack.end(19))
        assertFalse(ack.acknowledge(19, "99887766"))
    }

    @Test fun pendingVersionsRemainIsolated() {
        val ack = VaultReplicationAcks()
        ack.begin(19, listOf("AABB"))
        ack.begin(20, listOf("BBCC"))
        assertFalse(ack.acknowledge(20, "AABB"))
        assertTrue(ack.acknowledge(19, "AABB"))
        assertEquals(0, ack.confirmedCount(20))
        assertEquals(1, ack.end(19))
        assertEquals(0, ack.end(20))
    }

    @Test(expected = IllegalStateException::class)
    fun duplicateInFlightVersionCannotOverwriteAckState() {
        val ack=VaultReplicationAcks()
        ack.begin(1, listOf("aabb"))
        ack.begin(1, listOf("bbcc"))
    }
}
