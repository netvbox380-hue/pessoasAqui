package br.com.pessoasaqui.core.crypto

/** Authoritative per-pair revisions are never derived from local wall-clock time. */
object ConnectionRevisionPolicy {
    fun isNewerEvent(incoming: Long, saved: Long): Boolean =
        incoming > 0L && incoming > saved

    /** A missing pair in an older snapshot cannot undo an already versioned WS event. */
    fun preserveLocalSnapshotState(saved: Long, incoming: Long?): Boolean =
        saved > 0L && (incoming == null || incoming < saved)

    /** Check that the revisioned state agrees with direction rows in the
     * same PostgreSQL statement-level MVCC snapshot. */
    fun stateMatchesSnapshot(
        myIdentity: String,
        peerIdentity: String,
        state: String,
        markedByMe: Boolean,
        markingMe: Boolean,
        isMutual: Boolean
    ): Boolean {
        if (myIdentity.isBlank() || peerIdentity.isBlank() ||
            myIdentity.equals(peerIdentity, ignoreCase = true)) return false
        val isLow = myIdentity.compareTo(peerIdentity, ignoreCase = true) < 0
        val expectedOwn = state == "MUTUAL" ||
            (state == "MARKED_LOW" && isLow) ||
            (state == "MARKED_HIGH" && !isLow)
        val expectedIncoming = state == "MUTUAL" ||
            (state == "MARKED_HIGH" && isLow) ||
            (state == "MARKED_LOW" && !isLow)
        return state in setOf("NONE", "MUTUAL", "MARKED_LOW", "MARKED_HIGH") &&
            markedByMe == expectedOwn &&
            markingMe == expectedIncoming &&
            isMutual == (state == "MUTUAL")
    }
}
