package br.com.pessoasaqui

import br.com.pessoasaqui.core.crypto.InviteIdentityProof
import org.junit.Assert.*
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64

class InviteIdentityProofTest {
    private fun keyPair() = KeyPairGenerator.getInstance("EC").apply { initialize(256) }.generateKeyPair()
    private fun identityFromDer(der: ByteArray) = MessageDigest.getInstance("SHA-256")
        .digest(der).take(8).joinToString("") { "%02X".format(it.toInt() and 0xff) }

    @Test fun canonicalPayloadMatchesNodeFixedVector() {
        assertEquals(
            "17:PA-INVITE-AUTH-V1|6:CREATE|16:ABCDEF0123456789|4:João|0:|0:|13:1700000000000|36:12345678-1234-1234-1234-123456789abc",
            InviteIdentityProof.canonical(
                "CREATE", "ABCDEF0123456789", "João", "", "",
                1700000000000L, "12345678-1234-1234-1234-123456789abc"
            )
        )
    }

    @Test fun tokenDigestUsesSha256() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            InviteIdentityProof.tokenDigest("abc"))
    }

    @Test fun signatureCoversVersionOperationIdentityAndAlias() {
        val pair = keyPair()
        val identity = identityFromDer(pair.public.encoded)
        val alias = "João"
        val proof = InviteIdentityProof.create(
            operation = "CREATE",
            identity = identity, alias = alias,
            publicKeyBase64 = Base64.getEncoder().encodeToString(pair.public.encoded),
            signChallenge = { message ->
                Signature.getInstance("SHA256withECDSA").apply {
                    initSign(pair.private)
                    update(message)
                }.sign().let { Base64.getEncoder().encodeToString(it) }
            }
        )
        assertEquals(1, proof.v)
        val message = InviteIdentityProof.canonical(
            "CREATE",identity,alias,"","",proof.timestamp,proof.nonce)
        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(message.toByteArray(Charsets.UTF_8))
        }
        assertTrue(verifier.verify(Base64.getDecoder().decode(proof.signature)))
        val changed = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(pair.public)
            update(InviteIdentityProof.canonical(
                "CREATE",identity,"NomeFalso","","",proof.timestamp,proof.nonce
            ).toByteArray(Charsets.UTF_8))
        }
        assertFalse(changed.verify(Base64.getDecoder().decode(proof.signature)))
    }

    @Test fun redeemPayloadBindsInviteIdAndTokenDigest() {
        val original = InviteIdentityProof.canonical(
            "REDEEM","ABCDEF0123456789","Maria","inv_0123456789ab",
            InviteIdentityProof.tokenDigest("tokenOriginal"),1000L,"nonce"
        )
        val other = InviteIdentityProof.canonical(
            "REDEEM","ABCDEF0123456789","Maria","inv_0123456789ab",
            InviteIdentityProof.tokenDigest("tokenAlterado"),1000L,"nonce"
        )
        assertNotEquals(original,other)
        assertTrue(original.contains("REDEEM"))
    }

    @Test fun familyRecoveryClaimIsBoundToSecretAndDeviceIdentity() {
        val hash = InviteIdentityProof.tokenDigest("PA-REC-" + "A".repeat(64))
        val claim = InviteIdentityProof.canonical(
            "RECOVERY_CLAIM", "ABCDEF0123456789", "", "", hash,
            1700000000000L, "12345678-1234-1234-1234-123456789abc"
        )
        val tampered = InviteIdentityProof.canonical(
            "RECOVERY_CLAIM", "AABBCC0123456789", "", "", hash,
            1700000000000L, "12345678-1234-1234-1234-123456789abc"
        )
        val invite = InviteIdentityProof.canonical(
            "REDEEM", "ABCDEF0123456789", "", "", hash,
            1700000000000L, "12345678-1234-1234-1234-123456789abc"
        )
        assertNotEquals(claim, tampered)
        assertNotEquals(claim, invite)
    }

    @Test fun recoveryAuthorizationBindsFamilyPublicKey() {
        val key = "family-public-key"
        val digest = InviteIdentityProof.tokenDigest(key)
        val first = InviteIdentityProof.canonical(
            "RECOVERY_DELEGATE", "ABCDEF0123456789", "1234567890ABCDEF", "", digest,
            1700000000000L, "12345678-1234-1234-1234-123456789abc"
        )
        val altered = InviteIdentityProof.canonical(
            "RECOVERY_DELEGATE", "ABCDEF0123456789", "1234567890ABCDEF", "",
            InviteIdentityProof.tokenDigest("other-public-key"),
            1700000000000L, "12345678-1234-1234-1234-123456789abc"
        )
        assertNotEquals(first, altered)
    }

    @Test fun fallbackSignatureIsNeverSentAsAuthentication() {
        try {
            InviteIdentityProof.create(
                operation = "CREATE",identity = "ABCDEF0123456789",alias = "Teste",
                publicKeyBase64 = "invalid",signChallenge = { "sig-fallback" }
            )
            fail("Não se pode autenticar sem chave privada real")
        } catch (_: IllegalStateException) {
        }
    }
}
