package br.com.pessoasaqui.core.crypto

/** Authoritative per-pair revisions are never derived from local wall-clock time. */
object ConnectionRevisionPolicy {
    fun isNewerEvent(incoming: Long, saved: Long): Boolean =
        incoming > 0L && incoming > saved

    /** A missing pair in an older snapshot cannot undo an already versioned WS event. */
    fun preserveLocalSnapshotState(saved: Long, incoming: Long?): Boolean =
        saved > 0L && (incoming == null || incoming < saved)
}
