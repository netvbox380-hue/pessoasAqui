package br.com.pessoasaqui

import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.domain.model.NearbyPerson
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MutualConnectionTest {

    private lateinit var simulator: ProximitySimulator

    @Before
    fun setUp() {
        simulator = ProximitySimulator()
    }

    @Test
    fun testPeerAlreadyMarkingMeBecomesMutualWhenMarked() {
        val people = simulator.discoveredPeople.value
        val marcos = people.first { it.alias == "Marcos Mecânico" }
        assertTrue("Marcos já marcou o usuário", marcos.isMarkingMe)
        assertFalse("Usuário ainda não marcou Marcos", marcos.isMarkedByMe)
        assertFalse("Não é conexão mútua ainda", marcos.isMutualConnection)

        // Usuário aceita / marca de volta
        val updated = simulator.toggleMarkPerson(marcos.id)
        assertNotNull(updated)
        assertTrue("Usuário marcou Marcos", updated!!.isMarkedByMe)
        assertTrue("Conexão agora é mútua", updated.isMutualConnection)
        assertTrue("Foto fica visível após conexão mútua", updated.isPhotoVisible)
    }

    @Test
    fun testPeerNotMarkingMeAwaitsReciprocity() {
        val people = simulator.discoveredPeople.value
        val mariana = people.first { it.alias == "Mariana" }
        assertFalse("Mariana não marcou o usuário inicialmente", mariana.isMarkingMe)
        assertFalse("Usuário não marcou Mariana", mariana.isMarkedByMe)

        // Usuário marca Mariana unilateralmente
        val updated = simulator.toggleMarkPerson(mariana.id)
        assertNotNull(updated)
        assertTrue("Usuário marcou Mariana", updated!!.isMarkedByMe)
        assertFalse("Não é conexão mútua ainda pois Mariana não marcou", updated.isMutualConnection)

        // Desmarca
        val unmark = simulator.toggleMarkPerson(mariana.id)
        assertNotNull(unmark)
        assertFalse("Marcação removida", unmark!!.isMarkedByMe)
        assertFalse("Não é mútua", unmark.isMutualConnection)
    }

    @Test
    fun testNearbyPersonModelStateConsistency() {
        val person = NearbyPerson(
            id = "3F8E10BC",
            technicalIdentityHash = "3F8E10BC",
            alias = "rex",
            estimatedDistanceMeters = 3.0,
            proximityLabel = "Dentro do alcance (~3m)",
            isMarkedByMe = true,
            isMarkingMe = true,
            isMutualConnection = true
        )
        assertTrue(person.isMutualConnection)
        assertTrue(person.isMarkedByMe)
        assertTrue(person.isMarkingMe)
    }
}
