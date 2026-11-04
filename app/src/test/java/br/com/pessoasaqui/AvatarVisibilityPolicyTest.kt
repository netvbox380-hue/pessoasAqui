package br.com.pessoasaqui

import br.com.pessoasaqui.core.profile.AvatarVisibilityPolicy
import org.junit.Assert.*
import org.junit.Test

class AvatarVisibilityPolicyTest {
    @Test fun temporaryBleNeverSeesRealPhoto() {
        val photo="PRIVATE_REAL_PERSONAL_PHOTO"
        assertNull(AvatarVisibilityPolicy.permittedRealPhoto(false, photo))
        assertEquals("gato", AvatarVisibilityPolicy.publicBleAvatar(photo,"gato"))
    }

    @Test fun unselectedAnimalDefaultsToNoPhoto() {
        assertNull(AvatarVisibilityPolicy.publicAnimal(null))
        assertNull(AvatarVisibilityPolicy.publicBleAvatar("secret",null))
    }

    @Test fun invalidPublicAnimalRejected() {
        assertNull(AvatarVisibilityPolicy.publicAnimal("real_face"))
        assertEquals("coruja",AvatarVisibilityPolicy.publicAnimal("coruja"))
    }

    @Test fun mutualConfirmedCanSeeSharedRealPhoto() {
        assertEquals("photo",AvatarVisibilityPolicy.permittedRealPhoto(true,"photo"))
    }

    @Test fun revokedMutualCannotSeePreviouslyCachedPhoto() {
        assertNull(AvatarVisibilityPolicy.permittedRealPhoto(false,"cached_photo"))
    }
}
