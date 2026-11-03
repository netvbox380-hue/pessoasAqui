package br.com.pessoasaqui.core.media

import java.util.UUID

/**
 * Sinalização de chamada v2 sobre o canal E2EE do Modo B.
 *
 * A extensão opcional ":callId" no CALL_INIT preserva a interpretação do
 * cliente legado. Um peer legado recebe somente ACCEPT/END sem ID; peers v2
 * confirmam capacidade enviando RINGING com o ID da chamada.
 */
internal enum class CallSignalType { INIT, RINGING, ACCEPT, ACK, END }
internal enum class CallEndReason { COMPLETED, CANCELLED, DECLINED, TIMEOUT, BUSY, FAILED }

internal data class CallSignal(
    val type: CallSignalType,
    val callId: String? = null,
    val video: Boolean = false,
    val alias: String = "",
    val issuedAtMs: Long? = null,
    val endReason: CallEndReason? = null
)

internal object CallSessionProtocol {
    private fun validId(id: String?): String? =
        id?.takeIf { runCatching { UUID.fromString(it).toString().equals(it, ignoreCase = true) }.getOrDefault(false) }

    fun invite(video: Boolean, alias: String, callId: String, issuedAtMs: Long = System.currentTimeMillis()): String =
        "[CALL_INIT:$video:${alias.replace(':', ' ')}:$callId:$issuedAtMs]"
    fun ringing(id: String) = "[CALL_RINGING:$id]"
    fun accept(id: String) = "[CALL_ACCEPT:$id]"
    fun ack(id: String) = "[CALL_ACK:$id]"
    fun end(id: String, reason: CallEndReason) = "[CALL_END:$id:${reason.name}]"

    fun parse(payload: String): CallSignal? {
        if (!payload.startsWith("[CALL_") || !payload.endsWith("]")) return null
        val body = payload.removePrefix("[").removeSuffix("]")
        val parts = body.split(":")
        return when (parts[0]) {
            "CALL_INIT" -> {
                if (parts.size < 3 || (parts[1] != "true" && parts[1] != "false")) return null
                // Compatibilidade: INIT legado (sem ID), v2 anterior (ID
                // final) ou v2 com tempo de emissão para bloquear replay.
                val issuedAt = if (parts.size >= 5) parts.last().toLongOrNull() else null
                val timestampedId = if (issuedAt != null) validId(parts[parts.lastIndex - 1]) else null
                val id = timestampedId ?: if (parts.size >= 4) validId(parts.last()) else null
                val nameEnd = when {
                    timestampedId != null -> parts.lastIndex - 1
                    id != null -> parts.lastIndex
                    else -> parts.size
                }
                val name = parts.subList(2, nameEnd).joinToString(":")
                CallSignal(CallSignalType.INIT, id, parts[1].toBoolean(), name.take(64),
                    issuedAtMs = if (timestampedId != null) issuedAt else null)
            }
            "CALL_RINGING", "CALL_ACCEPT", "CALL_ACK" -> {
                val type = when (parts[0]) {
                    "CALL_RINGING" -> CallSignalType.RINGING
                    "CALL_ACCEPT" -> CallSignalType.ACCEPT
                    else -> CallSignalType.ACK
                }
                if (parts.size == 1 && type != CallSignalType.RINGING) CallSignal(type)
                else if (parts.size == 2) validId(parts[1])?.let { CallSignal(type, it) }
                else null
            }
            "CALL_END" -> {
                when (parts.size) {
                    1 -> CallSignal(CallSignalType.END)
                    3 -> {
                        val id = validId(parts[1]) ?: return null
                        val reason = runCatching { CallEndReason.valueOf(parts[2]) }.getOrNull() ?: return null
                        CallSignal(CallSignalType.END, id, endReason = reason)
                    }
                    else -> null
                }
            }
            else -> null
        }
    }
}

/**
 * Máquina de estado sem dependência do Android, testável em JVM.
 * A identificação técnica do emissor e o callId são verificados em toda
 * transição. Tombstones bloqueiam INIT atrasados e reenvios após encerramento.
 */
internal class CallSessionMachine {
    enum class Stage { OUTGOING, RINGING, INCOMING, ACTIVE }
    enum class OfferResult { NEW, DUPLICATE, BUSY, STALE }

    data class Session(
        val id: String,
        val peerId: String,
        val incoming: Boolean,
        val video: Boolean,
        val legacy: Boolean,
        var stage: Stage,
        var remoteRinging: Boolean = false,
        var peerSupportsV2: Boolean = false
    )

    private var current: Session? = null
    private val completed = LinkedHashMap<String, Long>()

    @Synchronized
    fun restoreTombstones(entries: Map<String, Long>, nowMs: Long = System.currentTimeMillis()) {
        for ((id, timestamp) in entries) {
            if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false) &&
                timestamp <= nowMs && nowMs - timestamp < 10 * 60_000L
            ) completed[id] = timestamp
        }
        expireTombstones(nowMs)
    }

    @Synchronized
    fun tombstones(): Map<String, Long> = completed.toMap()

    val active: Session?
        @Synchronized get() = current

    @Synchronized
    fun outgoing(peer: String, video: Boolean): Session? {
        if (current != null) return null
        val session = Session(UUID.randomUUID().toString(), peer, false, video, false, Stage.OUTGOING)
        current = session
        return session
    }

    @Synchronized
    fun incoming(peer: String, signal: CallSignal, nowMs: Long = System.currentTimeMillis()): OfferResult {
        if (signal.type != CallSignalType.INIT) return OfferResult.STALE
        val emitted = signal.issuedAtMs
        if (emitted != null &&
            (emitted < nowMs - 180_000L || emitted > nowMs + 300_000L)
        ) return OfferResult.STALE
        expireTombstones(nowMs)
        val id = signal.callId ?: UUID.randomUUID().toString()
        if (id in completed) return OfferResult.STALE
        val prev = current
        if (prev != null) {
            if (prev.incoming && prev.peerId == peer &&
                (signal.callId == prev.id || signal.callId == null && prev.legacy)
            ) return OfferResult.DUPLICATE
            return OfferResult.BUSY
        }
        current = Session(id, peer, true, signal.video, signal.callId == null,
            Stage.INCOMING, peerSupportsV2 = signal.callId != null)
        return OfferResult.NEW
    }

    @Synchronized
    fun ringing(peer: String, id: String?): Boolean {
        val s = current ?: return false
        if (s.incoming || s.peerId != peer || s.id != id || s.stage == Stage.ACTIVE) return false
        s.remoteRinging = true
        s.peerSupportsV2 = true
        s.stage = Stage.RINGING
        return true
    }

    @Synchronized
    fun remoteAccept(peer: String, id: String?): Boolean {
        val s = current ?: return false
        if (s.incoming || s.peerId != peer || s.stage == Stage.ACTIVE) return false
        if (id == null && s.peerSupportsV2) return false
        if (id != null && s.id != id) return false
        if (id != null) s.peerSupportsV2 = true
        s.stage = Stage.ACTIVE
        return true
    }

    @Synchronized
    fun answer(): Session? {
        val s = current ?: return null
        if (!s.incoming || s.stage != Stage.INCOMING) return null
        s.stage = Stage.ACTIVE
        return s
    }

    @Synchronized
    fun remoteAck(peer: String, id: String?): Boolean {
        val s = current ?: return false
        return s.incoming && s.peerId == peer && s.stage == Stage.ACTIVE &&
            (id == s.id || id == null && !s.peerSupportsV2)
    }

    @Synchronized
    fun validRemoteEnd(peer: String, id: String?): Boolean {
        val s = current ?: return false
        return s.peerId == peer && (id == s.id || id == null && !s.peerSupportsV2)
    }

    @Synchronized
    fun finish(nowMs: Long = System.currentTimeMillis()): Session? {
        val s = current ?: return null
        current = null
        completed[s.id] = nowMs
        expireTombstones(nowMs)
        return s
    }

    private fun expireTombstones(nowMs: Long) {
        val it = completed.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (nowMs - entry.value >= 10 * 60_000L) it.remove()
        }
        while (completed.size > 128) completed.remove(completed.keys.first())
    }
}
