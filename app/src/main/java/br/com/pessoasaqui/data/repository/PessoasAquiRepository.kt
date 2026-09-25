package br.com.pessoasaqui.data.repository

import android.content.Context
import android.util.Log
import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.DeviceSessionManager
import br.com.pessoasaqui.core.crypto.PinSecurityManager
import br.com.pessoasaqui.core.moderation.ContentModerationManager
import br.com.pessoasaqui.core.proximity.BleManager
import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient
import br.com.pessoasaqui.data.remote.RemotePeer
import br.com.pessoasaqui.domain.model.ActiveCallState
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.OfferItem
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.domain.model.UserIntent
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Repositório central do PessoasAqui que unifica as regras de negócio,
 * identidade técnica, rádio BLE nativo, presença em tempo real (Render/Supabase)
 * e fluxos de recuperação criptográfica.
 */
class PessoasAquiRepository(
    val context: Context? = null,
    val cryptoIdentityManager: CryptoIdentityManager = CryptoIdentityManager(context),
    val pinSecurityManager: PinSecurityManager = PinSecurityManager(),
    val deviceSessionManager: DeviceSessionManager = DeviceSessionManager(),
    val proximitySimulator: ProximitySimulator = ProximitySimulator(),
    val moderationManager: ContentModerationManager = ContentModerationManager(),
    val bleManager: BleManager? = null,
    val networkClient: PessoasAquiNetworkClient = PessoasAquiNetworkClient()
) {
    private val prefs = context?.getSharedPreferences("pessoasaqui_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    // Estado do usuário atual neste aparelho persistido com SharedPreferences
    private val _isAgeVerified = MutableStateFlow(prefs?.getBoolean("is_age_verified", false) ?: false)
    val isAgeVerified: StateFlow<Boolean> = _isAgeVerified.asStateFlow()

    private val _hasCompletedProfile = MutableStateFlow(prefs?.getBoolean("has_completed_profile", false) ?: false)
    val hasCompletedProfile: StateFlow<Boolean> = _hasCompletedProfile.asStateFlow()

    private val _hasGrantedPermissions = MutableStateFlow(prefs?.getBoolean("has_granted_permissions", false) ?: false)
    val hasGrantedPermissions: StateFlow<Boolean> = _hasGrantedPermissions.asStateFlow()

    // Modo Demonstração (desativado por padrão em PRODUÇÃO para exibir apenas dados reais)
    private val _isDemoMode = MutableStateFlow(prefs?.getBoolean("is_demo_mode", false) ?: false)
    val isDemoMode: StateFlow<Boolean> = _isDemoMode.asStateFlow()

    private val _currentAlias = MutableStateFlow(prefs?.getString("user_alias", "Eu") ?: "Eu")
    val currentAlias: StateFlow<String> = _currentAlias.asStateFlow()

    private val _currentIntent = MutableStateFlow(
        try {
            UserIntent.valueOf(prefs?.getString("user_intent", UserIntent.QUERO_CONVERSAR.name) ?: UserIntent.QUERO_CONVERSAR.name)
        } catch (_: Exception) {
            UserIntent.QUERO_CONVERSAR
        }
    )
    val currentIntent: StateFlow<UserIntent> = _currentIntent.asStateFlow()

    // Pessoas reais detectadas fisicamente via rádio BLE ou presença WebSocket
    private val _realDiscoveredPeople = MutableStateFlow<List<NearbyPerson>>(emptyList())

    // Conjuntos persistidos de marcações e conexões mútuas em SharedPreferences
    private val savedMarkedPeople = java.util.Collections.synchronizedSet(
        (prefs?.getStringSet("saved_marked_people", emptySet()) ?: emptySet()).toMutableSet()
    )
    private val savedMarkingMePeople = java.util.Collections.synchronizedSet(
        (prefs?.getStringSet("saved_marking_me_people", emptySet()) ?: emptySet()).toMutableSet()
    )
    private val savedMutualPeople = java.util.Collections.synchronizedSet(
        (prefs?.getStringSet("saved_mutual_people", emptySet()) ?: emptySet()).toMutableSet()
    )

    fun cleanId(id: String): String = id.removePrefix("peer-").trim().uppercase()

    fun saveConnectionsToPrefs() {
        prefs?.edit()
            ?.putStringSet("saved_marked_people", HashSet(savedMarkedPeople))
            ?.putStringSet("saved_marking_me_people", HashSet(savedMarkingMePeople))
            ?.putStringSet("saved_mutual_people", HashSet(savedMutualPeople))
            ?.apply()
    }

    fun isPeerMarkedByMe(person: NearbyPerson): Boolean {
        val cid = cleanId(person.technicalIdentityHash)
        val pid = cleanId(person.id)
        val aliasClean = cleanId(person.alias)
        return person.isMarkedByMe ||
                (cid.isNotBlank() && savedMarkedPeople.contains(cid)) ||
                (pid.isNotBlank() && savedMarkedPeople.contains(pid)) ||
                (aliasClean.isNotBlank() && !aliasClean.startsWith("PESSOA") && savedMarkedPeople.contains(aliasClean))
    }

    fun isPeerMarkingMe(person: NearbyPerson): Boolean {
        val cid = cleanId(person.technicalIdentityHash)
        val pid = cleanId(person.id)
        val aliasClean = cleanId(person.alias)
        return person.isMarkingMe ||
                (cid.isNotBlank() && savedMarkingMePeople.contains(cid)) ||
                (pid.isNotBlank() && savedMarkingMePeople.contains(pid)) ||
                (aliasClean.isNotBlank() && !aliasClean.startsWith("PESSOA") && savedMarkingMePeople.contains(aliasClean))
    }

    fun isPeerMutual(person: NearbyPerson): Boolean {
        if (person.isFamily) return true
        if (person.isMutualConnection) return true
        val cid = cleanId(person.technicalIdentityHash)
        val pid = cleanId(person.id)
        val aliasClean = cleanId(person.alias)
        if ((cid.isNotBlank() && savedMutualPeople.contains(cid)) ||
            (pid.isNotBlank() && savedMutualPeople.contains(pid)) ||
            (aliasClean.isNotBlank() && !aliasClean.startsWith("PESSOA") && savedMutualPeople.contains(aliasClean))) {
            return true
        }
        val marked = isPeerMarkedByMe(person)
        val marking = isPeerMarkingMe(person)
        return marked && marking
    }

    fun applyConnectionState(person: NearbyPerson): NearbyPerson {
        val cid = cleanId(if (person.technicalIdentityHash.isNotBlank()) person.technicalIdentityHash else person.id)
        val marked = isPeerMarkedByMe(person)
        val marking = isPeerMarkingMe(person)
        val mutual = isPeerMutual(person) || (marked && marking)

        if (mutual && cid.isNotBlank()) {
            savedMutualPeople.add(cid)
            savedMarkedPeople.add(cid)
            savedMarkingMePeople.add(cid)
            val aliasClean = cleanId(person.alias)
            if (aliasClean.isNotBlank() && !aliasClean.startsWith("PESSOA")) {
                savedMutualPeople.add(aliasClean)
                savedMarkedPeople.add(aliasClean)
                savedMarkingMePeople.add(aliasClean)
            }
        }

        return person.copy(
            id = cid,
            technicalIdentityHash = cid,
            isMarkedByMe = marked || mutual,
            isMarkingMe = marking || mutual,
            isMutualConnection = mutual,
            isPhotoVisible = mutual || person.isFamily
        )
    }

    fun getLivePerson(person: NearbyPerson): NearbyPerson {
        val targetCid = cleanId(person.technicalIdentityHash.ifBlank { person.id })
        val targetPid = cleanId(person.id)
        val targetAlias = cleanId(person.alias)

        val found = _realDiscoveredPeople.value.find { candidate ->
            val cCid = cleanId(candidate.technicalIdentityHash)
            val cPid = cleanId(candidate.id)
            val cAlias = cleanId(candidate.alias)

            (targetCid.isNotBlank() && (targetCid == cCid || targetCid == cPid)) ||
            (targetPid.isNotBlank() && (targetPid == cCid || targetPid == cPid)) ||
            (!targetAlias.startsWith("PESSOA") && targetAlias.isNotBlank() && targetAlias == cAlias)
        } ?: _unifiedDiscoveredPeople.value.find { candidate ->
            val cCid = cleanId(candidate.technicalIdentityHash)
            val cPid = cleanId(candidate.id)
            val cAlias = cleanId(candidate.alias)

            (targetCid.isNotBlank() && (targetCid == cCid || targetCid == cPid)) ||
            (targetPid.isNotBlank() && (targetPid == cCid || targetPid == cPid)) ||
            (!targetAlias.startsWith("PESSOA") && targetAlias.isNotBlank() && targetAlias == cAlias)
        }

        val basePerson = found ?: person
        return applyConnectionState(basePerson)
    }

    // Lista unificada exibida na UI
    private val _unifiedDiscoveredPeople = MutableStateFlow<List<NearbyPerson>>(emptyList())
    val discoveredPeople: StateFlow<List<NearbyPerson>> = _unifiedDiscoveredPeople.asStateFlow()

    private fun loadUserOffersFromPrefs(): List<OfferItem> {
        val jsonStr = prefs?.getString("saved_user_offers", null) ?: return emptyList()
        return try {
            val array = JSONArray(jsonStr)
            val list = mutableListOf<OfferItem>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val img = obj.optString("imageBase64").takeIf { it.isNotBlank() }
                val link = obj.optString("externalLink").takeIf { it.isNotBlank() }
                list.add(
                    OfferItem(
                        id = obj.getString("id"),
                        authorId = obj.getString("authorId"),
                        authorAlias = obj.getString("authorAlias"),
                        profession = obj.getString("profession"),
                        description = obj.getString("description"),
                        distanceMeters = obj.optDouble("distanceMeters", 0.5),
                        proximityLabel = obj.optString("proximityLabel", "Seu anúncio • 10m"),
                        imageBase64 = img,
                        externalLink = link
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveUserOffersToPrefs() {
        try {
            val array = JSONArray()
            val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
            val myOffers = _realOffers.value.filter {
                cleanId(it.authorId) == myId || it.proximityLabel.contains("Seu anúncio")
            }
            myOffers.forEach { offer ->
                val obj = JSONObject()
                obj.put("id", offer.id)
                obj.put("authorId", offer.authorId)
                obj.put("authorAlias", offer.authorAlias)
                obj.put("profession", offer.profession)
                obj.put("description", offer.description)
                obj.put("distanceMeters", offer.distanceMeters)
                obj.put("proximityLabel", offer.proximityLabel)
                obj.put("imageBase64", offer.imageBase64 ?: "")
                obj.put("externalLink", offer.externalLink ?: "")
                array.put(obj)
            }
            prefs?.edit()?.putString("saved_user_offers", array.toString())?.apply()
        } catch (e: Exception) {
            Log.e("PessoasAqui", "Erro ao salvar ofertas no prefs", e)
        }
    }

    private fun handleRemoteOfferPublished(remoteOffer: OfferItem) {
        val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
        val current = _realOffers.value.toMutableList()
        val isMine = cleanId(remoteOffer.authorId) == myId
        val adjustedOffer = if (isMine) remoteOffer.copy(proximityLabel = "Seu anúncio • 10m") else remoteOffer
        val idx = current.indexOfFirst { it.id == adjustedOffer.id }
        if (idx != -1) {
            current[idx] = adjustedOffer
        } else {
            current.add(0, adjustedOffer)
        }
        _realOffers.value = current
        updateUnifiedData()
    }

    private fun handleRemoteOfferDeleted(offerId: String) {
        _realOffers.value = _realOffers.value.filter { it.id != offerId }
        saveUserOffersToPrefs()
        updateUnifiedData()
    }

    // Ofertas locais reais (restauradas de SharedPreferences)
    private val _realOffers = MutableStateFlow<List<OfferItem>>(loadUserOffersFromPrefs())
    private val _unifiedOffers = MutableStateFlow<List<OfferItem>>(emptyList())
    val localOffers: StateFlow<List<OfferItem>> = _unifiedOffers.asStateFlow()

    val localChatMessages: StateFlow<List<ChatMessage>> = proximitySimulator.localChatMessages
    val privateChats: StateFlow<Map<String, List<ChatMessage>>> = proximitySimulator.privateChats

    val isBluetoothEnabled: StateFlow<Boolean> = bleManager?.isBluetoothEnabledFlow ?: MutableStateFlow(true)

    private val _activeCallState = MutableStateFlow<ActiveCallState?>(null)
    val activeCallState: StateFlow<ActiveCallState?> = _activeCallState.asStateFlow()

    private val _pendingFamilyRequest = MutableStateFlow<Pair<NearbyPerson, FamilyRole>?>(null)
    val pendingFamilyRequest: StateFlow<Pair<NearbyPerson, FamilyRole>?> = _pendingFamilyRequest.asStateFlow()

    init {
        updateUnifiedData()

        networkClient.onOfferPublished = { offer ->
            scope.launch { handleRemoteOfferPublished(offer) }
        }
        networkClient.onOfferDeleted = { offerId ->
            scope.launch { handleRemoteOfferDeleted(offerId) }
        }

        startPresencePollingLoop()

        // Se o perfil já estiver configurado, registra identidade no Supabase / Render
        if (_hasCompletedProfile.value) {
            registerIdentityOnBackend()
        }

        if (_hasGrantedPermissions.value) {
            startNativeBleHardware()
        }
    }

    private val processedMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun processIncomingPayload(senderHash: String, senderAlias: String, payload: String, messageId: String = "") {
        if (messageId.isNotBlank()) {
            if (processedMessageIds.contains(messageId)) return
            processedMessageIds.add(messageId)
            if (processedMessageIds.size > 2000) {
                processedMessageIds.clear()
            }
        }

        val cleanSender = cleanId(senderHash)
        when {
            payload.startsWith("[CALL_INIT:") -> {
                val parts = payload.removePrefix("[CALL_INIT:").removeSuffix("]").split(":")
                val isVideo = parts.getOrNull(0)?.toBoolean() ?: false
                val callerAlias = parts.getOrNull(1) ?: senderAlias
                _activeCallState.value = ActiveCallState(
                    isIncoming = true,
                    peerHash = cleanSender,
                    peerAlias = callerAlias,
                    isVideo = isVideo,
                    isConnected = false
                )
            }
            payload == "[CALL_ACCEPT]" -> {
                _activeCallState.value = _activeCallState.value?.copy(isConnected = true)
            }
            payload == "[CALL_END]" -> {
                _activeCallState.value = null
            }
            payload.startsWith("[FAMILY_REQ:") -> {
                val parts = payload.removePrefix("[FAMILY_REQ:").removeSuffix("]").split(":")
                val roleName = parts.getOrNull(0) ?: FamilyRole.OUTRO.name
                val reqAlias = parts.getOrNull(1) ?: senderAlias
                val role = try { FamilyRole.valueOf(roleName) } catch (_: Exception) { FamilyRole.OUTRO }
                val person = _realDiscoveredPeople.value.find { cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender }
                    ?: NearbyPerson(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = reqAlias,
                        estimatedDistanceMeters = 3.0,
                        proximityLabel = "Familiar"
                    )
                _pendingFamilyRequest.value = Pair(person, role)
            }
            payload.startsWith("[FAMILY_ACCEPT:") -> {
                val parts = payload.removePrefix("[FAMILY_ACCEPT:").removeSuffix("]").split(":")
                val roleName = parts.getOrNull(0) ?: FamilyRole.OUTRO.name
                val partnerAlias = parts.getOrNull(1) ?: senderAlias
                val role = try { FamilyRole.valueOf(roleName) } catch (_: Exception) { FamilyRole.OUTRO }
                setFamilyRole(cleanSender, role)
                proximitySimulator.receivePrivateMessage(
                    cleanSender,
                    senderAlias,
                    "✨ Vínculo Familiar (${role.label}) confirmado mutuamente com $partnerAlias! Chamadas, fotos e áudios liberados.",
                    messageId
                )
            }
            payload.startsWith("[MARK_ON:") -> {
                val callerAlias = payload.removePrefix("[MARK_ON:").removeSuffix("]").ifBlank { senderAlias }
                val currentReal = _realDiscoveredPeople.value.toMutableList()
                val idx = currentReal.indexOfFirst {
                    cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender ||
                    (callerAlias.isNotBlank() && !it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(callerAlias, ignoreCase = true))
                }

                val targetPeer = if (idx != -1) currentReal[idx] else null
                val hashId = cleanId(targetPeer?.technicalIdentityHash?.ifBlank { cleanSender } ?: cleanSender)
                val pId = cleanId(targetPeer?.id ?: cleanSender)
                val aliasId = cleanId(callerAlias)

                val keys = listOfNotNull(
                    hashId.takeIf { it.isNotBlank() },
                    pId.takeIf { it.isNotBlank() },
                    aliasId.takeIf { it.isNotBlank() && !it.startsWith("PESSOA") },
                    cleanSender.takeIf { it.isNotBlank() }
                ).distinct()

                keys.forEach { savedMarkingMePeople.add(it) }
                val isMarked = targetPeer?.let { isPeerMarkedByMe(it) } ?: keys.any { savedMarkedPeople.contains(it) }
                val isNowMutual = isMarked
                if (isNowMutual) {
                    keys.forEach {
                        savedMutualPeople.add(it)
                        savedMarkedPeople.add(it)
                    }
                }
                saveConnectionsToPrefs()

                val updatedPeer = if (idx != -1) {
                    val p = currentReal[idx]
                    val updated = p.copy(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = if (p.alias.startsWith("Pessoa Próxima #") && callerAlias.isNotBlank()) callerAlias else p.alias,
                        isMarkedByMe = isMarked,
                        isMarkingMe = true,
                        isMutualConnection = isNowMutual || p.isFamily,
                        isPhotoVisible = isNowMutual || p.isFamily,
                        lastSeenEpochMs = System.currentTimeMillis()
                    )
                    currentReal[idx] = updated
                    updated
                } else {
                    val newPeer = NearbyPerson(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = if (callerAlias.isNotBlank()) callerAlias else senderAlias,
                        estimatedDistanceMeters = 3.0,
                        proximityLabel = "Pessoas no local (~3m)",
                        isMarkedByMe = isMarked,
                        isMarkingMe = true,
                        isMutualConnection = isNowMutual,
                        isPhotoVisible = isNowMutual,
                        avatarColorHex = 0xFF00E5FF,
                        lastSeenEpochMs = System.currentTimeMillis()
                    )
                    currentReal.add(0, newPeer)
                    newPeer
                }
                _realDiscoveredPeople.value = currentReal
                updateUnifiedData()

                if (isNowMutual) {
                    sendPrivateMessage(cleanSender, "[MARK_MUTUAL:${_currentAlias.value}]")
                    proximitySimulator.receivePrivateMessage(
                        cleanSender,
                        "Sistema",
                        "✨ Conexão Mútua Estabelecida com ${updatedPeer.alias}! Comunicação à distância, chamadas e mídia liberadas.",
                        messageId
                    )
                } else {
                    proximitySimulator.receivePrivateMessage(
                        cleanSender,
                        "Sistema",
                        "★ ${updatedPeer.alias} marcou você! Toque no coração para aceitar conexão mútua.",
                        messageId
                    )
                }
            }
            payload.startsWith("[MARK_OFF:") -> {
                val currentReal = _realDiscoveredPeople.value.toMutableList()
                val idx = currentReal.indexOfFirst { cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender }
                val targetPeer = if (idx != -1) currentReal[idx] else null
                val hashId = cleanId(targetPeer?.technicalIdentityHash?.ifBlank { cleanSender } ?: cleanSender)
                val pId = cleanId(targetPeer?.id ?: cleanSender)
                val aliasId = cleanId(targetPeer?.alias ?: "")

                val keys = listOfNotNull(
                    hashId.takeIf { it.isNotBlank() },
                    pId.takeIf { it.isNotBlank() },
                    aliasId.takeIf { it.isNotBlank() && !it.startsWith("PESSOA") },
                    cleanSender.takeIf { it.isNotBlank() }
                ).distinct()

                keys.forEach {
                    savedMarkingMePeople.remove(it)
                    savedMutualPeople.remove(it)
                }
                saveConnectionsToPrefs()

                if (idx != -1) {
                    val p = currentReal[idx]
                    val updated = p.copy(
                        isMarkingMe = false,
                        isMutualConnection = p.isFamily,
                        isPhotoVisible = p.isFamily
                    )
                    currentReal[idx] = updated
                    _realDiscoveredPeople.value = currentReal
                    updateUnifiedData()
                }
            }
            payload.startsWith("[MARK_MUTUAL:") -> {
                val callerAlias = payload.removePrefix("[MARK_MUTUAL:").removeSuffix("]").ifBlank { senderAlias }
                val currentReal = _realDiscoveredPeople.value.toMutableList()
                val idx = currentReal.indexOfFirst {
                    cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender ||
                    (callerAlias.isNotBlank() && !it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(callerAlias, ignoreCase = true))
                }

                val targetPeer = if (idx != -1) currentReal[idx] else null
                val hashId = cleanId(targetPeer?.technicalIdentityHash?.ifBlank { cleanSender } ?: cleanSender)
                val pId = cleanId(targetPeer?.id ?: cleanSender)
                val aliasId = cleanId(callerAlias)

                val keys = listOfNotNull(
                    hashId.takeIf { it.isNotBlank() },
                    pId.takeIf { it.isNotBlank() },
                    aliasId.takeIf { it.isNotBlank() && !it.startsWith("PESSOA") },
                    cleanSender.takeIf { it.isNotBlank() }
                ).distinct()

                keys.forEach {
                    savedMarkingMePeople.add(it)
                    savedMarkedPeople.add(it)
                    savedMutualPeople.add(it)
                }
                saveConnectionsToPrefs()

                val updatedPeer = if (idx != -1) {
                    val p = currentReal[idx]
                    val updated = p.copy(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = if (p.alias.startsWith("Pessoa Próxima #") && callerAlias.isNotBlank()) callerAlias else p.alias,
                        isMarkedByMe = true,
                        isMarkingMe = true,
                        isMutualConnection = true,
                        isPhotoVisible = true,
                        lastSeenEpochMs = System.currentTimeMillis()
                    )
                    currentReal[idx] = updated
                    updated
                } else {
                    val newPeer = NearbyPerson(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = if (callerAlias.isNotBlank()) callerAlias else senderAlias,
                        estimatedDistanceMeters = 3.0,
                        proximityLabel = "Pessoas no local (~3m)",
                        isMarkedByMe = true,
                        isMarkingMe = true,
                        isMutualConnection = true,
                        isPhotoVisible = true,
                        avatarColorHex = 0xFF00E5FF,
                        lastSeenEpochMs = System.currentTimeMillis()
                    )
                    currentReal.add(0, newPeer)
                    newPeer
                }
                _realDiscoveredPeople.value = currentReal
                updateUnifiedData()

                proximitySimulator.receivePrivateMessage(
                    cleanSender,
                    "Sistema",
                    "✨ Conexão Mútua Estabelecida com ${updatedPeer.alias}! Comunicação à distância, chamadas e mídia liberadas.",
                    messageId
                )
            }
            else -> {
                proximitySimulator.receivePrivateMessage(cleanSender, senderAlias, payload, messageId)
            }
        }
    }

    private fun startPresencePollingLoop() {
        scope.launch {
            while (true) {
                try {
                    val myId = cryptoIdentityManager.getTechnicalIdentity()
                    val myAlias = _currentAlias.value
                    val myIntent = _currentIntent.value.name

                    // 1. Envia Heartbeat HTTP para manter a presença viva no Render / Supabase
                    networkClient.sendPresenceHeartbeat(myId, myAlias, myIntent)

                    // 2. Busca status de conexões mútuas no backend
                    val statusResult = networkClient.fetchConnectionStatus(myId)
                    statusResult.getOrNull()?.let { st ->
                        var changed = false
                        st.markedByMe.forEach {
                            val cid = cleanId(it)
                            if (savedMarkedPeople.add(cid)) changed = true
                        }
                        st.markingMe.forEach {
                            val cid = cleanId(it)
                            if (savedMarkingMePeople.add(cid)) changed = true
                        }
                        st.mutual.forEach {
                            val cid = cleanId(it)
                            if (savedMutualPeople.add(cid)) changed = true
                            if (savedMarkedPeople.add(cid)) changed = true
                            if (savedMarkingMePeople.add(cid)) changed = true
                        }
                        if (changed) {
                            saveConnectionsToPrefs()
                            val cur = _realDiscoveredPeople.value.map { applyConnectionState(it) }
                            _realDiscoveredPeople.value = cur
                            updateUnifiedData()
                        }
                    }

                    // 3. Busca pessoas próximas registradas no backend
                    val nearbyResult = networkClient.fetchNearbyPresence(myId, myAlias)
                    nearbyResult.getOrNull()?.let { remotePeers ->
                        handleRemotePresenceSync(remotePeers)
                    }

                    // 4. Busca mensagens pendentes na fila do servidor
                    val pendingResult = networkClient.fetchPendingMessages(myId)
                    pendingResult.getOrNull()?.forEach { pm ->
                        processIncomingPayload(pm.senderHash, pm.senderAlias, pm.ciphertext, pm.messageId)
                    }

                    // 5. Busca ofertas e divulgações de outros usuários próximos
                    val offersResult = networkClient.fetchNearbyOffers()
                    offersResult.getOrNull()?.let { remoteOffers ->
                        val myCleanId = cleanId(myId)
                        val current = _realOffers.value.toMutableList()
                        var offersChanged = false
                        remoteOffers.forEach { ro ->
                            val isMine = cleanId(ro.authorId) == myCleanId
                            val item = if (isMine) ro.copy(proximityLabel = "Seu anúncio • 10m") else ro
                            val idx = current.indexOfFirst { it.id == item.id }
                            if (idx != -1) {
                                current[idx] = item
                            } else {
                                current.add(item)
                                offersChanged = true
                            }
                        }
                        if (offersChanged) {
                            _realOffers.value = current
                            updateUnifiedData()
                        }
                    }

                    // 5. Garante que o WebSocket esteja ativo para sincronização instantânea
                    if (!networkClient.isWebSocketConnected) {
                        networkClient.startRealtimeSocket(
                            myIdentityHash = myId,
                            myAlias = myAlias,
                            myIntent = myIntent,
                            onE2eeMessageReceived = { sender, payload, _, msgId ->
                                val cleanSender = cleanId(sender)
                                val senderAlias = _realDiscoveredPeople.value.find { cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender }?.alias ?: "Pessoa Próxima"
                                processIncomingPayload(cleanSender, senderAlias, payload, msgId)
                            },
                            onMutualConnection = { peerA, peerB ->
                                val otherId = if (cleanId(peerA) == cleanId(myId)) cleanId(peerB) else cleanId(peerA)
                                savedMutualPeople.add(otherId)
                                savedMarkedPeople.add(otherId)
                                savedMarkingMePeople.add(otherId)
                                saveConnectionsToPrefs()

                                val currentReal = _realDiscoveredPeople.value.toMutableList()
                                val idx = currentReal.indexOfFirst { cleanId(it.id) == otherId || cleanId(it.technicalIdentityHash) == otherId }
                                if (idx != -1) {
                                    val p = currentReal[idx]
                                    currentReal[idx] = p.copy(
                                        isMarkedByMe = true,
                                        isMarkingMe = true,
                                        isMutualConnection = true,
                                        isPhotoVisible = true
                                    )
                                    _realDiscoveredPeople.value = currentReal
                                    updateUnifiedData()
                                }
                                proximitySimulator.receivePrivateMessage(
                                    otherId,
                                    "Sistema",
                                    "✨ Conexão Mútua Estabelecida pelo Servidor! Comunicação à distância liberada.",
                                    UUID.randomUUID().toString()
                                )
                            },
                            onPeerMarkedYou = { markerId, _ ->
                                val cleanMarker = cleanId(markerId)
                                savedMarkingMePeople.add(cleanMarker)
                                val isMutual = savedMarkedPeople.contains(cleanMarker)
                                if (isMutual) {
                                    savedMutualPeople.add(cleanMarker)
                                }
                                saveConnectionsToPrefs()

                                val currentReal = _realDiscoveredPeople.value.toMutableList()
                                val idx = currentReal.indexOfFirst { cleanId(it.id) == cleanMarker || cleanId(it.technicalIdentityHash) == cleanMarker }
                                if (idx != -1) {
                                    val p = currentReal[idx]
                                    val updated = p.copy(
                                        isMarkingMe = true,
                                        isMutualConnection = isMutual || p.isFamily,
                                        isPhotoVisible = isMutual || p.isFamily
                                    )
                                    currentReal[idx] = updated
                                    _realDiscoveredPeople.value = currentReal
                                    updateUnifiedData()
                                }
                            },
                            onSessionRevoked = { _ ->
                                deviceSessionManager.revokeCurrentDeviceSession()
                            },
                            onPresenceSync = { remotePeers ->
                                handleRemotePresenceSync(remotePeers)
                            },
                            onPeerOnline = { peer ->
                                handlePeerOnline(peer)
                            },
                            onPeerOffline = { peerHash ->
                                handlePeerOffline(peerHash)
                            }
                        )
                    }

                    // 6. Limpeza automática de fantasmas e pessoas que saíram do alcance (> 15 segundos)
                    // Pessoas com conexão mútua, parentesco ou marcação NUNCA são removidas pelo timeout de alcance!
                    val now = System.currentTimeMillis()
                    val activeList = _realDiscoveredPeople.value.filter { peer ->
                        val cid = cleanId(peer.technicalIdentityHash)
                        val isValidHex = cid.matches(Regex("^[0-9A-F]{8,16}$"))
                        val isRecent = (now - peer.lastSeenEpochMs) < 15000L
                        val isSpecial = peer.isMutualConnection || peer.isFamily || isPeerMutual(peer) || isPeerMarkedByMe(peer) || isPeerMarkingMe(peer)
                        (isValidHex && isRecent) || isSpecial
                    }
                    if (activeList.size != _realDiscoveredPeople.value.size) {
                        _realDiscoveredPeople.value = activeList
                        updateUnifiedData()
                    }
                } catch (e: Throwable) {
                    Log.w("PessoasAqui", "Polling de presença: ${e.message}")
                }
                delay(4000)
            }
        }
    }

    private fun updateUnifiedData() {
        if (_isDemoMode.value) {
            // Em modo demonstração, mescla pessoas reais com as pessoas simuladas
            val simulated = proximitySimulator.discoveredPeople.value
            val combined = (_realDiscoveredPeople.value + simulated)
                .distinctBy { it.technicalIdentityHash }
                .map { applyConnectionState(it) }
                .sortedBy { it.estimatedDistanceMeters }
            _unifiedDiscoveredPeople.value = combined
            _unifiedOffers.value = _realOffers.value + proximitySimulator.localOffers.value
        } else {
            // PRODUÇÃO PURA: Somente pessoas e ofertas reais no radar, sempre com estado de conexão atualizado
            val applied = _realDiscoveredPeople.value
                .map { applyConnectionState(it) }
                .sortedBy { it.estimatedDistanceMeters }
            _unifiedDiscoveredPeople.value = applied
            _unifiedOffers.value = _realOffers.value
        }
    }

    private fun handleRemotePresenceSync(remotePeers: List<RemotePeer>) {
        val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
        val myAlias = _currentAlias.value.trim()
        val current = _realDiscoveredPeople.value.toMutableList()

        val validPeers = remotePeers.filter { p ->
            cleanId(p.identityHash) != myId &&
            (p.alias.isBlank() || myAlias.isBlank() || !p.alias.trim().equals(myAlias, ignoreCase = true))
        }

        val distinctPeers = validPeers.distinctBy { cleanId(it.identityHash) }

        distinctPeers.forEach { p ->
            val cId = cleanId(p.identityHash)
            if (p.isMutual) {
                savedMutualPeople.add(cId)
                savedMarkedPeople.add(cId)
                savedMarkingMePeople.add(cId)
            }
            if (p.isMarkedByMe) savedMarkedPeople.add(cId)
            if (p.isMarkingMe) savedMarkingMePeople.add(cId)

            val safeIntent = try { UserIntent.valueOf(p.intent) } catch (_: Exception) { UserIntent.QUERO_CONVERSAR }
            val idx = current.indexOfFirst {
                cleanId(it.technicalIdentityHash) == cId || cleanId(it.id) == cId ||
                (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(p.alias, ignoreCase = true))
            }
            if (idx != -1) {
                val existing = current[idx]
                current[idx] = applyConnectionState(existing.copy(
                    id = cId,
                    technicalIdentityHash = cId,
                    alias = p.alias,
                    intent = safeIntent,
                    lastSeenEpochMs = System.currentTimeMillis()
                ))
            } else {
                current.add(
                    applyConnectionState(NearbyPerson(
                        id = cId,
                        technicalIdentityHash = cId,
                        alias = p.alias,
                        estimatedDistanceMeters = 3.2,
                        proximityLabel = "Dentro do alcance (~3m)",
                        intent = safeIntent,
                        avatarColorHex = 0xFF00E5FF,
                        lastSeenEpochMs = System.currentTimeMillis()
                    ))
                )
            }
        }
        saveConnectionsToPrefs()

        // Remove do radar qualquer registro com meu próprio id ou apelido
        val cleanedList = current.filter { peer ->
            cleanId(peer.technicalIdentityHash) != myId &&
            (myAlias.isBlank() || !peer.alias.trim().equals(myAlias, ignoreCase = true))
        }

        _realDiscoveredPeople.value = cleanedList
        updateUnifiedData()
    }

    private fun handlePeerOnline(peer: RemotePeer) {
        val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
        val myAlias = _currentAlias.value.trim()
        val cId = cleanId(peer.identityHash)
        if (cId == myId) return
        if (myAlias.isNotBlank() && peer.alias.trim().equals(myAlias, ignoreCase = true)) return

        if (peer.isMutual) {
            savedMutualPeople.add(cId)
            savedMarkedPeople.add(cId)
            savedMarkingMePeople.add(cId)
            saveConnectionsToPrefs()
        }
        if (peer.isMarkedByMe) savedMarkedPeople.add(cId)
        if (peer.isMarkingMe) savedMarkingMePeople.add(cId)

        val safeIntent = try { UserIntent.valueOf(peer.intent) } catch (_: Exception) { UserIntent.QUERO_CONVERSAR }
        val current = _realDiscoveredPeople.value.toMutableList()
        val idx = current.indexOfFirst {
            cleanId(it.technicalIdentityHash) == cId || cleanId(it.id) == cId ||
            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(peer.alias, ignoreCase = true))
        }
        if (idx != -1) {
            val existing = current[idx]
            current[idx] = applyConnectionState(existing.copy(
                id = cId,
                technicalIdentityHash = cId,
                alias = peer.alias,
                intent = safeIntent,
                lastSeenEpochMs = System.currentTimeMillis()
            ))
        } else {
            val newPeer = applyConnectionState(NearbyPerson(
                id = cId,
                technicalIdentityHash = cId,
                alias = peer.alias,
                estimatedDistanceMeters = 2.0,
                proximityLabel = "Dentro do alcance (~2m)",
                intent = safeIntent,
                avatarColorHex = 0xFF00E5FF,
                lastSeenEpochMs = System.currentTimeMillis()
            ))
            current.add(0, newPeer)
        }
        _realDiscoveredPeople.value = current
        updateUnifiedData()
    }

    private fun handlePeerOffline(peerHash: String) {
        val cleanPeerHash = cleanId(peerHash)
        val current = _realDiscoveredPeople.value.filter { cleanId(it.technicalIdentityHash) != cleanPeerHash && cleanId(it.id) != cleanPeerHash }
        _realDiscoveredPeople.value = current
        updateUnifiedData()
    }

    fun completeAgeVerification() {
        _isAgeVerified.value = true
        prefs?.edit()?.putBoolean("is_age_verified", true)?.apply()
    }

    fun completeProfile(alias: String, intent: UserIntent) {
        val trimmed = alias.trim()
        _currentAlias.value = trimmed
        _currentIntent.value = intent
        _hasCompletedProfile.value = true
        prefs?.edit()
            ?.putString("user_alias", trimmed)
            ?.putString("user_intent", intent.name)
            ?.putBoolean("has_completed_profile", true)
            ?.apply()

        registerIdentityOnBackend()
        startNativeBleHardware()
    }

    fun completePermissions() {
        _hasGrantedPermissions.value = true
        prefs?.edit()?.putBoolean("has_granted_permissions", true)?.apply()
        startNativeBleHardware()
    }

    fun setDemoMode(enabled: Boolean) {
        _isDemoMode.value = enabled
        prefs?.edit()?.putBoolean("is_demo_mode", enabled)?.apply()
        updateUnifiedData()
    }

    fun toggleDemoMode() {
        setDemoMode(!_isDemoMode.value)
    }

    private fun registerIdentityOnBackend() {
        scope.launch {
            try {
                networkClient.registerIdentity(
                    identityHash = cryptoIdentityManager.getTechnicalIdentity(),
                    publicKeyEd25519 = cryptoIdentityManager.getPublicKeyBase64(),
                    publicKeyX25519 = cryptoIdentityManager.getPublicKeyBase64(),
                    pin = "0000",
                    alias = _currentAlias.value
                )
                networkClient.sendPresenceHeartbeat(
                    identityHash = cryptoIdentityManager.getTechnicalIdentity(),
                    alias = _currentAlias.value,
                    intent = _currentIntent.value.name
                )
            } catch (e: Exception) {
                Log.e("PessoasAqui", "Erro ao registrar identidade no backend: ${e.message}")
            }
        }
    }

    private fun startNativeBleHardware() {
        try {
            bleManager?.let { ble ->
                if (ble.isBluetoothEnabled) {
                    ble.startAdvertising(
                        myIdentityHash = cryptoIdentityManager.getTechnicalIdentity(),
                        myAlias = _currentAlias.value,
                        myIntent = _currentIntent.value
                    )
                    ble.startScanning { realPeer ->
                        val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
                        val myAlias = _currentAlias.value
                        val peerCleanId = cleanId(realPeer.technicalIdentityHash)
                        if (peerCleanId == myId || cleanId(realPeer.id) == myId) return@startScanning
                        if (realPeer.alias.isNotBlank() && myAlias.isNotBlank() && realPeer.alias.equals(myAlias, ignoreCase = true)) return@startScanning

                        val currentList = _realDiscoveredPeople.value.toMutableList()
                        val idx = currentList.indexOfFirst {
                            cleanId(it.id) == peerCleanId ||
                            cleanId(it.technicalIdentityHash) == peerCleanId ||
                            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(realPeer.alias, ignoreCase = true))
                        }
                        if (idx != -1) {
                            val existing = currentList[idx]
                            val resolvedAlias = if (existing.alias.isNotBlank() && !existing.alias.startsWith("Pessoa Próxima #")) {
                                existing.alias
                            } else if (!realPeer.alias.startsWith("Pessoa Próxima #")) {
                                realPeer.alias
                            } else {
                                existing.alias
                            }
                            currentList[idx] = applyConnectionState(existing.copy(
                                id = peerCleanId,
                                technicalIdentityHash = peerCleanId,
                                alias = resolvedAlias,
                                estimatedDistanceMeters = realPeer.estimatedDistanceMeters,
                                proximityLabel = realPeer.proximityLabel,
                                intent = realPeer.intent,
                                lastSeenEpochMs = System.currentTimeMillis()
                            ))
                        } else {
                            currentList.add(0, applyConnectionState(realPeer.copy(id = peerCleanId, technicalIdentityHash = peerCleanId)))
                        }
                        _realDiscoveredPeople.value = currentList
                        updateUnifiedData()
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("PessoasAqui", "BLE não disponível ou permissão ausente: ${e.message}")
        }
    }

    fun restartBleHardware() {
        startNativeBleHardware()
    }

    fun setAlias(newAlias: String) {
        if (newAlias.isNotBlank()) {
            val trimmed = newAlias.trim()
            _currentAlias.value = trimmed
            prefs?.edit()?.putString("user_alias", trimmed)?.apply()
            registerIdentityOnBackend()
            startNativeBleHardware()
        }
    }

    fun setIntent(intent: UserIntent) {
        _currentIntent.value = intent
        prefs?.edit()?.putString("user_intent", intent.name)?.apply()
        registerIdentityOnBackend()
        startNativeBleHardware()
    }

    fun acceptMutualConnection(personId: String): NearbyPerson? {
        return setMarkPerson(personId, mark = true, explicitMutual = true)
    }

    fun setMarkPerson(personId: String, mark: Boolean, explicitMutual: Boolean = false): NearbyPerson? {
        val cleanTargetId = cleanId(personId)
        val currentReal = _realDiscoveredPeople.value.toMutableList()
        val idx = currentReal.indexOfFirst {
            val cCid = cleanId(it.technicalIdentityHash)
            val cPid = cleanId(it.id)
            val cAlias = cleanId(it.alias)
            cleanTargetId == cCid || cleanTargetId == cPid ||
            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(personId, ignoreCase = true)) ||
            (cleanTargetId.isNotBlank() && !cleanTargetId.startsWith("PESSOA") && cleanTargetId == cAlias)
        }

        var updatedPeer: NearbyPerson? = null

        if (idx != -1) {
            val current = currentReal[idx]
            val hashId = cleanId(current.technicalIdentityHash.ifBlank { current.id })
            val pId = cleanId(current.id)
            val aliasId = cleanId(current.alias)

            val keys = listOfNotNull(
                hashId.takeIf { it.isNotBlank() },
                pId.takeIf { it.isNotBlank() },
                aliasId.takeIf { it.isNotBlank() && !it.startsWith("PESSOA") },
                cleanTargetId.takeIf { it.isNotBlank() && !it.startsWith("PESSOA") }
            ).distinct()

            val wasMarkingMe = current.isMarkingMe || isPeerMarkingMe(current) || keys.any { savedMarkingMePeople.contains(it) }
            val newMarkedByMe = mark
            val isNowMutual = (newMarkedByMe && wasMarkingMe) || explicitMutual || current.isFamily

            if (newMarkedByMe) {
                keys.forEach { savedMarkedPeople.add(it) }
                if (isNowMutual) {
                    keys.forEach {
                        savedMutualPeople.add(it)
                        savedMarkingMePeople.add(it)
                    }
                }
            } else {
                keys.forEach {
                    savedMarkedPeople.remove(it)
                    savedMutualPeople.remove(it)
                }
            }
            saveConnectionsToPrefs()

            val updated = current.copy(
                isMarkedByMe = newMarkedByMe,
                isMarkingMe = wasMarkingMe || isNowMutual,
                isMutualConnection = isNowMutual || current.isFamily,
                isPhotoVisible = isNowMutual || current.isFamily,
                lastSeenEpochMs = System.currentTimeMillis()
            )
            currentReal[idx] = updated
            _realDiscoveredPeople.value = currentReal
            updatedPeer = updated
            updateUnifiedData()

            val myId = cryptoIdentityManager.getTechnicalIdentity()
            val myAlias = _currentAlias.value

            // 1. Envia sinal P2P cifrado em tempo real para o outro aparelho
            if (newMarkedByMe) {
                sendPrivateMessage(updated.technicalIdentityHash, "[MARK_ON:$myAlias]")
                if (isNowMutual) {
                    sendPrivateMessage(updated.technicalIdentityHash, "[MARK_MUTUAL:$myAlias]")
                }
            } else {
                sendPrivateMessage(updated.technicalIdentityHash, "[MARK_OFF:$myAlias]")
            }

            // 2. Registra no backend Supabase / Render
            scope.launch {
                try {
                    val markResp = networkClient.markConnection(
                        fromIdentity = myId,
                        toIdentity = updated.technicalIdentityHash
                    )
                    markResp.getOrNull()?.let { backendIsMutual ->
                        if (backendIsMutual || isNowMutual) {
                            keys.forEach {
                                savedMutualPeople.add(it)
                                savedMarkedPeople.add(it)
                                savedMarkingMePeople.add(it)
                            }
                            saveConnectionsToPrefs()
                            val cur = _realDiscoveredPeople.value.toMutableList()
                            val i = cur.indexOfFirst {
                                cleanId(it.id) in keys || cleanId(it.technicalIdentityHash) in keys
                            }
                            if (i != -1) {
                                cur[i] = cur[i].copy(isMarkedByMe = true, isMarkingMe = true, isMutualConnection = true, isPhotoVisible = true)
                                _realDiscoveredPeople.value = cur
                                updateUnifiedData()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w("PessoasAqui", "Erro ao sincronizar marcação no backend: ${e.message}")
                }
            }

            // Mensagem de feedback no chat privado
            if (isNowMutual) {
                proximitySimulator.receivePrivateMessage(
                    updated.technicalIdentityHash,
                    "Sistema",
                    "✨ Conexão Mútua Estabelecida com ${updated.alias}! Comunicação à distância, chamadas e mídia liberadas.",
                    UUID.randomUUID().toString()
                )
            } else if (newMarkedByMe) {
                proximitySimulator.receivePrivateMessage(
                    updated.technicalIdentityHash,
                    "Sistema",
                    "★ Você marcou ${updated.alias}. Aguardando a outra pessoa marcar você de volta para confirmar a conexão mútua.",
                    UUID.randomUUID().toString()
                )
            } else {
                proximitySimulator.receivePrivateMessage(
                    updated.technicalIdentityHash,
                    "Sistema",
                    "Marcação removida para ${updated.alias}.",
                    UUID.randomUUID().toString()
                )
            }
        }

        // Também atualiza o simulador se estiver em modo demo
        val simUpdated = proximitySimulator.toggleMarkPerson(personId)
        if (updatedPeer == null) {
            updatedPeer = simUpdated
        }

        updateUnifiedData()
        return updatedPeer
    }

    fun toggleMarkPerson(personId: String): NearbyPerson? {
        val cleanTargetId = cleanId(personId)
        val currentReal = _realDiscoveredPeople.value
        val peer = currentReal.firstOrNull {
            val cCid = cleanId(it.technicalIdentityHash)
            val cPid = cleanId(it.id)
            val cAlias = cleanId(it.alias)
            cleanTargetId == cCid || cleanTargetId == cPid ||
            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(personId, ignoreCase = true)) ||
            (cleanTargetId.isNotBlank() && !cleanTargetId.startsWith("PESSOA") && cleanTargetId == cAlias)
        }
        val currentMarked = peer?.let { isPeerMarkedByMe(it) } ?: savedMarkedPeople.contains(cleanTargetId)
        return setMarkPerson(personId, mark = !currentMarked)
    }

    fun postLocalOffer(
        profession: String,
        description: String,
        imageBase64: String? = null,
        externalLink: String? = null
    ) {
        val newOffer = OfferItem(
            id = "offer-${UUID.randomUUID()}",
            authorId = cryptoIdentityManager.getTechnicalIdentity(),
            authorAlias = _currentAlias.value,
            profession = profession.trim(),
            description = description.trim(),
            distanceMeters = 0.5,
            proximityLabel = "Seu anúncio • 10m",
            imageBase64 = imageBase64?.trim()?.takeIf { it.isNotBlank() },
            externalLink = externalLink?.trim()?.takeIf { it.isNotBlank() }
        )
        _realOffers.value = listOf(newOffer) + _realOffers.value
        saveUserOffersToPrefs()
        if (_isDemoMode.value) {
            proximitySimulator.updateLocalOffer(newOffer)
        }
        updateUnifiedData()

        scope.launch {
            try {
                networkClient.publishOffer(newOffer)
            } catch (e: Exception) {
                Log.e("PessoasAqui", "Erro ao publicar oferta no backend: ${e.message}")
            }
        }
    }

    fun updateLocalOffer(
        offerId: String,
        profession: String,
        description: String,
        imageBase64: String? = null,
        externalLink: String? = null
    ) {
        val current = _realOffers.value.toMutableList()
        val idx = current.indexOfFirst { it.id == offerId }
        if (idx != -1) {
            val existing = current[idx]
            val updated = existing.copy(
                profession = profession.trim(),
                description = description.trim(),
                imageBase64 = imageBase64?.trim()?.takeIf { it.isNotBlank() },
                externalLink = externalLink?.trim()?.takeIf { it.isNotBlank() }
            )
            current[idx] = updated
            _realOffers.value = current
            saveUserOffersToPrefs()
            if (_isDemoMode.value) {
                proximitySimulator.updateLocalOffer(updated)
            }
            updateUnifiedData()

            scope.launch {
                try {
                    networkClient.publishOffer(updated)
                } catch (e: Exception) {
                    Log.e("PessoasAqui", "Erro ao atualizar oferta no backend: ${e.message}")
                }
            }
        }
    }

    fun deleteLocalOffer(offerId: String) {
        _realOffers.value = _realOffers.value.filter { it.id != offerId }
        saveUserOffersToPrefs()
        if (_isDemoMode.value) {
            proximitySimulator.deleteLocalOffer(offerId)
        }
        updateUnifiedData()

        scope.launch {
            try {
                networkClient.deleteOffer(offerId)
            } catch (e: Exception) {
                Log.e("PessoasAqui", "Erro ao remover oferta no backend: ${e.message}")
            }
        }
    }

    fun getMyIdentity(): String = cryptoIdentityManager.getTechnicalIdentity()
    fun getMyAlias(): String = _currentAlias.value

    fun sendLocalMessage(text: String): ContentModerationManager.ModerationResult {
        val mod = moderationManager.evaluateContent(deviceSessionManager.getDeviceId(), text)
        if (mod.isAllowed) {
            proximitySimulator.sendLocalMessage(text, _currentAlias.value)
        }
        return mod
    }

    fun clearLocalMessages() {
        proximitySimulator.clearLocalMessages()
    }

    /**
     * Gera um link de convite criptografado de uso único para conexão mútua à distância
     */
    fun createOneTimeInviteLink(onResult: (String?) -> Unit) {
        scope.launch {
            try {
                val res = networkClient.createOneTimeInvite(getMyIdentity(), getMyAlias())
                if (res.isSuccess) {
                    onResult(res.getOrNull()?.inviteUrl)
                } else {
                    val invId = "inv_${UUID.randomUUID().toString().take(12)}"
                    val token = UUID.randomUUID().toString()
                    val myId = getMyIdentity()
                    val alias = getMyAlias()
                    val url = "https://pessoasaqui.onrender.com/invite?id=$invId&token=$token&sender=$myId&alias=${java.net.URLEncoder.encode(alias, "UTF-8")}"
                    onResult(url)
                }
            } catch (_: Exception) {
                val invId = "inv_${UUID.randomUUID().toString().take(12)}"
                val token = UUID.randomUUID().toString()
                val myId = getMyIdentity()
                val alias = getMyAlias()
                val url = "https://pessoasaqui.onrender.com/invite?id=$invId&token=$token&sender=$myId&alias=${java.net.URLEncoder.encode(alias, "UTF-8")}"
                onResult(url)
            }
        }
    }

    /**
     * Resgata o convite de uso único e estabelece conexão mútua à distância
     */
    fun redeemOneTimeInvite(
        inviteId: String,
        token: String,
        senderIdentity: String,
        senderAlias: String,
        onResult: (Boolean, String, NearbyPerson?) -> Unit
    ) {
        scope.launch {
            val myId = cleanId(getMyIdentity())
            val cleanSender = cleanId(senderIdentity)

            if (myId == cleanSender) {
                onResult(false, "Você não pode resgatar seu próprio convite.", null)
                return@launch
            }

            try {
                val res = networkClient.redeemOneTimeInvite(
                    inviteId = inviteId,
                    token = token,
                    receiverIdentity = myId,
                    receiverAlias = getMyAlias()
                )

                if (res.isSuccess) {
                    savedMutualPeople.add(cleanSender)
                    savedMarkedPeople.add(cleanSender)
                    savedMarkingMePeople.add(cleanSender)
                    saveConnectionsToPrefs()

                    val peer = NearbyPerson(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = senderAlias.ifBlank { "Conexão Convidada" },
                        estimatedDistanceMeters = 500.0,
                        proximityLabel = "Conexão Mútua • Ilimitada",
                        isMarkedByMe = true,
                        isMarkingMe = true,
                        isMutualConnection = true
                    )

                    val current = _realDiscoveredPeople.value.toMutableList()
                    val idx = current.indexOfFirst { cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender }
                    if (idx != -1) {
                        current[idx] = applyConnectionState(peer)
                    } else {
                        current.add(0, applyConnectionState(peer))
                    }
                    _realDiscoveredPeople.value = current
                    updateUnifiedData()

                    onResult(true, res.getOrNull()?.message ?: "Conexão Mútua estabelecida com sucesso!", peer)
                } else {
                    val errMsg = res.exceptionOrNull()?.message ?: "Falha ao validar convite."
                    onResult(false, errMsg, null)
                }
            } catch (e: Exception) {
                onResult(false, e.message ?: "Erro ao resgatar convite.", null)
            }
        }
    }

    fun sendPrivateMessage(recipientId: String, text: String): ContentModerationManager.ModerationResult {
        val cleanRecipient = cleanId(recipientId)
        val isSignaling = text.startsWith("[CALL_") || text.startsWith("[FAMILY_") || text.startsWith("[MARK_")
        val isAudio = text.startsWith("[AUDIO:")
        val isImage = text.startsWith("[IMAGE:")
        val isDoc = text.startsWith("[DOC:")
        val msgId = UUID.randomUUID().toString()

        if (!isSignaling && !isAudio && !isImage && !isDoc) {
            val mod = moderationManager.evaluateContent(deviceSessionManager.getDeviceId(), text)
            if (!mod.isAllowed) return mod
            proximitySimulator.sendPrivateMessage(cleanRecipient, text, _currentAlias.value)
        } else if (isAudio) {
            val dur = text.substringAfter("[AUDIO:").substringBefore("]").toIntOrNull() ?: 3
            proximitySimulator.sendAudioMessage(cleanRecipient, dur, _currentAlias.value, msgId)
        } else if (isImage) {
            val base64 = text.removePrefix("[IMAGE:").removeSuffix("]")
            proximitySimulator.sendImageMessage(cleanRecipient, base64, _currentAlias.value, msgId)
        } else if (isDoc) {
            val parts = text.removePrefix("[DOC:").removeSuffix("]").split(":", limit = 3)
            val fileName = parts.getOrNull(0) ?: "documento.pdf"
            val size = parts.getOrNull(1)?.toLongOrNull() ?: 0L
            val base64 = parts.getOrNull(2) ?: ""
            proximitySimulator.sendDocumentMessage(cleanRecipient, fileName, base64, size, _currentAlias.value, msgId)
        }

        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val iv = UUID.randomUUID().toString().take(12)

        // 1. Tenta envio instantâneo prioritário via WebSocket
        val sentViaWs = networkClient.sendE2eeEnvelope(
            recipientHash = cleanRecipient,
            ciphertext = text,
            ivNonce = iv,
            messageId = msgId
        )

        // 2. Se o WebSocket não estiver conectado ou falhar, envia imediatamente por REST HTTP (Fallback)
        if (!sentViaWs) {
            scope.launch {
                try {
                    networkClient.sendHttpMessage(
                        senderHash = myId,
                        recipientHash = cleanRecipient,
                        ciphertext = text,
                        senderAlias = _currentAlias.value,
                        messageId = msgId
                    )
                } catch (e: Exception) {
                    Log.w("PessoasAqui", "Falha no envio HTTP da mensagem: ${e.message}")
                }
            }
        }
        return ContentModerationManager.ModerationResult(isAllowed = true)
    }

    fun sendImageMessage(recipientId: String, base64: String) {
        val cleanRecipient = recipientId.removePrefix("peer-")
        sendPrivateMessage(cleanRecipient, "[IMAGE:$base64]")
    }

    fun sendDocumentMessage(recipientId: String, fileName: String, base64: String, sizeBytes: Long) {
        val cleanRecipient = recipientId.removePrefix("peer-")
        sendPrivateMessage(cleanRecipient, "[DOC:$fileName:$sizeBytes:$base64]")
    }

    fun startCall(person: NearbyPerson, isVideo: Boolean) {
        val cleanHash = person.technicalIdentityHash.removePrefix("peer-")
        _activeCallState.value = ActiveCallState(
            isIncoming = false,
            peerHash = cleanHash,
            peerAlias = person.alias,
            isVideo = isVideo,
            isConnected = false
        )
        sendPrivateMessage(cleanHash, "[CALL_INIT:$isVideo:${_currentAlias.value}]")
    }

    fun answerCall() {
        val current = _activeCallState.value ?: return
        _activeCallState.value = current.copy(isConnected = true)
        sendPrivateMessage(current.peerHash, "[CALL_ACCEPT]")
    }

    fun endCall() {
        val current = _activeCallState.value ?: return
        val peer = current.peerHash
        _activeCallState.value = null
        sendPrivateMessage(peer, "[CALL_END]")
    }

    fun toggleCallMute() {
        _activeCallState.value = _activeCallState.value?.let { it.copy(isMuted = !it.isMuted) }
    }

    fun toggleCallCamera() {
        _activeCallState.value = _activeCallState.value?.let { it.copy(isCameraOn = !it.isCameraOn) }
    }

    fun sendAudioMessage(recipientId: String, durationSeconds: Int) {
        val cleanRecipient = recipientId.removePrefix("peer-")
        sendPrivateMessage(cleanRecipient, "[AUDIO:$durationSeconds]")
    }

    fun requestFamilyRole(person: NearbyPerson, role: FamilyRole) {
        val cleanRecipient = person.technicalIdentityHash.removePrefix("peer-")
        sendPrivateMessage(cleanRecipient, "[FAMILY_REQ:${role.name}:${_currentAlias.value}]")
        proximitySimulator.receivePrivateMessage(
            cleanRecipient,
            "Sistema",
            "Convite de vínculo familiar (${role.label}) enviado para ${person.alias}. Aguardando confirmação mútua..."
        )
    }

    fun acceptFamilyRole(person: NearbyPerson, role: FamilyRole) {
        val cleanRecipient = person.technicalIdentityHash.removePrefix("peer-")
        setFamilyRole(cleanRecipient, role)
        sendPrivateMessage(cleanRecipient, "[FAMILY_ACCEPT:${role.name}:${_currentAlias.value}]")
        _pendingFamilyRequest.value = null
    }

    fun rejectFamilyRequest() {
        _pendingFamilyRequest.value = null
    }

    fun setFamilyRole(personId: String, role: br.com.pessoasaqui.domain.model.FamilyRole) {
        val cleanId = personId.removePrefix("peer-")
        val currentReal = _realDiscoveredPeople.value.toMutableList()
        val idx = currentReal.indexOfFirst { it.id == personId || it.id == cleanId || it.technicalIdentityHash == cleanId }
        if (idx != -1) {
            val p = currentReal[idx]
            currentReal[idx] = p.copy(
                isFamily = true,
                familyRole = role,
                isPhotoVisible = true,
                isMutualConnection = true,
                isMarkedByMe = true,
                isMarkingMe = true
            )
            _realDiscoveredPeople.value = currentReal
            updateUnifiedData()
        }
        proximitySimulator.setFamilyRole(personId, role)
        updateUnifiedData()
    }

    fun generateFamilyRecovery(targetIdentityHash: String, familyName: String): RecoveryAuthorization {
        return proximitySimulator.generateFamilyRecoveryAuthorization(targetIdentityHash, familyName)
    }

    suspend fun claimRecovery(recoveryKey: String, pin: String): Result<String> {
        // Tenta validação e reivindicação no servidor de produção (Render / Supabase)
        val remoteResult = networkClient.claimFamilyRecovery(
            recoveryKey = recoveryKey.trim().uppercase(),
            pin = pin.trim(),
            newDeviceFingerprint = deviceSessionManager.getDeviceId()
        )
        if (remoteResult.isSuccess) {
            val recoveredId = remoteResult.getOrThrow()
            deviceSessionManager.resetSession()
            return Result.success(recoveredId)
        }

        // Caso o servidor retorne erro ou chave seja do simulador local
        val localAuth = proximitySimulator.findActiveRecoveryAuthorization(recoveryKey)
        if (localAuth != null) {
            val pinResult = pinSecurityManager.verifyPinForRecovery("session-device-new", pin)
            if (pinResult.isSuccess) {
                deviceSessionManager.resetSession()
                return Result.success(localAuth.targetIdentityHash)
            } else {
                return Result.failure(pinResult.exceptionOrNull() ?: Exception("PIN incorreto"))
            }
        }

        return Result.failure(remoteResult.exceptionOrNull() ?: Exception("Chave inválida ou expirada."))
    }

    suspend fun issueFamilyRecoveryRemote(targetIdentityHash: String): Result<String> {
        return networkClient.issueFamilyRecovery(
            targetIdentity = targetIdentityHash,
            familyIdentity = cryptoIdentityManager.getTechnicalIdentity()
        )
    }

    fun transferIdentityToThisDevice(recoveredIdentityHash: String) {
        deviceSessionManager.resetSession()
    }
}
