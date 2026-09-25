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

    @Test
    fun testHysteresisSortingSuppressesMariaAndRexJitter() {
        val repo = br.com.pessoasaqui.data.repository.PessoasAquiRepository()
        val maria1 = NearbyPerson(id = "maria", technicalIdentityHash = "maria", alias = "Maria", estimatedDistanceMeters = 3.0, proximityLabel = "3m")
        val rex1 = NearbyPerson(id = "rex", technicalIdentityHash = "rex", alias = "Rex", estimatedDistanceMeters = 3.2, proximityLabel = "3m")

        // Rodada 1: Maria está mais próxima que Rex (3.0m vs 3.2m)
        val sorted1 = repo.sortWithHysteresis(listOf(rex1, maria1))
        assertEquals("maria", sorted1[0].id)
        assertEquals("rex", sorted1[1].id)

        // Rodada 2: Ruído de RF faz Rex parecer estar a 3.1m e Maria a 3.3m
        // Variação é de apenas 0.2m (< limiar de 1.5m de histerese)
        val maria2 = NearbyPerson(id = "maria", technicalIdentityHash = "maria", alias = "Maria", estimatedDistanceMeters = 3.3, proximityLabel = "3m")
        val rex2 = NearbyPerson(id = "rex", technicalIdentityHash = "rex", alias = "Rex", estimatedDistanceMeters = 3.1, proximityLabel = "3m")
        val sorted2 = repo.sortWithHysteresis(listOf(rex2, maria2))

        // Graças à histerese de posição, Maria permanece estável no topo, sem oscilar / inverter posição!
        assertEquals("Maria deve manter sua posição estável no topo", "maria", sorted2[0].id)
        assertEquals("Rex deve manter sua posição estável abaixo de Maria", "rex", sorted2[1].id)

        // Rodada 3: Rex se move fisicamente para perto (< 2.5m, Zona 0) enquanto Maria fica a 3.3m (Zona 1)
        val rex3 = NearbyPerson(id = "rex", technicalIdentityHash = "rex", alias = "Rex", estimatedDistanceMeters = 1.8, proximityLabel = "2m")
        val sorted3 = repo.sortWithHysteresis(listOf(rex3, maria2))
        assertEquals("Rex agora está em faixa de proximidade imediata (< 2.5m) e deve subir", "rex", sorted3[0].id)
        assertEquals("maria", sorted3[1].id)
    }
}
