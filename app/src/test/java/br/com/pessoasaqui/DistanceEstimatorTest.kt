package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.BleConstants
import br.com.pessoasaqui.core.proximity.DistanceEstimator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class DistanceEstimatorTest {

    private lateinit var estimator: DistanceEstimator

    @Before
    fun setUp() {
        estimator = DistanceEstimator()
    }

    @Test
    fun test10MeterBoundaryCondition() {
        // Abaixo ou igual a 10 metros: elegível para descoberta
        assertTrue(estimator.isWithin10Meters(1.5))
        assertTrue(estimator.isWithin10Meters(5.0))
        assertTrue(estimator.isWithin10Meters(10.0))

        // Acima de 10 metros: descartado do radar de descoberta
        assertFalse(estimator.isWithin10Meters(10.1))
        assertFalse(estimator.isWithin10Meters(15.0))
    }

    @Test
    fun testProximityLabelsDoNotRevealExactCoordinates() {
        val labelVeryClose = estimator.getProximityLabel(1.2)
        assertEquals("Muito perto", labelVeryClose)

        val labelWithinReach = estimator.getProximityLabel(8.7)
        assertTrue(labelWithinReach.contains("Dentro do alcance"))

        val labelOutOfReach = estimator.getProximityLabel(12.0)
        assertEquals("Fora do alcance", labelOutOfReach)
    }

    @Test
    fun testRssiCalculation() {
        // Com TxPower = -59 dBm, quando RSSI = -59 dBm, distância é exatamente ~1m
        val d = estimator.estimateDistanceMeters(-59)
        assertEquals(1.0, d, 0.1)

        // Sinal mais fraco resulta em maior distância
        val dFar = estimator.estimateDistanceMeters(-85)
        assertTrue(dFar > 5.0)
    }

    @Test
    fun testRssiSmoothingWithEMA() {
        // Primeiro pacote: inicializa o valor suavizado
        val r1 = estimator.smoothRssi(-60, null)
        assertEquals(-60.0, r1, 0.001)

        // Segundo pacote com ruído brusco de RF (-72 dBm)
        // EMA: -60.0 + 0.25 * (-72 - (-60.0)) = -60.0 - 3.0 = -63.0 dBm
        val r2 = estimator.smoothRssi(-72, r1)
        assertEquals(-63.0, r2, 0.001)

        // Terceiro pacote voltando para -60 dBm
        // EMA: -63.0 + 0.25 * (-60 - (-63.0)) = -63.0 + 0.75 = -62.25 dBm
        val r3 = estimator.smoothRssi(-60, r2)
        assertEquals(-62.25, r3, 0.001)
    }

    @Test
    fun testProximityZones() {
        // Zona 0: Imediato (< 2.5m)
        assertEquals(0, estimator.getProximityZone(0.5))
        assertEquals(0, estimator.getProximityZone(2.4))

        // Zona 1: Próximo (2.5m a 6.0m)
        assertEquals(1, estimator.getProximityZone(2.5))
        assertEquals(1, estimator.getProximityZone(5.9))

        // Zona 2: Alcance (6.0m a 10.0m)
        assertEquals(2, estimator.getProximityZone(6.0))
        assertEquals(2, estimator.getProximityZone(10.0))

        // Zona 3: Fora (> 10.0m)
        assertEquals(3, estimator.getProximityZone(10.1))
    }
}
