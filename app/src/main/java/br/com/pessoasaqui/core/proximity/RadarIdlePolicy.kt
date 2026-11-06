package br.com.pessoasaqui.core.proximity

/**
 * Política de descoberta BLE; não administra Bluetooth do sistema ou GATT.
 * Um chat TEMPORÁRIO aberto permanece apto a descobrir/reconectar mesmo
 * quando a Activity fica em segundo plano. Contatos permanentes seguem Modo B.
 */
internal object RadarIdlePolicy {
    const val TAB_EXIT_GRACE_MS = 30_000L
    const val BACKGROUND_GRACE_MS = 60_000L

    fun requiresDiscovery(
        appForeground: Boolean,
        radarTabVisible: Boolean,
        temporaryChatOpen: Boolean,
        offersTabVisible: Boolean
    ): Boolean = temporaryChatOpen ||
        (appForeground && (radarTabVisible || offersTabVisible))

    fun suspendAfterMs(appForeground: Boolean): Long =
        if (appForeground) TAB_EXIT_GRACE_MS else BACKGROUND_GRACE_MS
}
