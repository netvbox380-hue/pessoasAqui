package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.RadarIdlePolicy
import org.junit.Assert.*
import org.junit.Test

class RadarIdlePolicyTest {
    @Test fun opensInConversationsWithoutDiscovering() {
        assertFalse(RadarIdlePolicy.requiresDiscovery(
            appForeground = true, radarTabVisible = false,
            temporaryChatOpen = false, offersTabVisible = false
        ))
    }

    @Test fun radarAndOffersNeedDiscoveryOnlyWhileForeground() {
        assertTrue(RadarIdlePolicy.requiresDiscovery(true, true, false, false))
        assertTrue(RadarIdlePolicy.requiresDiscovery(true, false, false, true))
        assertFalse(RadarIdlePolicy.requiresDiscovery(false, true, false, false))
        assertFalse(RadarIdlePolicy.requiresDiscovery(false, false, false, true))
    }

    @Test fun temporaryBleChatRemainsActiveInBackground() {
        assertTrue(RadarIdlePolicy.requiresDiscovery(false, false, true, false))
        assertTrue(RadarIdlePolicy.requiresDiscovery(true, false, true, false))
    }

    @Test fun idleTimeoutIsLongerWhenBackgrounded() {
        assertEquals(30_000L, RadarIdlePolicy.suspendAfterMs(true))
        assertEquals(60_000L, RadarIdlePolicy.suspendAfterMs(false))
    }
}
