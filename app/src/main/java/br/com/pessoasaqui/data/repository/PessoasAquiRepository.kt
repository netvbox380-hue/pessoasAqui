package br.com.pessoasaqui.data.repository

import br.com.pessoasaqui.core.crypto.CryptoIdentityManager
import br.com.pessoasaqui.core.crypto.DeviceSessionManager
import br.com.pessoasaqui.core.crypto.PinSecurityManager
import br.com.pessoasaqui.core.moderation.ContentModerationManager
import br.com.pessoasaqui.core.proximity.ProximitySimulator
import br.com.pessoasaqui.domain.model.ChatMessage
import br.com.pessoasaqui.domain.model.NearbyPerson
import br.com.pessoasaqui.domain.model.OfferItem
import br.com.pessoasaqui.domain.model.RecoveryAuthorization
import br.com.pessoasaqui.domain.model.UserIntent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

import br.com.pessoasaqui.core.proximity.BleManager
import kotlinx.coroutines.flow.combine

import br.com.pessoasaqui.data.remote.PessoasAquiNetworkClient

/**
 * Repositório central do PessoasAqui que unifica as regras de negócio,
 * identidade técnica, radar de proximidade de 10 metros e fluxos de recuperação.
 */
class PessoasAquiRepository(
    val cryptoIdentityManager: CryptoIdentityManager = CryptoIdentityManager(),
    val pinSecurityManager: PinSecurityManager = PinSecurityManager(),
    val deviceSessionManager: DeviceSessionManager = DeviceSessionManager(),
    val proximitySimulator: ProximitySimulator = ProximitySimulator(),
    val moderationManager: ContentModerationManager = ContentModerationManager(),
    val bleManager: BleManager? = null,
    val networkClient: PessoasAquiNetworkClient = PessoasAquiNetworkClient()
) {

    // Estado do usuário atual neste aparelho
    private val _isAgeVerified = MutableStateFlow(false)
    val isAgeVerified: StateFlow<Boolean> = _isAgeVerified.asStateFlow()

    private val _hasGrantedPermissions = MutableStateFlow(false)
    val hasGrantedPermissions: StateFlow<Boolean> = _hasGrantedPermissions.asStateFlow()

    private val _currentAlias = MutableStateFlow("Eu")
    val currentAlias: StateFlow<String> = _currentAlias.asStateFlow()

    private val _currentIntent = MutableStateFlow(UserIntent.QUERO_CONVERSAR)
    val currentIntent: StateFlow<UserIntent> = _currentIntent.asStateFlow()

    // Lista unificada: Dispositivos reais físicos via rádio BLE + peers do simulador/playground
    private val _unifiedDiscoveredPeople = MutableStateFlow<List<NearbyPerson>>(emptyList())
    val discoveredPeople: StateFlow<List<NearbyPerson>> = _unifiedDiscoveredPeople.asStateFlow()

    init {
        // Inicializa com a lista do simulador e atualiza conforme novos peers reais ou simulados surgem
        _unifiedDiscoveredPeople.value = proximitySimulator.discoveredPeople.value

        // Inicia conexão WebSocket em tempo real para Relay E2EE e Revogação
        networkClient.startRealtimeSocket(
            myIdentityHash = cryptoIdentityManager.getTechnicalIdentity(),
            onE2eeMessageReceived = { sender, payload, _ ->
                proximitySimulator.sendPrivateMessage(sender, payload, sender)
            },
            onSessionRevoked = { _ ->
                deviceSessionManager.revokeCurrentDeviceSession()
            }
        )
    }

    val localOffers: StateFlow<List<OfferItem>> = proximitySimulator.localOffers
    val localChatMessages: StateFlow<List<ChatMessage>> = proximitySimulator.localChatMessages
    val privateChats: StateFlow<Map<String, List<ChatMessage>>> = proximitySimulator.privateChats

    fun completeAgeVerification() {
        _isAgeVerified.value = true
    }

    fun completePermissions() {
        _hasGrantedPermissions.value = true
        startNativeBleHardware()
    }

    private fun startNativeBleHardware() {
        bleManager?.let { ble ->
            if (ble.isBluetoothEnabled) {
                ble.startAdvertising(
                    myIdentityHash = cryptoIdentityManager.getTechnicalIdentity(),
                    myAlias = _currentAlias.value,
                    myIntent = _currentIntent.value
                )
                ble.startScanning { realPeer ->
                    val currentList = _unifiedDiscoveredPeople.value.toMutableList()
                    val idx = currentList.indexOfFirst { it.id == realPeer.id }
                    if (idx != -1) {
                        currentList[idx] = realPeer
                    } else {
                        currentList.add(0, realPeer)
                    }
                    _unifiedDiscoveredPeople.value = currentList.sortedBy { it.estimatedDistanceMeters }
                }
            }
        }
    }

    fun setAlias(newAlias: String) {
        if (newAlias.isNotBlank()) {
            _currentAlias.value = newAlias.trim()
            startNativeBleHardware()
        }
    }

    fun setIntent(intent: UserIntent) {
        _currentIntent.value = intent
    }

    fun toggleMarkPerson(personId: String): NearbyPerson? {
        return proximitySimulator.toggleMarkPerson(personId)
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

    fun generateFamilyRecovery(targetIdentityHash: String, familyName: String): RecoveryAuthorization {
        return proximitySimulator.generateFamilyRecoveryAuthorization(targetIdentityHash, familyName)
    }

    fun transferIdentityToThisDevice(recoveredIdentityHash: String) {
        // Marca dispositivo anterior como revogado e ativa a identidade recuperada aqui
        deviceSessionManager.resetSession()
    }
}
