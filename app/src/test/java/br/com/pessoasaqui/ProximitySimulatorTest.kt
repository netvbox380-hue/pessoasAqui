package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.ProximitySimulator
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProximitySimulatorTest {

    private lateinit var simulator: ProximitySimulator

    @Before
    fun setUp() {
        simulator = ProximitySimulator()
    }

    @Test
    fun testPeopleOutside10MetersAreFilteredOut() {
        val discovered = simulator.discoveredPeople.value

        // Contato que estava a 15m não deve estar presente no radar
        val hasOutOfRangePerson = discovered.any { it.alias == "Pessoa Fora do Raio" }
        assertFalse("Pessoas fora do raio de 10 metros não devem ser descobertas", hasOutOfRangePerson)

        // Marcos (4.2m) e Mariana (2.1m) devem estar presentes
        assertTrue(discovered.any { it.alias == "Marcos Mecânico" })
        assertTrue(discovered.any { it.alias == "Mariana" })
    }

    @Test
    fun testMutualConnectionFlow() {
        // Marcos Mecânico já tem isMarkingMe = true
        val discovered = simulator.discoveredPeople.value
        val marcosInitial = discovered.first { it.alias == "Marcos Mecânico" }
        assertFalse(marcosInitial.isMarkedByMe)
        assertFalse(marcosInitial.isMutualConnection)

        // Usuário marca Marcos de volta:
        val marcosUpdated = simulator.toggleMarkPerson(marcosInitial.id)
        assertNotNull(marcosUpdated)
        assertTrue("Deve estar marcado por mim", marcosUpdated!!.isMarkedByMe)
        assertTrue("Deve se tornar Conexão Mútua pois Marcos já havia marcado", marcosUpdated.isMutualConnection)
        assertTrue("Foto deve ficar visível após conexão mútua autorizada", marcosUpdated.isPhotoVisible)
    }

    @Test
    fun testDynamicDistanceUpdateFiltersRealtime() {
        val discovered = simulator.discoveredPeople.value
        val mariana = discovered.first { it.alias == "Mariana" }

        // Afasta Mariana para 14 metros (> 10m)
        simulator.updateSimulatedDistance(mariana.id, 14.0)

        val updatedDiscovered = simulator.discoveredPeople.value
        val stillFound = updatedDiscovered.any { it.id == mariana.id }
        assertFalse("Ao ultrapassar 10 metros sem conexão mútua, pessoa deve desaparecer do radar", stillFound)
    }
}
