package br.com.pessoasaqui.core.crypto

/**
 * Transient negative cache for authenticated remote-key lookups.
 * Only suppresses requests; never supplies a key or relaxes pinning.
 * Times are monotonic elapsed milliseconds, not wall-clock timestamps.
 */
class KeyLookupRetryPolicy {
    private data class State(val failures: Int, val retryAtMs: Long)
    private val failed = HashMap<String, State>()

    @Synchronized
    fun shouldAttempt(peer: String, nowMs: Long): Boolean =
        nowMs >= (failed[peer]?.retryAtMs ?: Long.MIN_VALUE)

    @Synchronized
    fun recordSuccess(peer: String) { failed.remove(peer) }

    @Synchronized
    fun recordFailure(peer: String, nowMs: Long, notFound: Boolean): Long {
        val count = ((failed[peer]?.failures ?: 0) + 1).coerceAtMost(16)
        val base = if (notFound) 60_000L else 15_000L
        val max = if (notFound) 15 * 60_000L else 5 * 60_000L
        val shift = (count - 1).coerceAtMost(5)
        val delay = (base * (1L shl shift)).coerceAtMost(max)
        failed[peer] = State(count, nowMs + delay)
        return delay
    }

    @Synchronized
    fun invalidate(peer: String) { failed.remove(peer) }
}
