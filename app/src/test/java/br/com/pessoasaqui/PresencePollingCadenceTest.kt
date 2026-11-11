package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.PresencePollingCadence
import org.junit.Assert.assertEquals
import org.junit.Test

class PresencePollingCadenceTest {
    @Test fun foregroundWithoutWebsocketPreservesFastRestFallback() {
        assertEquals(4_000L, PresencePollingCadence.intervalMs(true, false))
    }

    @Test fun foregroundWithWebsocketStillPeriodicallyReconciles() {
        assertEquals(15_000L, PresencePollingCadence.intervalMs(true, true))
    }

    @Test fun backgroundThrottlesRegardlessOfWebsocketState() {
        assertEquals(30_000L, PresencePollingCadence.intervalMs(false, true))
        assertEquals(30_000L, PresencePollingCadence.intervalMs(false, false))
    }
}
