package br.com.pessoasaqui.domain.model

import java.util.UUID

/**
 * Intenções expressas pelo usuário para orientar interações locais.
 */
enum class UserIntent(val icon: String, val label: String) {
    QUERO_CONVERSAR("💬", "Quero conversar"),
    TENHO_PERGUNTA("❓", "Tenho uma pergunta"),
    PROCURANDO_ALGO("🔎", "Estou procurando algo"),
    PRECISO_SERVICO("🛠️", "Preciso de um serviço"),
    DIVULGAR_TRABALHO("📢", "Quero divulgar meu trabalho"),
    CONHECER_PESSOAS("🤝", "Quero conhecer pessoas"),
    SO_OBSERVAR("👀", "Só quero ver o que está acontecendo")
}

/**
 * Papéis familiares autorizados como parte da rede de confiança.
 */
enum class FamilyRole(val label: String) {
    PAI("Pai"),
    MAE("Mãe"),
    MARIDO("Marido"),
    ESPOSA("Esposa"),
    FILHO("Filho"),
    FILHA("Filha"),
    IRMAO("Irmão"),
    IRMA("Irmã"),
    OUTRO("Familiar")
}

enum class MessageType {
    TEXT,
    AUDIO,
    IMAGE,
    DOCUMENT,
    SYSTEM
}

/**
 * Representa uma pessoa detectada dentro do raio de proximidade de 10 metros ou conexão existente.
 */
data class NearbyPerson(
    val id: String = UUID.randomUUID().toString(),
    val technicalIdentityHash: String,
    val alias: String, // Nome ou pseudônimo local
    val estimatedDistanceMeters: Double, // Ex: 3.2m (nunca expor coordenadas GPS!)
    val proximityLabel: String, // Ex: "Muito perto", "4 m", "6 m", "Dentro do alcance"
    val intent: UserIntent = UserIntent.QUERO_CONVERSAR,
    val isMarkedByMe: Boolean = false,
    val isMarkingMe: Boolean = false,
    val isMutualConnection: Boolean = false, // True se ambos se marcaram (permite distância ilimitada)
    val isFamily: Boolean = false,
    val familyRole: FamilyRole? = null,
    val isPhotoVisible: Boolean = false, // Foto oculta por padrão para desconhecidos
    val avatarColorHex: Long = 0xFF00E5FF,
    val lastSeenEpochMs: Long = System.currentTimeMillis(),
    val hasPendingFamilyRequest: Boolean = false,
    val pendingFamilyRole: FamilyRole? = null
)

/**
 * Mensagem em tópico local (10m) ou conversa privada ponto-a-ponta.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderAlias: String,
    val text: String,
    val timestampMs: Long = System.currentTimeMillis(),
    val isLocalOnly: Boolean = false, // True se restrita ao raio de 10m
    val isEncrypted: Boolean = true,
    val isFromMe: Boolean = false,
    val messageType: MessageType = MessageType.TEXT,
    val audioDurationSeconds: Int = 0,
    val mediaBase64: String? = null,
    val fileName: String? = null,
    val fileSizeBytes: Long = 0
)

/**
 * Estado de Chamada Criptografada Ativa (Áudio / Vídeo)
 */
data class ActiveCallState(
    val isIncoming: Boolean,
    val peerHash: String,
    val peerAlias: String,
    val isVideo: Boolean,
    val isConnected: Boolean = false,
    val durationSeconds: Int = 0,
    val isMuted: Boolean = false,
    val isCameraOn: Boolean = true,
    val isSpeakerphoneOn: Boolean = false,
    val remoteVideoFrameBase64: String? = null
)

/**
 * Divulgação ou oferta de serviço local (ex: Mecânico, Eletricista, Comércio).
 */
data class OfferItem(
    val id: String = UUID.randomUUID().toString(),
    val authorId: String,
    val authorAlias: String,
    val profession: String,
    val description: String,
    val distanceMeters: Double = 0.5,
    val proximityLabel: String = "10m",
    val createdAtMs: Long = System.currentTimeMillis(),
    val imageBase64: String? = null,
    val externalLink: String? = null
)

/**
 * Autorização temporária emitida por familiar para recuperação de identidade.
 */
data class RecoveryAuthorization(
    val id: String = UUID.randomUUID().toString(),
    val targetIdentityHash: String,
    val authorizedByFamilyName: String,
    val recoveryKey: String, // Chave alfanumérica ou payload do QR Code
    val expiresAtEpochMs: Long,
    val isUsed: Boolean = false
)

/**
 * Estado de proteção contra força bruta de PIN no novo aparelho.
 */
data class LockoutState(
    val isLocked: Boolean,
    val attemptCount: Int,
    val remainingLockoutSeconds: Long,
    val lockoutMessage: String? = null
)
