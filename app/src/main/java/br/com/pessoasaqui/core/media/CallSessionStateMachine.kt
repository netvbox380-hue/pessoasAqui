package br.com.pessoasaqui.core.media

/**
 * Máquina de estados independente do transporte (WebSocket/REST) e da UI.
 * Uma ligação é sempre identificada por callId e peerId, nunca pela última
 * mensagem que chegou. Operações fora de ordem ou duplicadas não mudam estado.
 */
class CallSessionStateMachine {
    enum class Phase { IDLE, OUTGOING, INCOMING, ACTIVE }
    enum class FinishReason { COMPLETED, CANCELLED, DECLINED, MISSED, NO_ANSWER, BUSY }

    data class Session(
        val callId: String,
        val peerId: String,
        val incoming: Boolean,
        val video: Boolean,
        val phase: Phase,
        val connectedAtMs: Long = 0L
    )

    private var session: Session? = null
    private val recentFinished = LinkedHashSet<String>()

    @Synchronized
    fun current(): Session? = session

    @Synchronized
    fun begin(callId: String, peerId: String, video: Boolean, incoming: Boolean): Boolean {
        if (session != null || callId.isBlank() || peerId.isBlank() || recentFinished.contains(callId)) return false
        session = Session(
            callId = callId,
            peerId = peerId,
            incoming = incoming,
            video = video,
            phase = if (incoming) Phase.INCOMING else Phase.OUTGOING
        )
        return true
    }

    /**
     * Aceite só pode ser emitido pelo usuário chamado ou recebido do
     * destinatário para uma chamada de saída pendente.
     */
    @Synchronized
    fun accept(callId: String, peerId: String, byLocalUser: Boolean, nowMs: Long = System.currentTimeMillis()): Boolean {
        val active = session ?: return false
        if (active.callId != callId || active.peerId != peerId) return false
        if (active.incoming != byLocalUser) return false
        val expected = if (active.incoming) Phase.INCOMING else Phase.OUTGOING
        if (active.phase != expected) return false
        session = active.copy(phase = Phase.ACTIVE, connectedAtMs = nowMs)
        return true
    }

    @Synchronized
    fun finish(callId: String, peerId: String, byTimeout: Boolean = false,
               byRemotePeer: Boolean = false, remoteDeclined: Boolean = false,
               remoteBusy: Boolean = false): FinishReason? {
        val active = session ?: return null
        if (active.callId != callId || active.peerId != peerId) return null
        session = null
        recentFinished.add(callId)
        if (recentFinished.size > 128) recentFinished.remove(recentFinished.first())
        return when {
            active.phase == Phase.ACTIVE -> FinishReason.COMPLETED
            !active.incoming && remoteBusy -> FinishReason.BUSY
            !active.incoming && remoteDeclined -> FinishReason.DECLINED
            active.incoming && (byRemotePeer || byTimeout) -> FinishReason.MISSED
            active.incoming -> FinishReason.DECLINED
            byRemotePeer || byTimeout -> FinishReason.NO_ANSWER
            else -> FinishReason.CANCELLED
        }
    }
}
