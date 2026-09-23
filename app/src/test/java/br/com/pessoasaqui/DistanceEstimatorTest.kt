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
}
