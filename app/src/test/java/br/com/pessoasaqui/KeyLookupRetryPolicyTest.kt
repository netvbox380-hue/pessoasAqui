package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.KeyLookupRetryPolicy
import org.junit.Assert.*
import org.junit.Test

class KeyLookupRetryPolicyTest {
    @Test fun notFoundBackoffPreventsEveryFourSecondRetry() {
        val cache = KeyLookupRetryPolicy()
        assertTrue(cache.shouldAttempt("AAA", 1000L))
        assertEquals(60_000L, cache.recordFailure("AAA", 1000L, true))
        assertFalse(cache.shouldAttempt("AAA", 5000L))
        assertFalse(cache.shouldAttempt("AAA", 60_999L))
        assertTrue(cache.shouldAttempt("AAA", 61_000L))
        assertEquals(120_000L, cache.recordFailure("AAA", 61_000L, true))
        assertFalse(cache.shouldAttempt("AAA", 181_000L - 1L))
        assertTrue(cache.shouldAttempt("AAA", 181_000L))
    }

    @Test fun serverOutageUsesShorterBoundedBackoff() {
        val cache = KeyLookupRetryPolicy()
        var time = 0L
        var delay = 0L
        repeat(12) {
            delay = cache.recordFailure("AAA", time, false)
            time += delay
        }
        assertEquals(300_000L, delay)
        assertTrue(cache.shouldAttempt("AAA", time))
    }

    @Test fun successfulReverificationAndExplicitInvalidationClearNegativeCache() {
        val cache = KeyLookupRetryPolicy()
        cache.recordFailure("A", 200L, true)
        assertFalse(cache.shouldAttempt("A", 300L))
        assertTrue(cache.shouldAttempt("B", 300L))
        cache.recordSuccess("A")
        assertTrue(cache.shouldAttempt("A", 300L))
        cache.recordFailure("A", 400L, true)
        cache.invalidate("A")
        assertTrue(cache.shouldAttempt("A", 401L))
    }
}
