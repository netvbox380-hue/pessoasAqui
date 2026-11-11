package br.com.pessoasaqui.core.crypto

/** REST reconciliation must remain available as a safety net for missed WS events. */
object PresencePollingCadence {
    fun intervalMs(appForeground: Boolean, websocketConnected: Boolean): Long = when {
        !appForeground -> 30_000L
        websocketConnected -> 15_000L
        else -> 4_000L
    }
}
