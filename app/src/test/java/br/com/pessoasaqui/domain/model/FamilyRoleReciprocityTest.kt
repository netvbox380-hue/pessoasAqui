package br.com.pessoasaqui.domain.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FamilyRoleReciprocityTest {
    @Test
    fun parentChosenBySenderIsChildOnReceiver() {
        // Se A chama B de Pai, B deve chamar A de Filho.
        assertEquals(FamilyRole.FILHO, FamilyRole.PAI.reciprocal())
        assertEquals(FamilyRole.PAI, FamilyRole.FILHO.reciprocal())
        assertEquals(FamilyRole.FILHA, FamilyRole.MAE.reciprocal())
        assertEquals(FamilyRole.MAE, FamilyRole.FILHA.reciprocal())
    }

    @Test
    fun spousesAndSiblingsUseTheirReciprocalDesignation() {
        assertEquals(FamilyRole.ESPOSA, FamilyRole.MARIDO.reciprocal())
        assertEquals(FamilyRole.MARIDO, FamilyRole.ESPOSA.reciprocal())
        assertEquals(FamilyRole.IRMAO, FamilyRole.IRMAO.reciprocal())
        assertEquals(FamilyRole.IRMA, FamilyRole.IRMA.reciprocal())
        assertEquals(FamilyRole.OUTRO, FamilyRole.OUTRO.reciprocal())
    }

    @Test
    fun roundTripPreservesOriginalRequestRoleForAcknowledgement() {
        for (role in FamilyRole.values()) {
            assertEquals(role, role.reciprocal().reciprocal())
        }
    }
}
