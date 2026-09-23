package br.com.pessoasaqui.core.proximity

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Motor de estimativa de proximidade física a partir do sinal BLE (RSSI).
 * Aplica o modelo log-distance path loss e impõe o limite estrito de 10 metros.
 *
 * IMPORTANTE (Seção 3):
 * Nunca expõe coordenadas exatas, latitude/longitude ou endereços.
 * Apenas faixas relativas como "Muito perto", "4 m", "6 m", "Dentro do alcance".
 */
class DistanceEstimator(
    private val txPower: Int = BleConstants.DEFAULT_MEASURED_POWER_AT_1M,
    private val pathLossExponent: Double = BleConstants.PATH_LOSS_EXPONENT
) {

    /**
     * Calcula a distância aproximada em metros com base no RSSI recebido.
     * Fórmula: d = 10 ^ ((TxPower - RSSI) / (10 * N))
     */
    fun estimateDistanceMeters(rssi: Int): Double {
        if (rssi == 0) return 999.0
        val ratio = (txPower - rssi) / (10.0 * pathLossExponent)
        val rawDistance = 10.0.pow(ratio)
        // Arredonda para 1 casa decimal
        return (rawDistance * 10).roundToInt() / 10.0
    }

    /**
     * Verifica se o dispositivo está dentro do raio permitido de 10 metros.
     */
    fun isWithin10Meters(distanceMeters: Double): Boolean {
        return distanceMeters in 0.0..BleConstants.MAX_DISCOVERY_DISTANCE_METERS
    }

    /**
     * Gera o rótulo amigável e preservador de privacidade para a interface.
     */
    fun getProximityLabel(distanceMeters: Double): String {
        return when {
            distanceMeters < 2.0 -> "Muito perto"
            distanceMeters < 4.5 -> "${distanceMeters.roundToInt()} m"
            distanceMeters < 7.5 -> "${distanceMeters.roundToInt()} m"
            distanceMeters <= BleConstants.MAX_DISCOVERY_DISTANCE_METERS -> "Dentro do alcance (~${distanceMeters.roundToInt()}m)"
            else -> "Fora do alcance"
        }
    }
}
