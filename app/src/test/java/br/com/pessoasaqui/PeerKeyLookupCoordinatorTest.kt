package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.PeerKeyLookupCoordinator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PeerKeyLookupCoordinatorTest {
    @Test
    fun tenConcurrentLookupsForSamePeerRunOnlyOneActionAtATime() = runTest {
        val coordinator = PeerKeyLookupCoordinator()
        val releaseFirstLookup = CompletableDeferred<Unit>()
        var networkInvocations = 0
        var authenticatedKeyCached = false

        val requests = (1..10).map {
            async {
                coordinator.withPeerLock("PEER-A") {
                    // Mirrors the repository's mandatory cache re-check after
                    // acquiring the per-peer lock and before making HTTP.
                    if (!authenticatedKeyCached) {
                        networkInvocations += 1
                        releaseFirstLookup.await()
                        authenticatedKeyCached = true
                    }
                    "authenticated"
                }
            }
        }

        runCurrent()
        assertEquals("Only the lock holder may start the lookup", 1, networkInvocations)

        releaseFirstLookup.complete(Unit)
        advanceUntilIdle()

        assertEquals(10, requests.size)
        assertTrue(requests.all { it.await() == "authenticated" })
        assertEquals("The cache re-check under the lock must reduce ten concurrent requests to one network lookup.", 1, networkInvocations)
    }

    @Test
    fun differentPeerIdentitiesDoNotBlockEachOther() = runTest {
        val coordinator = PeerKeyLookupCoordinator()
        val gateA = CompletableDeferred<Unit>()
        val gateB = CompletableDeferred<Unit>()
        var startedA = false
        var startedB = false

        val a = async {
            coordinator.withPeerLock("PEER-A") {
                startedA = true
                gateA.await()
            }
        }
        val b = async {
            coordinator.withPeerLock("PEER-B") {
                startedB = true
                gateB.await()
            }
        }

        runCurrent()
        assertTrue(startedA)
        assertTrue(startedB)
        gateA.complete(Unit)
        gateB.complete(Unit)
        advanceUntilIdle()
        a.await()
        b.await()
    }

    @Test
    fun cancellingAQueuedLookupDoesNotStrandThePeerLock() = runTest {
        val coordinator = PeerKeyLookupCoordinator()
        val releaseHolder = CompletableDeferred<Unit>()
        var actionsStarted = 0

        val holder = async {
            coordinator.withPeerLock("PEER-A") {
                actionsStarted += 1
                releaseHolder.await()
            }
        }
        runCurrent()
        assertEquals(1, actionsStarted)

        val queued = async {
            coordinator.withPeerLock("PEER-A") {
                actionsStarted += 1
            }
        }
        runCurrent()
        assertEquals(1, actionsStarted)
        queued.cancelAndJoin()

        releaseHolder.complete(Unit)
        holder.await()

        coordinator.withPeerLock("PEER-A") {
            actionsStarted += 1
        }
        assertEquals(2, actionsStarted)
    }
}
