package br.com.pessoasaqui.core.media

import java.util.UUID

/**
 * Envelopes de sinalização compatíveis com os clientes legados.
 *
 * CALL_INIT tem callId opcional no terceiro campo; clientes antigos ignoram
 * campos extras. ACCEPT/ACK/END mantêm o prefixo antigo e acrescentam #callId.
 * Um receptor antigo continua reconhecendo esses comandos via startsWith().
 *
 * O identificador vincula mensagens da MESMA ligação; nunca o substitua pelo
 * ID da mensagem WebSocket/HTTP, pois retransmissões têm IDs distintos.
 */
object CallSignalingProtocol {
    private val uuidPattern = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    fun newCallId(): String = UUID.randomUUID().toString()

    fun invite(video: Boolean, alias: String, callId: String): String {
        require(uuidPattern.matches(callId))
        // O separador ':' pertence ao wire format legado.
        val safeAlias = alias.replace(':', ' ').replace(']', ' ').take(64)
        return "[CALL_INIT:$video:$safeAlias:$callId]"
    }

    fun invitationCallId(payload: String): String? {
        if (!payload.startsWith("[CALL_INIT:") || !payload.endsWith("]")) return null
        val fields = payload.removePrefix("[CALL_INIT:").removeSuffix("]").split(":")
        return fields.getOrNull(2)?.takeIf(uuidPattern::matches)
    }

    enum class Command(val legacyPrefix: String) {
        ACCEPT("[CALL_ACCEPT]"),
        ACK("[CALL_ACK]"),
        END("[CALL_END]")
    }

    fun encode(command: Command, callId: String): String {
        require(uuidPattern.matches(callId))
        return "${command.legacyPrefix}#$callId"
    }

    /** null significa que o outro cliente usa o protocolo legado sem callId. */
    fun commandCallId(payload: String, command: Command): String? {
        if (!payload.startsWith(command.legacyPrefix)) return null
        val suffix = payload.removePrefix(command.legacyPrefix)
        if (suffix.isEmpty()) return null
        return suffix.removePrefix("#").takeIf(uuidPattern::matches)
    }

    /**
     * O legado sem sufixo é aceito apenas se existir uma chamada ativa para
     * aquele remetente; com ID presente ele obrigatoriamente deve coincidir.
     */
    fun belongsToCall(payload: String, command: Command, currentCallId: String?): Boolean {
        if (currentCallId == null || !payload.startsWith(command.legacyPrefix)) return false
        val suffix = payload.removePrefix(command.legacyPrefix)
        if (suffix.isEmpty()) return true // compatibilidade com clientes anteriores
        if (!suffix.startsWith("#")) return false
        return commandCallId(payload, command) == currentCallId
    }
}
