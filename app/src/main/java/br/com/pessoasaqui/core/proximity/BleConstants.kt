package br.com.pessoasaqui.core.proximity

import java.util.UUID

object BleConstants {
    // UUID customizado para o serviço de presença PessoasAqui
    val SERVICE_UUID: UUID = UUID.fromString("0000PA01-0000-1000-8000-00805F9B34FB")
    
    // Limite estrito de descoberta física (Seção 3 do Prompt)
    const val MAX_DISCOVERY_DISTANCE_METERS = 10.0

    // Constantes do modelo de perda de propagação (Log-Distance Path Loss)
    const val DEFAULT_MEASURED_POWER_AT_1M = -59 // Potência média calibrada em 1 metro (dBm)
    const val PATH_LOSS_EXPONENT = 2.4 // Coeficiente de atenuação para ambientes urbanos/fechados
}
