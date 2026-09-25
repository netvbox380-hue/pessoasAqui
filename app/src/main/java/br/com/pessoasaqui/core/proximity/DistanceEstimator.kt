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
     * Aplica filtro de Suavização Exponencial (EMA - Exponential Moving Average) no RSSI.
     * Elimina ruídos de rádio e oscilações bruscas instantâneas que causavam
     * a troca descontrolada de posições no radar.
     */
    fun smoothRssi(currentRssi: Int, previousRssi: Double?): Double {
        if (previousRssi == null || previousRssi == 0.0) return currentRssi.toDouble()
        val alpha = 0.25 // Peso para novas leituras (suavização suave e responsiva)
        return (alpha * currentRssi) + ((1.0 - alpha) * previousRssi)
    }

    /**
     * Retorna a zona de proximidade física para agrupamento estável:
     * 0 = Imediato (< 2.5m)
     * 1 = Próximo (2.5m a 6.0m)
     * 2 = Alcance (6.0m a 10.0m)
     * 3 = Fora do limite (> 10.0m)
     */
    fun getProximityZone(distanceMeters: Double): Int {
        return when {
            distanceMeters < 2.5 -> 0
            distanceMeters < 6.0 -> 1
            distanceMeters <= BleConstants.MAX_DISCOVERY_DISTANCE_METERS -> 2
            else -> 3
        }
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
