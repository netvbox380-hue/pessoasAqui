package br.com.pessoasaqui.core.proximity

import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.OfferItem
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.domain.model.UserIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Simulador e Playground de Proximidade Física do PessoasAqui.
 * Permite demonstrar e testar imediatamente no emulador ou dispositivo real:
 * - Descoberta estrita de pessoas em até 10 metros;
 * - Descarte automático de quem está acima de 10m;
 * - Marcação unilateral e transição para Conexão Mútua (autorizando conversa à distância);
 * - Vínculo familiar com privilégios e recuperação de identidade.
 */
class ProximitySimulator(
    private val distanceEstimator: DistanceEstimator = DistanceEstimator()
) {

    private val allSimulatedPeers = mutableListOf<NearbyPerson>()
    
    private val _discoveredPeople = MutableStateFlow<List<NearbyPerson>>(emptyList())
    val discoveredPeople: StateFlow<List<NearbyPerson>> = _discoveredPeople.asStateFlow()

    private val _localOffers = MutableStateFlow<List<OfferItem>>(emptyList())
    val localOffers: StateFlow<List<OfferItem>> = _localOffers.asStateFlow()

    private val _localChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val localChatMessages: StateFlow<List<ChatMessage>> = _localChatMessages.asStateFlow()

    private val _privateChats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val privateChats: StateFlow<Map<String, List<ChatMessage>>> = _privateChats.asStateFlow()

    private val _recoveryAuthorizations = MutableStateFlow<List<RecoveryAuthorization>>(emptyList())
    val recoveryAuthorizations: StateFlow<List<RecoveryAuthorization>> = _recoveryAuthorizations.asStateFlow()

    init {
        setupInitialPeers()
        refreshDiscoveredList()
    }

    private fun setupInitialPeers() {
        val marcosId = "peer-marcos-mecanico"
        val marianaId = "peer-mariana"
        val carlosId = "peer-carlos"
        val mariaId = "peer-maria-irma"
        val distanteId = "peer-fora-do-raio"

        allSimulatedPeers.addAll(
            listOf(
                NearbyPerson(
                    id = marcosId,
                    technicalIdentityHash = "A4F891BC",
                    alias = "Marcos Mecânico",
                    estimatedDistanceMeters = 4.2,
                    proximityLabel = distanceEstimator.getProximityLabel(4.2),
                    intent = UserIntent.DIVULGAR_TRABALHO,
                    isMarkedByMe = false,
                    isMarkingMe = true, // Ele já marcou você! Ao marcar de volta vira conexão mútua
                    isMutualConnection = false,
                    isFamily = false,
                    avatarColorHex = 0xFFFF9800
                ),
                NearbyPerson(
                    id = marianaId,
                    technicalIdentityHash = "C72109DE",
                    alias = "Mariana",
                    estimatedDistanceMeters = 2.1,
                    proximityLabel = distanceEstimator.getProximityLabel(2.1),
                    intent = UserIntent.QUERO_CONVERSAR,
                    isMarkedByMe = false,
                    isMarkingMe = false,
                    isMutualConnection = false,
                    isFamily = false,
                    avatarColorHex = 0xFF00E5FF
                ),
                NearbyPerson(
                    id = mariaId,
                    technicalIdentityHash = "F18832AA",
                    alias = "Maria",
                    estimatedDistanceMeters = 3.5,
                    proximityLabel = distanceEstimator.getProximityLabel(3.5),
                    intent = UserIntent.CONHECER_PESSOAS,
                    isMarkedByMe = true,
                    isMarkingMe = true,
                    isMutualConnection = true, // Já é conexão mútua autorizada
                    isFamily = true,
                    familyRole = FamilyRole.IRMA,
                    isPhotoVisible = true, // Família tem acesso autorizado à foto
                    avatarColorHex = 0xFFE91E63
                ),
                NearbyPerson(
                    id = carlosId,
                    technicalIdentityHash = "990145EF",
                    alias = "Carlos",
                    estimatedDistanceMeters = 7.8,
                    proximityLabel = distanceEstimator.getProximityLabel(7.8),
                    intent = UserIntent.TENHO_PERGUNTA,
                    isMarkedByMe = false,
                    isMarkingMe = false,
                    isMutualConnection = false,
                    isFamily = false,
                    avatarColorHex = 0xFF4CAF50
                ),
                // Exemplo de pessoa FORA dos 10 metros (NÃO deve aparecer no radar inicial)
                NearbyPerson(
                    id = distanteId,
                    technicalIdentityHash = "11223344",
                    alias = "Pessoa Fora do Raio",
                    estimatedDistanceMeters = 15.0,
                    proximityLabel = "Fora do alcance",
                    intent = UserIntent.SO_OBSERVAR,
                    avatarColorHex = 0xFF9E9E9E
                )
            )
        )

        // Oferta inicial de demonstração
        _localOffers.value = listOf(
            OfferItem(
                id = "offer-1",
                authorId = marcosId,
                authorAlias = "Marcos Mecânico",
                profession = "Mecânico Automotivo",
                description = "Sou Marcos, trabalho com mecânica de carro. Faço esse serviço. Está com algum barulho estranho, problema ou batida no carro? Caso queira conversar comigo depois, é só me marcar aqui e podemos conversar em qualquer distância.",
                distanceMeters = 4.2,
                proximityLabel = "4 m • Perto"
            )
        )

        // Mensagens do feed local (10m)
        _localChatMessages.value = listOf(
            ChatMessage(
                senderId = carlosId,
                senderAlias = "Carlos",
                text = "Alguém sabe se a padaria da esquina já abriu?",
                isLocalOnly = true
            ),
            ChatMessage(
                senderId = marianaId,
                senderAlias = "Mariana",
                text = "Sim, acabei de passar lá e está aberta!",
                isLocalOnly = true
            )
        )

        // Conversa privada com Maria (Irmã)
        _privateChats.value = mapOf(
            mariaId to listOf(
                ChatMessage(
                    senderId = mariaId,
                    senderAlias = "Maria",
                    text = "Oi! Você chegou bem? Se precisar recuperar sua identidade em outro celular, me avise pelo menu da conversa.",
                    isLocalOnly = false,
                    isEncrypted = true,
                    isFromMe = false
                )
            )
        )
    }

    /**
     * Atualiza a lista filtrando estritamente quem está dentro de 10 metros,
     * ou conexões mútuas/familiares já autorizadas.
     */
    fun refreshDiscoveredList() {
        val filtered = allSimulatedPeers.filter { person ->
            person.isMutualConnection || distanceEstimator.isWithin10Meters(person.estimatedDistanceMeters)
        }.sortedBy { it.estimatedDistanceMeters }
        _discoveredPeople.value = filtered
    }

    /**
     * Alterna a marcação do usuário atual em outra pessoa.
     * Se ambos se marcarem, vira CONEXÃO MÚTUA com comunicação à distância liberada!
     */
    fun toggleMarkPerson(personId: String): NearbyPerson? {
        val index = allSimulatedPeers.indexOfFirst { it.id == personId }
        if (index == -1) return null

        val current = allSimulatedPeers[index]
        val newMarkedByMe = !current.isMarkedByMe
        val isNowMutual = newMarkedByMe && current.isMarkingMe

        val updated = current.copy(
            isMarkedByMe = newMarkedByMe,
            isMutualConnection = isNowMutual,
            isPhotoVisible = isNowMutual || current.isFamily
        )
        allSimulatedPeers[index] = updated
        refreshDiscoveredList()
        return updated
    }

    /**
     * Vincula um contato como familiar autorizado
     */
    fun setFamilyRole(personId: String, role: FamilyRole) {
        val index = allSimulatedPeers.indexOfFirst { it.id == personId }
        if (index != -1) {
            val current = allSimulatedPeers[index]
            allSimulatedPeers[index] = current.copy(
                isFamily = true,
                familyRole = role,
                isPhotoVisible = true,
                isMutualConnection = true
            )
            refreshDiscoveredList()
        }
    }

    /**
     * Altera a distância simulada de um contato para testar o limite de 10 metros.
     */
    fun updateSimulatedDistance(personId: String, newDistance: Double) {
        val index = allSimulatedPeers.indexOfFirst { it.id == personId }
        if (index != -1) {
            val current = allSimulatedPeers[index]
            allSimulatedPeers[index] = current.copy(
                estimatedDistanceMeters = newDistance,
                proximityLabel = distanceEstimator.getProximityLabel(newDistance)
            )
            refreshDiscoveredList()
        }
    }

    /**
     * Adiciona nova oferta local (10 metros)
     */
    fun addLocalOffer(offer: OfferItem) {
        _localOffers.value = listOf(offer) + _localOffers.value.filter { it.id != offer.id }
    }

    fun updateLocalOffer(offer: OfferItem) {
        _localOffers.value = _localOffers.value.map { if (it.id == offer.id) offer else it }
    }

    fun deleteLocalOffer(offerId: String) {
        _localOffers.value = _localOffers.value.filter { it.id != offerId }
    }

    /**
     * Envia mensagem no chat local (10 metros)
     */
    fun sendLocalMessage(text: String, myAlias: String) {
        val msg = ChatMessage(
            senderId = "me",
            senderAlias = myAlias,
            text = text,
            isLocalOnly = true,
            isFromMe = true
        )
        _localChatMessages.value = _localChatMessages.value + msg
    }

    /**
     * Limpa as mensagens do mural local (10m)
     */
    fun clearLocalMessages() {
        _localChatMessages.value = emptyList()
    }

    /**
     * Envia mensagem privada para conexão mútua (qualquer distância)
     */
    fun sendPrivateMessage(recipientId: String, text: String, myAlias: String) {
        val msg = ChatMessage(
            senderId = "me",
            senderAlias = myAlias,
            text = text,
            isLocalOnly = false,
            isEncrypted = true,
            isFromMe = true
        )
        val cleanId = recipientId.removePrefix("peer-")
        val currentList = _privateChats.value[recipientId] ?: _privateChats.value[cleanId] ?: emptyList()
        val updatedMap = _privateChats.value.toMutableMap()
        updatedMap[recipientId] = currentList + msg
        updatedMap[cleanId] = currentList + msg
        updatedMap["peer-$cleanId"] = currentList + msg
        _privateChats.value = updatedMap
    }

    /**
     * Recebe mensagem privada entregue de outro dispositivo
     */
    fun receivePrivateMessage(senderId: String, senderAlias: String, text: String, messageId: String = "") {
        val cleanId = senderId.removePrefix("peer-")
        val currentList = _privateChats.value[cleanId] ?: _privateChats.value["peer-$cleanId"] ?: emptyList()

        // Deduplicação 1: Checa por ID único da mensagem
        if (messageId.isNotBlank() && currentList.any { it.id == messageId }) {
            return
        }

        // Deduplicação 2: Se a última mensagem for idêntica e tiver chegado em menos de 4 segundos
        val lastMsg = currentList.lastOrNull()
        if (lastMsg != null && lastMsg.senderId == cleanId && lastMsg.text == text && (System.currentTimeMillis() - lastMsg.timestampMs < 4000)) {
            return
        }

        val isAudio = text.startsWith("[AUDIO:")
        val isImage = text.startsWith("[IMAGE:")
        val isDoc = text.startsWith("[DOC:")

        val duration = if (isAudio) {
            text.substringAfter("[AUDIO:").substringBefore("]").toIntOrNull() ?: 3
        } else 0

        val (docName, docSize, docBase64) = if (isDoc) {
            val parts = text.removePrefix("[DOC:").removeSuffix("]").split(":", limit = 3)
            Triple(parts.getOrNull(0) ?: "documento.pdf", parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2) ?: "")
        } else {
            Triple(null, 0L, null)
        }

        val imageBase64 = if (isImage) {
            text.removePrefix("[IMAGE:").removeSuffix("]")
        } else null

        val finalType = when {
            isAudio -> MessageType.AUDIO
            isImage -> MessageType.IMAGE
            isDoc -> MessageType.DOCUMENT
            else -> MessageType.TEXT
        }

        val displayText = when {
            isAudio -> "Mensagem de Áudio (${duration}s)"
            isImage -> "📷 Foto/Imagem"
            isDoc -> "📄 ${docName ?: "Documento"}"
            else -> text
        }

        val msg = ChatMessage(
            id = if (messageId.isNotBlank()) messageId else UUID.randomUUID().toString(),
            senderId = cleanId,
            senderAlias = senderAlias,
            text = displayText,
            isLocalOnly = false,
            isEncrypted = true,
            isFromMe = false,
            messageType = finalType,
            audioDurationSeconds = duration,
            mediaBase64 = imageBase64 ?: docBase64,
            fileName = docName,
            fileSizeBytes = docSize
        )

        val updatedMap = _privateChats.value.toMutableMap()
        updatedMap[cleanId] = currentList + msg
        updatedMap["peer-$cleanId"] = currentList + msg
        _privateChats.value = updatedMap
    }

    fun sendAudioMessage(recipientId: String, durationSeconds: Int, myAlias: String, messageId: String = UUID.randomUUID().toString()) {
        val cleanId = recipientId.removePrefix("peer-")
        val currentList = _privateChats.value[recipientId] ?: _privateChats.value[cleanId] ?: emptyList()
        if (currentList.any { it.id == messageId }) return

        val msg = ChatMessage(
            id = messageId,
            senderId = "me",
            senderAlias = myAlias,
            text = "Mensagem de Áudio (${durationSeconds}s)",
            isLocalOnly = false,
            isEncrypted = true,
            isFromMe = true,
            messageType = MessageType.AUDIO,
            audioDurationSeconds = durationSeconds
        )

        val updatedMap = _privateChats.value.toMutableMap()
        updatedMap[recipientId] = currentList + msg
        updatedMap[cleanId] = currentList + msg
        updatedMap["peer-$cleanId"] = currentList + msg
        _privateChats.value = updatedMap
    }

    fun sendImageMessage(recipientId: String, base64: String, myAlias: String, messageId: String = UUID.randomUUID().toString()) {
        val cleanId = recipientId.removePrefix("peer-")
        val currentList = _privateChats.value[recipientId] ?: _privateChats.value[cleanId] ?: emptyList()
        if (currentList.any { it.id == messageId }) return

        val msg = ChatMessage(
            id = messageId,
            senderId = "me",
            senderAlias = myAlias,
            text = "📷 Foto/Imagem",
            isLocalOnly = false,
            isEncrypted = true,
            isFromMe = true,
            messageType = MessageType.IMAGE,
            mediaBase64 = base64
        )

        val updatedMap = _privateChats.value.toMutableMap()
        updatedMap[recipientId] = currentList + msg
        updatedMap[cleanId] = currentList + msg
        updatedMap["peer-$cleanId"] = currentList + msg
        _privateChats.value = updatedMap
    }

    fun sendDocumentMessage(recipientId: String, fileName: String, base64: String, sizeBytes: Long, myAlias: String, messageId: String = UUID.randomUUID().toString()) {
        val cleanId = recipientId.removePrefix("peer-")
        val currentList = _privateChats.value[recipientId] ?: _privateChats.value[cleanId] ?: emptyList()
        if (currentList.any { it.id == messageId }) return

        val msg = ChatMessage(
            id = messageId,
            senderId = "me",
            senderAlias = myAlias,
            text = "📄 $fileName",
            isLocalOnly = false,
            isEncrypted = true,
            isFromMe = true,
            messageType = MessageType.DOCUMENT,
            fileName = fileName,
            fileSizeBytes = sizeBytes,
            mediaBase64 = base64
        )

        val updatedMap = _privateChats.value.toMutableMap()
        updatedMap[recipientId] = currentList + msg
        updatedMap[cleanId] = currentList + msg
        updatedMap["peer-$cleanId"] = currentList + msg
        _privateChats.value = updatedMap
    }

    /**
     * Gera autorização temporária de recuperação de identidade por um familiar autorizado.
     */
    fun generateFamilyRecoveryAuthorization(
        targetIdentityHash: String,
        familyName: String
    ): RecoveryAuthorization {
        // Chave criptográfica temporária alfanumérica de uso único
        val rawToken = "PA-REC-${UUID.randomUUID().toString().take(8).uppercase()}"
        val auth = RecoveryAuthorization(
            targetIdentityHash = targetIdentityHash,
            authorizedByFamilyName = familyName,
            recoveryKey = rawToken,
            expiresAtEpochMs = System.currentTimeMillis() + (15 * 60 * 1000) // 15 minutos
        )
        _recoveryAuthorizations.value = _recoveryAuthorizations.value + auth
        return auth
    }

    fun findActiveRecoveryAuthorization(keyOrQrPayload: String): RecoveryAuthorization? {
        val now = System.currentTimeMillis()
        val trimmed = keyOrQrPayload.trim().uppercase()
        return _recoveryAuthorizations.value.firstOrNull {
            !it.isUsed && it.expiresAtEpochMs > now &&
                    (it.recoveryKey.uppercase() == trimmed || trimmed.contains(it.recoveryKey.uppercase()))
        }
    }
}
