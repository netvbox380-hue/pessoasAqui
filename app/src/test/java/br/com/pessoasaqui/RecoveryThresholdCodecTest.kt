package br.com.pessoasaqui

import br.com.pessoasaqui.core.backup.RecoveryThresholdCodec
import org.junit.Assert.*
import org.junit.Test

class RecoveryThresholdCodecTest {
    @Test fun everyPairRecoversPinAndSecretButOneShareDoesNot() {
        val pin="123456"
        val secret="PA-VAULT-V3-"+"A4".repeat(32)
        val shares=RecoveryThresholdCodec.split(pin,secret,5)
        assertEquals(5,shares.size)
        shares.forEach {
            assertEquals(RecoveryThresholdCodec.SHARE_SIZE,it.size)
            assertFalse(it.contentEquals(shares[(shares.indexOf(it)+1)%5]))
        }
        for(i in shares.indices)for(j in i+1 until shares.size) {
            val material=RecoveryThresholdCodec.combine(shares[i],shares[j])
            assertEquals(pin,material.pin)
            assertEquals(secret,material.recoverySecret)
        }
        val wrong=RecoveryThresholdCodec.split(pin,secret,5)
        assertFalse(shares[0].contentEquals(wrong[0]))
    }

    @Test fun distinctSharesMustBelongToSameEnrollment() {
        val secret="PA-VAULT-V3-"+"AB".repeat(32)
        val a=RecoveryThresholdCodec.split("555555",secret,3)
        val b=RecoveryThresholdCodec.split("123456",secret,3)
        val mixed=runCatching { RecoveryThresholdCodec.combine(a[0],b[1]) }
        assertTrue("Partes de distribuições distintas devem falhar", mixed.isFailure)
    }

    @Test(expected=IllegalArgumentException::class)
    fun cannotReconstructWithIdenticalShare() {
        val share=RecoveryThresholdCodec.split("123456","PA-VAULT-V3-"+"CD".repeat(32),2)[0]
        RecoveryThresholdCodec.combine(share,share)
    }

    @Test(expected=IllegalArgumentException::class)
    fun rejectsWeakLegacyPinEnrollment() {
        RecoveryThresholdCodec.split("12","PA-VAULT-V3-"+"00".repeat(32),3)
    }

    @Test fun singleShareTamperingIsRejected() {
        val secret="PA-VAULT-V3-"+"FF".repeat(32)
        val shares=RecoveryThresholdCodec.split("123456",secret,3)
        val altered=shares[0].clone()
        altered[altered.lastIndex]=(altered.last().toInt() xor 1).toByte()
        assertTrue(runCatching { RecoveryThresholdCodec.combine(altered,shares[1]) }.isFailure)
        val alteredCommitment=shares[0].clone()
        alteredCommitment[20]=(alteredCommitment[20].toInt() xor 1).toByte()
        assertTrue(runCatching { RecoveryThresholdCodec.combine(alteredCommitment,shares[1]) }.isFailure)
    }

    @Test fun distinctEnrollmentsHaveDistinctIdentifiersEvenForSameSecretAndPin() {
        val secret="PA-VAULT-V3-"+"12".repeat(32)
        val one=RecoveryThresholdCodec.split("123456",secret,3)
        val two=RecoveryThresholdCodec.split("123456",secret,3)
        assertFalse(one[0].contentEquals(two[0]))
        assertTrue(runCatching { RecoveryThresholdCodec.combine(one[0],two[1]) }.isFailure)
    }
}

