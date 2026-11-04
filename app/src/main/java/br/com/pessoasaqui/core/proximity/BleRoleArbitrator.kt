package br.com.pessoasaqui.core.proximity

/**
 * Motor de Arbitragem Determinística de Papéis Bluetooth BLE (FASE 3C).
 *
 * Diferencia rigorosamente:
 * - GAP Central vs GAP Peripheral (Camada de rádio, escaneamento e anúncios de presença)
 * - GATT Client vs GATT Server (Camada de atributos e conexão de dados)
 *
 * Aplica a matriz de decisão baseada nas capacidades efetivas dos dispositivos:
 * - Se ambos suportam GATT Server: desempate determinístico por ordenação lexicográfica de hash.
 * - Se apenas um suporta GATT Server: o aparelho capaz torna-se Peripheral/Server obrigatoriamente.
 * - Se nenhum suporta GATT Server: incompatibilidade para recepção direta BLE offline.
 */
object BleRoleArbitrator {

    enum class Role {
        CENTRAL_CLIENT,     // Atua como Central (Scan/Connect) e GATT Client (Write/Read)
        PERIPHERAL_SERVER,  // Atua como Peripheral (Advertise) e GATT Server (Listen/Notify)
        INCOMPATIBLE        // Nenhum aparelho consegue hospedar GATT Server
    }

    data class DeviceCapabilities(
        val canServe: Boolean,
        val canClient: Boolean = true
    )

    /**
     * Arbitra o papel de conexão entre dois aparelhos com base em seus identificadores e capacidades reais.
     *
     * @param localIdentityHash Hash técnico da identidade local.
     * @param remoteIdentityHash Hash técnico da identidade do parceiro descoberto.
     * @param localCaps Capacidades de hardware e rádio do aparelho local.
     * @param remoteCaps Capacidades de hardware e rádio do aparelho remoto.
     * @return [Role] designado para o aparelho local.
     */
    fun arbitrate(
        localIdentityHash: String,
        remoteIdentityHash: String,
        localCaps: DeviceCapabilities,
        remoteCaps: DeviceCapabilities
    ): Role {
        // Caso 1: Nenhum dispositivo suporta operar como GATT Server
        if (!localCaps.canServe && !remoteCaps.canServe) {
            return Role.INCOMPATIBLE
        }

        // Caso 2: Assimetria - apenas o dispositivo local suporta GATT Server
        if (localCaps.canServe && !remoteCaps.canServe) {
            return Role.PERIPHERAL_SERVER
        }

        // Caso 3: Assimetria - apenas o dispositivo remoto suporta GATT Server
        if (!localCaps.canServe && remoteCaps.canServe) {
            return Role.CENTRAL_CLIENT
        }

        // Caso 4: Simetria - ambos os aparelhos suportam GATT Server e GATT Client
        val cleanLocal = localIdentityHash.removePrefix("peer-").trim().uppercase()
        val cleanRemote = remoteIdentityHash.removePrefix("peer-").trim().uppercase()
        val cmp = cleanLocal.compareTo(cleanRemote)

        return if (cmp > 0) {
            Role.CENTRAL_CLIENT // Maior hash lexicográfico assume papel de Central/Client
        } else if (cmp < 0) {
            Role.PERIPHERAL_SERVER // Menor hash lexicográfico assume papel de Peripheral/Server
        } else {
            Role.INCOMPATIBLE // Identidades idênticas não estabelecem conexão
        }
    }
}
