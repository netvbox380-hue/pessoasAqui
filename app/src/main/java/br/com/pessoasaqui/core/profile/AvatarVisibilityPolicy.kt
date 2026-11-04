package br.com.pessoasaqui.core.profile

/** Regras de privacidade independentes da UI, BLE e flags antigas da foto. */
object AvatarVisibilityPolicy {
    fun permittedRealPhoto(mutualConfirmed: Boolean, photo: String?): String? =
        if (mutualConfirmed) photo else null

    fun publicAnimal(value: String?): String? =
        value?.takeIf { it in setOf("gato","cachorro","raposa","coruja",
            "panda","coelho","leao","urso") }

    /** Foto enviada por canal público (BLE/Ofertas) nunca pode ser real. */
    fun publicBleAvatar(realPhoto: String?, animal: String?): String? =
        publicAnimal(animal)
}
