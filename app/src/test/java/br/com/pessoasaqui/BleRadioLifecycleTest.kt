package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.BleRadioLifecycle
import br.com.pessoasaqui.core.proximity.BleRadioState
import org.junit.Assert.*
import org.junit.Test

class BleRadioLifecycleTest {
    @Test fun healthyRadioNeedsNoPeriodicRestart() {
        val state = BleRadioLifecycle()
        state.starting()
        state.started()
        assertEquals(BleRadioState.ACTIVE, state.state)
        assertFalse(state.shouldRetry(999_999_999L))
    }

    @Test fun backoffRetriesOnlyAfterFailureAndDeadline() {
        val state = BleRadioLifecycle()
        state.starting()
        state.failed(10_000L)
        assertEquals(BleRadioState.FAILED, state.state)
        assertFalse(state.shouldRetry(11_999L))
        assertTrue(state.shouldRetry(12_000L))
        state.starting()
        state.failed(12_000L)
        assertFalse(state.shouldRetry(15_999L))
        assertTrue(state.shouldRetry(16_000L))
        state.starting()
        state.started()
        assertFalse(state.shouldRetry(100_000L))
        assertEquals(0, state.failures)
    }

    @Test fun stoppingCancelsAllScheduledRecovery() {
        val state = BleRadioLifecycle()
        state.starting()
        state.failed(0L)
        state.stopped()
        assertFalse(state.shouldRetry(999_999L))
        assertEquals(BleRadioState.STOPPED, state.state)
    }
}
