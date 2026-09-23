package br.com.pessoasaqui.core.crypto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/**
 * Controla a regra fundamental de sessão do PessoasAqui:
 * "1 identidade = 1 dispositivo ativo"
 *
 * Ao recuperar com sucesso em um novo celular, a sessão do aparelho antigo é revogada imediatamente.
 */
class DeviceSessionManager {

    private val currentDeviceId = UUID.randomUUID().toString()
    
    // Estado da sessão deste dispositivo
    private val _isSessionRevoked = MutableStateFlow(false)
    val isSessionRevoked: StateFlow<Boolean> = _isSessionRevoked.asStateFlow()

    private val _revocationNotice = MutableStateFlow<String?>(null)
    val revocationNotice: StateFlow<String?> = _revocationNotice.asStateFlow()

    fun getDeviceId(): String = currentDeviceId

    /**
     * Chamado quando o dispositivo atual recebe a notificação de que a identidade
     * foi transferida para um novo aparelho.
     */
    fun revokeCurrentDeviceSession() {
        _isSessionRevoked.value = true
        _revocationNotice.value = "🔐 Sua identidade PessoasAqui foi transferida para um novo aparelho."
    }

    fun resetSession() {
        _isSessionRevoked.value = false
        _revocationNotice.value = null
    }
}
