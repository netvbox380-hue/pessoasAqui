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
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.OfferItem
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.domain.model.UserIntent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    val cryptoIdentityManager: CryptoIdentityManager = CryptoIdentityManager(),
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

    // Lista unificada exibida na UI
    private val _unifiedDiscoveredPeople = MutableStateFlow<List<NearbyPerson>>(emptyList())
    val discoveredPeople: StateFlow<List<NearbyPerson>> = _unifiedDiscoveredPeople.asStateFlow()

    // Ofertas locais reais
    private val _realOffers = MutableStateFlow<List<OfferItem>>(emptyList())
    private val _unifiedOffers = MutableStateFlow<List<OfferItem>>(emptyList())
    val localOffers: StateFlow<List<OfferItem>> = _unifiedOffers.asStateFlow()

    val localChatMessages: StateFlow<List<ChatMessage>> = proximitySimulator.localChatMessages
    val privateChats: StateFlow<Map<String, List<ChatMessage>>> = proximitySimulator.privateChats

    init {
        updateUnifiedData()

        // Inicia conexão WebSocket em tempo real para Relay E2EE, Presença e Revogação
        networkClient.startRealtimeSocket(
            myIdentityHash = cryptoIdentityManager.getTechnicalIdentity(),
            myAlias = _currentAlias.value,
            myIntent = _currentIntent.value.name,
            onE2eeMessageReceived = { sender, payload, _ ->
                proximitySimulator.sendPrivateMessage(sender, payload, sender)
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

        // Se o perfil já estiver configurado, registra identidade no Supabase / Render
        if (_hasCompletedProfile.value) {
            registerIdentityOnBackend()
        }

        if (_hasGrantedPermissions.value) {
            startNativeBleHardware()
        }
    }

    private fun updateUnifiedData() {
        if (_isDemoMode.value) {
            // Em modo demonstração, mescla pessoas reais com as pessoas simuladas
            val simulated = proximitySimulator.discoveredPeople.value
            val combined = (_realDiscoveredPeople.value + simulated)
                .distinctBy { it.technicalIdentityHash }
                .sortedBy { it.estimatedDistanceMeters }
            _unifiedDiscoveredPeople.value = combined
            _unifiedOffers.value = _realOffers.value + proximitySimulator.localOffers.value
        } else {
            // PRODUÇÃO PURA: Somente pessoas e ofertas reais no radar
            _unifiedDiscoveredPeople.value = _realDiscoveredPeople.value.sortedBy { it.estimatedDistanceMeters }
            _unifiedOffers.value = _realOffers.value
        }
    }

    private fun handleRemotePresenceSync(remotePeers: List<RemotePeer>) {
        val mapped = remotePeers.map { p ->
            val safeIntent = try { UserIntent.valueOf(p.intent) } catch (_: Exception) { UserIntent.QUERO_CONVERSAR }
            NearbyPerson(
                id = "peer-${p.identityHash}",
                technicalIdentityHash = p.identityHash,
                alias = p.alias,
                estimatedDistanceMeters = 3.2,
                proximityLabel = "Dentro do alcance (~3m)",
                intent = safeIntent,
                isMarkedByMe = false,
                isMarkingMe = false,
                isMutualConnection = false,
                isFamily = false,
                avatarColorHex = 0xFF00E5FF
            )
        }
        val current = _realDiscoveredPeople.value.toMutableList()
        mapped.forEach { newPeer ->
            val idx = current.indexOfFirst { it.technicalIdentityHash == newPeer.technicalIdentityHash }
            if (idx != -1) {
                current[idx] = newPeer
            } else {
                current.add(newPeer)
            }
        }
        _realDiscoveredPeople.value = current
        updateUnifiedData()
    }

    private fun handlePeerOnline(peer: RemotePeer) {
        val safeIntent = try { UserIntent.valueOf(peer.intent) } catch (_: Exception) { UserIntent.QUERO_CONVERSAR }
        val newPeer = NearbyPerson(
            id = "peer-${peer.identityHash}",
            technicalIdentityHash = peer.identityHash,
            alias = peer.alias,
            estimatedDistanceMeters = 2.0,
            proximityLabel = "Dentro do alcance (~2m)",
            intent = safeIntent,
            isMarkedByMe = false,
            isMarkingMe = false,
            isMutualConnection = false,
            isFamily = false,
            avatarColorHex = 0xFF00E5FF
        )
        val current = _realDiscoveredPeople.value.toMutableList()
        val idx = current.indexOfFirst { it.technicalIdentityHash == peer.identityHash }
        if (idx != -1) {
            current[idx] = newPeer
        } else {
            current.add(0, newPeer)
        }
        _realDiscoveredPeople.value = current
        updateUnifiedData()
    }

    private fun handlePeerOffline(peerHash: String) {
        val current = _realDiscoveredPeople.value.filter { it.technicalIdentityHash != peerHash }
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
                        val currentList = _realDiscoveredPeople.value.toMutableList()
                        val idx = currentList.indexOfFirst { it.id == realPeer.id || it.technicalIdentityHash == realPeer.technicalIdentityHash }
                        if (idx != -1) {
                            currentList[idx] = realPeer
                        } else {
                            currentList.add(0, realPeer)
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

    fun toggleMarkPerson(personId: String): NearbyPerson? {
        val updated = proximitySimulator.toggleMarkPerson(personId)
        if (updated != null) {
            scope.launch {
                networkClient.markConnection(
                    fromIdentity = cryptoIdentityManager.getTechnicalIdentity(),
                    toIdentity = updated.technicalIdentityHash
                )
            }
            updateUnifiedData()
        }
        return updated
    }

    fun postLocalOffer(profession: String, description: String) {
        val newOffer = OfferItem(
            id = "offer-${UUID.randomUUID()}",
            authorId = cryptoIdentityManager.getTechnicalIdentity(),
            authorAlias = _currentAlias.value,
            profession = profession.trim(),
            description = description.trim(),
            distanceMeters = 0.5,
            proximityLabel = "Seu anúncio • 10m"
        )
        _realOffers.value = listOf(newOffer) + _realOffers.value
        updateUnifiedData()
    }

    fun sendLocalMessage(text: String): ContentModerationManager.ModerationResult {
        val mod = moderationManager.evaluateContent(deviceSessionManager.getDeviceId(), text)
        if (mod.isAllowed) {
            proximitySimulator.sendLocalMessage(text, _currentAlias.value)
        }
        return mod
    }

    fun sendPrivateMessage(recipientId: String, text: String): ContentModerationManager.ModerationResult {
        val mod = moderationManager.evaluateContent(deviceSessionManager.getDeviceId(), text)
        if (mod.isAllowed) {
            proximitySimulator.sendPrivateMessage(recipientId, text, _currentAlias.value)
        }
        return mod
    }

    fun setFamilyRole(personId: String, role: br.com.pessoasaqui.domain.model.FamilyRole) {
        val currentReal = _realDiscoveredPeople.value.toMutableList()
        val idx = currentReal.indexOfFirst { it.id == personId || it.technicalIdentityHash == personId }
        if (idx != -1) {
            val p = currentReal[idx]
            currentReal[idx] = p.copy(
                isFamily = true,
                familyRole = role,
                isPhotoVisible = true,
                isMutualConnection = true
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
