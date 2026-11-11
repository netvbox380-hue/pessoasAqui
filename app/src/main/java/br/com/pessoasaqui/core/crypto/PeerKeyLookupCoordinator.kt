package br.com.pessoasaqui.core.crypto

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * Serializes authenticated public-key lookups per peer identity.
 *
 * The action executes while holding that peer's coroutine mutex. Different
 * peers remain independent. Cancellation and exceptions always release the lock.
 */
class PeerKeyLookupCoordinator {
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun <T> withPeerLock(peerIdentity: String, action: suspend () -> T): T {
        require(peerIdentity.isNotBlank()) { "Peer identity must not be blank" }
        val lock = locks.computeIfAbsent(peerIdentity) { Mutex() }
        lock.lock()
        try {
            return action()
        } finally {
            lock.unlock()
        }
    }
}
