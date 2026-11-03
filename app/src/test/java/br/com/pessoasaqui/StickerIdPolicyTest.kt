package br.com.pessoasaqui

import br.com.pessoasaqui.core.chat.StickerPersistenceManager
import org.junit.Assert.*
import org.junit.Test

class StickerIdPolicyTest {
    @Test fun legacyAndNewStickerIdsAreCompatible() {
        assertTrue(StickerPersistenceManager.isValidStickerId("sticker_1762195000000"))
        assertTrue(StickerPersistenceManager.isValidStickerId(
            "sticker_123e4567-e89b-12d3-a456-426614174000"))
    }

    @Test fun pathTraversalAndMalformedIdsCannotAccessPrivateStorage() {
        for (invalid in listOf(
            "", "sticker_", "sticker_../secrets", "../sticker_1",
            "sticker_..", "sticker_a/b", "sticker_a\\b",
            "sticker_abc.png", "other_123", "sticker_a\u0000b"
        )) {
            assertFalse("Deve bloquear caminho/ID inseguro: $invalid",
                StickerPersistenceManager.isValidStickerId(invalid))
        }
    }

    @Test fun invalidOverlongIdIsRejected() {
        assertFalse(StickerPersistenceManager.isValidStickerId("sticker_" + "x".repeat(81)))
    }
}
