package br.com.pessoasaqui.data.repository

import android.content.Context
import android.util.Log
import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.DeviceSessionManager
import br.com.pessoasaqui.core.crypto.E2eeCryptoEngine
import br.com.pessoasaqui.core.crypto.PinSecurityManager
import br.com.pessoasaqui.core.media.CallMediaEngine
import br.com.pessoasaqui.core.media.CallSignalingProtocol
import br.com.pessoasaqui.core.media.CallSessionStateMachine
import br.com.pessoasaqui.core.media.VoiceNoteManager
import br.com.pessoasaqui.core.moderation.ContentModerationManager
import br.com.pessoasaqui.core.proximity.BleManager
import br.com.pessoasaqui.core.proximity.DistanceEstimator
import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient
import br.com.pessoasaqui.data.remote.RemotePeer
import br.com.pessoasaqui.data.remote.SupportTicketItem
import br.com.pessoasaqui.core.reminder.RecurringReminderManager
import br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager
import br.com.pessoasaqui.core.subscription.SubscriptionPlanManager
import br.com.pessoasaqui.core.subscription.SubscriptionTier
import br.com.pessoasaqui.core.subscription.PlayBillingManager
import br.com.pessoasaqui.core.backup.P2PVaultManager
import br.com.pessoasaqui.core.backup.EncryptedVaultPackage
import br.com.pessoasaqui.core.backup.VaultContact
import br.com.pessoasaqui.core.crypto.LocalDiskCryptoManager
import br.com.pessoasaqui.domain.model.RecurringReminder
import android.util.Base64
import br.com.pessoasaqui.domain.model.ActiveCallState
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.FamilyRole
import br.com.pessoasaqui.domain.model.AppVersionInfo
import br.com.pessoasaqui.domain.model.ContactTag
import br.com.pessoasaqui.domain.model.MessageType
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.OfferItem
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.domain.model.UserIntent
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val pinSecurityManager: PinSecurityManager = PinSecurityManager(context),
    val deviceSessionManager: DeviceSessionManager = DeviceSessionManager(),
    val proximitySimulator: ProximitySimulator = ProximitySimulator(),
    val moderationManager: ContentModerationManager = ContentModerationManager(),
    val bleManager: BleManager? = null,
    val networkClient: PessoasAquiNetworkClient = PessoasAquiNetworkClient(),
    val distanceEstimator: DistanceEstimator = DistanceEstimator(),
    injectedChatPersistence: br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager? = null,
    injectedBleTransportCoordinator: br.com.pessoasaqui.core.proximity.BleDirectTransportCoordinator? = null
) {
    companion object {
        const val TEMPORARY_SESSION_GRACE_PERIOD_MS = 30000L // 30s de tolerância contra jitter/oscilação BLE
        const val TEMPORARY_SESSION_EXPIRATION_MS = 45000L   // 45s de ausência para encerramento e purga
    }


    val diskCrypto = LocalDiskCryptoManager(context)
    private val prefs = context?.getSharedPreferences("pessoasaqui_prefs", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO)

    val voiceNoteManager = VoiceNoteManager()

    val callRingtoneWakeManager: br.com.pessoasaqui.core.media.CallRingtoneAndWakeManager? = context?.let { ctx ->
        br.com.pessoasaqui.core.media.CallRingtoneAndWakeManager(ctx)
    }

    val callMediaEngine: CallMediaEngine? = context?.let { ctx ->
        CallMediaEngine(
            context = ctx,
            onSendAudioFrame = { b64Frame ->
                val peer = _activeCallState.value?.peerHash
                if (!peer.isNullOrBlank()) {
                    sendPrivateMessage(peer, "[CALL_AUDIO_FRAME:$b64Frame]")
                }
            },
            onSendVideoFrame = { b64Frame ->
                val peer = _activeCallState.value?.peerHash
                if (!peer.isNullOrBlank()) {
                    sendPrivateMessage(peer, "[CALL_VIDEO_FRAME:$b64Frame]")
                }
            }
        )
    }

    val reminderManager: RecurringReminderManager? = context?.let { ctx ->
        RecurringReminderManager(ctx)
    }
    val recurringReminders: StateFlow<List<RecurringReminder>> = reminderManager?.reminders 
        ?: MutableStateFlow<List<RecurringReminder>>(emptyList()).asStateFlow()

    val subscriptionManager: SubscriptionPlanManager? = context?.let { ctx ->
        SubscriptionPlanManager(ctx)
    }

    val playBillingManager: PlayBillingManager? = if (context != null && subscriptionManager != null) {
        PlayBillingManager(context, subscriptionManager)
    } else null

    val chatPersistence: br.com.pessoasaqui.core.chat.ChatMessagePersistenceManager? = injectedChatPersistence ?: context?.let { ctx ->
        ChatMessagePersistenceManager(ctx)
    }

    val bleTransportCoordinator: br.com.pessoasaqui.core.proximity.BleDirectTransportCoordinator =
        injectedBleTransportCoordinator ?: br.com.pessoasaqui.core.proximity.BleDirectTransportCoordinator(
            context = context,
            cryptoIdentityManager = cryptoIdentityManager
        )

    val p2pVaultManager: P2PVaultManager? = context?.let { ctx ->
        P2PVaultManager(ctx)
    }

    private val _receivedVaultResponses = MutableStateFlow<List<EncryptedVaultPackage>>(emptyList())
    val receivedVaultResponses: StateFlow<List<EncryptedVaultPackage>> = _receivedVaultResponses.asStateFlow()

    private val _vaultAckCount = MutableStateFlow(0)
    val vaultAckCount: StateFlow<Int> = _vaultAckCount.asStateFlow()

    // Estado do usuário atual neste aparelho persistido com SharedPreferences
    private val _isAgeVerified = MutableStateFlow(prefs?.getBoolean("is_age_verified", false) ?: false)
    val isAgeVerified: StateFlow<Boolean> = _isAgeVerified.asStateFlow()

    private val _hasCompletedProfile = MutableStateFlow(prefs?.getBoolean("has_completed_profile", false) ?: false)
    val hasCompletedProfile: StateFlow<Boolean> = _hasCompletedProfile.asStateFlow()

    private val _hasGrantedPermissions = MutableStateFlow(prefs?.getBoolean("has_granted_permissions", false) ?: false)
    val hasGrantedPermissions: StateFlow<Boolean> = _hasGrantedPermissions.asStateFlow()

    // Modo Demonstração permanentemente desativado (100% Produção Real)
    private val _isDemoMode = MutableStateFlow(false)
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
    // Presença BLE física é independente da telemetria de presença WebSocket/Render.
    // Um PEER_OFFLINE de rede nunca pode apagar alguém detectado no rádio.
    private val lastNativeBleSeenMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

    internal fun recordNativeBlePresence(peerId: String, seenAtMs: Long = System.currentTimeMillis()) {
        val id = cleanId(peerId)
        if (id.isNotBlank()) lastNativeBleSeenMs[id] = seenAtMs
    }
    private var lastStablePeerOrder: List<String> = emptyList()

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

    // Revogações persistem localmente até que o usuário aprove novamente o vínculo.
    // Isso impede que eventos atrasados, snapshots de presença e histórico salvo o reativem.
    private val revokedMutualPeople = java.util.Collections.synchronizedSet(
        (prefs?.getStringSet("saved_revoked_mutual_people", emptySet()) ?: emptySet()).toMutableSet()
    )
    // Se estiver offline, a retirada é aplicada imediatamente neste aparelho e enviada
    // ao backend na próxima oportunidade. Não declara revogação remota antes do sucesso.
    private val pendingUnmarkPeople = java.util.Collections.synchronizedSet(
        (prefs?.getStringSet("pending_unmark_people", emptySet()) ?: emptySet()).toMutableSet()
    )

    fun cleanId(id: String): String = id.removePrefix("peer-").trim().uppercase()

    fun saveConnectionsToPrefs() {
        prefs?.edit()
            ?.putStringSet("saved_marked_people", HashSet(savedMarkedPeople))
            ?.putStringSet("saved_marking_me_people", HashSet(savedMarkingMePeople))
            ?.putStringSet("saved_mutual_people", HashSet(savedMutualPeople))
            ?.putStringSet("saved_revoked_mutual_people", HashSet(revokedMutualPeople))
            ?.putStringSet("pending_unmark_people", HashSet(pendingUnmarkPeople))
            ?.apply()
        saveSavedContactsToPrefs()
    }


    fun isPeerPermanent(peerId: String): Boolean {
        val cid = cleanId(peerId)
        if (cid.isBlank()) return false
        val p = _realDiscoveredPeople.value.find { cleanId(it.id) == cid || cleanId(it.technicalIdentityHash) == cid }
        // Um flag antigo em NearbyPerson não comprova vínculo. Família tem política própria.
        return p?.isFamily == true || (cid !in revokedMutualPeople && cid in savedMutualPeople)
    }

    /** Somente confirmação vinda do backend (ou montagem explícita de cenário em teste). */
    internal fun confirmMutualConnectionFromServer(peerId: String) {
        val cid = cleanId(peerId)
        if (cid.isBlank() || cid in revokedMutualPeople) return
        savedMarkedPeople.add(cid)
        savedMarkingMePeople.add(cid)
        savedMutualPeople.add(cid)
        saveConnectionsToPrefs()
        _realDiscoveredPeople.value = _realDiscoveredPeople.value.map { peer ->
            if (cleanId(peer.technicalIdentityHash.ifBlank { peer.id }) == cid) {
                peer.copy(isMarkedByMe = true, isMarkingMe = true, isMutualConnection = true, isPhotoVisible = true)
            } else peer
        }
        updateUnifiedData()
        promoteTemporarySessionToMutual(cid) // Opção A, somente depois da confirmação
    }

    /** Derruba o privilégio permanente imediatamente, inclusive com UI e cache antigos. */
    internal fun revokeMutualLocally(peerId: String, removeMyMark: Boolean = false, queueRemoteUnmark: Boolean = false) {
        val cid = cleanId(peerId)
        if (cid.isBlank()) return
        revokedMutualPeople.add(cid)
        savedMutualPeople.remove(cid)
        savedMarkingMePeople.remove(cid)
        if (removeMyMark) savedMarkedPeople.remove(cid)
        if (queueRemoteUnmark) pendingUnmarkPeople.add(cid)
        if (_activeCallState.value?.peerHash?.let(::cleanId) == cid) {
            finishActiveCall(notifyPeer = false)
        }
        _realDiscoveredPeople.value = _realDiscoveredPeople.value.map { peer ->
            if (cleanId(peer.technicalIdentityHash.ifBlank { peer.id }) == cid) {
                peer.copy(
                    isMarkedByMe = if (removeMyMark) false else cid in savedMarkedPeople,
                    isMarkingMe = false,
                    isMutualConnection = peer.isFamily,
                    isPhotoVisible = peer.isFamily
                )
            } else peer
        }
        saveConnectionsToPrefs()
        updateUnifiedData()
    }

    /** Reconciliação de mão única: servidor informa o estado, cliente NÃO o recria no servidor. */
    internal fun reconcileConnectionStatus(
        markedByMe: Collection<String>, markingMe: Collection<String>, mutual: Collection<String>
    ) {
        val blocked = revokedMutualPeople.toSet()
        val own = markedByMe.map(::cleanId).filter { it.isNotBlank() && it !in blocked }.toSet()
        val remote = markingMe.map(::cleanId).filter { it.isNotBlank() && it !in blocked }.toSet()
        val confirmed = mutual.map(::cleanId).filter { it.isNotBlank() && it !in blocked && it in own && it in remote }.toSet()
        val previouslyConfirmed = savedMutualPeople.toSet()
        val newlyConfirmed = confirmed - previouslyConfirmed
        // Um snapshot incompleto ou antigo não é prova de revogação explícita.
        // Não criar veto persistente a partir de ausência em /status: a conexão
        // poderá ser confirmada novamente quando o backend voltar a sincronizar.
        // Tombstones são criadas somente por unmark local ou evento de revogação.
        savedMarkedPeople.clear(); savedMarkedPeople.addAll(own)
        savedMarkingMePeople.clear(); savedMarkingMePeople.addAll(remote)
        savedMutualPeople.clear(); savedMutualPeople.addAll(confirmed)
        _realDiscoveredPeople.value = _realDiscoveredPeople.value.map { peer ->
            val cid = cleanId(peer.technicalIdentityHash.ifBlank { peer.id })
            val active = peer.isFamily || cid in confirmed
            peer.copy(
                isMarkedByMe = cid in own || cid in confirmed,
                isMarkingMe = cid in remote || cid in confirmed,
                isMutualConnection = active,
                isPhotoVisible = active
            )
        }
        saveConnectionsToPrefs()
        updateUnifiedData()
        newlyConfirmed.forEach { promoteTemporarySessionToMutual(it) }
    }

    /**
     * Política Oficial da ETAPA 2 (Opção A - Preservação Automática do Histórico Temporário):
     * Promove o histórico em memória de uma sessão temporária para armazenamento local criptografado (AES-256-GCM)
     * única e exclusivamente mediante conexão mútua confirmada, preservando mensagens prévias e evitando duplicações.
     */
    fun promoteTemporarySessionToMutual(peerId: String) {
        val clean = cleanId(peerId)
        if (clean.isBlank()) return
        // 1. Confirmar que a conexão mútua ou familiar foi validamente estabelecida
        if (!isPeerPermanent(clean)) return

        // 2. Recuperar as mensagens temporárias existentes na memória
        val memoryMsgs = _unifiedPrivateChats.value[clean]
            ?: _unifiedPrivateChats.value["peer-$clean"]
            ?: _unifiedPrivateChats.value[clean.lowercase()]
            ?: proximitySimulator.privateChats.value[clean]
            ?: emptyList()

        // 3. Evitar duplicação ou sobrescrita: mescla histórico prévio do disco com as mensagens de proximidade
        val existingDiskMsgs = chatPersistence?.loadAllPrivateChats()?.let { all ->
            all[clean.lowercase()] ?: all[clean] ?: emptyList()
        } ?: emptyList()

        val merged = (existingDiskMsgs + memoryMsgs)
            .distinctBy { it.id }
            .sortedBy { it.timestampMs }

        // 4. Preserva automaticamente o histórico consolidado no disco criptografado
        if (merged.isNotEmpty()) {
            chatPersistence?.savePrivateChat(clean, merged)
            proximitySimulator.loadPersistedMessages()
        }
    }

    fun cleanupExpiredTemporarySessions(
        now: Long = System.currentTimeMillis(),
        expirationMs: Long = TEMPORARY_SESSION_EXPIRATION_MS
    ): List<String> {
        val current = _realDiscoveredPeople.value.toMutableList()
        val expiredPeers = current.filter { peer ->
            val isPermanent = isPeerPermanent(peer.technicalIdentityHash.ifBlank { peer.id })
            val elapsed = now - peer.lastSeenEpochMs
            !isPermanent && elapsed >= expirationMs
        }

        if (expiredPeers.isEmpty()) return emptyList()

        val expiredIds = mutableListOf<String>()
        expiredPeers.forEach { p ->
            val cid = cleanId(p.technicalIdentityHash.ifBlank { p.id })
            expiredIds.add(cid)
            lastNativeBleSeenMs.remove(cid)
            proximitySimulator.clearPrivateChat(cid)
            // Preservação estrita: sessões temporárias existem apenas na memória RAM.
            // Históricos legados em disco NUNCA são apagados por timeout de afastamento BLE.
            // Exclusão de arquivos em disco é prerrogativa exclusiva de ação voluntária do usuário.
        }

        val remaining = current.filter { it !in expiredPeers }
        _realDiscoveredPeople.value = remaining
        updateUnifiedData()

        // Purga sessões lógicas expiradas no coordenador BLE com memory wiping seguro
        bleTransportCoordinator.cleanupExpiredSessions(now)

        return expiredIds
    }

    fun isPeerMarkedByMe(person: NearbyPerson): Boolean {
        val cid = cleanId(person.technicalIdentityHash)
        val pid = cleanId(person.id)
        return (cid.isNotBlank() && cid in savedMarkedPeople) ||
                (pid.isNotBlank() && pid in savedMarkedPeople)
    }

    fun isPeerMarkingMe(person: NearbyPerson): Boolean {
        val cid = cleanId(person.technicalIdentityHash)
        val pid = cleanId(person.id)
        return (cid.isNotBlank() && cid in savedMarkingMePeople) ||
                (pid.isNotBlank() && pid in savedMarkingMePeople)
    }

    fun isPeerMutual(person: NearbyPerson): Boolean {
        if (person.isFamily) return true
        val cid = cleanId(person.technicalIdentityHash.ifBlank { person.id })
        return cid.isNotBlank() && cid !in revokedMutualPeople && cid in savedMutualPeople
    }

    fun isPeerMutual(peerId: String): Boolean {
        val cid = cleanId(peerId)
        if (cid.isBlank()) return false
        if (cid in revokedMutualPeople) return false
        if (cid in savedMutualPeople) return true
        val p = _realDiscoveredPeople.value.find { cleanId(it.id) == cid || cleanId(it.technicalIdentityHash) == cid }
        return p?.isFamily == true
    }

    fun applyConnectionState(person: NearbyPerson): NearbyPerson {
        val cid = cleanId(if (person.technicalIdentityHash.isNotBlank()) person.technicalIdentityHash else person.id)
        val marked = isPeerMarkedByMe(person)
        val marking = isPeerMarkingMe(person)
        val mutual = isPeerMutual(person) // marcações recíprocas sem confirmação NÃO concedem Modo B

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

    private fun parseContactsJson(jsonStr: String, outList: MutableList<NearbyPerson>) {
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = cleanId(obj.getString("id"))
                if (id.isBlank() || id.startsWith("sim-", ignoreCase = true) || id.startsWith("sim_", ignoreCase = true)) continue
                val alias = obj.optString("alias", "Contato")
                if (alias.equals("Maria", ignoreCase = true) || alias.equals("Marcos Mecânico", ignoreCase = true) || alias.equals("Mariana", ignoreCase = true) || alias.equals("Carlos", ignoreCase = true)) continue
                val isFamily = obj.optBoolean("isFamily", false)
                val isMutual = (cleanId(id) in savedMutualPeople) || isFamily
                val roleStr = obj.optString("familyRole", "")
                val familyRole = if (roleStr.isNotBlank()) {
                    try { FamilyRole.valueOf(roleStr) } catch (_: Exception) { null }
                } else null
                outList.add(
                    NearbyPerson(
                        id = id,
                        technicalIdentityHash = id,
                        alias = alias,
                        estimatedDistanceMeters = 500.0,
                        proximityLabel = if (isFamily) "Família • Ilimitado" else if (isMutual) "Conexão Mútua • Ilimitada" else "Histórico de conversa salvo",
                        isMarkedByMe = isMutual,
                        isMarkingMe = isMutual,
                        isMutualConnection = isMutual,
                        isFamily = isFamily,
                        familyRole = familyRole,
                        isPhotoVisible = true,
                        lastSeenEpochMs = System.currentTimeMillis()
                    )
                )
            }
        } catch (_: Exception) {}
    }

    internal fun loadSavedContactsFromPrefs(): List<NearbyPerson> {
        val list = mutableListOf<NearbyPerson>()

        // 1. Carrega do arquivo persistente dedicado no disco permanente com decifração AES-256
        try {
            val file = context?.filesDir?.resolve("pessoasaqui_contacts/mutual_contacts.json")
            if (file != null && file.exists()) {
                val raw = file.readText(Charsets.UTF_8)
                val jsonStr = diskCrypto.decrypt(raw)
                parseContactsJson(jsonStr, list)
            }
        } catch (e: Exception) {
            Log.e("PessoasAqui", "Erro ao carregar contatos de arquivo dedicado", e)
        }

        // 2. Carrega de SharedPreferences se houver com decifração AES-256
        val rawPref = prefs?.getString("saved_contacts_v2", null)
        if (!rawPref.isNullOrBlank()) {
            val jsonStr = diskCrypto.decrypt(rawPref)
            parseContactsJson(jsonStr, list)
        }

        // 3. Mescla com contatos que possuem conversas privadas salvas em disco
        chatPersistence?.let { cp ->
            try {
                val chats = cp.loadAllPrivateChats()
                chats.keys.forEach { peerId ->
                    val cleanPeerId = cleanId(peerId)
                    if (cleanPeerId.isNotBlank() && list.none { cleanId(it.technicalIdentityHash) == cleanPeerId || cleanId(it.id) == cleanPeerId }) {
                        val lastMsg = chats[peerId]?.lastOrNull()
                        val isMut = cleanPeerId in savedMutualPeople
                        val alias = if (lastMsg?.isFromMe == false) lastMsg.senderAlias else if (isMut) "Contato Mútuo" else "Contato Salvo"
                        list.add(
                            NearbyPerson(
                                id = cleanPeerId,
                                technicalIdentityHash = cleanPeerId,
                                alias = alias,
                                estimatedDistanceMeters = 500.0,
                                proximityLabel = if (isMut) "Conexão Mútua • Ilimitada" else "Histórico de conversa salvo",
                                isMarkedByMe = isMut,
                                isMarkingMe = isMut,
                                isMutualConnection = isMut,
                                isPhotoVisible = isMut,
                                lastSeenEpochMs = System.currentTimeMillis()
                            )
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        // Remove duplicatas e contatos simulados e assegura sincronismo com savedMutualPeople
        val distinct = list
            .filter { !it.id.startsWith("sim-", ignoreCase = true) && !it.technicalIdentityHash.startsWith("sim-", ignoreCase = true) }
            .distinctBy { cleanId(it.technicalIdentityHash.ifBlank { it.id }) }
        return distinct
    }

    fun saveSavedContactsToPrefs() {
        try {
            val currentList = _realDiscoveredPeople.value.filter {
                (isPeerPermanent(it.technicalIdentityHash.ifBlank { it.id }) || it.isFamily) && !it.id.startsWith("sim-", ignoreCase = true)
            }
            val existingSaved = loadSavedContactsFromPrefs().filter {
                !it.id.startsWith("sim-", ignoreCase = true)
            }

            // Mescla apenas contatos que ainda permanecem ativos em savedMutualPeople ou família
            val mergedMap = LinkedHashMap<String, NearbyPerson>()
            existingSaved.forEach { p ->
                val k = cleanId(p.technicalIdentityHash.ifBlank { p.id })
                if (k.isNotBlank() && (p.isFamily || (k in savedMutualPeople && k !in revokedMutualPeople))) {
                    mergedMap[k] = p
                }
            }
            currentList.forEach { p ->
                val k = cleanId(p.technicalIdentityHash.ifBlank { p.id })
                if (k.isNotBlank()) {
                    if (p.isFamily || (k in savedMutualPeople && k !in revokedMutualPeople)) {
                        mergedMap[k] = p
                    } else {
                        mergedMap.remove(k)
                    }
                }
            }

            val array = JSONArray()
            mergedMap.values.forEach { person ->
                val cid = cleanId(person.technicalIdentityHash.ifBlank { person.id })
                if (cid.isBlank() || cid.startsWith("sim-", ignoreCase = true)) return@forEach
                val obj = JSONObject()
                obj.put("id", cid)
                obj.put("alias", person.alias)
                obj.put("isMutual", person.isMutualConnection)
                obj.put("isFamily", person.isFamily)
                obj.put("familyRole", person.familyRole?.name ?: "")
                array.put(obj)

            }

            val jsonOutput = array.toString()
            val encryptedOutput = diskCrypto.encrypt(jsonOutput)
            prefs?.edit()?.putString("saved_contacts_v2", encryptedOutput)?.apply()

            // Salva também no arquivo dedicado no disco físico permanente com criptografia AES-256
            context?.let { ctx ->
                try {
                    val dir = ctx.filesDir.resolve("pessoasaqui_contacts")
                    if (!dir.exists()) dir.mkdirs()
                    val file = dir.resolve("mutual_contacts.json")
                    file.writeText(encryptedOutput, Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.e("PessoasAqui", "Erro ao gravar contatos no arquivo físico de disco", e)
                }
            }
        } catch (e: Exception) {
            Log.e("PessoasAqui", "Erro ao salvar contatos mútuos", e)
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

    private val _unifiedLocalChatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val localChatMessages: StateFlow<List<ChatMessage>> = _unifiedLocalChatMessages.asStateFlow()

    private val _unifiedPrivateChats = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val privateChats: StateFlow<Map<String, List<ChatMessage>>> = _unifiedPrivateChats.asStateFlow()

    val isBluetoothEnabled: StateFlow<Boolean> = bleManager?.isBluetoothEnabledFlow ?: MutableStateFlow(true)

    private val _activeCallState = MutableStateFlow<ActiveCallState?>(null)
    val activeCallState: StateFlow<ActiveCallState?> = _activeCallState.asStateFlow()

    private val callStateLock = Any()
    @Volatile private var activeCallGeneration: String? = null
    private var callConnectedAtMs: Long = 0L
    private var outgoingCallTimeoutJob: Job? = null
    private val callLifecycle = CallSessionStateMachine()
    // Impede nova chamada entre a transição terminal e a liberação da mídia.
    @Volatile private var callCleanupInProgress = false

    private fun markActiveCallConnected() {
        synchronized(callStateLock) {
            if (callConnectedAtMs == 0L) callConnectedAtMs = System.currentTimeMillis()
            outgoingCallTimeoutJob?.cancel()
            outgoingCallTimeoutJob = null
        }
    }

    private fun finishActiveCall(
        timedOut: Boolean = false,
        remoteEnded: Boolean = false,
        notifyPeer: Boolean = !remoteEnded,
        remoteDeclined: Boolean = false,
        remoteBusy: Boolean = false
    ) {
        val completed = synchronized(callStateLock) {
            val current = _activeCallState.value ?: return
            val durationSeconds = if (current.isConnected && callConnectedAtMs > 0L) {
                ((System.currentTimeMillis() - callConnectedAtMs) / 1000L).coerceAtLeast(0L)
            } else 0L
            val callId = activeCallGeneration ?: CallSignalingProtocol.newCallId()
            val reason = callLifecycle.finish(callId, cleanId(current.peerHash),
                byTimeout = timedOut, byRemotePeer = remoteEnded,
                remoteDeclined = remoteDeclined, remoteBusy = remoteBusy) ?: return
            callCleanupInProgress = true
            _activeCallState.value = null
            activeCallGeneration = null
            callConnectedAtMs = 0L
            outgoingCallTimeoutJob?.cancel()
            outgoingCallTimeoutJob = null
            Pair(Triple(current, durationSeconds, callId), reason)
        }
        val (callDetails, reason) = completed
        val (current, durationSeconds, callId) = callDetails
        try {
            callRingtoneWakeManager?.stopAllCallAlerts()
            callMediaEngine?.stopCallMedia()

            val type = if (current.isVideo) "📹 Videochamada" else "📞 Chamada de voz"
            val outcome = when (reason) {
                CallSessionStateMachine.FinishReason.COMPLETED -> if (current.isIncoming) "recebida" else "realizada"
                CallSessionStateMachine.FinishReason.CANCELLED -> "cancelada"
                CallSessionStateMachine.FinishReason.DECLINED -> "recusada"
                CallSessionStateMachine.FinishReason.MISSED -> "perdida"
                CallSessionStateMachine.FinishReason.NO_ANSWER -> "não atendida"
                CallSessionStateMachine.FinishReason.BUSY -> "ocupada"
            }
            val durationText = if (current.isConnected) " — ${durationSeconds / 60}min ${durationSeconds % 60}s" else ""
            proximitySimulator.recordCallHistory(
                peerId = current.peerHash,
                description = "$type $outcome$durationText",
                outgoing = !current.isIncoming,
                myAlias = _currentAlias.value,
                entryId = "call-$callId"
            )
            updateUnifiedData()

            if (notifyPeer) {
                val terminal = CallSignalingProtocol.EndReason.valueOf(reason.name)
                val endSignal = CallSignalingProtocol.encode(CallSignalingProtocol.Command.END, callId, terminal)
                sendPrivateMessage(current.peerHash, endSignal)
                scope.launch {
                    delay(200L)
                    sendPrivateMessage(current.peerHash, endSignal)
                }
            }
        } finally {
            callCleanupInProgress = false
        }
    }

    private val _pendingFamilyRequest = MutableStateFlow<Pair<NearbyPerson, FamilyRole>?>(null)
    val pendingFamilyRequest: StateFlow<Pair<NearbyPerson, FamilyRole>?> = _pendingFamilyRequest.asStateFlow()

    // Sistema Integrado de Suporte e Moderação
    private val _mySupportTickets = MutableStateFlow<List<SupportTicketItem>>(emptyList())
    val mySupportTickets: StateFlow<List<SupportTicketItem>> = _mySupportTickets.asStateFlow()

    private val _moderationWarningAlert = MutableStateFlow<String?>(null)
    val moderationWarningAlert: StateFlow<String?> = _moderationWarningAlert.asStateFlow()
    fun dismissModerationWarning() { _moderationWarningAlert.value = null }

    private val _accountBannedAlert = MutableStateFlow<String?>(null)
    val accountBannedAlert: StateFlow<String?> = _accountBannedAlert.asStateFlow()
    private var lastSupportSyncMs = 0L

    init {
        // Revogações não podem ser apagadas ao reiniciar, evitando ressuscitar vínculos antigos.
        chatPersistence?.let { cp ->
            proximitySimulator.setPersistenceManager(cp)
        }
        proximitySimulator.shouldPersistPrivateChat = { peerId ->
            isPeerPermanent(peerId)
        }

        val restoredContacts = loadSavedContactsFromPrefs()
        if (restoredContacts.isNotEmpty()) {
            _realDiscoveredPeople.value = restoredContacts.map { applyConnectionState(it) }
        }
        updateUnifiedData()

        scope.launch {
            proximitySimulator.localChatMessages.collect {
                updateUnifiedData()
            }
        }
        scope.launch {
            proximitySimulator.privateChats.collect {
                updateUnifiedData()
            }
        }

        networkClient.onOfferPublished = { offer ->
            scope.launch { handleRemoteOfferPublished(offer) }
        }
        networkClient.onOfferDeleted = { offerId ->
            scope.launch { handleRemoteOfferDeleted(offerId) }
        }
        networkClient.onSupportTicketReplied = { ticketId, subject, reply, timestamp ->
            scope.launch { handleSupportTicketReplied(ticketId, subject, reply, timestamp) }
        }
        networkClient.onModerationWarning = { warningMsg ->
            scope.launch {
                _moderationWarningAlert.value = warningMsg
                proximitySimulator.receivePrivateMessage(
                    "moderacao-oficial",
                    "Moderação PessoasAqui",
                    warningMsg
                )
                callRingtoneWakeManager?.notifyIncomingMessageIfBackground("Moderação PessoasAqui", warningMsg)
            }
        }
        networkClient.onAccountBanned = { banMsg ->
            scope.launch {
                _accountBannedAlert.value = banMsg
                proximitySimulator.receivePrivateMessage(
                    "moderacao-oficial",
                    "Moderação PessoasAqui",
                    "🚫 $banMsg"
                )
            }
        }

        // Configura Coordenador de Transporte Bluetooth Direto (ETAPA 3D & 3E)
        bleTransportCoordinator.onTemporaryMessageReceived = { peerId, text, _ ->
            val cleanSender = cleanId(peerId)
            // Se o contato for permanente (Modo B), não roteia por canal temporário
            if (!isPeerPermanent(cleanSender)) {
                val peerAlias = _realDiscoveredPeople.value.firstOrNull {
                    cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender
                }?.alias ?: "Pessoa Próxima"
                proximitySimulator.receivePrivateMessage(cleanSender, peerAlias, text)
                updateUnifiedData()
            }
        }

        // Confirmação física real de entrega via ACK verificado
        bleTransportCoordinator.onDeliveryConfirmedWithUuid = { peerId, _, messageUuid ->
            val cleanSender = cleanId(peerId)
            if (messageUuid.isNotBlank()) {
                proximitySimulator.updateMessageDeliveryStatus(
                    cleanSender,
                    messageUuid,
                    br.com.pessoasaqui.domain.model.MessageDeliveryStatus.DELIVERED
                )
                updateUnifiedData()
            }
        }

        // Notificação real de falha de entrega (timeout ou desconexão)
        bleTransportCoordinator.onDeliveryFailed = { peerId, _, messageUuid, _ ->
            val cleanSender = cleanId(peerId)
            if (messageUuid.isNotBlank()) {
                proximitySimulator.updateMessageDeliveryStatus(
                    cleanSender,
                    messageUuid,
                    br.com.pessoasaqui.domain.model.MessageDeliveryStatus.FAILED
                )
                updateUnifiedData()
            }
        }

        // Recepção segura do nome completo autenticado via metadados AES-256-GCM
        bleTransportCoordinator.onPeerAliasReceived = { peerId, fullAlias ->
            val cleanSender = cleanId(peerId)
            updatePeerFullAlias(cleanSender, fullAlias)
        }

        bleManager?.onDeviceAddressDiscovered = { peerHash, address ->
            bleTransportCoordinator.recordPeerDeviceAddress(peerHash, address)
        }
        bleManager?.onBluetoothStateChanged = { enabled ->
            try {
                if (!enabled) {
                    // O Android encerra conexões GATT ao desligar o adaptador:
                    // descartar handles antigos antes da próxima inicialização.
                    bleTransportCoordinator.stop()
                } else if (_hasGrantedPermissions.value) {
                    // Mesmo se o app iniciou com Bluetooth desligado (sem callbacks
                    // anteriores), iniciar GATT + advertising + scanner por inteiro.
                    startNativeBleHardware()
                }
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Falha ao sincronizar GATT com estado do Bluetooth: ${e.message}")
            }
        }

        // Objetos criados nos testes JVM (sem Context Android) não devem
        // executar polling real e consumir recursos do Render.
        if (context != null) {
            startPresencePollingLoop()
            startBleMaintenanceLoop()
        }

        // Se o perfil já estiver configurado, registra identidade no Supabase / Render
        if (_hasCompletedProfile.value) {
            registerIdentityOnBackend()
            scope.launch { refreshMySupportTickets() }
        }

        if (_hasGrantedPermissions.value) {
            startNativeBleHardware()
            context?.let { br.com.pessoasaqui.core.service.PessoasAquiForegroundService.startServiceSafely(it) }
        }
    }

    private val processedMessageIds = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    internal fun processIncomingPayload(senderHash: String, senderAlias: String, rawPayload: String, iv: String = "", messageId: String = "") {
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val cleanSender = cleanId(senderHash)
        // Uma falha de autenticação/decifragem nunca vira bolha de chat nem
        // sinalização de chamada. Não marcar o ID como processado antes de
        // validar a mensagem: uma retransmissão futura pode ter dados válidos.
        val payload = try {
            E2eeCryptoEngine.decrypt(rawPayload, iv, cleanSender, myId, cryptoIdentityManager.getActivePrivateKey())
        } catch (_: SecurityException) {
            Log.w("PessoasAqui", "Envelope E2EE não autenticado; descartado sem exibir dados cifrados")
            return
        }
        if (payload.startsWith("ENC_ECDH:") || payload.startsWith("ENC:")) {
            Log.w("PessoasAqui", "Envelope cifrado inesperado após decifragem; descartado")
            return
        }
        if (messageId.isNotBlank()) {
            synchronized(processedMessageIds) {
                if (!processedMessageIds.add(messageId)) return
                if (processedMessageIds.size > 2000) processedMessageIds.clear()
            }
        }

        when {
            payload.startsWith("[CALL_AUDIO_FRAME:") -> {
                // Mídia não constitui aceitação: o usuário precisa atender.
                val active = _activeCallState.value
                if (active?.isConnected == true && cleanId(active.peerHash) == cleanSender) {
                    callMediaEngine?.playRemoteAudioFrame(payload.removePrefix("[CALL_AUDIO_FRAME:").removeSuffix("]"))
                }
            }
            payload.startsWith("[CALL_VIDEO_FRAME:") -> {
                val active = _activeCallState.value
                if (active?.isConnected == true && active.isVideo &&
                    cleanId(active.peerHash) == cleanSender) {
                    val frame = payload.removePrefix("[CALL_VIDEO_FRAME:").removeSuffix("]")
                    _activeCallState.value = active.copy(remoteVideoFrameBase64 = frame)
                }
            }
            payload.startsWith("[CALL_INIT:") -> {
                // Chamadas são exclusivas de vínculos permanentes. Retransmissões
                // CALL_INIT não podem reiniciar o toque nem o timer da ligação.
                if (!isPeerPermanent(cleanSender)) return
                val candidate = CallSignalingProtocol.invitationCallId(payload)
                if (_activeCallState.value != null || callCleanupInProgress) {
                    // Convite duplicado da mesma ligação: ignorar sem afetar
                    // o telefone. Convite diferente: responder "ocupado".
                    if (candidate != null && candidate != activeCallGeneration) {
                        sendPrivateMessage(cleanSender,
                            CallSignalingProtocol.encode(
                                CallSignalingProtocol.Command.END, candidate,
                                CallSignalingProtocol.EndReason.BUSY))
                    }
                    return
                }

                val parts = payload.removePrefix("[CALL_INIT:").removeSuffix("]").split(":")
                val isVideo = parts.getOrNull(0)?.toBoolean() ?: false
                val callerAlias = parts.getOrNull(1) ?: senderAlias
                val generation = candidate ?: CallSignalingProtocol.newCallId()
                // O histórico do Modo B é persistido criptografado. Impede
                // reabrir uma chamada concluída após morte/reinício do processo.
                if (proximitySimulator.privateChats.value[cleanSender].orEmpty()
                        .any { it.id == "call-$generation" }) return
                synchronized(callStateLock) {
                    if (!callLifecycle.begin(generation, cleanSender, isVideo, incoming = true)) return
                    activeCallGeneration = generation
                    callConnectedAtMs = 0L
                }
                _activeCallState.value = ActiveCallState(
                    isIncoming = true,
                    peerHash = cleanSender,
                    peerAlias = callerAlias,
                    isVideo = isVideo,
                    isConnected = false
                )
                callRingtoneWakeManager?.onIncomingCallReceived(
                    callerHash = cleanSender,
                    callerAlias = callerAlias,
                    isVideo = isVideo,
                    onAutoTimeout = {
                        if (activeCallGeneration == generation) finishActiveCall(timedOut = true)
                    }
                )
            }
            payload.startsWith("[CALL_ACCEPT]") -> {
                // Aceite e timeout competem entre callbacks de rede diferentes.
                // A transição e o estado visível devem mudar atomicamente.
                val accepted = synchronized(callStateLock) {
                    val current = _activeCallState.value
                    val callId = activeCallGeneration
                    if (current != null && !current.isIncoming && !current.isConnected &&
                        cleanId(current.peerHash) == cleanSender &&
                        CallSignalingProtocol.belongsToCall(payload, CallSignalingProtocol.Command.ACCEPT, callId) &&
                        callLifecycle.accept(callId!!, cleanSender, byLocalUser = false)) {
                        _activeCallState.value = current.copy(isConnected = true, isSpeakerphoneOn = current.isVideo)
                        markActiveCallConnected()
                        Pair(current, callId!!)
                    } else null
                }
                accepted?.let { (current, callId) ->
                    if (activeCallGeneration == callId) {
                        callRingtoneWakeManager?.stopAllCallAlerts()
                        callMediaEngine?.startCallMedia(current.isVideo)
                        sendPrivateMessage(current.peerHash,
                            CallSignalingProtocol.encode(CallSignalingProtocol.Command.ACK, callId))
                    }
                }
            }
            payload.startsWith("[CALL_ACK]") -> {
                // ACK é confirmação de sinalização, jamais aceite.
            }
            payload.startsWith("[CALL_END]") -> {
                val current = _activeCallState.value
                if (current != null && cleanId(current.peerHash) == cleanSender &&
                    CallSignalingProtocol.belongsToCall(payload, CallSignalingProtocol.Command.END, activeCallGeneration)) {
                    val terminal = CallSignalingProtocol.terminationReason(payload)
                    finishActiveCall(
                        remoteEnded = true,
                        notifyPeer = false,
                        remoteDeclined = terminal == CallSignalingProtocol.EndReason.DECLINED,
                        remoteBusy = terminal == CallSignalingProtocol.EndReason.BUSY
                    )
                }
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
                    cleanSender.takeIf { it.isNotBlank() }
                ).distinct()

                keys.forEach { savedMarkingMePeople.add(it) }
                val isMarked = targetPeer?.let { isPeerMarkedByMe(it) } ?: keys.any { savedMarkedPeople.contains(it) }
                val isNowMutual = isPeerPermanent(cleanSender)
                // MARK_ON é apenas indicação de pedido, não prova de dupla aprovação.
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
                        estimatedDistanceMeters = 500.0,
                        proximityLabel = if (isNowMutual) "Conexão Mútua • Ilimitada" else "Solicitação recebida",
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
                // Compatibilidade com notificações antigas do backend: invalida o
                // vínculo local, não tenta revivê-lo a partir de um chat antigo.
                revokeMutualLocally(cleanSender)
            }
            payload.startsWith("[MARK_MUTUAL:") -> {
                // Eventos legados ou atrasados não são prova de vínculo permanente.
                // A conexão só é confirmada pelo evento oficial do servidor ou por /status.
                Log.d("PessoasAqui", "Ignorando controle MARK_MUTUAL legado; aguardando confirmação do servidor")
            }
            payload.startsWith("[VAULT_STORE:") -> {
                val b64 = payload.removePrefix("[VAULT_STORE:").removeSuffix("]")
                scope.launch(Dispatchers.IO) {
                    try {
                        val jsonStr = String(E2eeCryptoEngine.Base64Helper.decode(b64), Charsets.UTF_8)
                        val vault = p2pVaultManager?.deserializeVaultFromJson(jsonStr)
                        if (vault != null) {
                            val stored = p2pVaultManager?.storePeerVault(vault) ?: false
                            if (stored) {
                                sendPrivateMessage(cleanSender, "[VAULT_ACK:${vault.version}]")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("PessoasAqui", "Falha ao processar VAULT_STORE silencioso: ${e.message}")
                    }
                }
            }
            payload.startsWith("[VAULT_ACK:") -> {
                val ackVersion = payload.removePrefix("[VAULT_ACK:").removeSuffix("]").toLongOrNull() ?: 0L
                Log.i("PessoasAqui", "Cofre v$ackVersion confirmado silenciosamente pelo contato $cleanSender")
                _vaultAckCount.value = _vaultAckCount.value + 1
            }
            payload.startsWith("[VAULT_QUERY:") -> {
                val targetOwnerHash = payload.removePrefix("[VAULT_QUERY:").removeSuffix("]").trim().lowercase()
                scope.launch(Dispatchers.IO) {
                    val vault = p2pVaultManager?.getStoredPeerVault(targetOwnerHash)
                    if (vault != null) {
                        val jsonStr = p2pVaultManager?.serializeVaultToJson(vault) ?: ""
                        val b64 = E2eeCryptoEngine.Base64Helper.encode(jsonStr.toByteArray(Charsets.UTF_8))
                        sendPrivateMessage(cleanSender, "[VAULT_RESP:$b64]")
                    }
                }
            }
            payload.startsWith("[VAULT_RESP:") -> {
                val b64 = payload.removePrefix("[VAULT_RESP:").removeSuffix("]")
                scope.launch(Dispatchers.IO) {
                    try {
                        val jsonStr = String(E2eeCryptoEngine.Base64Helper.decode(b64), Charsets.UTF_8)
                        val vault = p2pVaultManager?.deserializeVaultFromJson(jsonStr)
                        if (vault != null) {
                            val current = _receivedVaultResponses.value.toMutableList()
                            current.add(vault)
                            _receivedVaultResponses.value = current
                        }
                    } catch (e: Exception) {
                        Log.w("PessoasAqui", "Falha ao processar VAULT_RESP: ${e.message}")
                    }
                }
            }
            else -> {
                // Um comando interno de versão futura ou malformado não é texto.
                if (payload.startsWith("[CALL_")) return
                val isPermanent = isPeerPermanent(cleanSender)
                // Este método recebe somente payloads da INTERNET. Mensagens do Modo A
                // devem chegar por onTemporaryMessageReceived (BLE), nunca pelo Render,
                // mesmo que o contato esteja fisicamente presente no Radar.
                if (!isPermanent) {
                    Log.d("PessoasAqui", "Descartando mensagem de rede para contato temporário $cleanSender")
                    return
                }
                proximitySimulator.receivePrivateMessage(cleanSender, senderAlias, payload, messageId)
                callRingtoneWakeManager?.notifyIncomingMessageIfBackground(senderAlias, payload)
            }
        }
    }

    /**
     * Manutenção BLE independente do backend. Ao perder Wi-Fi/Render, o Radar
     * continua limpando sessões físicas expiradas e recuperando scan/advertise.
     * Não reinicia o servidor GATT durante sessões já autenticadas.
     */
    private fun startBleMaintenanceLoop() {
        // A expiração de contatos é uma política de sessões, não um comando
        // para reiniciar o rádio. O scanner é gerido por eventos do Android.
        if (bleManager == null) return
        scope.launch {
            while (true) {
                delay(5000L)
                if (!_hasGrantedPermissions.value) continue
                try {
                    cleanupExpiredTemporarySessions()
                } catch (e: Exception) {
                    Log.w("PessoasAqui", "Manutenção de sessões BLE: ${e.message}")
                }
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

                    // 2. Primeiro envia revogações pendentes (pode ter sido solicitado offline).
                    // Nunca repovoar o servidor com vínculos da memória: isso ressuscitava
                    // relações desfeitas pela outra pessoa.
                    for (peerId in pendingUnmarkPeople.toList()) {
                        val result = networkClient.unmarkConnection(myId, peerId)
                        if (result.getOrNull() == true) {
                            pendingUnmarkPeople.remove(peerId)
                            saveConnectionsToPrefs()
                        }
                    }
                    val statusResult = networkClient.fetchConnectionStatus(myId)
                    statusResult.getOrNull()?.let { st ->
                        reconcileConnectionStatus(st.markedByMe, st.markingMe, st.mutual)
                    }

                    // 3. Busca pessoas próximas registradas no backend
                    val nearbyResult = networkClient.fetchNearbyPresence(myId, myAlias)
                    nearbyResult.getOrNull()?.let { remotePeers ->
                        handleRemotePresenceSync(remotePeers)
                    }

                    // 4. Busca mensagens pendentes na fila do servidor
                    val pendingResult = networkClient.fetchPendingMessages(myId)
                    pendingResult.getOrNull()?.forEach { pm ->
                        processIncomingPayload(pm.senderHash, pm.senderAlias, pm.ciphertext, pm.ivNonce, pm.messageId)
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

                    // 5.1 Busca atualizações e respostas a chamados de suporte do usuário
                    if (System.currentTimeMillis() - lastSupportSyncMs > 25000L) {
                        lastSupportSyncMs = System.currentTimeMillis()
                        refreshMySupportTickets()
                    }

                    // 6. Garante que o WebSocket esteja ativo para sincronização instantânea
                    if (!networkClient.isWebSocketConnected) {
                        networkClient.startRealtimeSocket(
                            myIdentityHash = myId,
                            myAlias = myAlias,
                            myIntent = myIntent,
                            onE2eeMessageReceived = { sender, payload, iv, msgId ->
                                val cleanSender = cleanId(sender)
                                val senderAlias = _realDiscoveredPeople.value.find { cleanId(it.id) == cleanSender || cleanId(it.technicalIdentityHash) == cleanSender }?.alias ?: "Pessoa Próxima"
                                processIncomingPayload(cleanSender, senderAlias, payload, iv, msgId)
                            },
                            onMutualConnection = { partnerId, partnerAlias ->
                                val cid = cleanId(partnerId)
                                // O evento de socket pode chegar atrasado. Só o status bilateral
                                // atual do servidor concede Modo B após consulta bem-sucedida.
                                if (cid !in revokedMutualPeople) scope.launch {
                                    val status = networkClient.fetchConnectionStatus(myId).getOrNull()
                                    if (status != null) {
                                        reconcileConnectionStatus(status.markedByMe, status.markingMe, status.mutual)
                                        if (isPeerPermanent(cid)) {
                                            val current = _realDiscoveredPeople.value.toMutableList()
                                            val idx = current.indexOfFirst { cleanId(it.technicalIdentityHash.ifBlank { it.id }) == cid }
                                            if (idx >= 0 && current[idx].alias.startsWith("Pessoa Próxima #")) {
                                                current[idx] = current[idx].copy(alias = partnerAlias)
                                                _realDiscoveredPeople.value = current
                                                updateUnifiedData()
                                            }
                                        }
                                    }
                                }
                            },
                            onMutualConnectionBroken = { partnerId, _ ->
                                revokeMutualLocally(partnerId)
                            },
                            onPeerMarkedYou = { markerId, _ ->
                                val cleanMarker = cleanId(markerId)
                                savedMarkingMePeople.add(cleanMarker)
                                saveConnectionsToPrefs()
                                val current = _realDiscoveredPeople.value.toMutableList()
                                val idx = current.indexOfFirst { cleanId(it.technicalIdentityHash.ifBlank { it.id }) == cleanMarker }
                                if (idx >= 0) {
                                    current[idx] = applyConnectionState(current[idx])
                                    _realDiscoveredPeople.value = current
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
                            },
                            myPublicKeyBase64 = cryptoIdentityManager.getPublicKeyBase64(),
                            signChallenge = { data -> cryptoIdentityManager.signChallenge(data) }
                        )
                    }

                    // 6. Limpeza automática e segura de sessões temporárias expiradas (> 45s de afastamento)
                    // Contatos mútuos e familiares são permanentes e protegidos contra expiração física.
                    cleanupExpiredTemporarySessions()
                } catch (e: Throwable) {
                    Log.w("PessoasAqui", "Polling de presença: ${e.message}")
                }
                delay(4000)
            }
        }
    }

    /**
     * Algoritmo de Ordenação Estável com Histerese de Posição.
     * Resolve a causa de aparelhos "ficarem doidos trocando de posição direto":
     * - Contatos mútuos e família permanecem priorizados.
     * - Dispositivos dentro da mesma faixa física (<2.5m, 2.5-6m, 6-10m)
     *   MANTÊM a ordem visual anterior a menos que a variação de distância física
     *   seja real e sustentada (>= 1.5 metros).
     * - Suporta 200+ pessoas com estabilidade total e zero tremor.
     */
    fun sortWithHysteresis(peers: List<NearbyPerson>): List<NearbyPerson> {
        if (peers.isEmpty()) return emptyList()
        val previousOrder = lastStablePeerOrder ?: emptyList()
        val prevIndexMap = previousOrder.withIndex().associate { it.value to it.index }

        val sorted = peers.sortedWith(Comparator { a, b ->
            // 1. Conexões mútuas e familiares sempre têm prioridade no topo
            val aPriority = if (a.isMutualConnection || a.isFamily) 1 else 0
            val bPriority = if (b.isMutualConnection || b.isFamily) 1 else 0
            if (aPriority != bPriority) {
                return@Comparator bPriority.compareTo(aPriority)
            }

            // 2. Faixas de proximidade física (Imediato < 2.5m, Próximo 2.5m-6m, Alcance 6m-10m)
            val zoneA = distanceEstimator.getProximityZone(a.estimatedDistanceMeters)
            val zoneB = distanceEstimator.getProximityZone(b.estimatedDistanceMeters)
            if (zoneA != zoneB) {
                return@Comparator zoneA.compareTo(zoneB)
            }

            // 3. Aplicação de Histerese de Posição:
            // Se ambos os contatos já estavam na lista anterior, preserva estritamente
            // a ordem relativa anterior se a diferença entre eles for inferior a 1.5 metros.
            val idA = cleanId(a.technicalIdentityHash.ifBlank { a.id })
            val idB = cleanId(b.technicalIdentityHash.ifBlank { b.id })
            val idxA = prevIndexMap[idA]
            val idxB = prevIndexMap[idB]

            if (idxA != null && idxB != null) {
                val distanceDiff = kotlin.math.abs(a.estimatedDistanceMeters - b.estimatedDistanceMeters)
                if (distanceDiff < 1.5) {
                    return@Comparator idxA.compareTo(idxB)
                }
            }

            // Variação física substancial ou novo contato: ordena por distância real
            a.estimatedDistanceMeters.compareTo(b.estimatedDistanceMeters)
        })

        lastStablePeerOrder = sorted.map { cleanId(it.technicalIdentityHash.ifBlank { it.id }) }
        return sorted
    }

    private fun updateUnifiedData() {
        val savedContacts = loadSavedContactsFromPrefs().filter { !it.id.startsWith("sim-", ignoreCase = true) }
        // PRODUÇÃO PURA: Somente pessoas e ofertas reais no radar e contatos mútuos/familiares legítimos
        val applied = (_realDiscoveredPeople.value + savedContacts)
            .filter { !it.id.startsWith("sim-", ignoreCase = true) }
            .distinctBy { cleanId(it.technicalIdentityHash.ifBlank { it.id }) }
            .map { applyConnectionState(it) }
        _unifiedDiscoveredPeople.value = sortWithHysteresis(applied)
        _unifiedOffers.value = _realOffers.value

        // Apenas mensagens reais trocadas ou persistidas (isolamento absoluto)
        _unifiedLocalChatMessages.value = proximitySimulator.localChatMessages.value
        _unifiedPrivateChats.value = proximitySimulator.privateChats.value
    }

    internal fun handleRemotePresenceSync(remotePeers: List<RemotePeer>) {
        val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
        val myAlias = _currentAlias.value.trim()
        val current = _realDiscoveredPeople.value.toMutableList()

        val validPeers = remotePeers.filter { p ->
            // Apenas a identidade técnica identifica o próprio aparelho.
            cleanId(p.identityHash) != myId
        }

        val distinctPeers = validPeers.distinctBy { cleanId(it.identityHash) }

        distinctPeers.forEach { p ->
            val cId = cleanId(p.identityHash)
            p.publicKeyBase64?.let { pubKey ->
                E2eeCryptoEngine.registerPeerPublicKey(cId, pubKey)
            }
            // Presença remota é telemetria, NÃO autorização de relacionamento.
            // Somente /connections/status ou evento de confirmação do backend altera o vínculo.

            val safeIntent = try { UserIntent.valueOf(p.intent) } catch (_: Exception) { UserIntent.QUERO_CONVERSAR }
            val idx = current.indexOfFirst {
                // Apelido NÃO é identidade técnica: duas pessoas podem ter o mesmo nome.
                cleanId(it.technicalIdentityHash) == cId || cleanId(it.id) == cId
            }
            if (idx != -1) {
                // Atualiza contato já existente (detectado fisicamente pelo BLE nativo ou conexão prévia)
                val existing = current[idx]
                current[idx] = applyConnectionState(existing.copy(
                    id = cId,
                    technicalIdentityHash = cId,
                    alias = if (existing.alias.isNotBlank() && !existing.alias.startsWith("Pessoa Próxima #")) existing.alias else p.alias,
                    intent = safeIntent,
                    // A nuvem não comprova proximidade: não renova o relógio BLE.
                    lastSeenEpochMs = if (lastNativeBleSeenMs.containsKey(cId)) existing.lastSeenEpochMs else System.currentTimeMillis()
                ))
            } else if (isPeerPermanent(cId)) {
                // Presença remota de Conexão Mútua comprovada pré-existente
                current.add(
                    applyConnectionState(NearbyPerson(
                        id = cId,
                        technicalIdentityHash = cId,
                        alias = p.alias,
                        estimatedDistanceMeters = 500.0,
                        proximityLabel = "Conexão Mútua • Ilimitada",
                        intent = safeIntent,
                        avatarColorHex = 0xFF00E5FF,
                        isMutualConnection = true,
                        isMarkedByMe = true,
                        isMarkingMe = true,
                        isPhotoVisible = true,
                        lastSeenEpochMs = System.currentTimeMillis()
                    ))
                )
            }
            // Estranhos remotos NÃO são adicionados ao radar; o radar é 100% rádio Bluetooth BLE físico (< 10m)!
        }
        saveConnectionsToPrefs()

        // Apelidos repetidos são válidos; remover apenas a identidade própria.
        val cleanedList = current.filter { peer ->
            cleanId(peer.technicalIdentityHash) != myId
        }

        _realDiscoveredPeople.value = cleanedList
        updateUnifiedData()
    }

    internal fun handlePeerOnline(peer: RemotePeer) {
        val myId = cleanId(cryptoIdentityManager.getTechnicalIdentity())
        val myAlias = _currentAlias.value.trim()
        val cId = cleanId(peer.identityHash)
        if (cId == myId) return
        // O apelido remoto pode ser igual ao local; isso não torna a identidade igual.

        peer.publicKeyBase64?.let { pubKey ->
            E2eeCryptoEngine.registerPeerPublicKey(cId, pubKey)
        }

        // Estado de presença não concede privilégios permanentes.

        val safeIntent = try { UserIntent.valueOf(peer.intent) } catch (_: Exception) { UserIntent.QUERO_CONVERSAR }
        val current = _realDiscoveredPeople.value.toMutableList()
        val idx = current.indexOfFirst {
            cleanId(it.technicalIdentityHash) == cId || cleanId(it.id) == cId
        }
        if (idx != -1) {
            val existing = current[idx]
            current[idx] = applyConnectionState(existing.copy(
                id = cId,
                technicalIdentityHash = cId,
                alias = if (existing.alias.isNotBlank() && !existing.alias.startsWith("Pessoa Próxima #")) existing.alias else peer.alias,
                intent = safeIntent,
                // Eventos da internet não mantêm artificialmente o Radar ligado.
                lastSeenEpochMs = if (lastNativeBleSeenMs.containsKey(cId)) existing.lastSeenEpochMs else System.currentTimeMillis()
            ))
            _realDiscoveredPeople.value = current
            updateUnifiedData()
        } else if (isPeerPermanent(cId)) {
            val newPeer = applyConnectionState(NearbyPerson(
                id = cId,
                technicalIdentityHash = cId,
                alias = peer.alias,
                estimatedDistanceMeters = 500.0,
                proximityLabel = "Conexão Mútua • Ilimitada",
                intent = safeIntent,
                avatarColorHex = 0xFF00E5FF,
                isMutualConnection = true,
                isMarkedByMe = true,
                isMarkingMe = true,
                isPhotoVisible = true,
                lastSeenEpochMs = System.currentTimeMillis()
            ))
            current.add(0, newPeer)
            _realDiscoveredPeople.value = current
            updateUnifiedData()
        }
        // Estranhos online na nuvem são descartados: o radar local é restrito ao rádio BLE físico!
    }

    internal fun handlePeerOffline(peerHash: String) {
        val cleanPeerHash = cleanId(peerHash)
        val current = _realDiscoveredPeople.value.filter {
            val matches = cleanId(it.technicalIdentityHash) == cleanPeerHash || cleanId(it.id) == cleanPeerHash
            if (matches) {
                // Desconexão WebSocket NÃO significa saída dos 10 m: a validade do
                // BLE só é determinada pelos anúncios de rádio e timeout próprio.
                isPeerPermanent(it.technicalIdentityHash.ifBlank { it.id }) ||
                    lastNativeBleSeenMs.containsKey(cleanPeerHash)
            } else {
                true
            }
        }
        if (current.size != _realDiscoveredPeople.value.size) {
            _realDiscoveredPeople.value = current
            updateUnifiedData()
        }
    }

    fun addDiscoveredPeer(person: NearbyPerson) {
        val current = _realDiscoveredPeople.value.toMutableList()
        val cId = cleanId(person.technicalIdentityHash.ifBlank { person.id })
        current.removeAll { cleanId(it.technicalIdentityHash.ifBlank { it.id }) == cId }
        current.add(0, applyConnectionState(person))
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
        context?.let { br.com.pessoasaqui.core.service.PessoasAquiForegroundService.startServiceSafely(it) }
    }

    fun setDemoMode(enabled: Boolean) {
        _isDemoMode.value = false
        prefs?.edit()?.remove("is_demo_mode")?.apply()
        updateUnifiedData()
    }

    fun toggleDemoMode() {
        setDemoMode(false)
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

    private var bleBatchJob: Job? = null

    private fun startNativeBleHardware() {
        try {
            bleTransportCoordinator.start()
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
                        // Nomes iguais não impedem descoberta de identidades distintas.
                        recordNativeBlePresence(peerCleanId, realPeer.lastSeenEpochMs)

                        val currentList = _realDiscoveredPeople.value.toMutableList()
                        val idx = currentList.indexOfFirst {
                            cleanId(it.id) == peerCleanId || cleanId(it.technicalIdentityHash) == peerCleanId
                        }
                        if (idx != -1) {
                            val existing = currentList[idx]
                            val resolvedAlias = if (existing.alias.isNotBlank() && existing.alias != "Pessoa Próxima" && !existing.alias.startsWith("Pessoa Próxima #")) {
                                existing.alias
                            } else if (realPeer.alias.isNotBlank() && realPeer.alias != "Pessoa Próxima" && !realPeer.alias.startsWith("Pessoa Próxima #")) {
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
                            _realDiscoveredPeople.value = currentList

                            // Contato existente: agrupa atualizações a cada 600ms para evitar recalcular ordenação 100x por segundo
                            if (bleBatchJob?.isActive != true) {
                                bleBatchJob = scope.launch {
                                    delay(600)
                                    updateUnifiedData()
                                }
                            }
                        } else {
                            // Novo contato: adiciona imediatamente para descoberta visual instantânea
                            currentList.add(0, applyConnectionState(realPeer.copy(id = peerCleanId, technicalIdentityHash = peerCleanId)))
                            _realDiscoveredPeople.value = currentList
                            updateUnifiedData()
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            Log.e("PessoasAqui", "BLE não disponível ou permissão ausente: ${e.message}")
        }
    }

    /**
     * Atualiza o nome completo autenticado do participante recebido via metadados criptografados GATT (AES-256-GCM).
     */
    fun updatePeerFullAlias(peerId: String, fullAlias: String) {
        if (fullAlias.isBlank()) return
        val cleanPeer = cleanId(peerId)
        val currentReal = _realDiscoveredPeople.value.toMutableList()
        val idx = currentReal.indexOfFirst {
            cleanId(it.id) == cleanPeer || cleanId(it.technicalIdentityHash) == cleanPeer
        }
        if (idx != -1) {
            val existing = currentReal[idx]
            currentReal[idx] = existing.copy(alias = fullAlias)
            _realDiscoveredPeople.value = currentReal
        }
        proximitySimulator.updatePeerSenderAlias(cleanPeer, fullAlias)
        updateUnifiedData()
    }

    fun restartBleHardware() {
        startNativeBleHardware()
    }

    /** Recupera descoberta ao voltar ao app, sem forçar reinício do GATT ativo. */
    fun setBleAppForeground(visible: Boolean) {
        if (!_hasGrantedPermissions.value) return
        try {
            bleManager?.setAppForeground(visible)
        } catch (e: Exception) {
            Log.w("PessoasAqui", "Transição de visibilidade BLE: ${e.message}")
        }
    }

    fun ensureBleHealthOnForeground(forceRefresh: Boolean = false) {
        if (!_hasGrantedPermissions.value) return
        val ble = bleManager ?: return
        if (!ble.isBluetoothEnabled) return
        try {
            if (!bleTransportCoordinator.serverManager.isServerRunning) {
                bleTransportCoordinator.start()
            }
            // forceRefresh apenas compatibilidade com a interface anterior:
            // recuperação real ocorre por erro de rádio e mudanças de ciclo de vida.
            ble.ensureDiscoveryContinuity()
        } catch (e: Exception) {
            Log.w("PessoasAqui", "Falha ao verificar BLE ao voltar ao app: ${e.message}")
        }
    }

    fun setAlias(newAlias: String): Boolean {
        if (newAlias.isNotBlank()) {
            val trimmed = newAlias.trim()
            if (moderationManager.isReservedAlias(trimmed)) {
                return false
            }
            _currentAlias.value = trimmed
            prefs?.edit()?.putString("user_alias", trimmed)?.apply()
            registerIdentityOnBackend()
            startNativeBleHardware()
            return true
        }
        return false
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
        var idx = currentReal.indexOfFirst {
            val cCid = cleanId(it.technicalIdentityHash)
            val cPid = cleanId(it.id)
            val cAlias = cleanId(it.alias)
            cleanTargetId == cCid || cleanTargetId == cPid ||
            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(personId, ignoreCase = true)) ||
            (cleanTargetId.isNotBlank() && !cleanTargetId.startsWith("PESSOA") && cleanTargetId == cAlias)
        }

        if (idx == -1) {
            val fallback = _unifiedDiscoveredPeople.value.find {
                val cCid = cleanId(it.technicalIdentityHash)
                val cPid = cleanId(it.id)
                cleanTargetId == cCid || cleanTargetId == cPid
            } ?: loadSavedContactsFromPrefs().find {
                val cCid = cleanId(it.technicalIdentityHash)
                val cPid = cleanId(it.id)
                cleanTargetId == cCid || cleanTargetId == cPid
            } ?: NearbyPerson(
                id = cleanTargetId,
                technicalIdentityHash = cleanTargetId,
                alias = "Contato $cleanTargetId",
                estimatedDistanceMeters = 5.0,
                proximityLabel = "5m"
            )
            currentReal.add(fallback)
            idx = currentReal.size - 1
        }

        var updatedPeer: NearbyPerson? = null

        if (idx != -1) {
            val current = currentReal[idx]
            val hashId = cleanId(current.technicalIdentityHash.ifBlank { current.id })
            val pId = cleanId(current.id)
            val aliasId = cleanId(current.alias)

            // Identidade técnica é a chave de vínculo; nunca persistir apelido como autorização.
            val keys = listOf(hashId, pId).filter { it.isNotBlank() }.distinct()
            val targetId = hashId.ifBlank { cleanTargetId }
            val newMarkedByMe = mark

            if (mark) {
                // A aprovação é unilateral até o servidor confirmar reciprocidade.
                revokedMutualPeople.remove(targetId) // apenas uma nova ação explícita limpa o veto
                keys.forEach { savedMarkedPeople.add(it) }
            } else {
                // Revoga no aparelho AGORA; backend será notificado mesmo se estiver offline.
                revokeMutualLocally(targetId, removeMyMark = true, queueRemoteUnmark = true)
                keys.forEach {
                    savedMarkedPeople.remove(it)
                    savedMutualPeople.remove(it)
                }
            }
            saveConnectionsToPrefs()

            val isNowMutual = isPeerPermanent(targetId)
            val updated = current.copy(
                isMarkedByMe = newMarkedByMe,
                isMarkingMe = targetId in savedMarkingMePeople,
                isMutualConnection = isNowMutual,
                isPhotoVisible = isNowMutual || current.isFamily,
                lastSeenEpochMs = System.currentTimeMillis()
            )
            currentReal[idx] = updated
            _realDiscoveredPeople.value = currentReal
            updatedPeer = updated
            updateUnifiedData()

            val myId = cryptoIdentityManager.getTechnicalIdentity()
            // Convite e revogação são eventos de relacionamento (internet), NÃO mensagens BLE.
            // O chat temporário por Bluetooth não depende de convite/aceitação.
            if (context != null) scope.launch {
                try {
                    if (mark) {
                        // Se houve revogação offline anterior, ela deve ser entregue
                        // antes de um pedido NOVO. Evita a corrida mark/unmark no servidor.
                        if (targetId in pendingUnmarkPeople) {
                            val unmark = networkClient.unmarkConnection(myId, targetId)
                            if (unmark.getOrNull() != true) {
                                Log.w("PessoasAqui", "Não foi possível confirmar revogação anterior; conexão permanece pendente")
                                return@launch
                            }
                            pendingUnmarkPeople.remove(targetId)
                            saveConnectionsToPrefs()
                        }
                        val result = networkClient.markConnection(myId, targetId)
                        if (result.getOrNull() == true && targetId !in revokedMutualPeople && targetId in savedMarkedPeople) {
                            confirmMutualConnectionFromServer(targetId)
                        } else if (result.isFailure) {
                            Log.w("PessoasAqui", "Marcação não confirmada pelo servidor: ${result.exceptionOrNull()?.message}")
                        }
                    } else {
                        val result = networkClient.unmarkConnection(myId, targetId)
                        if (result.getOrNull() == true) {
                            pendingUnmarkPeople.remove(targetId)
                            saveConnectionsToPrefs()
                        } else {
                            Log.w("PessoasAqui", "Revogação pendente de sincronização com servidor")
                        }
                    }
                } catch (e: Exception) {
                    Log.w("PessoasAqui", "Erro ao sincronizar vínculo com servidor: ${e.message}")
                }
            }

            // Feedback local não deve anunciar vínculo permanente antes da confirmação.
            val feedback = if (newMarkedByMe) {
                "★ Você solicitou conexão com ${updated.alias}. A conversa temporária por Bluetooth continua disponível sem convite."
            } else {
                "Marcação removida para ${updated.alias}. A revogação será sincronizada com o servidor."
            }
            proximitySimulator.receivePrivateMessage(
                targetId, "Sistema", feedback, UUID.randomUUID().toString()
            )
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
            val link = try {
                // Convite válido só existe quando criado/assinado no backend.
                // Nunca produzir um URL offline fictício nem expor token inventado.
                val alias = getMyAlias().trim().take(64).ifBlank { "Usuário" }
                val proof = br.com.pessoasaqui.core.crypto.InviteIdentityProof.create(
                    operation = "CREATE", identity = getMyIdentity(), alias = alias,
                    publicKeyBase64 = cryptoIdentityManager.getPublicKeyBase64(),
                    signChallenge = cryptoIdentityManager::signChallenge
                )
                networkClient.createOneTimeInvite(getMyIdentity(), alias, proof)
                    .getOrNull()?.inviteUrl
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Não foi possível criar convite seguro: ${e.message}")
                null
            }
            onResult(link)
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
        signature: String? = null,
        expiresAt: Long? = null,
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
                val receiverAlias = getMyAlias().trim().take(64).ifBlank { "Usuário Convidado" }
                val proof = br.com.pessoasaqui.core.crypto.InviteIdentityProof.create(
                    operation = "REDEEM", identity = myId, alias = receiverAlias,
                    inviteId = inviteId, token = token,
                    publicKeyBase64 = cryptoIdentityManager.getPublicKeyBase64(),
                    signChallenge = cryptoIdentityManager::signChallenge
                )
                val res = networkClient.redeemOneTimeInvite(
                    inviteId = inviteId,
                    token = token,
                    receiverIdentity = myId,
                    receiverAlias = receiverAlias,
                    identityProof = proof,
                    senderIdentity = cleanSender,
                    senderAlias = senderAlias,
                    signature = signature,
                    expiresAt = expiresAt
                )

                if (res.isSuccess) {
                    // Resgate válido é nova aceitação explícita das duas partes.
                    revokedMutualPeople.remove(cleanSender)
                    confirmMutualConnectionFromServer(cleanSender)

                    val peer = NearbyPerson(
                        id = cleanSender,
                        technicalIdentityHash = cleanSender,
                        alias = senderAlias.ifBlank { "Conexão Convidada" },
                        estimatedDistanceMeters = 500.0,
                        proximityLabel = "Conexão Mútua • Ilimitada",
                        isMarkedByMe = true,
                        isMarkingMe = true,
                        isMutualConnection = true,
                        isPhotoVisible = true,
                        lastSeenEpochMs = System.currentTimeMillis()
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
                    saveSavedContactsToPrefs()

                    proximitySimulator.receivePrivateMessage(
                        cleanSender,
                        "Sistema",
                        "✨ Conexão Mútua Estabelecida via Link Seguro com ${peer.alias}! Conversas privadas e chamadas liberadas à distância.",
                        UUID.randomUUID().toString()
                    )

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
        val rawClean = cleanId(recipientId)
        val matchedPeer = _realDiscoveredPeople.value.firstOrNull {
            cleanId(it.id) == rawClean ||
            cleanId(it.technicalIdentityHash) == rawClean ||
            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(recipientId.trim(), ignoreCase = true))
        } ?: _unifiedDiscoveredPeople.value.firstOrNull {
            cleanId(it.id) == rawClean ||
            cleanId(it.technicalIdentityHash) == rawClean ||
            (!it.alias.startsWith("Pessoa Próxima #") && it.alias.equals(recipientId.trim(), ignoreCase = true))
        }
        val cleanRecipient = matchedPeer?.technicalIdentityHash?.let { cleanId(it) }?.takeIf { it.isNotBlank() } ?: rawClean

        val isSignaling = text.startsWith("[CALL_") || text.startsWith("[FAMILY_") || text.startsWith("[MARK_") ||
                          text == "[READ_ACK]" || text.startsWith("[MSG_EDIT:") || text.startsWith("[MSG_DELETE:")
        val isAudio = text.startsWith("[AUDIO:")
        val isImage = text.startsWith("[IMAGE:")
        val isDoc = text.startsWith("[DOC:")
        val msgId = UUID.randomUUID().toString()

        val isPermanent = isPeerPermanent(cleanRecipient)

        if (!isPermanent) {
            // O Modo A permite SOMENTE texto; mídia/chamadas são recursos permanentes.
            // Nunca adiciona mídia à RAM nem tenta encaminhá-la ao BLE (máx. 4096 B).
            if (isAudio || isImage || isDoc || text.startsWith("[CALL_")) {
                return ContentModerationManager.ModerationResult(
                    isAllowed = false,
                    reason = "Fotos, áudios, arquivos e chamadas exigem vínculo permanente confirmado."
                )
            }
            // MODO A: Conversa Temporária
            // Criação local com status inicial PENDING até confirmação física por ACK
            if (!isSignaling && !isAudio && !isImage && !isDoc) {
                val mod = moderationManager.evaluateContent(deviceSessionManager.getDeviceId(), text)
                if (!mod.isAllowed) return mod
                proximitySimulator.sendPrivateMessage(
                    recipientId = cleanRecipient,
                    text = text,
                    myAlias = _currentAlias.value,
                    messageId = msgId,
                    deliveryStatus = br.com.pessoasaqui.domain.model.MessageDeliveryStatus.PENDING
                )
            } else if (isAudio) {
                val content = text.removePrefix("[AUDIO:").removeSuffix("]")
                val parts = content.split(":", limit = 2)
                val dur = parts.getOrNull(0)?.toIntOrNull() ?: 3
                val b64 = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
                proximitySimulator.sendAudioMessage(cleanRecipient, dur, _currentAlias.value, msgId, b64)
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

            // Roteamento exclusivamente via rádio Bluetooth BLE GATT direto.
            // Sem contato com a Internet / Render / WebSocket / HTTP.
            // Em caso de falha de rádio, NUNCA faz fallback para o servidor Render.
            val accepted = bleTransportCoordinator.sendTemporaryMessage(cleanRecipient, text, _currentAlias.value, messageUuid = msgId)
            if (!accepted && !isSignaling) {
                proximitySimulator.updateMessageDeliveryStatus(
                    cleanRecipient, msgId, br.com.pessoasaqui.domain.model.MessageDeliveryStatus.FAILED
                )
            }
            updateUnifiedData()
            return if (accepted) ContentModerationManager.ModerationResult(isAllowed = true)
                   else ContentModerationManager.ModerationResult(isAllowed = false, reason = "Bluetooth indisponível ou pessoa fora de alcance.")
        }

        // MODO B: Conexão Mútua / Conexão Permanente
        if (!isSignaling && !isAudio && !isImage && !isDoc) {
            val mod = moderationManager.evaluateContent(deviceSessionManager.getDeviceId(), text)
            if (!mod.isAllowed) return mod
            proximitySimulator.sendPrivateMessage(
                recipientId = cleanRecipient,
                text = text,
                myAlias = _currentAlias.value,
                messageId = msgId,
                deliveryStatus = br.com.pessoasaqui.domain.model.MessageDeliveryStatus.DELIVERED
            )
        } else if (isAudio) {
            val content = text.removePrefix("[AUDIO:").removeSuffix("]")
            val parts = content.split(":", limit = 2)
            val dur = parts.getOrNull(0)?.toIntOrNull() ?: 3
            val b64 = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
            proximitySimulator.sendAudioMessage(cleanRecipient, dur, _currentAlias.value, msgId, b64)
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

        // MODO B: Conexão Mútua / Conexão Permanente
        // Roteamento exclusivamente via Internet (WebSocket + REST HTTP) com E2EE permanente.
        // Nunca utiliza Bluetooth BLE para conversas permanentes.
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        // Criptografia de Ponta a Ponta com AES-256-GCM e ECDH Zero-Knowledge
        val (ciphertext, iv) = try {
            E2eeCryptoEngine.encrypt(text, myId, cleanRecipient, cryptoIdentityManager.getActivePrivateKey())
        } catch (e: Exception) {
            Log.e("PessoasAqui", "Falha na cifragem da mensagem permanente; envio cancelado", e)
            if (!isSignaling && !isAudio && !isImage && !isDoc) {
                proximitySimulator.updateMessageDeliveryStatus(
                    cleanRecipient, msgId, br.com.pessoasaqui.domain.model.MessageDeliveryStatus.FAILED
                )
            }
            updateUnifiedData()
            return ContentModerationManager.ModerationResult(isAllowed = false, reason = "Falha ao proteger mensagem criptograficamente.")
        }

        // 1. Envio instantâneo prioritário via WebSocket (0ms latência)
        networkClient.sendE2eeEnvelope(
            recipientHash = cleanRecipient,
            ciphertext = ciphertext,
            ivNonce = iv,
            messageId = msgId
        )

        // 2. Redundância Garantida via REST HTTP em paralelo para todas as mensagens discretas
        // (O servidor e o receptor deduplicam automaticamente por messageId, garantindo 100% de entrega mesmo se o WebSocket oscilar)
        val isRealtimeMediaStream = text.startsWith("[CALL_AUDIO_FRAME:") || text.startsWith("[CALL_VIDEO_FRAME:")
        if (!isRealtimeMediaStream) {
            scope.launch {
                try {
                    networkClient.sendHttpMessage(
                        senderHash = myId,
                        recipientHash = cleanRecipient,
                        ciphertext = ciphertext,
                        senderAlias = _currentAlias.value,
                        messageId = msgId,
                        ivNonce = iv
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

    private val lastReadAckSentTime = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun markMessagesAsRead(peerId: String) {
        val cleanRecipient = peerId.removePrefix("peer-")
        val hadUnread = proximitySimulator.markMessagesAsRead(cleanRecipient)
        if (!hadUnread) return

        // Debounce anti-flood: no máximo 1 envio de [READ_ACK] a cada 3 segundos por contato
        val now = System.currentTimeMillis()
        val lastSent = lastReadAckSentTime[cleanRecipient] ?: 0L
        if (now - lastSent < 3000L) return
        lastReadAckSentTime[cleanRecipient] = now

        sendPrivateMessage(cleanRecipient, "[READ_ACK]")
    }

    fun deleteMessageForMe(peerId: String, messageId: String): Boolean {
        val cleanRecipient = peerId.removePrefix("peer-")
        return proximitySimulator.deleteMessageForMe(cleanRecipient, messageId)
    }

    fun deleteMessageForEveryone(peerId: String, messageId: String): Boolean {
        val cleanRecipient = peerId.removePrefix("peer-")
        val success = proximitySimulator.deleteMessageForEveryone(cleanRecipient, messageId)
        if (success) {
            sendPrivateMessage(cleanRecipient, "[MSG_DELETE:$messageId]")
        }
        return success
    }

    /**
     * Exclui permanentemente um contato e todo o histórico de mensagens dele do aparelho,
     * desfazendo qualquer marcação ou vínculo mútuo no servidor.
     */
    fun deleteConversationAndContact(peerId: String) {
        val cleanPeerId = cleanId(peerId)
        if (cleanPeerId.isBlank()) return
        val keys = listOf(cleanPeerId, "peer-$cleanPeerId", peerId).distinct()

        // 1. Remove do disco físico de histórico de mensagens e da memória
        chatPersistence?.deletePrivateChat(cleanPeerId)
        proximitySimulator.clearPrivateChat(cleanPeerId)

        val curChats = _unifiedPrivateChats.value.toMutableMap()
        keys.forEach { curChats.remove(it) }
        _unifiedPrivateChats.value = curChats

        // 2. Revoga privilégios; ação pendente será sincronizada mesmo se estiver offline.
        revokeMutualLocally(cleanPeerId, removeMyMark = true, queueRemoteUnmark = true)
        keys.forEach {
            savedMutualPeople.remove(it)
            savedMarkedPeople.remove(it)
            savedMarkingMePeople.remove(it)
        }

        val curReal = _realDiscoveredPeople.value.toMutableList()
        val idx = curReal.indexOfFirst { cleanId(it.technicalIdentityHash) in keys || cleanId(it.id) in keys }
        if (idx != -1) {
            curReal[idx] = curReal[idx].copy(isMarkedByMe = false, isMutualConnection = false, isMarkingMe = false)
        }
        _realDiscoveredPeople.value = curReal

        saveConnectionsToPrefs()
        saveSavedContactsToPrefs()
        updateUnifiedData()

        // 3. Notifica backend Supabase / Render para desmarcar conexão mútua
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        if (context != null) scope.launch {
            try {
                if (networkClient.unmarkConnection(myId, cleanPeerId).getOrNull() == true) {
                    pendingUnmarkPeople.remove(cleanPeerId)
                    saveConnectionsToPrefs()
                }
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Erro ao desmarcar no backend: ${e.message}")
            }
        }
        // Nunca usar o Modo A/Render como fallback implícito para controle do vínculo.
    }

    fun deleteMultipleConversations(peerIds: List<String>) {
        peerIds.forEach { deleteConversationAndContact(it) }
    }

    fun toggleImportantMessage(peerId: String, messageId: String): Boolean {
        val cleanRecipient = peerId.removePrefix("peer-")
        return proximitySimulator.toggleImportantMessage(cleanRecipient, messageId)
    }

    fun editPrivateMessage(peerId: String, messageId: String, newText: String): Boolean {
        val cleanRecipient = peerId.removePrefix("peer-")
        val success = proximitySimulator.editPrivateMessage(cleanRecipient, messageId, newText)
        if (success) {
            sendPrivateMessage(cleanRecipient, "[MSG_EDIT:$messageId:$newText]")
        }
        return success
    }

    fun sendDocumentMessage(recipientId: String, fileName: String, base64: String, sizeBytes: Long) {
        val cleanRecipient = recipientId.removePrefix("peer-")
        sendPrivateMessage(cleanRecipient, "[DOC:$fileName:$sizeBytes:$base64]")
    }

    fun startCall(person: NearbyPerson, isVideo: Boolean) {
        val cleanHash = cleanId(person.technicalIdentityHash.ifBlank { person.id })
        // O Modo A é texto BLE: nunca abrir chamada sem conexão confirmada.
        if (!isPeerPermanent(cleanHash) || _activeCallState.value != null || callCleanupInProgress) return
        val generation = CallSignalingProtocol.newCallId()
        synchronized(callStateLock) {
            if (!callLifecycle.begin(generation, cleanHash, isVideo, incoming = false)) return
            activeCallGeneration = generation
            callConnectedAtMs = 0L
            outgoingCallTimeoutJob?.cancel()
        }
        _activeCallState.value = ActiveCallState(
            isIncoming = false,
            peerHash = cleanHash,
            peerAlias = person.alias,
            isVideo = isVideo,
            isConnected = false,
            isSpeakerphoneOn = isVideo
        )
        callRingtoneWakeManager?.startOutgoingRingbackTone()
        val inviteSignal = CallSignalingProtocol.invite(isVideo, _currentAlias.value, generation)
        sendPrivateMessage(cleanHash, inviteSignal)

        // Chamadas não atendidas precisam terminar automaticamente no chamador.
        outgoingCallTimeoutJob = scope.launch {
            delay(45_000L)
            if (activeCallGeneration == generation &&
                _activeCallState.value?.let { !it.isIncoming && !it.isConnected } == true
            ) {
                finishActiveCall(timedOut = true)
            }
        }

        // Retransmissão preventiva de CALL_INIT após 800ms para garantir toque caso haja oscilação inicial
        scope.launch {
            delay(800)
            val cur = _activeCallState.value
            if (activeCallGeneration == generation && cur != null &&
                !cur.isConnected && !cur.isIncoming && cur.peerHash == cleanHash) {
                sendPrivateMessage(cleanHash, inviteSignal)
            }
        }
    }

    fun answerCall() {
        val accepted = synchronized(callStateLock) {
            val current = _activeCallState.value ?: return
            val generation = activeCallGeneration ?: return
            if (!current.isIncoming || current.isConnected ||
                !callLifecycle.accept(generation, cleanId(current.peerHash), byLocalUser = true)) return
            _activeCallState.value = current.copy(isConnected = true, isSpeakerphoneOn = current.isVideo)
            markActiveCallConnected()
            Pair(current, generation)
        }
        val (current, generation) = accepted
        if (activeCallGeneration != generation) return
        callRingtoneWakeManager?.stopAllCallAlerts()
        callMediaEngine?.startCallMedia(current.isVideo)

        val acceptSignal = CallSignalingProtocol.encode(CallSignalingProtocol.Command.ACCEPT, generation)
        sendPrivateMessage(current.peerHash, acceptSignal)

        val peer = current.peerHash
        scope.launch {
            repeat(3) {
                delay(300)
                if (activeCallGeneration == generation &&
                    _activeCallState.value?.let { it.isConnected && it.isIncoming && it.peerHash == peer } == true) {
                    sendPrivateMessage(peer, acceptSignal)
                }
            }
        }
    }

    fun endCall() {
        finishActiveCall()
    }

    fun toggleCallMute() {
        _activeCallState.value = _activeCallState.value?.let {
            val nextMute = !it.isMuted
            callMediaEngine?.setMuted(nextMute)
            it.copy(isMuted = nextMute)
        }
    }

    fun toggleCallCamera() {
        _activeCallState.value = _activeCallState.value?.let {
            val nextCam = !it.isCameraOn
            callMediaEngine?.setCameraEnabled(nextCam)
            it.copy(isCameraOn = nextCam)
        }
    }

    fun toggleCallSpeakerphone() {
        val spk = callMediaEngine?.toggleSpeakerphone() ?: false
        _activeCallState.value = _activeCallState.value?.copy(isSpeakerphoneOn = spk)
    }

    fun sendAudioMessage(recipientId: String, durationSeconds: Int, audioBase64: String? = null) {
        val cleanRecipient = recipientId.removePrefix("peer-")
        if (!audioBase64.isNullOrBlank()) {
            sendPrivateMessage(cleanRecipient, "[AUDIO:$durationSeconds:$audioBase64]")
        } else {
            sendPrivateMessage(cleanRecipient, "[AUDIO:$durationSeconds]")
        }
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
        val cleanHash = cleanId(recoveredIdentityHash)
        if (cleanHash.isBlank()) return

        // 1. Atualiza e persiste a identidade criptográfica neste aparelho
        cryptoIdentityManager.setRecoveredIdentity(cleanHash)
        deviceSessionManager.resetSession()

        // 2. Registra a nova identidade no backend (Render / Supabase)
        registerIdentityOnBackend()

        // 3. Reconecta o WebSocket em tempo real com a nova identidade
        networkClient.disconnectSocket()
        networkClient.startRealtimeSocket(
            myIdentityHash = cleanHash,
            myAlias = _currentAlias.value,
            myIntent = _currentIntent.value.name,
            onE2eeMessageReceived = { senderHash, ciphertext, ivNonce, messageId ->
                processIncomingPayload(senderHash, "Contato", ciphertext, ivNonce, messageId)
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
            },
            onMutualConnection = { partnerId, _ ->
                confirmMutualConnectionFromServer(partnerId)
            },
            onMutualConnectionBroken = { partnerId, _ ->
                revokeMutualLocally(partnerId)
            },
            onPeerMarkedYou = { markerId, _ ->
                savedMarkingMePeople.add(cleanId(markerId))
                saveConnectionsToPrefs()
            },
            myPublicKeyBase64 = cryptoIdentityManager.getPublicKeyBase64(),
            signChallenge = { data -> cryptoIdentityManager.signChallenge(data) }
        )

        // 4. Sincroniza e recarrega os contatos mútuos desta identidade no backend
        scope.launch {
            try {
                val statusResult = networkClient.fetchConnectionStatus(cleanHash)
                statusResult.getOrNull()?.let { st ->
                    reconcileConnectionStatus(st.markedByMe, st.markingMe, st.mutual)
                }
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Aviso ao sincronizar conexões da identidade recuperada: ${e.message}")
            }
        }
    }

    // ================= Lembretes e Mensagens Recorrentes =================

    fun saveRecurringReminder(reminder: RecurringReminder) {
        reminderManager?.saveReminder(reminder)
    }

    fun deleteRecurringReminder(reminderId: String) {
        reminderManager?.deleteReminder(reminderId)
    }

    fun toggleRecurringReminder(reminderId: String, isEnabled: Boolean) {
        reminderManager?.toggleReminder(reminderId, isEnabled)
    }

    fun confirmRecurringReminder(reminderId: String) {
        reminderManager?.confirmReminder(reminderId)
    }

    fun triggerDueRemindersNow() {
        reminderManager?.checkAndTriggerDueReminders { targetId, text, mediaBase64, mediaType ->
            if (!mediaBase64.isNullOrBlank()) {
                sendImageMessage(targetId, mediaBase64)
            }
            if (text.isNotBlank()) {
                sendPrivateMessage(targetId, text)
            }
        }
    }

    // ================= Mensagens de Transmissão (Broadcast) =================

    suspend fun sendBroadcastMessage(
        targetPeerIds: List<String>,
        text: String,
        mediaBase64: String? = null,
        onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> {
        val subMgr = subscriptionManager
        if (subMgr != null) {
            val remainingCooldown = subMgr.getRemainingCooldownMs()
            if (remainingCooldown > 0) {
                val mins = (remainingCooldown / 60000) + 1
                return Result.failure(Exception("Aguarde mais $mins minuto(s) para enviar outra transmissão (proteção anti-spam)."))
            }

            if (!subMgr.canSendToRecipientCount(targetPeerIds.size)) {
                val max = subMgr.currentTier.value.maxBroadcastRecipients
                return Result.failure(Exception("O plano ${subMgr.currentTier.value.label} permite no máximo $max destinatários por transmissão. Assine o VIP para enviar para todos!"))
            }
        }

        if (targetPeerIds.isEmpty()) {
            return Result.failure(Exception("Nenhum destinatário selecionado."))
        }

        if (text.isBlank() && mediaBase64.isNullOrBlank()) {
            return Result.failure(Exception("Digite o texto ou selecione uma mídia para transmitir."))
        }

        var sentCount = 0
        val total = targetPeerIds.size

        for (i in targetPeerIds.indices) {
            val peerId = targetPeerIds[i]
            try {
                // Envia mídia anexada caso presente
                if (!mediaBase64.isNullOrBlank()) {
                    sendImageMessage(peerId, mediaBase64)
                    delay(40L)
                }

                // Envia o texto da mensagem caso presente
                if (text.isNotBlank()) {
                    sendPrivateMessage(peerId, "📢 [Transmissão]: ${text.trim()}")
                }
                sentCount++
                onProgress(sentCount, total)
                delay(40L)
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Falha ao enviar transmissão para $peerId: ${e.message}")
            }
        }

        subMgr?.recordBroadcastExecuted()
        return Result.success(sentCount)
    }

    suspend fun sendBroadcastMessage(
        targetPeerIds: List<String>,
        text: String,
        onProgress: (sent: Int, total: Int) -> Unit
    ): Result<Int> = sendBroadcastMessage(targetPeerIds, text, null, onProgress)

    fun setSubscriptionTier(tier: SubscriptionTier) {
        subscriptionManager?.setSubscriptionTier(tier)
    }

    fun launchVipPurchaseFlow(activity: android.app.Activity): Result<Unit> {
        val billing = playBillingManager ?: return Result.failure(Exception("Serviço de faturamento não inicializado."))
        return billing.launchPurchaseFlow(activity)
    }

    fun restoreVipPurchases(onFinished: (Boolean, String) -> Unit) {
        val billing = playBillingManager
        if (billing != null) {
            billing.restorePurchases(onFinished)
        } else {
            onFinished(false, "Serviço de faturamento indisponível.")
        }
    }

    fun setVipTestOverride(enabled: Boolean) {
        subscriptionManager?.setTestOverride(enabled)
    }

    /**
     * Executa o backup silencioso distribuído com replicação redundante 3x em contatos de confiança.
     */
    suspend fun executeDistributedBackup(
        pin: String,
        onProgress: (sent: Int, total: Int) -> Unit = { _, _ -> }
    ): Result<Int> {
        val vm = p2pVaultManager ?: return Result.failure(Exception("Gerenciador de cofre indisponível."))
        val cp = chatPersistence ?: return Result.failure(Exception("Persistência de mensagens indisponível."))

        if (pin.isNotBlank()) {
            pinSecurityManager.registerUserPin(pin)
        }

        val privateChats = cp.loadAllPrivateChats()
        val localMural = cp.loadLocalMural()
        val myIdentity = cryptoIdentityManager.getTechnicalIdentity()
        val myAlias = _currentAlias.value

        val myContacts = loadSavedContactsFromPrefs().map { p ->
            VaultContact(
                id = cleanId(p.technicalIdentityHash.ifBlank { p.id }),
                alias = p.alias,
                isMutual = p.isMutualConnection,
                isFamily = p.isFamily,
                familyRole = p.familyRole?.name
            )
        }

        val vault = vm.createVaultPackage(
            pin = pin,
            ownerIdentityHash = myIdentity,
            ownerAlias = myAlias,
            privateChats = privateChats,
            localMural = localMural,
            contacts = myContacts
        )

        val jsonStr = vm.serializeVaultToJson(vault)
        val b64 = E2eeCryptoEngine.Base64Helper.encode(jsonStr.toByteArray(Charsets.UTF_8))
        val payload = "[VAULT_STORE:$b64]"

        // Seleciona prioritariamente até 3 contatos de confiança (família ou conexões mútuas)
        val allPeers = discoveredPeople.value
        val targetPeers = allPeers
            .filter { it.isFamily || it.isMutualConnection }
            .distinctBy { it.technicalIdentityHash }
            .sortedByDescending { if (it.isFamily) 1 else 0 }
            .take(3)

        if (targetPeers.isEmpty()) {
            // Se ainda não há contatos mútuos conectados, salva localmente o registro da versão
            vm.recordBackupSuccess(vault.version, 0, vault.totalMessagesCount)
            return Result.failure(Exception("Nenhum familiar ou contato mútuo disponível para replicação. Utilize a 'Exportação de Arquivo' para backup pessoal."))
        }

        var sentCount = 0
        for (i in targetPeers.indices) {
            val peer = targetPeers[i]
            try {
                sendPrivateMessage(peer.technicalIdentityHash, payload)
                sentCount++
                onProgress(sentCount, targetPeers.size)
                delay(60L)
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Falha ao enviar cofre silencioso para ${peer.alias}: ${e.message}")
            }
        }

        vm.recordBackupSuccess(vault.version, sentCount, vault.totalMessagesCount)
        return Result.success(sentCount)
    }

    /**
     * Consulta os familiares conectados, elege o backup mais recente e restaura com o PIN do usuário.
     */
    suspend fun queryAndRestoreFromFamilyVault(
        pin: String,
        onStatusUpdate: (String) -> Unit = {}
    ): Result<Int> {
        val vm = p2pVaultManager ?: return Result.failure(Exception("Gerenciador de cofre indisponível."))
        val cp = chatPersistence ?: return Result.failure(Exception("Persistência de mensagens indisponível."))

        _receivedVaultResponses.value = emptyList()
        onStatusUpdate("Consultando familiares e conexões de confiança...")

        val myIdentity = cryptoIdentityManager.getTechnicalIdentity().removePrefix("peer-").trim().lowercase()
        val allPeers = discoveredPeople.value
        val targets = allPeers.filter { it.isFamily || it.isMutualConnection }

        if (targets.isEmpty()) {
            return Result.failure(Exception("Nenhum familiar ou conexão mútua conectada no momento. Utilize a restauração por arquivo de backup (.backup)."))
        }

        for (p in targets) {
            sendPrivateMessage(p.technicalIdentityHash, "[VAULT_QUERY:$myIdentity]")
        }

        // Aguarda respostas silenciosas dos aparelhos por até 3.5 segundos
        var waitMs = 0
        while (waitMs < 3500 && _receivedVaultResponses.value.isEmpty()) {
            delay(200)
            waitMs += 200
        }

        val responses = _receivedVaultResponses.value
        if (responses.isEmpty()) {
            return Result.failure(Exception("Nenhum familiar possui um cofre salvo para esta conta ou os contatos estão indisponíveis."))
        }

        // Eleição do backup mais recente (maior versão e timestamp)
        val bestVault = responses.maxWithOrNull(compareBy({ it.version }, { it.timestampEpochMs }))
            ?: return Result.failure(Exception("Cofre inválido recebido."))

        onStatusUpdate("Cofre versão ${bestVault.version} localizado! Descriptografando...")

        val restoreResult = vm.restoreFromVaultPackage(bestVault, pin)
        if (restoreResult.isFailure) {
            return Result.failure(restoreResult.exceptionOrNull() ?: Exception("Falha na descriptografia"))
        }

        val content = restoreResult.getOrThrow()
        for ((peerId, msgs) in content.privateChats) {
            cp.savePrivateChat(peerId, msgs)
        }
        cp.saveLocalMural(content.localMural)
        proximitySimulator.loadPersistedMessages()

        // Restaura contatos preservados no cofre
        restoreVaultContacts(content.contacts)

        val totalMsgs = content.privateChats.values.sumOf { it.size } + content.localMural.size
        return Result.success(totalMsgs)
    }

    /**
     * Exporta o cofre em formato texto/JSON cifrado para contingência pessoal.
     */
    fun exportLocalBackupJson(pin: String): Result<String> {
        val vm = p2pVaultManager ?: return Result.failure(Exception("Gerenciador de cofre indisponível."))
        val cp = chatPersistence ?: return Result.failure(Exception("Persistência de mensagens indisponível."))

        if (pin.isNotBlank()) {
            pinSecurityManager.registerUserPin(pin)
        }

        val privateChats = cp.loadAllPrivateChats()
        val localMural = cp.loadLocalMural()
        val myIdentity = cryptoIdentityManager.getTechnicalIdentity()
        val myAlias = _currentAlias.value

        val myContacts = loadSavedContactsFromPrefs().map { p ->
            VaultContact(
                id = cleanId(p.technicalIdentityHash.ifBlank { p.id }),
                alias = p.alias,
                isMutual = p.isMutualConnection,
                isFamily = p.isFamily,
                familyRole = p.familyRole?.name
            )
        }

        val vault = vm.createVaultPackage(
            pin = pin,
            ownerIdentityHash = myIdentity,
            ownerAlias = myAlias,
            privateChats = privateChats,
            localMural = localMural,
            contacts = myContacts
        )

        val jsonStr = vm.serializeVaultToJson(vault)
        return Result.success(jsonStr)
    }

    /**
     * Restaura o cofre a partir de um arquivo/JSON de backup local.
     */
    fun restoreFromLocalBackupJson(jsonString: String, pin: String): Result<Int> {
        val vm = p2pVaultManager ?: return Result.failure(Exception("Gerenciador de cofre indisponível."))
        val cp = chatPersistence ?: return Result.failure(Exception("Persistência de mensagens indisponível."))

        val vault = vm.deserializeVaultFromJson(jsonString)
            ?: return Result.failure(Exception("Arquivo de backup inválido ou incompatível."))

        val restoreResult = vm.restoreFromVaultPackage(vault, pin)
        if (restoreResult.isFailure) {
            return Result.failure(restoreResult.exceptionOrNull() ?: Exception("PIN incorreto ou falha na descriptografia."))
        }

        if (pin.isNotBlank()) {
            pinSecurityManager.registerUserPin(pin)
        }

        val content = restoreResult.getOrThrow()
        for ((peerId, msgs) in content.privateChats) {
            cp.savePrivateChat(peerId, msgs)
        }
        cp.saveLocalMural(content.localMural)
        proximitySimulator.loadPersistedMessages()

        // Restaura contatos preservados no cofre
        restoreVaultContacts(content.contacts)

        val totalMsgs = content.privateChats.values.sumOf { it.size } + content.localMural.size
        return Result.success(totalMsgs)
    }

    private fun restoreVaultContacts(contacts: List<VaultContact>) {
        if (contacts.isEmpty()) return
        val restoredPeople = mutableListOf<NearbyPerson>()
        for (c in contacts) {
            val cid = cleanId(c.id)
            if (cid.isBlank()) continue
            val fRole = c.familyRole?.let { roleName ->
                try { FamilyRole.valueOf(roleName) } catch (_: Exception) { null }
            }
            // Backup restaura os dados do contato, NÃO a aprovação mútua.
            // O vínculo atual será revalidado com /connections/status do servidor.
            restoredPeople.add(
                NearbyPerson(
                    id = cid,
                    technicalIdentityHash = cid,
                    alias = c.alias,
                    estimatedDistanceMeters = 500.0,
                    proximityLabel = if (c.isFamily) "Família • Ilimitado" else "Conexão Mútua • Ilimitada",
                    isMarkedByMe = cid in savedMarkedPeople && cid !in revokedMutualPeople,
                    isMarkingMe = cid in savedMarkingMePeople && cid !in revokedMutualPeople,
                    isMutualConnection = cid in savedMutualPeople && cid !in revokedMutualPeople,
                    isFamily = c.isFamily,
                    familyRole = fRole,
                    isPhotoVisible = true,
                    lastSeenEpochMs = System.currentTimeMillis()
                )
            )
        }
        saveConnectionsToPrefs()
        saveSavedContactsToPrefs()
        val cur = _realDiscoveredPeople.value.toMutableList()
        restoredPeople.forEach { rp ->
            val idx = cur.indexOfFirst { cleanId(it.technicalIdentityHash) == cleanId(rp.technicalIdentityHash) }
            if (idx != -1) cur[idx] = rp else cur.add(rp)
        }
        _realDiscoveredPeople.value = cur
        updateUnifiedData()

        // NUNCA enviar contatos restaurados ao endpoint de sync que cria vínculos:
        // o backup é dado histórico e não contém consentimento atual das duas partes.
    }

    /**
     * Executa o backup silencioso em segundo plano se o intervalo de replicação estiver vencido.
     * Utiliza o PIN configurado pelo usuário sem interromper sua navegação.
     */
    fun executeSilentBackgroundBackupIfDue() {
        val vm = p2pVaultManager ?: return
        if (!vm.isBackupDue()) return
        val pin = pinSecurityManager.getUserPin() ?: return
        scope.launch {
            try {
                val res = executeDistributedBackup(pin)
                if (res.isSuccess) {
                    Log.i("PessoasAqui", "Backup silencioso em segundo plano executado com sucesso: ${res.getOrNull()} familiares replicados.")
                }
            } catch (e: Exception) {
                Log.w("PessoasAqui", "Aviso no backup silencioso automático: ${e.message}")
            }
        }
    }

    /**
     * Envia formalmente denúncia de abuso ou conduta imprópria e bloqueia localmente o contato.
     */
    fun reportAndBlockUser(
        accusedIdentity: String,
        violationType: String,
        reason: String,
        evidenceMessages: List<ChatMessage> = emptyList()
    ) {
        moderationManager.blockUser(accusedIdentity)
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val evidenceMaps = evidenceMessages.map { m ->
            mapOf(
                "senderAlias" to m.senderAlias,
                "text" to (m.text ?: "")
            )
        }
        scope.launch {
            networkClient.submitReport(
                reporterIdentity = myId,
                accusedIdentity = accusedIdentity,
                violationType = violationType,
                reason = reason,
                evidenceMessages = evidenceMaps
            )
        }
    }

    private fun handleSupportTicketReplied(ticketId: String, subject: String, reply: String, timestamp: Long) {
        val text = "📩 [RESPOSTA DE SUPORTE]\nChamado: $subject\n\n$reply"
        proximitySimulator.receivePrivateMessage(
            "suporte-oficial",
            "Suporte PessoasAqui",
            text,
            "tkt_reply_$ticketId"
        )
        callRingtoneWakeManager?.notifyIncomingMessageIfBackground("Suporte PessoasAqui", "Resposta ao chamado [$subject]: $reply")
        scope.launch {
            refreshMySupportTickets()
        }
    }

    /**
     * Consulta todos os chamados e petições do usuário para recuperar respostas da moderação
     */
    suspend fun refreshMySupportTickets(): Result<List<SupportTicketItem>> {
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val res = networkClient.fetchMySupportTickets(myId)
        res.getOrNull()?.let { tickets ->
            _mySupportTickets.value = tickets
            tickets.filter { it.status == "ANSWERED" && !it.moderatorReply.isNullOrBlank() }.forEach { t ->
                val replyMsgId = "tkt_reply_${t.id}"
                val text = "📩 [RESPOSTA DE SUPORTE]\nChamado: ${t.subject}\n\n${t.moderatorReply}"
                proximitySimulator.receivePrivateMessage(
                    "suporte-oficial",
                    "Suporte PessoasAqui",
                    text,
                    replyMsgId
                )
            }
        }
        return res
    }

    /**
     * Abre uma petição ou chamado de suporte com a equipe.
     */
    suspend fun openSupportTicket(
        category: String,
        subject: String,
        message: String
    ): Result<Boolean> {
        val myId = cryptoIdentityManager.getTechnicalIdentity()
        val myAlias = _currentAlias.value
        val res = networkClient.submitSupportTicket(
            authorIdentity = myId,
            authorAlias = myAlias,
            category = category,
            subject = subject,
            message = message
        )
        if (res.isSuccess) {
            scope.launch {
                delay(400)
                refreshMySupportTickets()
            }
        }
        return res
    }

    // ==========================================
    // GESTÃO DE ETIQUETAS DE CONTATOS
    // ==========================================
    private val _contactTags = MutableStateFlow<List<ContactTag>>(loadContactTagsFromPrefs())
    val contactTags: StateFlow<List<ContactTag>> = _contactTags.asStateFlow()

    private val _contactTagAssignments = MutableStateFlow<Map<String, Set<String>>>(loadContactTagAssignmentsFromPrefs())
    val contactTagAssignments: StateFlow<Map<String, Set<String>>> = _contactTagAssignments.asStateFlow()

    // ==========================================
    // ATUALIZAÇÃO DO APP NO GOOGLE PLAY
    // ==========================================
    private val _availableAppUpdate = MutableStateFlow<AppVersionInfo?>(null)
    val availableAppUpdate: StateFlow<AppVersionInfo?> = _availableAppUpdate.asStateFlow()

    private fun loadContactTagsFromPrefs(): List<ContactTag> {
        val json = prefs?.getString("contact_tags_v1", null)
        if (json.isNullOrBlank()) {
            val defaultTags = listOf(
                ContactTag(id = "tag_vip", name = "VIP 💎", colorHex = "#FFB300"),
                ContactTag(id = "tag_clients", name = "Clientes 💼", colorHex = "#00D4FF"),
                ContactTag(id = "tag_family", name = "Família 💜", colorHex = "#B388FF"),
                ContactTag(id = "tag_friends", name = "Amigos 🟢", colorHex = "#00E676")
            )
            saveContactTagsToPrefs(defaultTags)
            return defaultTags
        }
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<ContactTag>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ContactTag(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        colorHex = obj.optString("colorHex", "#00D4FF")
                    )
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveContactTagsToPrefs(tags: List<ContactTag>) {
        try {
            val array = JSONArray()
            for (tag in tags) {
                val obj = JSONObject()
                obj.put("id", tag.id)
                obj.put("name", tag.name)
                obj.put("colorHex", tag.colorHex)
                array.put(obj)
            }
            prefs?.edit()?.putString("contact_tags_v1", array.toString())?.apply()
        } catch (_: Exception) {}
    }

    private fun loadContactTagAssignmentsFromPrefs(): Map<String, Set<String>> {
        val json = prefs?.getString("contact_tag_assignments_v1", null) ?: return emptyMap()
        return try {
            val obj = JSONObject(json)
            val map = mutableMapOf<String, Set<String>>()
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val array = obj.getJSONArray(key)
                val set = mutableSetOf<String>()
                for (i in 0 until array.length()) {
                    set.add(array.getString(i))
                }
                map[key] = set
            }
            map
        } catch (_: Exception) {
            emptyMap()
        }
    }

    private fun saveContactTagAssignmentsToPrefs(map: Map<String, Set<String>>) {
        try {
            val obj = JSONObject()
            for ((key, set) in map) {
                val array = JSONArray()
                for (tagId in set) {
                    array.put(tagId)
                }
                obj.put(key, array)
            }
            prefs?.edit()?.putString("contact_tag_assignments_v1", obj.toString())?.apply()
        } catch (_: Exception) {}
    }

    fun createContactTag(name: String, colorHex: String = "#00D4FF"): ContactTag {
        val trimmed = name.trim()
        val newTag = ContactTag(name = trimmed, colorHex = colorHex)
        val updated = _contactTags.value + newTag
        _contactTags.value = updated
        saveContactTagsToPrefs(updated)
        return newTag
    }

    fun deleteContactTag(tagId: String) {
        val updatedTags = _contactTags.value.filter { it.id != tagId }
        _contactTags.value = updatedTags
        saveContactTagsToPrefs(updatedTags)

        val currentMap = _contactTagAssignments.value.toMutableMap()
        var changed = false
        for ((contactId, set) in currentMap) {
            if (set.contains(tagId)) {
                currentMap[contactId] = set - tagId
                changed = true
            }
        }
        if (changed) {
            _contactTagAssignments.value = currentMap
            saveContactTagAssignmentsToPrefs(currentMap)
        }
    }

    fun toggleTagForContact(contactIdentity: String, tagId: String) {
        val clean = cleanId(contactIdentity)
        if (clean.isBlank()) return
        val currentMap = _contactTagAssignments.value.toMutableMap()
        val existingSet = currentMap[clean] ?: emptySet()
        val newSet = if (existingSet.contains(tagId)) {
            existingSet - tagId
        } else {
            existingSet + tagId
        }
        currentMap[clean] = newSet
        _contactTagAssignments.value = currentMap
        saveContactTagAssignmentsToPrefs(currentMap)
    }

    fun getTagsForContact(contactIdentity: String): List<ContactTag> {
        val clean = cleanId(contactIdentity)
        val tagIds = _contactTagAssignments.value[clean] ?: emptySet()
        return _contactTags.value.filter { tagIds.contains(it.id) }
    }

    fun checkForAppUpdates(currentVersionCode: Long, currentVersionName: String = "1.2.4") {
        scope.launch {
            try {
                val res = networkClient.checkAppVersion()
                if (res.isSuccess) {
                    val info = res.getOrNull()
                    if (info != null) {
                        val isCodeNewer = info.latestVersionCode > currentVersionCode
                        val isNameNewer = isVersionNameGreater(info.latestVersionName, currentVersionName)
                        if (isCodeNewer || isNameNewer) {
                            _availableAppUpdate.value = info
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("PessoasAquiRepository", "Falha ao verificar atualização: ${e.message}")
            }
        }
    }

    private fun isVersionNameGreater(latest: String, current: String): Boolean {
        return try {
            val cleanL = latest.trim().split(".").mapNotNull { it.toIntOrNull() }
            val cleanC = current.trim().split(".").mapNotNull { it.toIntOrNull() }
            for (i in 0 until maxOf(cleanL.size, cleanC.size)) {
                val l = cleanL.getOrElse(i) { 0 }
                val c = cleanC.getOrElse(i) { 0 }
                if (l > c) return true
                if (l < c) return false
            }
            false
        } catch (_: Exception) {
            false
        }
    }

    fun dismissAppUpdate() {
        _availableAppUpdate.value = null
    }
}

