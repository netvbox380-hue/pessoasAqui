package br.com.pessoasaqui.core.backup

import java.util.concurrent.ConcurrentHashMap

/**
 * Confirmações do cofre vindas do próprio contato; ACK de outro contato,
 * de versão anterior, duplicado ou atrasado não contabiliza entrega.
 */
class VaultReplicationAcks {
    private data class Pending(val expected: Set<String>, val confirmed: MutableSet<String>)
    private val pending = ConcurrentHashMap<Long, Pending>()

    private fun id(input: String): String =
        input.removePrefix("peer-").trim().uppercase(java.util.Locale.ROOT)

    fun begin(version: Long, targets: Collection<String>) {
        require(version > 0L)
        val ids = targets.map(::id).filter { it.isNotBlank() }.toSet()
        require(ids.isNotEmpty())
        check(pending.putIfAbsent(version, Pending(ids, ConcurrentHashMap.newKeySet<String>())) == null) {
            "Backup desta versão já está em andamento"
        }
    }

    fun acknowledge(version: Long, sender: String): Boolean {
        val p = pending[version] ?: return false
        val source = id(sender)
        return source in p.expected && p.confirmed.add(source)
    }

    fun confirmedCount(version: Long): Int = pending[version]?.confirmed?.size ?: 0

    fun end(version: Long): Int = pending.remove(version)?.confirmed?.size ?: 0
}
